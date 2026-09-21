#!/usr/bin/env python3
"""One bounded, real HTTP multi-Task matrix. Preserves every failed sample and all private evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

from live_client import FIXTURE, PROJECT, query


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--scenario", type=Path, default=FIXTURE / "scenario-multitask-32k.json")
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / ".client-state.json").exists():
        raise ValueError("Use a fresh output directory; never overwrite or silently repeat a matrix")
    with socket.socket() as connection:
        if connection.connect_ex(("127.0.0.1", 8080)) == 0:
            raise RuntimeError("Port 8080 is occupied; inspect and stop the idle test service first")
    if query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]:
        raise RuntimeError("An existing Run is active; do not change services")
    scenario_file = args.scenario.resolve()
    scenario = json.loads(scenario_file.read_text())
    jar = PROJECT / "target/gitnova-0.0.1-SNAPSHOT.jar"
    image = subprocess.check_output(["docker", "image", "inspect", "gitnova-workspace:java21", "--format", "{{.Id}}"], text=True).strip()
    manifest = {"scenario": scenario, "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "imageId": image,
                "taskDigests": {task["name"]: hashlib.sha256((FIXTURE / task["file"]).read_bytes()).hexdigest()
                                for task in scenario["tasks"]}, "arms": {}}
    (args.output / "matrix.json").write_text(json.dumps(manifest, indent=2))
    common = [sys.executable, str(FIXTURE / "live_client.py")]
    client_args = ["--output", str(args.output), "--repository", str(args.repository), "--scenario", str(scenario_file)]
    p = scenario["experiment"]
    for arm, switches in scenario["arms"].items():
        arm_dir = args.output / arm
        arm_dir.mkdir()
        env = {**os.environ, "LLM_MODEL": scenario["model"], "LLM_THINKING_MODE": scenario["thinkingMode"],
               "WORKSPACE_DOCKER_ENABLED": "true", "WORKSPACE_DOCKER_USER": f"{os.getuid()}:{os.getgid()}",
               "RATE_LIMIT_ENABLED": "true", "AGENT_CONTEXT_WINDOW_TOKENS": str(p["contextWindowTokens"]),
               "AGENT_CONTEXT_SAFETY_MARGIN_TOKENS": str(p["safetyMarginTokens"]),
               "AGENT_CONTEXT_SUMMARY_TRIGGER_RATIO": str(p["summaryTriggerRatio"]),
               "AGENT_CONTEXT_COMPACT_TRIGGER_RATIO": str(p["compactTriggerRatio"]),
               "AGENT_CONTEXT_KEEP_RECENT_GROUPS": str(p["keepRecentGroups"]),
               "AGENT_OBSERVATION_MAX_INLINE_TOKENS": str(p["maxInlineTokens"]),
               "AGENT_OBSERVATION_MAX_PREVIEW_TOKENS": str(p["maxPreviewTokens"]),
               "AGENT_CONTEXT_SUMMARY_ENABLED": str(switches["summarization"]).lower(),
               "AGENT_OBSERVATION_EXTERNALIZATION_ENABLED": str(switches["externalization"]).lower()}
        server_log = arm_dir / "server.log"
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
                        raise RuntimeError(f"Server {arm} did not start; inspect {server_log}")
                    time.sleep(1)
                print(f"{arm}: service ready, pid={server.pid}, externalization={switches['externalization']}, summary={switches['summarization']}", flush=True)
                if arm == "A":
                    subprocess.run(common + ["setup"] + client_args, env=env, check=True)
                results = []
                manifest["arms"][arm] = results
                for index, task in enumerate(scenario["tasks"]):
                    output = arm_dir if index == 0 else arm_dir / "followups" / task["name"]
                    command = common + ["run", "--arm", arm] + client_args
                    if index:
                        command += ["--follow-up", task["name"], "--task-file", str(FIXTURE / task["file"]), "--continue-after-failure"]
                    print(f"{arm}: starting Task {index + 1} ({task['name']})", flush=True)
                    subprocess.run(command, env=env, check=True)
                    result = json.loads((output / "summary.json").read_text())
                    steps = json.loads((output / "run-steps.json").read_text())
                    before = json.loads((output / "initial-workspace.json").read_text())["files"]
                    after = json.loads((output / "post-workspace.json").read_text())["files"]
                    changed = sorted(name for name in set(before) | set(after) if before.get(name) != after.get(name))
                    result["taskName"] = task["name"]
                    result["changedFiles"] = changed
                    result["writeScopePassed"] = set(changed) <= set(task["editableFiles"])
                    result["edgeRegressionPresent"] = "src/test/java/pricing/EdgeRegression.java" in after
                    starts = [s for s in steps if s["type"] == "MODEL_CALL_STARTED"]
                    responses = [s for s in steps if s["type"] == "MODEL_RESPONSE"]
                    summary_calls = [s for s in steps if s["type"] == "CONTEXT_SUMMARY_RESULT"]
                    result["unansweredMainCalls"] = len(starts) - len(responses)
                    result["unknownUsageRecords"] = sum(s["payload"].get("usage", {}).get("totalTokens") is None for s in responses)
                    result["unknownSummaryUsageRecords"] = sum(s["payload"].get("output", {}).get("usage", {}).get("totalTokens") is None for s in summary_calls)
                    result["firstModelInput"] = starts[0]["payload"] if starts else None
                    result["lastModelInput"] = starts[-1]["payload"] if starts else None
                    result["knownTotalTokens"] = result["mainUsage"].get("totalTokens", 0) + result["summaryUsage"].get("totalTokens", 0)
                    verification = [sys.executable, str(FIXTURE / "verify.py"), "--candidate", result["workspace"], "--output", str(output / "verification")]
                    if (Path(result["workspace"]) / "src/test/java/pricing/EdgeRegression.java").exists():
                        verification.append("--followup-tests")
                    with (output / "verification.log").open("w") as verifier_log:
                        verified = subprocess.run(verification, stdout=verifier_log, stderr=subprocess.STDOUT, env=env)
                    result["independentVerificationPassed"] = verified.returncode == 0
                    # Passing the original pricing suite does not mean Task 2 produced new tests.
                    result["newRegressionVerificationPassed"] = result["edgeRegressionPresent"] and verified.returncode == 0
                    results.append(result)
                    (args.output / "matrix.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
                    print(f"{arm}/{task['name']}: status={result['status']}, tokens={result['knownTotalTokens']}, scope={result['writeScopePassed']}, independentTests={verified.returncode == 0}", flush=True)
                    if not result["writeScopePassed"]:
                        raise RuntimeError("Observed unauthorized file changes; preserve evidence and stop")
            finally:
                # Never stop a JVM that may still own a running tool. Unknown DB state also leaves it alive.
                if query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]:
                    print(f"Leaving service pid={server.pid} alive because a Run is still active", flush=True)
                    raise RuntimeError("Active Run requires inspection before changing services")
                server.terminate()
                try:
                    server.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    raise RuntimeError(f"Service pid={server.pid} did not stop; inspect before continuing")
        print(f"{arm}: three Tasks recorded; service stopped", flush=True)
    print(f"Matrix complete: {args.output / 'matrix.json'}", flush=True)


if __name__ == "__main__":
    main()
