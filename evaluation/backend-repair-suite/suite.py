#!/usr/bin/env python3
"""Build reproducible public fixtures; verify outside the Agent workspace. Never calls an LLM."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess

ROOT = Path(__file__).resolve().parent
COUNTS = {"stockroom": (8, 192), "eventstats": (8, 260), "bundlesync": (7, 84), "jobqueue": (7, 146)}
FAILURES = {
    "stockroom": ["reserve/", "replay/", "expiry/", "transfer/", "batch/"],
    "eventstats": ["parse/", "dedup/", "window/", "percentile/", "cursor/"],
    "bundlesync": ["path/", "copy/", "plan/", "layout/", "publish/"],
    "jobqueue": ["ready/", "lease/", "retry/", "reaper/", "cancel/"],
}
shared_spec = importlib.util.spec_from_file_location("pricing_verifier", ROOT.parent / "context-large-output/verify.py")
shared = importlib.util.module_from_spec(shared_spec)
shared_spec.loader.exec_module(shared)


def snapshot(root):
    """No VCS data, links or special files enter the fixture/verification copy."""
    files = {}
    total = 0
    for directory, directories, names in os.walk(root, followlinks=False):
        if Path(directory) == root:
            directories[:] = [name for name in directories if name not in {".git", ".gitlet"}]
        if root == ROOT:
            directories[:] = [name for name in directories if name != "__pycache__"]
        for name in directories + names:
            path = Path(directory) / name
            metadata = path.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                raise ValueError(f"Symlink is not allowed: {path.relative_to(root)}")
            if stat.S_ISDIR(metadata.st_mode):
                continue
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 2 * 1024 * 1024:
                raise ValueError(f"Unsupported/oversized fixture entry: {path.relative_to(root)}")
            total += metadata.st_size
            if total > 32 * 1024 * 1024 or len(files) >= 4096:
                raise ValueError("Fixture exceeds verifier source limits")
            files[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not files:
        raise ValueError(f"No source files: {root}")
    return dict(sorted(files.items()))


def load_suite():
    suite = json.loads((ROOT / "suite.json").read_text())
    tasks = suite["tasks"]
    if len(tasks) != 20 or len({task["id"] for task in tasks}) != 20 or suite["repetitions"] != 3:
        raise ValueError("This suite must contain 20 unique tasks with 3 independent attempts each")
    if Counter(task["repo"] for task in tasks) != {repo: 5 for repo in COUNTS}:
        raise ValueError("Expected five tasks in each of four repositories")
    for task in tasks:
        if not re.fullmatch(re.escape(task["repo"]) + r"-0[1-5]", task["id"]):
            raise ValueError("Invalid task id")
        for edit in task["starter"] + task["partialFix"]:
            if not re.fullmatch(r"[A-Za-z]+\.java", edit["file"]) or not edit["before"] or edit["before"] == edit["after"]:
                raise ValueError("Invalid defect overlay")
    return suite


def materialize(task, variant, destination):
    repo = task["repo"]
    source = ROOT / "reference" / repo
    files = snapshot(source)
    destination.mkdir(parents=True, exist_ok=False)
    for relative in files:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / relative, target)
    checks = destination / "src/test/java/testing/Checks.java"
    checks.parent.mkdir(parents=True)
    shutil.copyfile(ROOT / "common/Checks.java", checks)
    (destination / "run-tests.sh").write_text((ROOT / "common/run-tests.sh").read_text().replace("@PACKAGE@", repo))
    edits = [] if variant == "reference" else task[variant]
    for edit in edits:
        path = destination / "src/main/java" / repo / edit["file"]
        source_text = path.read_text()
        if source_text.count(edit["before"]) != 1:
            raise ValueError(f"Overlay must match exactly once: {task['id']}/{variant}/{edit['file']}")
        path.write_text(source_text.replace(edit["before"], edit["after"], 1))
    return snapshot(destination)


def scope_changes(task, expected, actual):
    editable = {f"src/main/java/{task['repo']}/{edit['file']}" for edit in task["starter"] + task["partialFix"]}
    optional = f"src/test/java/{task['repo']}/RegressionChecks.java"
    missing = set(expected) - set(actual)
    extra = set(actual) - set(expected) - {optional}
    changed = {path for path in expected.keys() & actual.keys() if expected[path] != actual[path]}
    if missing or extra or changed - editable:
        raise ValueError(f"Scope violation: missing={sorted(missing)}, extra={sorted(extra)}, protected={sorted(changed - editable)}")
    return sorted(changed | (set(actual) - set(expected)))


def evaluate(task, workspace, image, output, label):
    repo = task["repo"]
    # Bypass the candidate's runner. Logs live on bounded tmpfs; emit <= 256 KiB to the host.
    # The existing executor supplies read-only mounts, no network, non-root, PID/memory/CPU/time limits.
    command = f'''build=$(mktemp -d /tmp/repair-verify.XXXXXX) || exit 70
find src/main/java src/test/java -name '*.java' -print | sort > "$build/sources"
printf '%s\\n' /oracle/Oracle.java >> "$build/sources"
if ! javac -encoding UTF-8 -d "$build/classes" @"$build/sources" > "$build/compile.log" 2>&1; then
    head -c 262144 "$build/compile.log"
    exit 2
fi
java -cp "$build/classes" {repo}.PublicChecks > "$build/public.log" 2>&1
public_code=$?
java -cp "$build/classes" {repo}.Oracle > "$build/oracle.log" 2>&1
oracle_code=$?
regression_code=0
if [ -f src/test/java/{repo}/RegressionChecks.java ]; then
    java -cp "$build/classes" {repo}.RegressionChecks >> "$build/public.log" 2>&1
    regression_code=$?
fi
if [ "$(wc -c < "$build/public.log")" -gt 131072 ] || [ "$(wc -c < "$build/oracle.log")" -gt 131072 ]; then
    printf '%s\\n' 'VERIFIER_OUTPUT_LIMIT'
    exit 86
fi
cat "$build/public.log" "$build/oracle.log"
printf 'PUBLIC_EXIT=%s ORACLE_EXIT=%s REGRESSION_EXIT=%s\\n' "$public_code" "$oracle_code" "$regression_code"
if [ "$public_code" -eq 0 ] && [ "$oracle_code" -eq 0 ] && [ "$regression_code" -eq 0 ]; then exit 0; else exit 1; fi'''
    outcome = shared.execute(workspace, image, command, output, label, ROOT / "oracle" / repo)
    log = (output / f"{label}.stdout").read_text(errors="replace")
    markers = re.findall(r"^(PUBLIC|ORACLE) checks=(\d+) failed=(\d+)$", log, re.MULTILINE)
    if outcome["exitCode"] == 2:
        return {**outcome, "status": "COMPILE_ERROR", "accepted": False}
    if len(markers) != 2 or [marker[0] for marker in markers] != ["PUBLIC", "ORACLE"]:
        return {**outcome, "status": "INCONCLUSIVE", "accepted": False}
    public, hidden = ({"checks": int(marker[1]), "failed": int(marker[2])} for marker in markers)
    exits = re.findall(r"^PUBLIC_EXIT=(\d+) ORACLE_EXIT=(\d+) REGRESSION_EXIT=(\d+)$", log, re.MULTILINE)
    if (public["checks"], hidden["checks"]) != COUNTS[repo] or len(exits) != 1:
        return {**outcome, "status": "INCONCLUSIVE", "accepted": False}
    expected_exits = tuple(1 if result["failed"] else 0 for result in (public, hidden))
    regression_failed = int(exits[0][2]) != 0
    expected_status = 1 if max(expected_exits) or regression_failed else 0
    if tuple(map(int, exits[0][:2])) != expected_exits or outcome["exitCode"] != expected_status:
        return {**outcome, "status": "INCONCLUSIVE", "accepted": False}
    hidden_log = log.split(f"PUBLIC checks={public['checks']} failed={public['failed']}\n", 1)[1]
    return {**outcome, "status": "ACCEPTED" if expected_status == 0 else "REJECTED", "accepted": expected_status == 0,
            "public": public, "oracle": hidden, "regressionExitCode": int(exits[0][2]),
            "hiddenFailureLabels": re.findall(r"^FAIL ([^:]+):", hidden_log, re.MULTILINE)}


def prepare(suite, output, task_id):
    selected = [task for task in suite["tasks"] if not task_id or task["id"] == task_id]
    plan = {"suite": suite["name"], "distinctTasks": len(selected), "attemptsPerTask": suite["repetitions"],
            "totalRuns": len(selected) * suite["repetitions"], "status": "NOT_RUN", "attempts": []}
    freeze = {"evaluatorFiles": snapshot(ROOT), "tasks": {}, "scope": "functional repair; independent cold starts"}
    for task in selected:
        fixture = output / "tasks" / task["id"]
        source_hashes = materialize(task, "starter", fixture / "repository")
        editable = sorted({f"src/main/java/{task['repo']}/{edit['file']}" for edit in task["starter"] + task["partialFix"]})
        prompt = (f"# {task['title']}\n\n{task['request']}\n\n"
                  "先阅读 README 契约和公开测试，用 `sh run-tests.sh` 复现与验证。"
                  "这是单个修复任务，不要求重构项目或引入依赖。\n\n允许修改：\n"
                  + "\n".join(f"- {path}" for path in editable)
                  + f"\n- 可选新增 src/test/java/{task['repo']}/RegressionChecks.java（含 main 方法）\n\n"
                  "其他文件保持不变，尤其不要修改公开测试、构建入口或放宽原有 API 契约。"
                  "请在最终回复中区分实际通过的检查和未验证的行为。\n")
        (fixture / "task.md").write_text(prompt)
        # Same input shape as the existing live_client; no automatic service/model invocation here.
        scenario = {"scenario": task["id"], "repository": "repository", "model": "deepseek-v4-flash",
                    "thinkingMode": "enabled", "reasoningEffort": "max", "summaryThinking": {"mode": "enabled", "effort": "high"},
                    "temperature": 0, "tasks": [{"name": task["id"], "file": "task.md", "editableFiles": editable}],
                    "experiment": {"contextWindowTokens": 128000, "safetyMarginTokens": 1024,
                                   "summaryTriggerRatio": 0.8, "compactTriggerRatio": 0.9, "compactTargetRatio": 0.6,
                                   "keepRecentGroups": 2, "maxOutputTokens": 32768, "maxModelCalls": 40,
                                   "maxToolCalls": 80, "callTimeoutSeconds": 300, "readTimeoutSeconds": 300,
                                   "maxInlineTokens": 4096, "maxPreviewTokens": 1024, "taskWallClockLimitSeconds": 1800},
                    "arms": {"G80": {"externalization": True, "summarization": True}}}
        (fixture / "scenario.json").write_text(json.dumps(scenario, ensure_ascii=False, indent=2) + "\n")
        stats = {"files": len(source_hashes), "bytes": sum((fixture / "repository" / path).stat().st_size for path in source_hashes),
                 "lines": sum(len((fixture / "repository" / path).read_text().splitlines()) for path in source_hashes)}
        freeze["tasks"][task["id"]] = {"files": source_hashes, "stats": stats,
                                            "promptSha256": hashlib.sha256(prompt.encode()).hexdigest(),
                                            "scenarioSha256": hashlib.sha256((fixture / "scenario.json").read_bytes()).hexdigest()}
        for attempt in range(1, suite["repetitions"] + 1):
            plan["attempts"].append({"attemptId": f"{task['id']}-r{attempt}", "taskId": task["id"], "repo": task["repo"],
                                     "fixture": str(fixture), "freshSessionRequired": True, "status": "NOT_RUN"})
    (output / "freeze.json").write_text(json.dumps(freeze, indent=2) + "\n")
    (output / "run-plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    print(f"Prepared {len(selected)} tasks / {plan['totalRuns']} planned runs; no runs launched: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "self-test", "verify"])
    parser.add_argument("--output", type=Path, required=True, help="New directory only; never overwrites a previous batch")
    parser.add_argument("--task", help="One task id; otherwise all 20")
    parser.add_argument("--candidate", type=Path, help="Model workspace, only for verify")
    parser.add_argument("--image", default="gitnova-workspace:java21")
    parser.add_argument("--jobs", type=int, choices=[1, 2], default=2, help="Local Docker fixture checks, not model concurrency")
    args = parser.parse_args()
    suite = load_suite()
    task_map = {task["id"]: task for task in suite["tasks"]}
    if args.task and args.task not in task_map:
        parser.error("Unknown task id")
    if args.action == "verify" and (not args.task or not args.candidate):
        parser.error("verify requires --task and --candidate")
    if args.action != "verify" and args.candidate:
        parser.error("--candidate is only allowed for verify")
    output = args.output.expanduser().resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error("Output must be outside the tracked suite; keep generated candidates separate from private reference/oracle")
    os.umask(0o077)
    output.mkdir(parents=True, exist_ok=False)
    if args.action == "prepare":
        prepare(suite, output, args.task)
        return
    image = subprocess.check_output(["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    report = {"suite": suite["name"], "imageId": image, "evaluatorFiles": snapshot(ROOT), "variants": {}}
    if args.action == "verify":
        task = task_map[args.task]
        candidate = args.candidate.expanduser().resolve()
        expected = materialize(task, "starter", output / "baseline")
        actual = snapshot(candidate)
        try:
            changes = scope_changes(task, expected, actual)
        except ValueError as failure:
            result = {"status": "SCOPE_VIOLATION", "accepted": False, "reason": str(failure)}
        else:
            workspace = output / "candidate"
            workspace.mkdir()
            for relative in actual:
                target = workspace / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(candidate / relative, target)
            if snapshot(workspace) != actual:
                raise ValueError("Candidate changed while copying; retry verification against a stable workspace")
            result = {**evaluate(task, workspace, image, output, "candidate"), "changedFiles": changes}
            if snapshot(candidate) != actual:
                result.update(status="INCONCLUSIVE", accepted=False, reason="Candidate changed during verification")
        report["variants"][args.task] = result
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"{args.task}: {result['status']}; report: {output / 'report.json'}")
        raise SystemExit(0 if result["accepted"] else 1)

    selected = [task for task in suite["tasks"] if not args.task or task["id"] == args.task]
    plans = []
    seen_repos = set()
    for task in selected:
        if task["repo"] not in seen_repos:
            plans.append((task, "reference", task["repo"] + "-reference"))
            seen_repos.add(task["repo"])
        plans.extend((task, variant, task["id"] + "-" + variant) for variant in ("starter", "partialFix"))

    def check(plan):
        task, variant, label = plan
        workspace = output / label
        before = materialize(task, variant, workspace)
        result = evaluate(task, workspace, image, output, label)
        result["taskId"] = task["id"]
        result["variant"] = variant
        result["files"] = before
        prefix = FAILURES[task["repo"]][int(task["id"][-2:]) - 1]
        expected = result["status"] == "ACCEPTED" if variant == "reference" else (
            result["status"] == "REJECTED" and result["oracle"]["failed"] > 0
            and any(label.startswith(prefix) for label in result["hiddenFailureLabels"]))
        result["controlPassed"] = expected and snapshot(workspace) == before
        return label, result

    with ThreadPoolExecutor(max_workers=args.jobs) as workers:
        futures = [workers.submit(check, plan) for plan in plans]
        for future in as_completed(futures):
            label, result = future.result()
            report["variants"][label] = result
            (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
            print(f"{label}: {result['status']}, controlPassed={result['controlPassed']}", flush=True)
    passed = sum(result["controlPassed"] for result in report["variants"].values())
    report["controlsPassed"] = passed
    report["controlsTotal"] = len(plans)
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Fixture controls {passed}/{len(plans)}; report: {output / 'report.json'}", flush=True)
    raise SystemExit(0 if passed == len(plans) else 1)


if __name__ == "__main__":
    main()
