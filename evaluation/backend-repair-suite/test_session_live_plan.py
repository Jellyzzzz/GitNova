"""Orchestrator tests with all HTTP/model/service processes mocked; not live validation."""
import json
import contextlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import run_live
import session_chain
import suite


class SessionLivePlanTest(unittest.TestCase):
    def exercise(self, failure_stage=None, drift_stage=None):
        with tempfile.TemporaryDirectory(prefix="chain-dispatch-test-") as directory:
            root = Path(directory)
            project = root / "project"
            (project / "target").mkdir(parents=True)
            (project / "target/gitnova-0.0.1-SNAPSHOT.jar").write_bytes(b"mock-service-not-executable")
            packs = root / "packs"
            packs.mkdir()
            session_chain.prepare(packs)
            fixture = packs / "tasks/eventstats-session-chain"
            workspace = root / "workspace"
            shutil.copytree(fixture / "repository", workspace)
            files = suite.snapshot(workspace)
            calls = []
            server = MagicMock(pid=12345)

            def start_server(*args, **kwargs):
                kwargs["stdout"].write("Started GitNovaApplication\n")
                kwargs["stdout"].flush()
                return server

            def run(arguments, **kwargs):
                # Gitlet init/add/commit do not call a real process in this orchestration test.
                if arguments[0] == "java":
                    return subprocess.CompletedProcess(arguments, 0)
                self.assertTrue(arguments[1].endswith("live_client.py"))
                action = arguments[2]
                calls.append((action, arguments))
                if action == "setup":
                    return subprocess.CompletedProcess(arguments, 0)
                number = sum(action == "run" for action, _ in calls)
                trial = Path(arguments[arguments.index("--output") + 1])
                destination = trial / "G80"
                if number > 1:
                    name = arguments[arguments.index("--follow-up") + 1]
                    self.assertEqual(session_chain.definition()[2]["tasks"][number - 1]["name"], name)
                    destination = destination / "followups" / name
                else:
                    self.assertNotIn("--follow-up", arguments)
                destination.mkdir(parents=True)
                state = {"files": files, "generation": 0, "providerRef": str(workspace), "epoch": 0}
                initial = {**state, "generation": 1} if number == drift_stage else state
                records = {
                    "summary.json": {"status": "COMPLETED", "workspace": str(workspace), "sessionId": "same-session",
                                     "taskId": f"task-{number}", "runId": f"run-{number}"},
                    "initial-workspace.json": initial, "post-workspace.json": state,
                    "workspace.json": {"workspaceId": "same-workspace", **state},
                    "run-steps.json": [{"sequence": number * 10, "type": "MODEL_RESPONSE", "payload": {
                        "modelCallId": f"call-{number}", "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12}}}],
                }
                for name, value in records.items():
                    (destination / name).write_text(json.dumps(value))
                return subprocess.CompletedProcess(arguments, 0)

            def verify(candidate, before, stage, image, output):
                passed = stage != failure_stage
                return {"accepted": passed, "status": "ACCEPTED" if passed else "REJECTED"}

            output = root / "live"
            arguments = ["run_live.py", "--session-chain", "--packs", str(packs), "--output", str(output)]
            connection = MagicMock()
            connection.__enter__.return_value.connect_ex.return_value = 1
            with (patch.object(sys, "argv", arguments),
                  contextlib.redirect_stdout(io.StringIO()),
                  patch.object(run_live, "PROJECT", project),
                  patch.dict(os.environ, {"LLM_API_KEY": "not-a-real-key", "MYSQL_PWD": "not-a-real-password"}),
                  patch.object(run_live.socket, "socket", return_value=connection),
                  patch.object(run_live, "query", return_value=[{"active": 0}]),
                  patch.object(run_live.subprocess, "check_output", return_value="frozen-id\n"),
                  patch.object(run_live.subprocess, "Popen", side_effect=start_server),
                  patch.object(run_live.subprocess, "run", side_effect=run),
                  patch.object(session_chain, "verify", side_effect=verify),
                  self.assertRaises(SystemExit) as stopped):
                run_live.main()
            result = json.loads((output / "batch.json").read_text())
            self.assertEqual(1, sum(action == "setup" for action, _ in calls))
            self.assertTrue(all("--continue-after-failure" not in command for _, command in calls))
            self.assertEqual(8, result["plannedTasks"])
            self.assertTrue(result["serviceStopped"])
            server.terminate.assert_called_once()
            return result, stopped.exception.code

    def test_eight_followups_reuse_one_session_and_account_each_run_once(self):
        result, code = self.exercise()
        self.assertEqual(0, code)
        self.assertEqual((8, 96), (result["passed"], result["knownTotalTokens"]))
        self.assertEqual({"same-session"}, {task["sessionId"] for task in result["tasks"]})
        self.assertEqual(8, len({task["taskId"] for task in result["tasks"]}))
        self.assertTrue(all(task["continuityPassed"] for task in result["tasks"]))

    def test_failed_stage_stops_without_skipping_or_replaying(self):
        result, code = self.exercise(failure_stage=3)
        self.assertEqual(1, code)
        self.assertEqual("STOPPED_AT_FAILED_STAGE", result["status"])
        self.assertEqual((2, 3, 5), (result["passed"], len(result["tasks"]), len(result["unstartedTasks"])))

    def test_generation_discontinuity_stops_even_if_run_and_oracle_pass(self):
        result, code = self.exercise(drift_stage=3)
        self.assertEqual(1, code)
        self.assertEqual(3, len(result["tasks"]))
        self.assertFalse(result["tasks"][-1]["continuityPassed"])


if __name__ == "__main__":
    unittest.main()
