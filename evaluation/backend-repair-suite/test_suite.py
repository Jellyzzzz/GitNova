import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import suite
from run_live import usage_report


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="repair-suite-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.catalog = suite.load_suite()
        self.task = self.catalog["tasks"][0]

    def test_all_overlays_are_repeatable_and_distinct(self):
        for task in self.catalog["tasks"]:
            with self.subTest(task=task["id"]):
                first = suite.materialize(task, "starter", self.root / task["id"] / "first")
                second = suite.materialize(task, "starter", self.root / task["id"] / "second")
                correct = suite.materialize(task, "reference", self.root / task["id"] / "correct")
                incomplete = suite.materialize(task, "partialFix", self.root / task["id"] / "incomplete")
                self.assertEqual(first, second)
                self.assertNotEqual(first, correct)
                self.assertNotEqual(incomplete, correct)
                self.assertNotEqual(incomplete, first)

    def test_changed_reference_invalidates_exact_overlay(self):
        task = copy.deepcopy(self.task)
        task["starter"][0]["before"] = "THIS CODE NO LONGER EXISTS"
        with self.assertRaisesRegex(ValueError, "exactly once"):
            suite.materialize(task, "starter", self.root / "stale-overlay")

    def test_scope_protects_tests_and_runner(self):
        expected = suite.materialize(self.task, "starter", self.root / "source")
        for path in ("run-tests.sh", "src/test/java/stockroom/PublicChecks.java", "README.md"):
            actual = dict(expected)
            actual[path] = "changed"
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "Scope violation"):
                suite.scope_changes(self.task, expected, actual)

    def test_scope_allows_only_target_code_and_optional_test(self):
        expected = suite.materialize(self.task, "starter", self.root / "source")
        actual = dict(expected)
        actual["src/main/java/stockroom/ReservationService.java"] = "repaired"
        actual["src/test/java/stockroom/RegressionChecks.java"] = "new-test"
        self.assertEqual(2, len(suite.scope_changes(self.task, expected, actual)))
        actual["oracle/Oracle.java"] = "leak"
        with self.assertRaises(ValueError):
            suite.scope_changes(self.task, expected, actual)

    def test_missing_source_is_rejected(self):
        expected = suite.materialize(self.task, "starter", self.root / "source")
        actual = dict(expected)
        del actual["src/main/java/stockroom/ReservationService.java"]
        with self.assertRaises(ValueError):
            suite.scope_changes(self.task, expected, actual)

    def test_symlinks_are_not_followed(self):
        for directory_link in (True, False):
            root = self.root / str(directory_link)
            root.mkdir()
            target = self.root if directory_link else Path(__file__).resolve()
            (root / "leak").symlink_to(target, target_is_directory=directory_link)
            with self.assertRaisesRegex(ValueError, "Symlink"):
                suite.snapshot(root)

    def test_prepare_has_20_tasks_and_60_unstarted_attempts(self):
        destination = self.root / "packs"
        destination.mkdir()
        suite.prepare(self.catalog, destination, None)
        import json
        plan = json.loads((destination / "run-plan.json").read_text())
        freeze = json.loads((destination / "freeze.json").read_text())
        self.assertEqual((20, 60), (plan["distinctTasks"], plan["totalRuns"]))
        self.assertEqual(60, len({attempt["attemptId"] for attempt in plan["attempts"]}))
        self.assertEqual({"NOT_RUN"}, {attempt["status"] for attempt in plan["attempts"]})
        for task in self.catalog["tasks"]:
            records = [attempt for attempt in plan["attempts"] if attempt["taskId"] == task["id"]]
            self.assertEqual(3, len(records))
            self.assertTrue(all(record["freshSessionRequired"] for record in records))
            files = suite.snapshot(Path(records[0]["fixture"]) / "repository")
            self.assertEqual(files, freeze["tasks"][task["id"]]["files"])
            self.assertFalse(any("Oracle.java" in path or "reference/" in path or "suite.json" in path for path in files))

    def fake_execution(self, text, code):
        def execute(workspace, image, command, output, label, oracle):
            self.assertIn("/oracle/Oracle.java", command)
            self.assertIn("head -c 262144", command)
            self.assertEqual(suite.ROOT / "oracle/stockroom", oracle)
            (output / f"{label}.stdout").write_text(text)
            return {"exitCode": code}
        return execute

    def test_missing_oracle_marker_is_not_a_pass(self):
        log = "PUBLIC checks=8 failed=0\nPUBLIC_EXIT=0 ORACLE_EXIT=0 REGRESSION_EXIT=0\n"
        with patch.object(suite.shared, "execute", self.fake_execution(log, 0)):
            result = suite.evaluate(self.task, self.root, "image", self.root, "test")
        self.assertEqual("INCONCLUSIVE", result["status"])

    def test_nonzero_user_regression_rejects_even_if_oracle_passes(self):
        log = "PUBLIC checks=8 failed=0\nORACLE checks=192 failed=0\nPUBLIC_EXIT=0 ORACLE_EXIT=0 REGRESSION_EXIT=1\n"
        with patch.object(suite.shared, "execute", self.fake_execution(log, 1)):
            result = suite.evaluate(self.task, self.root, "image", self.root, "test")
        self.assertEqual("REJECTED", result["status"])
        self.assertFalse(result["accepted"])

    def test_behavioral_failure_is_distinct_from_compilation_failure(self):
        log = "PUBLIC checks=8 failed=1\nFAIL reserve/zero: expected rejection\nORACLE checks=192 failed=1\nPUBLIC_EXIT=1 ORACLE_EXIT=1 REGRESSION_EXIT=0\n"
        with patch.object(suite.shared, "execute", self.fake_execution(log, 1)):
            result = suite.evaluate(self.task, self.root, "image", self.root, "test")
        self.assertEqual("REJECTED", result["status"])
        self.assertEqual(["reserve/zero"], result["hiddenFailureLabels"])
        with patch.object(suite.shared, "execute", self.fake_execution("compile error", 2)):
            result = suite.evaluate(self.task, self.root, "image", self.root, "compile")
        self.assertEqual("COMPILE_ERROR", result["status"])

    def test_live_usage_keeps_lost_and_failed_calls_unknown(self):
        steps = [
            {"type": "MODEL_CALL_STARTED", "payload": {"modelCallId": "m1"}},
            {"type": "MODEL_CALL_STARTED", "payload": {"modelCallId": "m2"}},
            {"type": "MODEL_RESPONSE", "payload": {"modelCallId": "m1", "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12}}},
            {"type": "CONTEXT_SUMMARY_RESULT", "payload": {"operation": "SUMMARY", "output": {"usage": {"inputTokens": 20, "outputTokens": 3, "totalTokens": 23}}}},
            {"type": "CONTEXT_SUMMARY_RESULT", "payload": {"operation": "COMPACTION", "disposition": "FAILED"}},
        ]
        report = usage_report(steps)
        self.assertEqual(35, report["knownTotalTokens"])
        self.assertEqual(2, report["unknownTotalUsageCalls"])
        self.assertEqual(1, report["main"]["unansweredCalls"])
        self.assertEqual(1, report["compaction"]["unknownByMetric"]["totalTokens"])
        self.assertEqual(23, report["summary"]["knownTokens"]["totalTokens"])

    def test_live_usage_no_compaction_is_not_missing_usage(self):
        report = usage_report([])
        self.assertEqual(0, report["compaction"]["records"])
        self.assertEqual(0, report["unknownTotalUsageCalls"])
        self.assertIsNone(report["inputPeak"])


if __name__ == "__main__":
    unittest.main()
