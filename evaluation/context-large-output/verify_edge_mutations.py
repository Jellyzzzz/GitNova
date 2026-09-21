#!/usr/bin/env python3
"""Check that model-written edge tests detect each original defect, only in disposable copies."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from verify import ROOT, execute


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    if not (candidate / "src/test/java/pricing/EdgeRegression.java").is_file():
        raise ValueError("No EdgeRegression was produced; do not count the original suite as new test coverage")
    args.output.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="edge-mutations-", dir=args.output.resolve()))
    image = subprocess.check_output(["docker", "image", "inspect", "gitnova-workspace:java21", "--format", "{{.Id}}"], text=True).strip()
    report = {"imageId": image, "variants": {}}
    for name, old_file in [("current", None), ("old-discount", "DiscountPolicy.java"), ("old-shipping", "ShippingPolicy.java")]:
        workspace = output / name
        shutil.copytree(candidate, workspace)
        if old_file:
            relative = "src/main/java/pricing/" + old_file
            shutil.copyfile(ROOT / "repository" / relative, workspace / relative)
        result = execute(workspace, image,
                'classes=$(mktemp -d /tmp/edge.XXXXXX) && '
                'javac -d "$classes" src/main/java/pricing/*.java src/test/java/pricing/EdgeRegression.java && '
                'java -cp "$classes" pricing.EdgeRegression', output, name)
        report["variants"][name] = result
        (output / "report.json").write_text(json.dumps(report, indent=2))
        assert result["exitCode"] == (0 if name == "current" else 1), result
        assert result["stdoutTail"] and "EDGE SUMMARY" in result["stdoutTail"][0], result
        print(f"{name}: exit={result['exitCode']}, {result['stdoutTail'][0]}", flush=True)
    print(f"Report: {output / 'report.json'}")


if __name__ == "__main__":
    main()
