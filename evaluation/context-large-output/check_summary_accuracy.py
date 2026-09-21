"""Deterministic evidence checks only; semantic accuracy still requires source review."""
import json
import re
import sys
from pathlib import Path


def main():
    directory = Path(sys.argv[1])
    headings = ["## Goal", "## Constraints & Preferences", "## Progress", "### Done",
                "### In Progress", "### Blocked", "## Key Decisions", "## Next Steps", "## Critical Context"]
    checks = []
    for result in json.loads((directory / "results.json").read_text()):
        name = result["case"]
        response_file = directory / f"{name}-response.json"
        if not response_file.exists():
            checks.append({"case": name, "status": result["status"], "accuracyEvaluated": False})
            continue
        source = json.loads((directory / f"{name}-request.json").read_text())["messages"][1]["content"]
        content = json.loads(response_file.read_text())["summary"]["content"]
        source_ids = set(re.findall(r'(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])', source))
        output_ids = set(re.findall(r'(?<![a-f0-9])[a-f0-9]{64}(?![a-f0-9])', content))
        artifact_ids = set(re.findall(r'\\?"artifactId\\?"\s*:\s*\\?"([a-f0-9]{64})', source))
        source_calls = set(re.findall(r'call_[A-Za-z0-9_]+', source))
        output_calls = set(re.findall(r'call_[A-Za-z0-9_]+', content))
        positions = [content.find(heading) for heading in headings]
        checks.append({
            "case": name,
            "headingsInOrder": all(position >= 0 for position in positions) and positions == sorted(positions),
            "exactBatchFailureRecord": "BATCH SUMMARY cases=160 passed=153 failed=7" in content,
            "exactBatchSuccessRecord": "BATCH SUMMARY cases=160 passed=160 failed=0" in content,
            "exactEdgePassRecord": "EDGE SUMMARY cases=16 failed=0" in content,
            "exactEdgeDeliberateFailureRecord": "EDGE SUMMARY cases=16 failed=1" in content,
            "knownBadEquationPresent": bool(re.search(r'100\s*[×*]\s*50\s*/\s*100\s*=\s*50\.5', content)),
            "sourceArtifactIds": sorted(artifact_ids),
            "retainedArtifactIds": sorted(artifact_ids & output_ids),
            "unknown64HexReferences": sorted(output_ids - source_ids),
            "unknownCallIds": sorted(output_calls - source_calls),
            "shortHexReferencesToReview": re.findall(r'(?<![a-f0-9])[a-f0-9]{4,63}(?:…|\.{3})', content),
            "requiresSemanticReview": True,
        })
    (directory / "checks.json").write_text(json.dumps(checks, ensure_ascii=False, indent=2))
    for check in checks:
        print(json.dumps(check, ensure_ascii=False))


if __name__ == "__main__":
    main()
