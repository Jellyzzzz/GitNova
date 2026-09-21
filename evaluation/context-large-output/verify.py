#!/usr/bin/env python3
"""Docker-only fixture/candidate validation. No HTTP requests, JUnit or model calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parent
EDITABLE = {
    "src/main/java/pricing/DiscountPolicy.java",
    "src/main/java/pricing/ShippingPolicy.java",
}


def execute(workspace, image, command, output, label, oracle_directory=None):
    container = "gitnova-scenario2-" + uuid.uuid4().hex
    arguments = [
        "docker", "run", "--rm", "--name", container,
        "--network", "none", "--read-only",
        "--user", f"{os.getuid()}:{os.getgid()}",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--cpus", "1", "--memory", "512m", "--pids-limit", "128",
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=128m,mode=1777",
        "--env", "HOME=/tmp", "--workdir", "/workspace",
        "--mount", f"type=bind,src={workspace},dst=/workspace,readonly",
        "--mount", f"type=bind,src={oracle_directory or ROOT / 'oracle'},dst=/oracle,readonly",
        "--entrypoint", "/usr/bin/timeout", image,
        "--kill-after=2s", "40s", "sh", "-c", command,
    ]
    started = time.monotonic()
    try:
        result = subprocess.run(arguments, capture_output=True, timeout=50)
    except subprocess.TimeoutExpired:
        subprocess.run(["docker", "rm", "--force", container], capture_output=True, timeout=10)
        raise
    (output / f"{label}.stdout").write_bytes(result.stdout)
    (output / f"{label}.stderr").write_bytes(result.stderr)
    return {
        "exitCode": result.returncode,
        "durationMillis": round((time.monotonic() - started) * 1000),
        "stdoutBytes": len(result.stdout),
        "stderrBytes": len(result.stderr),
        "stdoutTail": result.stdout.decode("utf-8").splitlines()[-1:],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, help="Validate an actual model-modified checkout instead of the self-check matrix")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image", default="gitnova-workspace:java21")
    parser.add_argument("--followup-tests", action="store_true", help="Allow the test-only follow-up's EdgeRegression and runner changes")
    options = parser.parse_args()
    if options.followup_tests and not options.candidate:
        parser.error("--followup-tests requires --candidate")
    options.output.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="verification-", dir=options.output.resolve()))
    # Pin the concrete image for every invocation in this matrix, not a moving tag.
    image = subprocess.check_output([
        "docker", "image", "inspect", options.image, "--format", "{{.Id}}"
    ], text=True).strip()
    source = ROOT / "repository"
    original = {str(file.relative_to(source)): hashlib.sha256(file.read_bytes()).hexdigest()
                for file in sorted(source.rglob("*")) if file.is_file()}
    report = {"imageId": image, "sourceSha256": original, "variants": {}}
    expected_files = set(original)
    editable = set(EDITABLE)
    if options.followup_tests:
        expected_files.add("src/test/java/pricing/EdgeRegression.java")
        editable.update({"run-tests.sh", "src/test/java/pricing/EdgeRegression.java"})
    names = ["candidate"] if options.candidate else [
        "baseline", "discount-only", "shipping-only", "correct"
    ]
    for name in names:
        candidate = options.candidate.resolve() if options.candidate else source
        # Exclude only known VCS metadata; extra files elsewhere are observable modifications.
        actual_files = {str(file.relative_to(candidate)) for file in candidate.rglob("*")
                        if file.is_file() and not set(file.relative_to(candidate).parts) & {".gitlet", ".git"}}
        if actual_files != expected_files:
            raise AssertionError(f"Unexpected candidate layout: {actual_files ^ expected_files}")
        for relative in expected_files:
            file = candidate / relative
            if file.is_symlink():
                raise AssertionError(f"Symlink not accepted: {relative}")
            if relative not in editable and hashlib.sha256(file.read_bytes()).hexdigest() != original[relative]:
                raise AssertionError(f"Protected file changed: {relative}")
        # Copy only public source into a fresh disposable build checkout. Oracle stays outside it.
        workspace = output / name
        workspace.mkdir()
        for relative in sorted(expected_files):
            destination = workspace / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(candidate / relative, destination)
        if name in {"discount-only", "correct"}:
            shutil.copyfile(ROOT / "oracle/DiscountPolicy.java", workspace / "src/main/java/pricing/DiscountPolicy.java")
        if name in {"shipping-only", "correct"}:
            shutil.copyfile(ROOT / "oracle/ShippingPolicy.java", workspace / "src/main/java/pricing/ShippingPolicy.java")
        public = execute(workspace, image, "sh run-tests.sh", output, name + "-public")
        if options.followup_tests:
            public_log = (output / f"{name}-public.stdout").read_text()
            if "BATCH SUMMARY cases=160 passed=160 failed=0" not in public_log:
                raise AssertionError("Follow-up runner must still execute the original batch suite")
        oracle = execute(workspace, image,
                         'classes=$(mktemp -d /tmp/oracle.XXXXXX) && '
                         'javac -d "$classes" src/main/java/pricing/*.java /oracle/HiddenPricingCheck.java && '
                         'java -cp "$classes" pricing.HiddenPricingCheck', output, name + "-oracle")
        report["variants"][name] = {"public": public, "oracle": oracle}
        (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        expected = 0 if name in {"correct", "candidate"} else 1
        for kind, outcome in (("public", public), ("oracle", oracle)):
            if outcome["exitCode"] != expected:
                raise AssertionError(f"{name}/{kind}: expected exit {expected}, got {outcome}")
            marker = "BATCH SUMMARY" if kind == "public" else "ORACLE SUMMARY"
            if kind == "public" and options.followup_tests:
                marker = "EDGE SUMMARY"
            if not outcome["stdoutTail"] or marker not in outcome["stdoutTail"][0]:
                raise AssertionError(f"{name}/{kind}: application tests did not finish")
        print(f"{name}: public={public['stdoutTail'][0]}; hidden={oracle['stdoutTail'][0]}")
    print(f"Report: {output / 'report.json'}")


if __name__ == "__main__":
    main()
