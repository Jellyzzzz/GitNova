#!/usr/bin/env python3
"""One Claude Code session on the same fixture; reuse GitNova's independent verifier."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import uuid
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent


def snapshot(workspace):
    return {str(p.relative_to(workspace)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(workspace.rglob("*"))
            if p.is_file() and ".git" not in p.relative_to(workspace).parts}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fixture-root", type=Path, default=ROOT)
    parser.add_argument("--scenario", type=Path)
    args = parser.parse_args()
    fixture = args.fixture_root.resolve()
    scenario = json.loads((args.scenario or ROOT / "scenario-multitask-128k.json").read_text())
    policy = scenario["experiment"]
    stage_verifier = scenario.get("verifier") == "stage"
    max_turns = policy["maxModelCalls"]
    wall_limit = policy.get("taskWallClockLimitSeconds", 600)
    os.umask(0o077)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    workspace = output / "repository"
    if workspace.exists():
        raise ValueError("Use a fresh directory; never overwrite a previous sample")
    config = json.loads((Path.home() / ".claude/settings.json").read_text())
    credentials = config.get("env", {})
    base_url = credentials.get("ANTHROPIC_BASE_URL", "")
    if urlsplit(base_url).hostname != "api.deepseek.com":
        raise ValueError("Expected the official DeepSeek endpoint; inspect configuration")
    token = credentials.get("ANTHROPIC_AUTH_TOKEN")
    if not token:
        raise ValueError("No configured DeepSeek credential; no request was sent")
    shutil.copytree(fixture / "repository", workspace)
    initial = snapshot(workspace)
    subprocess.run(["git", "-c", "init.defaultBranch=main", "init", "-q"], cwd=workspace, check=True)
    subprocess.run(["git", "add", "--", *initial], cwd=workspace, check=True)
    subprocess.run(["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                    "-c", "core.hooksPath=/dev/null", "commit", "-qm", "Unmodified pricing fixture"],
                   cwd=workspace, check=True)
    config_dir = output / "claude-state"
    config_dir.mkdir()
    java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    # Do not inherit database passwords, cloud credentials or the user's broad tool allow rules.
    env = {k: os.environ[k] for k in ("HOME", "USER", "LOGNAME", "TMPDIR", "LANG") if k in os.environ}
    env.update({"PATH": java_home + "/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin",
                "JAVA_HOME": java_home, "SHELL": "/bin/bash",
                "CLAUDE_CONFIG_DIR": str(config_dir), "ANTHROPIC_BASE_URL": base_url,
                "ANTHROPIC_AUTH_TOKEN": token, "ANTHROPIC_MODEL": "deepseek-v4-flash",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL": "deepseek-v4-flash",
                "ANTHROPIC_DEFAULT_SONNET_MODEL": "deepseek-v4-flash",
                "ANTHROPIC_DEFAULT_OPUS_MODEL": "deepseek-v4-flash",
                "CLAUDE_CODE_SUBAGENT_MODEL": "deepseek-v4-flash",
                "CLAUDE_CODE_MAX_CONTEXT_TOKENS": str(policy["contextWindowTokens"]),
                "CLAUDE_CODE_MAX_OUTPUT_TOKENS": str(policy["maxOutputTokens"]),
                "MAX_THINKING_TOKENS": "0", "CLAUDE_CODE_MAX_RETRIES": "2",
                "API_TIMEOUT_MS": str(policy.get("callTimeoutSeconds", 60) * 1000),
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                "CLAUDE_CODE_DISABLE_TERMINAL_TITLE": "1", "DISABLE_AUTOUPDATER": "1"})
    session_id = str(uuid.uuid4())
    manifest = {"cliVersion": subprocess.check_output(["claude", "--version"], text=True).strip(),
                "requestedModel": "deepseek-v4-flash", "endpoint": base_url,
                "sessionId": session_id, "workspace": str(workspace), "initialFiles": initial,
                "requestedContextWindow": policy["contextWindowTokens"], "requestedMaxOutput": policy["maxOutputTokens"],
                "requestedThinking": "disabled", "temperature": "native CLI default, not forced",
                "maxTurnsPerTask": max_turns, "processTimeoutSeconds": wall_limit,
                "contextSettingVerified": False,
                "comparability": "Native CLI policy; requested environment settings do not prove effective limits or thinking mode.",
                "taskDigests": {t["name"]: hashlib.sha256((fixture / t["file"]).read_bytes()).hexdigest()
                                for t in scenario["tasks"]}, "tasks": []}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    print(f"Claude Code {manifest['cliVersion']}; session={session_id}; workspace={workspace}", flush=True)
    for index, task in enumerate(scenario["tasks"]):
        task_dir = output / task["name"]
        task_dir.mkdir()
        before = snapshot(workspace)
        # Native tools/prompt, but no account-specific plugins, MCP, browser or extra agents.
        allowed = ["Read", "Glob", "Grep", "Bash(sh run-tests.sh)", "Bash(sh run-tests.sh *)",
                   "Bash(git diff *)", "Bash(git status *)", "Bash(git show *)", "Bash(pwd)", "Bash(ls *)"]
        for relative in task["editableFiles"]:
            allowed.extend([f"Edit(./{relative})", f"Write(./{relative})"])
        command = ["claude", "-p", "--output-format", "stream-json", "--verbose", "--safe-mode",
                   "--setting-sources", "", "--settings", '{"alwaysThinkingEnabled":false}',
                   "--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}', "--no-chrome",
                   "--disable-slash-commands", "--model", "deepseek-v4-flash", "--max-turns", str(max_turns),
                   "--permission-mode", "dontAsk", "--tools", "Bash,Read,Glob,Grep,Edit,Write",
                   "--allowedTools", *allowed,
                   "--session-id" if index == 0 else "--resume", session_id]
        (task_dir / "command.json").write_text(json.dumps(command, indent=2))
        (task_dir / "before.json").write_text(json.dumps(before, indent=2))
        print(f"Task {index + 1}: {task['name']} started", flush=True)
        started = time.monotonic()
        timed_out = False
        with (task_dir / "events.jsonl").open("w") as stdout, (task_dir / "stderr.log").open("w") as stderr:
            process = subprocess.Popen(command, cwd=workspace, env=env, stdin=subprocess.PIPE,
                                       stdout=stdout, stderr=stderr, start_new_session=True)
            try:
                process.communicate((fixture / task["file"]).read_bytes(), timeout=wall_limit)
            except subprocess.TimeoutExpired:
                timed_out = True
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
        events = [json.loads(line) for line in (task_dir / "events.jsonl").read_text().splitlines() if line.strip()]
        finals = [event for event in events if event.get("type") == "result"]
        final = finals[-1] if finals else None
        (task_dir / "result.json").write_text(json.dumps(final, indent=2, ensure_ascii=False))
        after = snapshot(workspace)
        (task_dir / "after.json").write_text(json.dumps(after, indent=2))
        changed = sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))
        record = {"name": task["name"], "processExitCode": process.returncode, "timedOut": timed_out,
                  "elapsedSeconds": round(time.monotonic() - started, 3), "changedFiles": changed,
                  "writeScopePassed": set(changed) <= set(task["editableFiles"]),
                  "resultSubtype": final.get("subtype") if final else None,
                  "usage": final.get("usage") if final else None,
                  "modelUsage": final.get("modelUsage") if final else None,
                  "permissionDenials": final.get("permission_denials") if final else None}
        verify = [sys.executable, str(fixture / "verify.py"), "--candidate", str(workspace),
                  "--output", str(task_dir / "verification")]
        if stage_verifier:
            verify += ["--stage", str(index + 1)]
        elif (workspace / "src/test/java/pricing/EdgeRegression.java").exists():
            verify.append("--followup-tests")
        with (task_dir / "verification.log").open("w") as log:
            verified = subprocess.run(verify, stdout=log, stderr=subprocess.STDOUT, timeout=240)
        record["independentVerificationPassed"] = verified.returncode == 0
        edge = "orderflow" if stage_verifier else "pricing"
        record["edgeRegressionPresent"] = (workspace / f"src/test/java/{edge}/EdgeRegression.java").exists()
        messages = {}
        thinking_blocks = set()
        for event in events:
            message = event.get("message", {})
            if event.get("type") == "assistant" and message.get("id"):
                messages[message["id"]] = message
                # stream-json may emit separate content blocks with the SAME message id.
                # The last tool/text block must not erase an earlier observed thinking block.
                for block in message.get("content", []):
                    if block.get("type") == "thinking" and block.get("thinking"):
                        thinking_blocks.add((message["id"], hashlib.sha256(block["thinking"].encode()).hexdigest()))
        record["assistantMessages"] = len(messages)
        record["responseModels"] = sorted({m.get("model", "") for m in messages.values()})
        record["thinkingBlocks"] = len(thinking_blocks)
        record["compactionEvents"] = sum(event.get("subtype") == "compact_boundary" for event in events)
        # On a compacting Task, CLI result.usage can omit internal compaction calls.
        # Keep both original records, but account from modelUsage once, never add both.
        model_usage = record.get("modelUsage")
        record["accountedTokensIncludingCache"] = (
            sum(usage["inputTokens"] + usage.get("cacheReadInputTokens", 0)
                + usage.get("cacheCreationInputTokens", 0) + usage["outputTokens"]
                for usage in model_usage.values()) if model_usage else None
        )
        manifest["tasks"].append(record)
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
        print(f"{task['name']}: exit={process.returncode}, result={record['resultSubtype']}, "
              f"scope={record['writeScopePassed']}, verified={record['independentVerificationPassed']}", flush=True)
        if timed_out or not record["writeScopePassed"] or not finals or (final and final.get("is_error")) or not record["independentVerificationPassed"]:
            print("Stop and inspect this sample; do not silently rerun it", flush=True)
            break
    print(f"Evidence: {output / 'manifest.json'}", flush=True)


if __name__ == "__main__":
    main()
