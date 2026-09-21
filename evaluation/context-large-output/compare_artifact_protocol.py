#!/usr/bin/env python3
"""Read saved experiment facts; no model calls, DB mutations, or Workspace changes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

PROJECT = Path(__file__).resolve().parents[2]


def inspect(root):
    matrix = json.loads((root / "matrix.json").read_text())
    actor_id = json.loads((root / ".client-state.json").read_text())["actorId"]
    report = {"root": str(root), "jarSha256": matrix["jarSha256"], "imageId": matrix["imageId"], "arms": {}}
    for arm, tasks in matrix["arms"].items():
        rows = []
        for index, task in enumerate(tasks):
            folder = root / arm if index == 0 else root / arm / "followups" / task["taskName"]
            steps = json.loads((folder / "run-steps.json").read_text())
            responses = [s["payload"] for s in steps if s["type"] == "MODEL_RESPONSE"]
            starts = [s["payload"] for s in steps if s["type"] == "MODEL_CALL_STARTED"]
            results = [s for s in steps if s["type"] == "TOOL_RESULT"]
            projected = [s for s in steps if s["type"] == "TOOL_OBSERVATION_PROJECTED"]
            calls = {call["id"]: call for response in responses for call in response.get("toolCalls", [])}
            call_ids = [call["id"] for response in responses for call in response.get("toolCalls", [])]
            result_ids = [s["payload"]["toolCallId"] for s in results]
            row = {key: task.get(key) for key in ["taskName", "status", "terminationReason", "knownTotalTokens",
                    "mainUsage", "summaryUsage", "elapsedSeconds", "independentVerificationPassed", "writeScopePassed",
                    "changedFiles", "unknownUsageRecords", "unknownSummaryUsageRecords", "unansweredMainCalls"]}
            row.update({"modelCalls": len(responses), "tools": dict(Counter(c["name"] for c in calls.values())),
                        "inputPeak": max((r.get("usage", {}).get("inputTokens") or 0 for r in responses), default=0),
                        "summaryAttempts": sum(s["type"] == "CONTEXT_SUMMARY_RESULT" for s in steps),
                        "feedback": sum(s["type"] == "HARNESS_FEEDBACK" for s in steps),
                        "projectionCount": len(projected), "artifactReads": [], "toolErrors": [], "previews": [],
                        "causalityPassed": len(call_ids) == len(calls) and len(result_ids) == len(set(result_ids))
                            and set(call_ids) == set(result_ids)
                            and all(calls[s["payload"]["toolCallId"]]["name"] == s["payload"]["toolName"] for s in results),
                        "estimatedInputPeak": max((s.get("contextInput", {}).get("estimatedInputTokens", 0) for s in starts), default=0),
                        "fixedInputEstimates": sorted({s.get("contextInput", {}).get("fixedTokens", 0) for s in starts})})
            for step in results:
                payload = step["payload"]
                call = calls[payload["toolCallId"]]
                result = payload["result"]
                arguments = call["arguments"]
                path = arguments.get("filePath", arguments.get("path", ""))
                artifact = call["name"] == "readArtifact" or isinstance(path, str) and path.startswith("artifact:")
                if artifact:
                    row["artifactReads"].append({"tool": call["name"], "arguments": arguments,
                            "status": result["status"], "error": result.get("errorCode"),
                            "sourceSequence": result.get("payload", {}).get("sourceStepSequence") if result.get("payload") else None})
                if result["status"] != "SUCCESS":
                    row["toolErrors"].append({"tool": call["name"], "error": result.get("errorCode")})
            original = {s["payload"]["toolCallId"]: s for s in results}
            for step in projected:
                payload = step["payload"]
                observation = payload["observation"]
                metadata = observation["externalization"]
                source = original[payload["toolCallId"]]
                content = source["payload"]["result"].get("payload") or {}
                stdout = content.get("stdout", "")
                diagnostic = [(n, line) for n, line in enumerate(stdout.splitlines(), 1)
                              if re.search(r"^FAIL(?:ED|URE)?\b", line, re.I)]
                excerpt = observation.get("payload", {}).get("stdout", "")
                shown = [n for n, line in diagnostic if line in excerpt]
                item = {"sourceSequence": source["sequence"], "projectionSequence": step["sequence"],
                        "tool": source["payload"]["toolName"], "failureLines": [n for n, _ in diagnostic],
                        "visibleFailureLines": shown, "resources": metadata.get("resources", {}),
                        "sourceBindingPassed": metadata.get("sourceStepSequence", source["sequence"]) == source["sequence"]}
                submission = json.loads((folder / "submission.json").read_text())
                # Test-scoped source repository identity, not model-supplied URI contents.
                session = submission["session"]
                ref = metadata["artifact"]
                # setup() creates a repository owned by this synthetic test account.
                repo_key = f"{actor_id}/{submission['repoId']}"
                namespace = hashlib.sha256((repo_key + "\n" + session["sessionId"]).encode()).hexdigest()
                stored = PROJECT / "data/agent-artifacts" / namespace / (ref["artifactId"] + ".json")
                raw = stored.read_bytes()
                item["integrityPassed"] = len(raw) == ref["sizeBytes"] and hashlib.sha256(raw).hexdigest() == ref["sha256"]
                item["originalContentPassed"] = json.loads(raw) == source["payload"]["result"]
                row["previews"].append(item)
            rows.append(row)
        last = root / arm / "followups" / tasks[-1]["taskName"] if len(tasks) > 1 else root / arm
        history = json.loads((last / "steps.json").read_text())
        continuity = all(json.loads((root / arm / "followups" / tasks[i]["taskName"] / "initial-workspace.json").read_text())["files"]
                         == json.loads(((root / arm if i == 1 else root / arm / "followups" / tasks[i-1]["taskName"]) / "post-workspace.json").read_text())["files"]
                         for i in range(1, len(tasks)))
        reads = [read for row in rows for read in row["artifactReads"]]
        report["arms"][arm] = {"tasks": rows, "totals": {
                "completed": sum(r["status"] == "COMPLETED" for r in rows),
                "tokens": sum(r["knownTotalTokens"] for r in rows), "modelCalls": sum(r["modelCalls"] for r in rows),
                "inputTokens": sum(r["mainUsage"].get("inputTokens", 0) + r["summaryUsage"].get("inputTokens", 0) for r in rows),
                "outputTokens": sum(r["mainUsage"].get("outputTokens", 0) + r["summaryUsage"].get("outputTokens", 0) for r in rows),
                "inputPeak": max(r["inputPeak"] for r in rows), "summaryAttempts": sum(r["summaryAttempts"] for r in rows),
                "artifactReads": len(reads), "invalidArtifactReads": sum(r["status"] != "SUCCESS" for r in reads),
                "projectionCount": sum(r["projectionCount"] for r in rows), "feedback": sum(r["feedback"] for r in rows),
                "elapsedSeconds": sum(r["elapsedSeconds"] or 0 for r in rows)},
                "workspaceContinued": continuity,
                "sessionSequenceContiguous": [s["sequence"] for s in history] == list(range(1, len(history) + 1))}
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    old, new = inspect(args.baseline.resolve()), inspect(args.current.resolve())
    report = {"baseline": old, "current": new, "tokenChangePercent": {
        arm: round((new["arms"][arm]["totals"]["tokens"] / old["arms"][arm]["totals"]["tokens"] - 1) * 100, 2)
        for arm in new["arms"]}}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({"current": {arm: item["totals"] for arm, item in new["arms"].items()},
                      "tokenChangePercent": report["tokenChangePercent"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
