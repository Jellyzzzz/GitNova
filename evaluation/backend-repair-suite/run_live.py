#!/usr/bin/env python3
"""Sequential live preflight. Reuses the existing HTTP/push client and independent verifier."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

from suite import ROOT, load_suite, snapshot

PROJECT = ROOT.parents[1]
SHARED = ROOT.parent / "context-large-output"
sys.path.insert(0, str(SHARED))
from live_client import query


def usage_report(steps):
    starts = {step["payload"]["modelCallId"] for step in steps if step["type"] == "MODEL_CALL_STARTED"}
    responses = [step["payload"] for step in steps if step["type"] == "MODEL_RESPONSE"]
    answered = {response["modelCallId"] for response in responses}
    records = {"main": [response.get("usage") or {} for response in responses], "summary": [], "compaction": []}
    for step in steps:
        if step["type"] == "CONTEXT_SUMMARY_RESULT":
            payload = step["payload"]
            kind = "compaction" if payload.get("operation") == "COMPACTION" else "summary"
            # A failed context operation may lack provider usage; keep it unknown, not zero.
            records[kind].append((payload.get("output") or {}).get("usage") or {})
    report = {}
    for kind, values in records.items():
        known = Counter()
        missing = Counter()
        for value in values:
            for key in ("inputTokens", "outputTokens", "totalTokens"):
                if value.get(key) is None:
                    missing[key] += 1
                else:
                    known[key] += value[key]
        unanswered = len(starts - answered) if kind == "main" else 0
        report[kind] = {"records": len(values), "unansweredCalls": unanswered, "knownTokens": dict(known),
                        "unknownByMetric": {key: missing[key] + unanswered for key in ("inputTokens", "outputTokens", "totalTokens")}}
    report["knownTotalTokens"] = sum(report[kind]["knownTokens"].get("totalTokens", 0) for kind in records)
    report["unknownTotalUsageCalls"] = sum(report[kind]["unknownByMetric"]["totalTokens"] for kind in records)
    report["inputPeak"] = max((record.get("inputTokens") for record in records["main"] if record.get("inputTokens") is not None), default=None)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New private output directory, no implicit retry")
    parser.add_argument("--tasks", nargs="+")
    parser.add_argument("--session-chain", action="store_true", help="Eight sequential Eventstats Tasks in one Session; stop at the first failed stage")
    args = parser.parse_args()
    tasks = {task["id"]: task for task in load_suite()["tasks"]}
    if args.session_chain:
        if args.tasks:
            parser.error("--session-chain uses its frozen eight-Task plan; do not also pass --tasks")
        from session_chain import definition, verify as verify_stage
        _, combined, _ = definition()
        tasks[combined["id"]] = combined
        args.tasks = [combined["id"]]
    elif not args.tasks:
        args.tasks = ["stockroom-01", "eventstats-01", "bundlesync-01", "jobqueue-01"]
    if len(set(args.tasks)) != len(args.tasks) or any(task not in tasks for task in args.tasks):
        parser.error("Choose distinct task ids from the suite")
    if not os.environ.get("LLM_API_KEY") or not os.environ.get("MYSQL_PWD"):
        parser.error("Load LLM_API_KEY and MYSQL_PWD through the environment; never put secrets in arguments")
    with socket.socket() as connection:
        if connection.connect_ex(("127.0.0.1", 8080)) == 0:
            raise RuntimeError("8080 is occupied; inspect the existing service first")
    if query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]:
        raise RuntimeError("An existing Run is active; preserve it and inspect before starting")
    packs = args.packs.resolve()
    frozen = json.loads((packs / "freeze.json").read_text())
    fixtures = {}
    for task_id in args.tasks:
        fixture = packs / "tasks" / task_id
        source = frozen["tasks"][task_id]
        if snapshot(fixture / "repository") != source["files"]:
            raise ValueError(f"Frozen public source changed: {task_id}")
        documents = {"scenario.json": source["scenarioSha256"]}
        documents.update(source["prompts"] if args.session_chain else {"task.md": source["promptSha256"]})
        for filename, digest in documents.items():
            if hashlib.sha256((fixture / filename).read_bytes()).hexdigest() != digest:
                raise ValueError(f"Frozen {filename} changed: {task_id}")
        fixtures[task_id] = (fixture, json.loads((fixture / "scenario.json").read_text()))
    if args.session_chain and snapshot(ROOT) != frozen["evaluatorFiles"]:
        raise ValueError("Chain evaluator changed after prepare; create a new frozen pack, do not silently change acceptance")
    first = fixtures[args.tasks[0]][1]
    for _, scenario in fixtures.values():
        for key in ("experiment", "model", "thinkingMode", "reasoningEffort", "summaryThinking", "temperature", "arms"):
            if scenario[key] != first[key]:
                raise ValueError("A single-service batch must use identical runtime policies")
    image = subprocess.check_output(["docker", "image", "inspect", "gitnova-workspace:java21", "--format", "{{.Id}}"], text=True).strip()
    jar = PROJECT / "target/gitnova-0.0.1-SNAPSHOT.jar"
    os.umask(0o077)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    planned = len(first["tasks"]) if args.session_chain else len(args.tasks)
    results = {"status": "STARTING", "kind": "eight-task-session-chain" if args.session_chain else "live-preflight-not-full-60-run-batch",
               "selectedTasks": args.tasks, "plannedTasks": planned,
               "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "imageId": image,
               "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=PROJECT, text=True).strip(),
               "fixtureFreeze": frozen, "evaluatorFilesAtRun": snapshot(ROOT),
               "clientSha256": hashlib.sha256((SHARED / "live_client.py").read_bytes()).hexdigest(),
               "runtimePolicy": first, "tasks": [], "serviceStopped": False}
    report_file = output / "batch.json"
    report_file.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    p = first["experiment"]
    env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "LLM_MODEL": first["model"],
           "LLM_THINKING_MODE": first["thinkingMode"], "LLM_REASONING_EFFORT": first["reasoningEffort"],
           "SUMMARY_LLM_THINKING_MODE": first["summaryThinking"]["mode"],
           "SUMMARY_LLM_REASONING_EFFORT": first["summaryThinking"]["effort"],
           "WORKSPACE_DOCKER_ENABLED": "true", "WORKSPACE_DOCKER_IMAGE": image,
           "WORKSPACE_DOCKER_USER": f"{os.getuid()}:{os.getgid()}", "RATE_LIMIT_ENABLED": "true",
           "AGENT_CONTEXT_WINDOW_TOKENS": str(p["contextWindowTokens"]),
           "AGENT_CONTEXT_SAFETY_MARGIN_TOKENS": str(p["safetyMarginTokens"]),
           "AGENT_CONTEXT_SUMMARY_TRIGGER_RATIO": str(p["summaryTriggerRatio"]),
           "AGENT_CONTEXT_COMPACT_TRIGGER_RATIO": str(p["compactTriggerRatio"]),
           "AGENT_CONTEXT_COMPACT_TARGET_RATIO": str(p["compactTargetRatio"]),
           "AGENT_CONTEXT_KEEP_RECENT_GROUPS": str(p["keepRecentGroups"]),
           "AGENT_OBSERVATION_MAX_INLINE_TOKENS": str(p["maxInlineTokens"]),
           "AGENT_OBSERVATION_MAX_PREVIEW_TOKENS": str(p["maxPreviewTokens"]),
           "AGENT_CONTEXT_SUMMARY_ENABLED": "true", "AGENT_OBSERVATION_EXTERNALIZATION_ENABLED": "true",
           "SPRING_DATASOURCE_PASSWORD": os.environ["MYSQL_PWD"]}
    cli = ["java", "-Dloader.main=com.gitnova.gitlet.Main", "-cp", str(jar),
           "org.springframework.boot.loader.launch.PropertiesLauncher"]
    server_log = output / "server.log"
    with server_log.open("w") as log:
        server = subprocess.Popen(["java", "-jar", str(jar),
                    f"--gitnova.llm.timeout={p['callTimeoutSeconds']}",
                    f"--gitnova.llm.read-timeout={p['readTimeoutSeconds']}",
                    f"--gitnova.agent.runtime.max-output-tokens={p['maxOutputTokens']}",
                    f"--gitnova.agent.runtime.max-model-calls={p['maxModelCalls']}",
                    f"--gitnova.agent.runtime.max-tool-calls={p['maxToolCalls']}"],
                    cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
        results["serverPid"] = server.pid
        report_file.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
        try:
            deadline = time.monotonic() + 90
            while "Started GitNovaApplication" not in server_log.read_text():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Service failed to start; inspect {server_log}")
                time.sleep(1)
            results["status"] = "RUNNING"
            print(f"Service ready pid={server.pid}; starting {planned} HTTP tasks; sharedSession={args.session_chain}", flush=True)
            for task_id, (fixture, scenario) in fixtures.items():
                trial = output / task_id
                trial.mkdir()
                repository = trial / "local-repository"
                shutil.copytree(fixture / "repository", repository)
                operations = [["init"], *[["add", path] for path in frozen["tasks"][task_id]["files"]],
                              ["commit", f"Unmodified {task_id} fixture"], ["branch", "main"], ["checkout", "main"]]
                with (trial / "local-cli.log").open("w") as cli_log:
                    for operation in operations:
                        subprocess.run(cli + operation, cwd=repository, env=env, stdout=cli_log,
                                       stderr=subprocess.STDOUT, timeout=30, check=True)
                if snapshot(repository) != frozen["tasks"][task_id]["files"]:
                    raise ValueError("CLI initialization changed source files")
                client_args = ["--output", str(trial), "--repository", str(repository),
                               "--fixture-root", str(fixture), "--scenario", str(fixture / "scenario.json")]
                client = [sys.executable, str(SHARED / "live_client.py")]
                with (trial / "setup.log").open("w") as setup_log:
                    subprocess.run(client + ["setup"] + client_args, env=env, stdout=setup_log,
                                   stderr=subprocess.STDOUT, check=True, timeout=180)
                print(f"{task_id}: pushed through CLI; creating Session", flush=True)
                stages = scenario["tasks"] if args.session_chain else [{"name": task_id}]
                previous = None
                for number, stage in enumerate(stages, 1):
                    continuation = ["--follow-up", stage["name"], "--task-file", str(fixture / stage["file"])] if number > 1 else []
                    destination = trial / "G80" if number == 1 else trial / "G80/followups" / stage["name"]
                    with (trial / f"{stage['name']}-client.log").open("w") as client_log:
                        subprocess.run(client + ["run", "--arm", "G80"] + client_args + continuation,
                                       env=env, stdout=client_log, stderr=subprocess.STDOUT, check=True,
                                       timeout=p["taskWallClockLimitSeconds"] + 90)
                    result = json.loads((destination / "summary.json").read_text())
                    steps = json.loads((destination / "run-steps.json").read_text())
                    result.update(taskName=stage["name"], accounting=usage_report(steps))
                    if args.session_chain:
                        initial = json.loads((destination / "initial-workspace.json").read_text())
                        final = json.loads((destination / "post-workspace.json").read_text())
                        identity = json.loads((destination / "workspace.json").read_text())
                        continuity = bool(steps) and final["generation"] >= initial["generation"]
                        if initial["files"] != final["files"]:
                            continuity = continuity and final["generation"] > initial["generation"]
                        if previous:
                            continuity = continuity and all((
                                result["sessionId"] == previous["sessionId"], identity["workspaceId"] == previous["workspaceId"],
                                result["taskId"] != previous["taskId"], result["runId"] != previous["runId"],
                                initial == previous["postWorkspace"],
                                bool(steps) and min(step["sequence"] for step in steps) > previous["lastRunSequence"]))
                        result.update(workspaceId=identity["workspaceId"], generationBefore=initial["generation"],
                                      generationAfter=final["generation"], continuityPassed=continuity,
                                      postWorkspace=final, lastRunSequence=max((step["sequence"] for step in steps), default=0))
                        verification = destination / "verification"
                        verification.mkdir()
                        verdict = verify_stage(Path(result["workspace"]), initial["files"], number, image, verification)
                        (verification / "report.json").write_text(json.dumps(verdict, indent=2) + "\n")
                        previous = result
                    else:
                        with (trial / "verification.log").open("w") as verification_log:
                            verified = subprocess.run([sys.executable, str(ROOT / "suite.py"), "verify", "--task", task_id,
                                            "--candidate", result["workspace"], "--image", image, "--output", str(trial / "verification")],
                                            env=env, stdout=verification_log, stderr=subprocess.STDOUT, timeout=90)
                        if verified.returncode not in (0, 1) or not (trial / "verification/report.json").exists():
                            raise RuntimeError(f"Verifier did not produce a result for {task_id}")
                        verdict = json.loads((trial / "verification/report.json").read_text())["variants"][task_id]
                    result["verification"] = verdict
                    result["independentVerificationPassed"] = verdict["accepted"]
                    result["taskSucceeded"] = result["status"] == "COMPLETED" and verdict["accepted"] and result.get("continuityPassed", True)
                    results["tasks"].append(result)
                    report_file.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
                    print(f"{stage['name']}: Run={result['status']}, oracle={verdict['status']}, "
                          f"tokens={result['accounting']['knownTotalTokens']}, modelResponses={result['accounting']['main']['records']}", flush=True)
                    if args.session_chain and not result["taskSucceeded"]:
                        results["status"] = "STOPPED_AT_FAILED_STAGE"
                        results["unstartedTasks"] = [task["name"] for task in stages[number:]]
                        break
                # Independent cold starts may continue; the Session chain never skips a failed stage.
            if results["status"] != "STOPPED_AT_FAILED_STAGE":
                results["status"] = "FINISHED"
            results["passed"] = sum(task["taskSucceeded"] for task in results["tasks"])
            results["knownTotalTokens"] = sum(task["accounting"]["knownTotalTokens"] for task in results["tasks"])
            results["unknownTotalUsageCalls"] = sum(task["accounting"]["unknownTotalUsageCalls"] for task in results["tasks"])
        except BaseException as failure:
            results["status"] = "INTERRUPTED"
            results["errorType"] = type(failure).__name__
            raise
        finally:
            try:
                active = query("SELECT JSON_OBJECT('active',COUNT(*)) FROM agent_run WHERE status IN ('QUEUED','RUNNING')")[0]["active"]
                if active:
                    print(f"Preserving service pid={server.pid}: {active} Run(s) still active; no automatic replay or termination", flush=True)
                else:
                    server.terminate()
                    server.wait(timeout=30)
                    results["serviceStopped"] = True
            finally:
                report_file.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n")
    print(f"Evidence: {report_file}; pass={results['passed']}/{planned}; serviceStopped={results['serviceStopped']}", flush=True)
    raise SystemExit(0 if results["passed"] == planned else 1)


if __name__ == "__main__":
    main()
