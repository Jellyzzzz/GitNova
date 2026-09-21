#!/usr/bin/env python3
"""Stage-aware Docker acceptance. Oracle/reference sources never enter the public fixture."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "repository"
SCENARIO = json.loads((ROOT / "scenario.json").read_text())
spec = importlib.util.spec_from_file_location("pricing_verifier", ROOT.parent / "context-large-output/verify.py")
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
REPAIRS = {Path(p).name: p for task in SCENARIO["tasks"][1:4] for p in task["editableFiles"]}
SUITES = {"contract", "inventory", "pricing", "cancellation", "idempotency", "batch", "workflow"}
EDGE = "src/test/java/orderflow/EdgeRegression.java"


def snapshot(root):
    files = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if relative.parts[0] in {".git", ".gitlet"}:
            continue
        if path.is_symlink():
            raise ValueError(f"Symlink is not a source file: {relative}")
        if path.is_file():
            files[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def run(workspace, image, output, label, mode):
    # Compile independently of a model-edited shell entrypoint. The runtime remains networkless.
    if mode == "entry":
        command = "sh run-tests.sh all"
    else:
        roots = "src/main/java src/test/java" if mode in {"public", "edge"} else "src/main/java"
        main = {"public": "orderflow.AllTests all", "edge": "orderflow.EdgeRegression",
                "hidden": "orderflow.oracle.HiddenWorkflowCheck"}[mode]
        command = ('build=$(mktemp -d /tmp/orderflow-verify.XXXXXX) && '
                   f'find {roots} -name "*.java" -print | sort > "$build/sources" && ')
        if mode == "hidden":
            command += 'printf "%s\\n" /oracle/HiddenWorkflowCheck.java >> "$build/sources" && '
        command += 'javac -encoding UTF-8 -d "$build/classes" @"$build/sources" && '
        command += f'java -cp "$build/classes" {main}'
    result = shared.execute(workspace, image, command, output, label, ROOT / "oracle")
    text = (output / (label + ".stdout")).read_text()
    pattern = (r"SUITE (\w+) checks=(\d+) failed=(\d+)" if mode == "public"
               else r"ORACLE suite=(\w+) checks=(\d+) failed=(\d+)")
    result["suites"] = {name: {"checks": int(checks), "failed": int(failed)}
                        for name, checks, failed in re.findall(pattern, text)}
    if mode in {"public", "hidden"}:
        if result["exitCode"] not in {0, 1} or set(result["suites"]) != SUITES:
            raise RuntimeError(f"{label} did not complete all checks; inspect {output}")
        failures = sum(suite["failed"] for suite in result["suites"].values())
        if result["exitCode"] != (1 if failures else 0):
            raise RuntimeError(f"{label}: inconsistent exit status and assertions")
    if mode == "edge":
        matches = re.findall(r"EDGE SUMMARY checks=(\d+) failed=(\d+)", text)
        result["edge"] = {"checks": int(matches[-1][0]), "failed": int(matches[-1][1])} if matches else None
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--stage", type=int, choices=range(1, 7), default=4)
    parser.add_argument("--image", default="gitnova-workspace:java21")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="verification-", dir=args.output.resolve()))
    image = subprocess.check_output(["docker", "image", "inspect", args.image, "--format", "{{.Id}}"], text=True).strip()
    original = snapshot(SOURCE)
    report = {"imageId": image, "initialFiles": original, "variants": {}, "candidateStage": args.stage if args.candidate else None}
    plans = [("candidate", None)] if args.candidate else [
        ("baseline", []),
        ("through-pricing", list(REPAIRS)[:2]),
        ("through-cancellation", list(REPAIRS)[:3]),
        ("correct", list(REPAIRS)),
        *[("regress-" + name.removesuffix(".java"), [other for other in REPAIRS if other != name]) for name in REPAIRS],
        ("reference-edge", list(REPAIRS)),
    ]
    success = True
    for name, repairs in plans:
        source = args.candidate.resolve() if args.candidate else SOURCE
        actual = snapshot(source)
        allowed = {p for task in SCENARIO["tasks"][:args.stage] for p in task["editableFiles"]} if args.candidate else set()
        if set(actual) - set(original) - allowed or set(original) - set(actual):
            raise ValueError("Candidate file set does not match the stage contract")
        changed = {p for p in actual if actual[p] != original.get(p)}
        if changed - allowed:
            raise ValueError(f"Protected source changed: {sorted(changed - allowed)}")
        workspace = output / name
        workspace.mkdir()
        for relative in actual:
            destination = workspace / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / relative, destination)
        for repair in repairs or []:
            shutil.copyfile(ROOT / "oracle/reference" / repair, workspace / REPAIRS[repair])
        if name == "reference-edge":
            shutil.copyfile(ROOT / "oracle/reference/EdgeRegression.java", workspace / EDGE)
        public = run(workspace, image, output, name + "-public", "public")
        hidden = run(workspace, image, output, name + "-hidden", "hidden")
        result = {"public": public, "hidden": hidden}
        if args.candidate:
            required = set(SCENARIO["tasks"][args.stage - 1]["requiredSuites"]) - {"edge"}
        else:
            required = {"contract", "inventory"}
            if name == "through-pricing": required |= {"pricing"}
            if name == "through-cancellation": required |= {"pricing", "cancellation"}
            if name in {"correct", "reference-edge"}: required = SUITES
        result["stagePassed"] = all(outcome["suites"][suite]["failed"] == 0
                                    for suite in required for outcome in (public, hidden))
        if not args.candidate:
            target = {"PricingPolicy": "pricing", "ShippingPolicy": "pricing",
                      "CancellationService": "cancellation", "RequestFingerprint": "idempotency",
                      "BatchImporter": "batch"}.get(name.removeprefix("regress-"))
            expected_failures = {target} if target else (
                {"pricing", "cancellation", "idempotency", "batch"} if name == "baseline" else
                {"cancellation", "idempotency", "batch"} if name == "through-pricing" else
                {"idempotency", "batch"} if name == "through-cancellation" else set())
            result["negativeControlsPassed"] = all(outcome["suites"][suite]["failed"] > 0
                                                   for suite in expected_failures for outcome in (public, hidden))
            result["stagePassed"] &= result["negativeControlsPassed"]
        if (args.candidate and args.stage >= 5) or name == "reference-edge":
            if not (workspace / EDGE).exists():
                result["stagePassed"] = False
                result["edgeMissing"] = True
            else:
                edge = run(workspace, image, output, name + "-edge", "edge")
                result["edge"] = edge
                result["stagePassed"] &= edge["exitCode"] == 0 and edge["edge"] is not None and edge["edge"]["checks"] >= 18 and edge["edge"]["failed"] == 0
                if args.candidate:
                    entry = run(workspace, image, output, name + "-entry", "entry")
                    entry_text = (output / (name + "-entry.stdout")).read_text()
                    result["entry"] = entry
                    result["stagePassed"] &= entry["exitCode"] == 0 and "ALL SUMMARY selection=all failed=0" in entry_text and "EDGE SUMMARY" in entry_text
                result["edgeMutations"] = {}
                for repair, relative in REPAIRS.items():
                    mutation = output / (name + "-mutate-" + repair.removesuffix(".java"))
                    shutil.copytree(workspace, mutation)
                    shutil.copyfile(SOURCE / relative, mutation / relative)
                    outcome = run(mutation, image, output, mutation.name, "edge")
                    detected = outcome["exitCode"] == 1 and outcome["edge"] is not None and outcome["edge"]["failed"] > 0
                    result["edgeMutations"][repair] = {"detected": detected, "result": outcome}
                    result["stagePassed"] &= detected
        report["variants"][name] = result
        success &= result["stagePassed"]
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f"{name}: public failures={sum(s['failed'] for s in public['suites'].values())}, "
              f"hidden failures={sum(s['failed'] for s in hidden['suites'].values())}, accepted={result['stagePassed']}", flush=True)
    # Snapshot copied candidates only; originals must remain untouched.
    if args.candidate and snapshot(args.candidate.resolve()) != actual:
        raise RuntimeError("Candidate changed during verification")
    print(f"Report: {output / 'report.json'}", flush=True)
    if not success: raise SystemExit(1)


if __name__ == "__main__":
    main()
