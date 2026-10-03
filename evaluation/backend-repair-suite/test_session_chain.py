import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import session_chain as chain
import suite


class SessionChainTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="session-chain-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.tasks, self.combined, self.scenario = chain.definition()
        self.initial = suite.materialize(self.combined, "starter", self.root / "initial")

    def test_combined_fixture_contains_exactly_five_broken_modules(self):
        reference = suite.materialize(self.combined, "reference", self.root / "correct")
        changed = {path for path in self.initial if self.initial[path] != reference[path]}
        self.assertEqual(5, len(changed))
        self.assertTrue(all(path.startswith("src/main/java/eventstats/") for path in changed))
        self.assertEqual(8, len(self.scenario["tasks"]))
        self.assertEqual(8, len({task["name"] for task in self.scenario["tasks"]}))
        self.assertEqual(128000, self.scenario["experiment"]["contextWindowTokens"])

    def test_readonly_stage_rejects_any_file_edit(self):
        actual = dict(self.initial)
        actual["src/main/java/eventstats/EventParser.java"] = "changed"
        with self.assertRaisesRegex(ValueError, "Scope violation"):
            chain.check_scope(self.initial, self.initial, actual, self.scenario["tasks"], 1)

    def test_current_task_cannot_undo_a_previous_repair(self):
        before = dict(self.initial)
        before["src/main/java/eventstats/EventParser.java"] = "repaired"
        actual = dict(before)
        actual["src/main/java/eventstats/EventStore.java"] = "repaired-too"
        self.assertEqual(["src/main/java/eventstats/EventStore.java"],
                         chain.check_scope(self.initial, before, actual, self.scenario["tasks"], 3))
        actual["src/main/java/eventstats/EventParser.java"] = self.initial["src/main/java/eventstats/EventParser.java"]
        with self.assertRaises(ValueError):
            chain.check_scope(self.initial, before, actual, self.scenario["tasks"], 3)

    def test_future_fix_and_early_regression_creation_are_rejected(self):
        for path in ("src/main/java/eventstats/CursorPager.java", chain.REGRESSION):
            actual = {**self.initial, path: "new"}
            with self.subTest(path=path), self.assertRaises(ValueError):
                chain.check_scope(self.initial, self.initial, actual, self.scenario["tasks"], 2)

    def test_protected_files_and_deletions_remain_protected_at_end(self):
        before = {**self.initial, chain.REGRESSION: "test"}
        for path in ("README.md", "run-tests.sh", "src/test/java/testing/Checks.java"):
            actual = {**before, path: "changed"}
            with self.subTest(path=path), self.assertRaises(ValueError):
                chain.check_scope(self.initial, before, actual, self.scenario["tasks"], 8)
        del before["src/main/java/eventstats/EventParser.java"]
        with self.assertRaises(ValueError):
            chain.check_scope(self.initial, before, before, self.scenario["tasks"], 8)

    def test_regression_test_is_required_from_stage_seven(self):
        with self.assertRaisesRegex(ValueError, "require RegressionChecks"):
            chain.check_scope(self.initial, self.initial, self.initial, self.scenario["tasks"], 7)
        actual = {**self.initial, chain.REGRESSION: "tests"}
        self.assertEqual([chain.REGRESSION],
                         chain.check_scope(self.initial, self.initial, actual, self.scenario["tasks"], 7))

    def test_known_pending_failure_does_not_fail_completed_stage(self):
        raw = {"status": "REJECTED", "hiddenFailureLabels": ["dedup/conflict/1", "window/boundaries/1"],
               "regressionExitCode": 0}
        self.assertTrue(chain.stage_verdict(raw, ["dedup/tenants", "windows/negative-epoch"], 1)["accepted"])
        self.assertFalse(chain.stage_verdict(raw, [], 2)["accepted"])

    def test_unknown_failure_or_missing_verifier_evidence_is_never_waived(self):
        for label in ("empty-and-invalid", "report/integration", "parse/invalid-row"):
            raw = {"status": "REJECTED", "hiddenFailureLabels": [], "regressionExitCode": 0}
            self.assertFalse(chain.stage_verdict(raw, [label], 0)["accepted"])
        raw = {"status": "REJECTED", "hiddenFailureLabels": ["empty-and-invalid"], "regressionExitCode": 0}
        self.assertFalse(chain.stage_verdict(raw, [], 0)["accepted"])
        raw = {"status": "INCONCLUSIVE"}
        self.assertFalse(chain.stage_verdict(raw, [], 0)["accepted"])
        self.assertFalse(chain.stage_verdict({"status": "COMPILE_ERROR"}, [], 0)["accepted"])

    def test_regression_failure_cannot_hide_behind_pending_business_checks(self):
        result = chain.stage_verdict({"status": "REJECTED", "hiddenFailureLabels": [], "regressionExitCode": 1}, [], 0)
        self.assertFalse(result["accepted"])

    def test_generated_pack_contains_no_oracle_or_reference_test(self):
        output = self.root / "pack"
        output.mkdir()
        chain.prepare(output)
        fixture = output / "tasks/eventstats-session-chain"
        self.assertEqual(self.initial, suite.snapshot(fixture / "repository"))
        self.assertFalse((fixture / "repository" / chain.REGRESSION).exists())
        plan = json.loads((output / "run-plan.json").read_text())
        self.assertEqual((1, 8, 8), (plan["sessions"], plan["distinctTasks"], plan["plannedRuns"]))
        self.assertEqual({"NOT_RUN"}, {task["status"] for task in plan["tasks"]})
        self.assertEqual(8, len(list((fixture / "tasks").glob("*.md"))))

    def test_compile_error_does_not_count_as_detected_mutation(self):
        correct = self.root / "correct"
        suite.materialize(self.combined, "reference", correct)
        output = self.root / "quality"
        output.mkdir()

        def execute(workspace, image, command, destination, label):
            log = "REGRESSION checks=6 failed=0\n" if label == "regression-healthy" else "compile failed\n"
            (destination / (label + ".stdout")).write_text(log)
            return {"exitCode": 0 if label == "regression-healthy" else 2}

        with patch.object(suite.shared, "execute", execute):
            quality = chain.regression_quality(correct, "image", output)
        self.assertFalse(quality["accepted"])
        self.assertEqual(1, sum(control["valid"] for control in quality["controls"]))


if __name__ == "__main__":
    unittest.main()
