#!/usr/bin/env python3
"""Real HTTP/CLI submission plus read-only MySQL observation. Never inserts Steps."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.parse
import urllib.request

FIXTURE = Path(__file__).resolve().parent
PROJECT = FIXTURE.parents[1]
BASE = "http://127.0.0.1:8080"


def request(path, data, token=None, key=None, form=False):
    headers = {"Content-Type": "application/x-www-form-urlencoded" if form else "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    body = urllib.parse.urlencode(data).encode() if form else json.dumps(data).encode()
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=body, headers=headers), timeout=120) as response:
        result = json.load(response)
    if result.get("code") != 200:
        raise RuntimeError(f"HTTP application failure: {result.get('code')}, {result.get('message')}")
    return result["data"]


def query(sql):
    if not sql.lstrip().upper().startswith("SELECT "):
        raise ValueError("Experiment observer only permits SELECT")
    result = subprocess.run(["mysql", "--protocol=TCP", "-h", "127.0.0.1", "-u", "root",
                             "--connect-timeout=5", "--batch", "--raw", "--skip-column-names",
                             "gitnova", "-e", sql], capture_output=True, text=True, timeout=15, check=True)
    return [json.loads(line) for line in result.stdout.splitlines() if line]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["setup", "run"])
    parser.add_argument("--arm")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--scenario", type=Path, default=FIXTURE / "scenario.json")
    parser.add_argument("--fixture-root", type=Path, default=FIXTURE,
                        help="Public fixture/tasks root; leaves the HTTP and push protocol unchanged")
    parser.add_argument("--follow-up", help="Stable name for a new Task in this arm's existing Session")
    parser.add_argument("--task-file", type=Path, help="User message for --follow-up")
    parser.add_argument("--continue-after-failure", action="store_true",
                        help="Explicitly submit a different follow-up after a terminal failed/partial Run; never replays it")
    args = parser.parse_args()
    if args.follow_up and (args.action != "run" or not args.task_file
                           or not re.fullmatch(r"[a-z0-9-]{1,40}", args.follow_up)):
        parser.error("--follow-up requires run, --task-file and a short lowercase/digit/hyphen name")
    if args.task_file and not args.follow_up:
        parser.error("--task-file requires --follow-up")
    if args.continue_after_failure and not args.follow_up:
        parser.error("--continue-after-failure requires --follow-up")
    fixture = args.fixture_root.resolve()
    desired = json.loads(args.scenario.read_text())
    if args.arm and (not re.fullmatch(r"[A-Za-z0-9-]{1,16}", args.arm) or args.arm not in desired["arms"]):
        parser.error("arm must be declared by the scenario")
    initial_task = fixture / desired["tasks"][0]["file"] if desired.get("tasks") else fixture / "TASK.md"
    message = (args.task_file or initial_task).read_text()
    if not message.strip():
        parser.error("Task message must not be blank")
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=True)
    state_file = args.output / ".client-state.json"
    if args.action == "setup":
        if state_file.exists():
            raise SystemExit("Client state already exists; refusing duplicate setup")
        suffix = secrets.token_hex(6)
        username = "s2_" + suffix
        password = secrets.token_urlsafe(24)
        actor = request("/api/auth/register", {"username": username, "password": password,
                        "email": username + "@example.test"}, form=True)
        token = request("/api/auth/login", {"username": username, "password": password}, form=True)
        repository = request("/api/repos", {"name": "context-" + suffix,
                             "description": "Controlled context experiment", "isPrivate": "true"}, token, form=True)
        state = {"suffix": suffix, "username": username, "password": password, "token": token,
                 "actorId": actor["id"], "repoId": repository["id"], "arms": {}}
        state_file.write_text(json.dumps(state, indent=2))
        pushed = subprocess.run(["java", "-Dloader.main=com.gitnova.gitlet.Main", "-cp",
                                 str(PROJECT / "target/gitnova-0.0.1-SNAPSHOT.jar"),
                                 "org.springframework.boot.loader.launch.PropertiesLauncher",
                                 "push", BASE, str(repository["id"]), "main"], cwd=args.repository,
                                env={**os.environ, "GITNOVA_TOKEN": token}, capture_output=True,
                                text=True, check=True, timeout=120)
        (args.output / "push.log").write_text(pushed.stdout + pushed.stderr)
        print(f"Created and pushed repoId={repository['id']}; credentials kept in private client state", flush=True)
        return

    if not args.arm:
        raise SystemExit("run requires --arm")
    if not os.environ.get("MYSQL_PWD"):
        raise SystemExit("Read-only observer requires MYSQL_PWD in the environment")
    state = json.loads(state_file.read_text())
    arm = state["arms"].setdefault(args.arm, {})
    output = args.output / args.arm
    task_record = arm
    if args.follow_up:
        if "session" not in arm or "task" not in arm:
            raise ValueError("A follow-up requires an existing Session and initial Task")
        task_record = arm.setdefault("followUps", {}).setdefault(args.follow_up, {})
        output = output / "followups" / args.follow_up
    output.mkdir(parents=True, exist_ok=True)
    message_digest = hashlib.sha256(message.encode()).hexdigest()
    if task_record.get("messageDigest", message_digest) != message_digest:
        raise ValueError("This logical Task already has a different message; choose a new follow-up name")
    route = f"/api/repos/{state['repoId']}/agent/sessions"
    if "session" not in arm:
        arm["session"] = request(route, {"branchName": "main"}, state["token"], f"s2-{state['suffix']}-{args.arm}-session")
        state_file.write_text(json.dumps(state, indent=2))
    session = arm["session"]
    session_id = session["sessionId"]
    if not re.fullmatch(r"[0-9a-f-]{36}", session_id):
        raise ValueError("Unexpected Session identity")
    workspace = query("SELECT JSON_OBJECT('providerRef',provider_ref,'generation',generation,'epoch',workspace_epoch) "
                      f"FROM agent_workspace WHERE session_id='{session_id}'")[0]
    workspace_root = Path(workspace["providerRef"]).resolve()
    if not workspace_root.is_relative_to((PROJECT / "data/workspaces").resolve()):
        raise ValueError("Unexpected Workspace root; inspect configured storage before proceeding")
    if "task" not in task_record:
        if args.follow_up:
            prior = [arm] + [record for record in arm["followUps"].values()
                             if record is not task_record and "task" in record]
            for record in prior:
                prior_id = record["task"]["initialRunId"]
                if not re.fullmatch(r"[0-9a-f-]{36}", prior_id):
                    raise ValueError("Unexpected predecessor Run identity")
                status = query(f"SELECT JSON_OBJECT('status',status) FROM agent_run WHERE run_id='{prior_id}'")[0]
                if status["status"] != "COMPLETED" and not (
                        args.continue_after_failure and status["status"] in {"PARTIAL", "FAILED"}):
                    raise ValueError("Preceding Task did not complete; preserve the failure instead of continuing silently")
        actual = {str(file.relative_to(workspace_root)): hashlib.sha256(file.read_bytes()).hexdigest()
                  for file in sorted(workspace_root.rglob("*")) if file.is_file()}
        expected = {str(file.relative_to(fixture / "repository")): hashlib.sha256(file.read_bytes()).hexdigest()
                    for file in sorted((fixture / "repository").rglob("*")) if file.is_file()}
        if not args.follow_up and (actual != expected or workspace["generation"] != 0):
            raise AssertionError("Experiment must begin with exactly the unchanged fixture and generation 0")
        (output / "initial-workspace.json").write_text(json.dumps({"files": actual, **workspace}, indent=2))
        (output / "task.md").write_text(message)
        task_record["messageDigest"] = message_digest
        state_file.write_text(json.dumps(state, indent=2))
        key = f"s2-{state['suffix']}-{args.arm}-task" + (f"-{args.follow_up}" if args.follow_up else "")
        task_record["task"] = request(route + f"/{session_id}/tasks", {"message": message}, state["token"], key)
        state_file.write_text(json.dumps(state, indent=2))
    run_id = task_record["task"]["initialRunId"]
    if not re.fullmatch(r"[0-9a-f-]{36}", run_id):
        raise ValueError("Unexpected Run identity")
    run_sql = ("SELECT JSON_OBJECT('runId',run_id,'status',status,'terminationReason',termination_reason,"
               "'config',execution_config_json,'configDigest',execution_config_digest,"
               "'createdAt',created_at,'claimedAt',claimed_at,'finishedAt',finished_at,"
               "'elapsedSeconds',TIMESTAMPDIFF(MICROSECOND,claimed_at,finished_at)/1000000) "
               f"FROM agent_run WHERE run_id='{run_id}'")
    current = query(run_sql)[0]
    (output / "submission.json").write_text(json.dumps({"repoId": state["repoId"], "session": session,
                                                        "task": task_record["task"], "followUp": args.follow_up}, indent=2))
    config = current["config"]
    switches = desired["arms"][args.arm]
    assert config["contextBudget"].get("summaryEnabled", True) == switches["summarization"]
    assert config["observationPolicy"].get("externalizationEnabled", True) == switches["externalization"]
    for key in ("contextWindowTokens", "safetyMarginTokens", "summaryTriggerRatio", "compactTriggerRatio", "keepRecentGroups"):
        assert config["contextBudget"][key] == switches.get(key, desired["experiment"].get(key)), key
    for key in ("maxInlineTokens", "maxPreviewTokens"):
        assert config["observationPolicy"][key] == desired["experiment"][key], key
    for key in ("maxOutputTokens", "maxModelCalls", "maxToolCalls"):
        assert config["policy"][key] == desired["experiment"][key], key
    assert config["policy"]["temperature"] == desired["temperature"]
    assert config["policy"]["model"] == desired["model"]
    if "reasoningEffort" in desired:
        assert config["policy"]["thinking"] == {"mode": desired["thinkingMode"], "effort": desired["reasoningEffort"]}
    if "summaryThinking" in desired:
        assert config["policy"]["summaryThinking"] == desired["summaryThinking"]
    if desired.get("thinkingMode") == "enabled":
        assert "edit" in config["toolSet"]["enabledDefinitionNames"]
    assert "readArtifact" in config["toolSet"]["enabledDefinitionNames"]
    previous = None
    deadline = time.monotonic() + desired["experiment"].get("taskWallClockLimitSeconds", 900)
    while True:
        current = query(run_sql)[0]
        counts = query("SELECT JSON_OBJECT('type',step_type,'count',COUNT(*)) FROM agent_step "
                       f"WHERE run_id='{run_id}' AND step_type IN ('MODEL_RESPONSE','TOOL_RESULT','CONTEXT_SUMMARY_RESULT') GROUP BY step_type")
        progress = (current["status"], tuple(sorted((row["type"], row["count"]) for row in counts)))
        if progress != previous:
            print(f"{args.arm}: {progress[0]} {dict(progress[1])}", flush=True)
            previous = progress
        if current["status"] not in {"QUEUED", "RUNNING"}:
            break
        if time.monotonic() >= deadline:
            raise TimeoutError("Run still active; preserved for inspection, do not restart service")
        time.sleep(3)
    steps = query("SELECT JSON_OBJECT('sequence',session_sequence,'type',step_type,'createdAt',created_at,"
                  "'runId',run_id,'taskId',task_id,'payload',payload_json) "
                  f"FROM agent_step WHERE session_id='{session_id}' ORDER BY session_sequence")
    run_steps = [step for step in steps if step["runId"] == run_id]
    (output / "run.json").write_text(json.dumps(current, ensure_ascii=False, indent=2))
    (output / "steps.json").write_text(json.dumps(steps, ensure_ascii=False, indent=2))
    (output / "run-steps.json").write_text(json.dumps(run_steps, ensure_ascii=False, indent=2))
    final_workspace = query("SELECT JSON_OBJECT('providerRef',provider_ref,'generation',generation,'epoch',workspace_epoch) "
                            f"FROM agent_workspace WHERE session_id='{session_id}'")[0]
    (output / "workspace.json").write_text(json.dumps({"workspaceId": session["workspaceId"], **final_workspace}, indent=2))
    final_files = {str(file.relative_to(workspace_root)): hashlib.sha256(file.read_bytes()).hexdigest()
                   for file in sorted(workspace_root.rglob("*")) if file.is_file()}
    (output / "post-workspace.json").write_text(json.dumps({"files": final_files, **final_workspace}, indent=2))
    main_usage = Counter()
    summary_usage = Counter()
    tools = Counter()
    failures = []
    for step in run_steps:
        payload = step["payload"]
        if step["type"] == "MODEL_RESPONSE":
            main_usage.update({key: value for key, value in payload.get("usage", {}).items() if value is not None})
        if step["type"] == "CONTEXT_SUMMARY_RESULT" and payload.get("output"):
            summary_usage.update({key: value for key, value in payload["output"].get("usage", {}).items() if value is not None})
        if step["type"] == "TOOL_RESULT":
            tools[payload["toolName"]] += 1
            if payload["result"]["status"] != "SUCCESS":
                failures.append({"tool": payload["toolName"], "error": payload["result"].get("errorCode")})
    summary = {"arm": args.arm, "status": current["status"], "terminationReason": current["terminationReason"],
               "mainUsage": dict(main_usage), "summaryUsage": dict(summary_usage), "tools": dict(tools),
               "toolErrors": failures, "stepCounts": dict(Counter(step["type"] for step in run_steps)),
               "sessionStepCounts": dict(Counter(step["type"] for step in steps)),
               "runId": run_id, "taskId": task_record["task"]["taskId"], "sessionId": session_id,
               "elapsedSeconds": current["elapsedSeconds"], "workspace": str(workspace_root)}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
