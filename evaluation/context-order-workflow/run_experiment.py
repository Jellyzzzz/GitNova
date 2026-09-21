#!/usr/bin/env python3
"""One real six-Task Session per invocation, with frozen inputs and stage-aware acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parents[1]
SHARED = ROOT.parent / "context-large-output"
sys.path.insert(0, str(SHARED))
from live_client import query
import importlib.util

scene_spec = importlib.util.spec_from_file_location("orderflow_verifier", ROOT / "verify.py")
scene_verify = importlib.util.module_from_spec(scene_spec)
scene_spec.loader.exec_module(scene_verify)
snapshot = scene_verify.snapshot


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--arm", choices=["G70", "G75", "G80", "G85", "CC"], default="G80")
    parser.add_argument("--max-tool-calls", type=int,
                        help="Explicit calibration override; frozen per output directory, never changed mid-arm")
    parser.add_argument("--max-model-calls", type=int)
    parser.add_argument("--max-output-tokens", type=int)
    parser.add_argument("--timeout-seconds", type=int, help="Both model call and read timeout")
    parser.add_argument("--task-timeout-seconds", type=int)
    efforts = {"minimal": "low", "low": "low", "medium": "high", "high": "high",
               "xhigh": "high", "max": "max", "ultra": "max"}
    parser.add_argument("--reasoning-effort", choices=efforts, help="Enable main-model thinking at this effort")
    parser.add_argument("--summary-reasoning-effort", choices=efforts, help="Enable independent summary thinking")
    parser.add_argument("--continue-delivery", action="store_true",
                        help="Explicitly continue only the final read-only Task after a verified but failed fifth Run")
    args = parser.parse_args()
    os.umask(0o077)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    arm_dir = output / args.arm
    previous_results = None
    if args.continue_delivery:
        previous_results = json.loads((arm_dir / "experiment.json").read_text())
        finished = previous_results["tasks"]
        if (previous_results["status"] != "STOPPED_ON_FAILED_SAMPLE" or len(finished) != 5
                or previous_results.get("blockedRemainingTasks") != ["delivery"]
                or finished[-1]["status"] not in {"PARTIAL", "FAILED"}
                or not all(t["independentVerificationPassed"] and t["writeScopePassed"]
                           and t["workspaceContinuity"] for t in finished)
                or (arm_dir / "experiment-before-delivery.json").exists()):
            raise ValueError("Continuation requires a preserved, verified fifth-Task failure and no prior continuation")
    elif arm_dir.exists():
        raise ValueError("Arm already exists. Preserve this sample; do not silently replay it.")
    scenario = json.loads((ROOT / "scenario.json").read_text())
    scenario["reasoningEffort"] = None
    scenario["summaryThinking"] = {"mode": "disabled", "effort": None}
    if args.reasoning_effort:
        scenario["thinkingMode"] = "enabled"
        scenario["reasoningEffort"] = efforts[args.reasoning_effort]
    if args.summary_reasoning_effort:
        scenario["summaryThinking"] = {"mode": "enabled", "effort": efforts[args.summary_reasoning_effort]}
    for value, keys in [(args.max_tool_calls, ["maxToolCalls"]),
                        (args.max_model_calls, ["maxModelCalls"]),
                        (args.max_output_tokens, ["maxOutputTokens"]),
                        (args.timeout_seconds, ["callTimeoutSeconds", "readTimeoutSeconds"]),
                        (args.task_timeout_seconds, ["taskWallClockLimitSeconds"])]:
        if value is not None:
            if value < 1:
                raise ValueError("Calibration limits must be positive")
            for key in keys:
                scenario["experiment"][key] = value
    scenario["arms"]["CC"]["requestedMaxOutputTokens"] = scenario["experiment"]["maxOutputTokens"]
    scenario["arms"]["CC"]["requestedMaxTurns"] = scenario["experiment"]["maxModelCalls"]
    jar = PROJECT / "target/gitnova-0.0.1-SNAPSHOT.jar"
    image = subprocess.check_output(["docker", "image", "inspect", "gitnova-workspace:java21", "--format", "{{.Id}}"], text=True).strip()
    frozen = {"scenario": scenario, "initialFiles": snapshot(ROOT / "repository"),
              "taskDigests": {t["name"]: hashlib.sha256((ROOT / t["file"]).read_bytes()).hexdigest() for t in scenario["tasks"]},
              "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "dockerImageId": image}
    manifest = output / "frozen.json"
    if manifest.exists():
        if json.loads(manifest.read_text()) != frozen:
            raise ValueError("Frozen inputs changed between arms")
    else:
        manifest.write_text(json.dumps(frozen, ensure_ascii=False, indent=2) + "\n")
    scenario_file = output / "effective-scenario.json"
    scenario_file.write_text(json.dumps(scenario, ensure_ascii=False, indent=2) + "\n")
    if args.arm == "CC":
        command = [sys.executable, str(SHARED / "run_claude_comparison.py"), "--output", str(arm_dir),
                   "--fixture-root", str(ROOT), "--scenario", str(scenario_file)]
        completed = subprocess.run(command, timeout=(scenario["experiment"]["taskWallClockLimitSeconds"] + 300) * len(scenario["tasks"]))
        raise SystemExit(completed.returncode)
    if not os.environ.get("MYSQL_PWD") or not os.environ.get("LLM_API_KEY"):
        raise ValueError("Load .env.local and MYSQL_PWD before starting; never pass secrets as arguments")
    with socket.socket() as connection:
        if connection.connect_ex(("127.0.0.1", 8080)) == 0:
            raise RuntimeError("Port 8080 is occupied; inspect the existing service before proceeding")
    if query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]:
        raise RuntimeError("An existing Run is active; do not restart services")
    repository = output / "local-repository"
    if not repository.exists():
        shutil.copytree(ROOT / "repository", repository)
        cli = ["java", "-Dloader.main=com.gitnova.gitlet.Main", "-cp", str(jar),
               "org.springframework.boot.loader.launch.PropertiesLauncher"]
        operations = [["init"], *[["add", path] for path in frozen["initialFiles"]],
                      ["commit", "Unmodified OrderFlow context fixture"], ["branch", "main"], ["checkout", "main"]]
        with (output / "local-cli.log").open("w") as log:
            for operation in operations:
                subprocess.run(cli + operation, cwd=repository, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=30)
        if snapshot(repository) != frozen["initialFiles"]:
            raise RuntimeError("Local CLI initialization changed the public source")
    arm_dir.mkdir(exist_ok=previous_results is not None)
    p = scenario["experiment"]
    arm = scenario["arms"][args.arm]
    env = {**os.environ, "LLM_MODEL": scenario["model"], "LLM_THINKING_MODE": scenario["thinkingMode"],
           "LLM_REASONING_EFFORT": scenario["reasoningEffort"] or "high",
           "SUMMARY_LLM_THINKING_MODE": scenario["summaryThinking"]["mode"],
           "SUMMARY_LLM_REASONING_EFFORT": scenario["summaryThinking"]["effort"] or "high",
           "WORKSPACE_DOCKER_ENABLED": "true", "WORKSPACE_DOCKER_USER": f"{os.getuid()}:{os.getgid()}",
           "RATE_LIMIT_ENABLED": "true", "AGENT_CONTEXT_WINDOW_TOKENS": str(p["contextWindowTokens"]),
           "AGENT_CONTEXT_SAFETY_MARGIN_TOKENS": str(p["safetyMarginTokens"]),
           "AGENT_CONTEXT_SUMMARY_TRIGGER_RATIO": str(arm["summaryTriggerRatio"]),
           "AGENT_CONTEXT_COMPACT_TRIGGER_RATIO": str(p["compactTriggerRatio"]),
           "AGENT_CONTEXT_KEEP_RECENT_GROUPS": str(p["keepRecentGroups"]),
           "AGENT_OBSERVATION_MAX_INLINE_TOKENS": str(p["maxInlineTokens"]),
           "AGENT_OBSERVATION_MAX_PREVIEW_TOKENS": str(p["maxPreviewTokens"]),
           "AGENT_CONTEXT_SUMMARY_ENABLED": "true", "AGENT_OBSERVATION_EXTERNALIZATION_ENABLED": "true"}
    client = [sys.executable, str(SHARED / "live_client.py")]
    client_args = ["--output", str(output), "--repository", str(repository), "--scenario", str(scenario_file),
                   "--fixture-root", str(ROOT)]
    server_log = arm_dir / ("delivery-continuation-server.log" if previous_results else "server.log")
    results = previous_results or {"arm": args.arm, "tasks": [], "summaryPublished": 0, "status": "RUNNING"}
    if previous_results:
        shutil.copy2(arm_dir / "experiment.json", arm_dir / "experiment-before-delivery.json")
        results["status"] = "RUNNING_CONTINUATION"
        results["continuationAfterFailedRun"] = results["tasks"][-1]["runId"]
        results.pop("blockedRemainingTasks", None)
    with server_log.open("w") as log:
        server = subprocess.Popen(["java", "-jar", str(jar),
                    f"--gitnova.llm.timeout={p['callTimeoutSeconds']}",
                    f"--gitnova.llm.read-timeout={p['readTimeoutSeconds']}",
                    f"--gitnova.agent.runtime.max-output-tokens={p['maxOutputTokens']}",
                    f"--gitnova.agent.runtime.max-model-calls={p['maxModelCalls']}",
                    f"--gitnova.agent.runtime.max-tool-calls={p['maxToolCalls']}"],
                    cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while "Started GitNovaApplication" not in server_log.read_text():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Service failed to start; inspect {server_log}")
                time.sleep(1)
            print(f"{args.arm}: service ready pid={server.pid}", flush=True)
            if not (output / ".client-state.json").exists():
                subprocess.run(client + ["setup"] + client_args, env=env, check=True, timeout=180)
            previous_after = frozen["initialFiles"]
            if previous_results:
                previous_after = json.loads((arm_dir / "followups/regression/post-workspace.json").read_text())["files"]
            for index, task in enumerate(scenario["tasks"]):
                if previous_results and index < 5:
                    continue
                task_dir = arm_dir if index == 0 else arm_dir / "followups" / task["name"]
                command = client + ["run", "--arm", args.arm] + client_args
                if index:
                    command += ["--follow-up", task["name"], "--task-file", str(ROOT / task["file"])]
                if previous_results:
                    command += ["--continue-after-failure"]
                print(f"{args.arm}: Task {index + 1} {task['name']} started", flush=True)
                with (arm_dir / f"task-{index + 1}-client.log").open("w") as client_log:
                    subprocess.run(command, env=env, stdout=client_log, stderr=subprocess.STDOUT, check=True,
                                   timeout=p["taskWallClockLimitSeconds"] + 60)
                task_result = json.loads((task_dir / "summary.json").read_text())
                steps = json.loads((task_dir / "run-steps.json").read_text())
                before = json.loads((task_dir / "initial-workspace.json").read_text())["files"]
                after = json.loads((task_dir / "post-workspace.json").read_text())["files"]
                changed = sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
                task_result["name"] = task["name"]
                task_result["changedFiles"] = changed
                task_result["writeScopePassed"] = set(changed) <= set(task["editableFiles"])
                task_result["workspaceContinuity"] = before == previous_after
                previous_after = after
                summaries = [s for s in steps if s["type"] == "CONTEXT_SUMMARY_CREATED"]
                responses = [s for s in steps if s["type"] == "MODEL_RESPONSE"]
                started_ids = {s["payload"]["modelCallId"] for s in steps if s["type"] == "MODEL_CALL_STARTED"}
                response_ids = {s["payload"]["modelCallId"] for s in responses}
                task_result["summaryPublished"] = len(summaries)
                task_result["summaryControlDisarmed"] = sum(
                    s["type"] == "CONTEXT_CONTROL_UPDATED" and s["payload"].get("summaryArmed") is False
                    for s in steps)
                results["summaryPublished"] += len(summaries)
                task_result["modelCalls"] = len(responses)
                task_result["inputPeak"] = max((s["payload"].get("usage", {}).get("inputTokens") or 0 for s in responses), default=0)
                # A rejected or lost response may still cost tokens. Never count its missing usage as zero.
                task_result["unansweredMainCalls"] = len(started_ids - response_ids)
                task_result["unknownMainUsage"] = task_result["unansweredMainCalls"] + sum(
                    s["payload"].get("usage", {}).get("totalTokens") is None for s in responses)
                task_result["knownTotalTokens"] = task_result["mainUsage"].get("totalTokens", 0) + task_result["summaryUsage"].get("totalTokens", 0)
                verification = [sys.executable, str(ROOT / "verify.py"), "--candidate", task_result["workspace"],
                                "--stage", str(index + 1), "--output", str(task_dir / "verification")]
                with (task_dir / "verification.log").open("w") as verifier_log:
                    validated = subprocess.run(verification, env=env, stdout=verifier_log, stderr=subprocess.STDOUT, timeout=240)
                task_result["independentVerificationPassed"] = validated.returncode == 0
                results["tasks"].append(task_result)
                (arm_dir / "experiment.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
                print(f"{args.arm}/{task['name']}: {task_result['status']}, tokens={task_result['knownTotalTokens']}, "
                      f"peak={task_result['inputPeak']}, summaries={len(summaries)}, verified={validated.returncode == 0}", flush=True)
                if not task_result["writeScopePassed"] or not task_result["workspaceContinuity"] or validated.returncode != 0 or task_result["status"] != "COMPLETED":
                    results["status"] = "STOPPED_ON_FAILED_SAMPLE"
                    results["blockedRemainingTasks"] = [t["name"] for t in scenario["tasks"][index + 1:]]
                    break
            else:
                results["status"] = "COMPLETED_WITH_PRIOR_FAILURE" if previous_results else "COMPLETED"
            results["knownTotalTokens"] = sum(t["knownTotalTokens"] for t in results["tasks"])
            results["summaryCalibration"] = (
                "INSPECT_CREATED_SUMMARIES_AND_SUBSEQUENT_REQUESTS" if results["summaryPublished"]
                else "NO_SUMMARY_PUBLISHED_CHECK_CONTROL_AND_LOGS"
            )
            (arm_dir / "experiment.json").write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        finally:
            # Unknown/active durable state is not authorization to kill the executing JVM.
            if query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]:
                print(f"Leaving pid={server.pid} alive: a Run is active; inspect before stopping", flush=True)
                raise RuntimeError("An active Run requires inspection")
            server.terminate()
            server.wait(timeout=30)
    print(f"Evidence: {arm_dir / 'experiment.json'}; service stopped", flush=True)


if __name__ == "__main__":
    main()
