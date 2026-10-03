#!/usr/bin/env python3
"""Prepare and independently verify eight Tasks sharing one evolving Eventstats workspace."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import suite

CHAIN = suite.ROOT / "session-chain"
REGRESSION = "src/test/java/eventstats/RegressionChecks.java"
CATEGORIES = ("parse/", "dedup/", "window", "percentile/", "cursor/")


def definition():
    tasks = [task for task in suite.load_suite()["tasks"] if task["repo"] == "eventstats"]
    combined = {"id": "eventstats-session-chain", "repo": "eventstats",
                "starter": [edit for task in tasks for edit in task["starter"]], "partialFix": []}
    return tasks, combined, json.loads((CHAIN / "scenario.json").read_text())


def prepare(output):
    _, combined, scenario = definition()
    fixture = output / "tasks" / combined["id"]
    files = suite.materialize(combined, "starter", fixture / "repository")
    shutil.copytree(CHAIN / "tasks", fixture / "tasks")
    shutil.copyfile(CHAIN / "scenario.json", fixture / "scenario.json")
    stats = {"files": len(files), "bytes": sum((fixture / "repository" / path).stat().st_size for path in files),
             "lines": sum(len((fixture / "repository" / path).read_text().splitlines()) for path in files)}
    frozen = {"files": files, "stats": stats,
              "scenarioSha256": hashlib.sha256((fixture / "scenario.json").read_bytes()).hexdigest(),
              "prompts": {task["file"]: hashlib.sha256((fixture / task["file"]).read_bytes()).hexdigest()
                          for task in scenario["tasks"]}}
    (output / "freeze.json").write_text(json.dumps({"evaluatorFiles": suite.snapshot(suite.ROOT),
                                                   "tasks": {combined["id"]: frozen}}, indent=2) + "\n")
    (output / "run-plan.json").write_text(json.dumps({"sessions": 1, "distinctTasks": 8,
        "plannedRuns": 8, "status": "NOT_RUN", "scope": "Session continuity, not eight independent attempts",
        "tasks": [{"stage": i, "name": task["name"], "status": "NOT_RUN"}
                  for i, task in enumerate(scenario["tasks"], 1)]}, indent=2) + "\n")
    print(f"Prepared 1 Session / 8 sequential Tasks; public source={stats}; no model calls: {output}")


def check_scope(expected, before, actual, stages, stage):
    """Cumulative scope protects the original fixture; step scope protects previous repairs."""
    cumulative = {path for task in stages[:stage] for path in task["editableFiles"]}
    current = set(stages[stage - 1]["editableFiles"])
    for previous, allowed in ((expected, cumulative), (before, current)):
        removed = previous.keys() - actual.keys()
        changed = {path for path in previous.keys() | actual.keys() if previous.get(path) != actual.get(path)}
        if removed or changed - allowed:
            raise ValueError(f"Scope violation: removed={sorted(removed)}, unauthorized={sorted(changed - allowed)}")
    if stage >= 7 and REGRESSION not in actual:
        raise ValueError("Stage 7 and 8 require RegressionChecks.java")
    return sorted(path for path in before.keys() | actual.keys() if before.get(path) != actual.get(path))


def stage_verdict(result, public_failures, fixed):
    if result["status"] not in {"ACCEPTED", "REJECTED"}:
        return {"accepted": False, "status": result["status"]}
    required = CATEGORIES[:fixed]
    pending = CATEGORIES[fixed:]
    # Invalid-input checks, integration checks and any unrecognized failures are never waived.
    pending_public = ("parse/offset", "dedup/tenants", "windows/negative-epoch", "percentile/rank", "cursor/ties")[fixed:]
    blocking = [label for label in public_failures if label not in pending_public]
    blocking += [label for label in result["hiddenFailureLabels"]
                 if label.startswith(required) or not label.startswith(pending)]
    if result.get("regressionExitCode", 0) != 0:
        blocking.append("regression/nonzero-exit")
    return {"accepted": not blocking, "status": "ACCEPTED" if not blocking else "REJECTED",
            "blockingFailureLabels": blocking, "remainingFailureLabels": [
                label for label in result["hiddenFailureLabels"] if label.startswith(pending)]}


def regression_quality(workspace, image, output):
    """Execute the new tests alone, then replant each original defect outside the live workspace."""
    tasks, _, _ = definition()
    records = []
    for defect in [None] + tasks:
        label = "regression-healthy" if defect is None else "regression-" + defect["id"]
        mutant = output / label
        shutil.copytree(workspace, mutant)
        if defect:
            old = output / (label + "-original")
            suite.materialize(defect, "starter", old)
            for filename in {edit["file"] for edit in defect["starter"]}:
                relative = "src/main/java/eventstats/" + filename
                shutil.copyfile(old / relative, mutant / relative)
        command = '''build=$(mktemp -d /tmp/chain-regression.XXXXXX) || exit 70
find src/main/java src/test/java -name '*.java' -print | sort > "$build/sources"
if ! javac -encoding UTF-8 -d "$build/classes" @"$build/sources" > "$build/compile.log" 2>&1; then
    head -c 131072 "$build/compile.log"
    exit 2
fi
java -cp "$build/classes" eventstats.RegressionChecks > "$build/result.log" 2>&1
code=$?
if [ "$(wc -c < "$build/result.log")" -gt 131072 ]; then exit 86; fi
cat "$build/result.log"
exit "$code"'''
        execution = suite.shared.execute(mutant, image, command, output, label)
        log = (output / (label + ".stdout")).read_text(errors="replace")
        markers = re.findall(r"^REGRESSION checks=(\d+) failed=(\d+)$", log, re.MULTILINE)
        valid = len(markers) == 1 and int(markers[0][0]) >= 5
        if valid:
            failures = int(markers[0][1])
            valid = execution["exitCode"] == (1 if failures else 0) and (failures > 0 if defect else failures == 0)
        records.append({"label": label, **execution, "valid": valid, "markers": markers})
    return {"accepted": all(record["valid"] for record in records), "controls": records}


def verify(candidate, before, stage, image, output):
    _, combined, scenario = definition()
    expected = suite.materialize(combined, "starter", output / "baseline")
    actual = suite.snapshot(candidate)
    try:
        changed = check_scope(expected, before, actual, scenario["tasks"], stage)
    except ValueError as failure:
        return {"accepted": False, "status": "SCOPE_VIOLATION", "reason": str(failure)}
    copy = output / "candidate"
    copy.mkdir()
    for relative in actual:
        target = copy / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(candidate / relative, target)
    if suite.snapshot(copy) != actual:
        return {"accepted": False, "status": "INCONCLUSIVE", "reason": "Candidate changed while copying"}
    raw = suite.evaluate(combined, copy, image, output, "stage")
    public_log = (output / "stage.stdout").read_text(errors="replace").split("PUBLIC checks=", 1)[0]
    public_failures = re.findall(r"^FAIL ([^:]+):", public_log, re.MULTILINE)
    result = {**stage_verdict(raw, public_failures, scenario["tasks"][stage - 1]["fixedCategories"]),
              "stage": stage, "changedFiles": changed, "allBehaviorChecks": raw,
              "publicFailureLabels": public_failures, "historyEvidenceReview": "NOT_REVIEWED"}
    if result["accepted"] and stage >= 7:
        quality = regression_quality(copy, image, output)
        result["regressionQuality"] = quality
        if not quality["accepted"]:
            result.update(accepted=False, status="REGRESSION_QUALITY_FAILED")
    if suite.snapshot(candidate) != actual:
        result.update(accepted=False, status="INCONCLUSIVE", reason="Workspace changed during verification")
    return result


def self_test(image, output):
    tasks, combined, _ = definition()
    workspace = output / "progressive-reference"
    suite.materialize(combined, "starter", workspace)
    records = []
    for stage in range(1, 9):
        before = suite.snapshot(workspace)
        if 2 <= stage <= 6:
            task = tasks[stage - 2]
            correct = output / f"correct-{stage}"
            suite.materialize(task, "reference", correct)
            for filename in {edit["file"] for edit in task["starter"]}:
                relative = "src/main/java/eventstats/" + filename
                shutil.copyfile(correct / relative, workspace / relative)
        if stage == 7:
            shutil.copyfile(CHAIN / "RegressionChecks.java", workspace / REGRESSION)
        check_output = output / f"stage-{stage}"
        check_output.mkdir()
        result = verify(workspace, before, stage, image, check_output)
        records.append({"label": f"stage-{stage}-correct", "valid": result["accepted"], "result": result})
        print(f"Stage {stage} positive control: {result['status']}", flush=True)
        if 2 <= stage <= 6:
            wrong = output / f"wrong-{stage}"
            shutil.copytree(workspace, wrong)
            partial = output / f"partial-source-{stage}"
            suite.materialize(task, "partialFix", partial)
            for filename in {edit["file"] for edit in task["starter"] + task["partialFix"]}:
                relative = "src/main/java/eventstats/" + filename
                shutil.copyfile(partial / relative, wrong / relative)
            check_output = output / f"wrong-check-{stage}"
            check_output.mkdir()
            result = verify(wrong, before, stage, image, check_output)
            prefix = CATEGORIES[stage - 2]
            valid = result["status"] == "REJECTED" and any(
                label.startswith(prefix) for label in result["allBehaviorChecks"]["hiddenFailureLabels"])
            records.append({"label": f"stage-{stage}-partial", "valid": valid, "result": result})
            print(f"Stage {stage} incomplete repair detected: {valid}", flush=True)
    return {"imageId": image, "controls": records, "passed": sum(record["valid"] for record in records),
            "total": len(records), "accepted": all(record["valid"] for record in records)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "verify", "self-test"])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image", default="gitnova-workspace:java21")
    parser.add_argument("--stage", type=int, choices=range(1, 9))
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--before", type=Path, help="Saved initial-workspace.json for this Task, not the original base")
    args = parser.parse_args()
    if args.action == "verify" and not (args.stage and args.candidate and args.before):
        parser.error("verify requires --stage, --candidate and --before")
    output = args.output.resolve()
    if output == suite.ROOT or suite.ROOT in output.parents:
        parser.error("Use a new output directory outside the suite")
    os.umask(0o077)
    output.mkdir(parents=True, exist_ok=False)
    if args.action == "prepare":
        prepare(output)
        return
    image = subprocess.check_output(["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    result = self_test(image, output) if args.action == "self-test" else verify(
        args.candidate.resolve(), json.loads(args.before.read_text())["files"], args.stage, image, output)
    (output / "report.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"accepted={result['accepted']}; report: {output / 'report.json'}")
    raise SystemExit(0 if result["accepted"] else 1)


if __name__ == "__main__":
    main()
