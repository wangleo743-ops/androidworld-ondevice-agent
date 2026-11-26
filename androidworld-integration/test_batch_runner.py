"""Offline catalog, official-instance and batch-accounting checks; no ADB/API."""
import argparse
import contextlib
import copy
import importlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import batch_task_runner as batch
import official_task_runner as single
from task_catalog import DEFAULT_TASKS, PROFILE, get_spec, validate_scope


class CatalogTests(unittest.TestCase):
    def test_catalog_matches_actual_official_instances_for_multiple_seeds(self):
        if importlib.util.find_spec("android_world") is None:
            self.skipTest("Install the pinned AndroidWorld environment to compare official instances")
        from android_world import suite_utils
        for seed in (0, 1, 30, 1122, 2**32 + 1):
            for name in DEFAULT_TASKS:
                with self.subTest(seed=seed, task=name):
                    spec = get_spec(name)
                    cls = getattr(importlib.import_module("android_world.task_evals.single." + spec.module), name)
                    task = suite_utils.create_suite({name: cls}, seed=seed)[name][0]
                    plan = single.task_plan(seed, name)
                    self.assertEqual(plan["goal"], task.goal)
                    self.assertEqual(plan["params"], task.params)
                    self.assertEqual(plan["max_steps"], int(task.complexity * 10))
                    self.assertEqual(task.app_names, (spec.app_name,))
                    self.assertTrue(task.start_on_home_screen)
                    self.assertEqual(plan["profile"], single.DEFAULT_PROFILE)

    def test_target_goal_and_budget_cannot_cross_task_boundaries(self):
        cases = [
            ("SystemBrightnessMax", "com.google.android.deskclock", "Turn brightness to the max value.", 10),
            ("CameraTakePhoto", "com.android.camera2", "Take one video.", 10),
            ("CameraTakePhoto", "com.android.camera2", "Take one photo.", 11),
            ("ClockTimerEntry", "com.google.android.deskclock", "Create a timer with 24 hours, 0 minutes, and 0 seconds. Do not start the timer.", 10),
            ("ClockTimerEntry", "com.google.android.deskclock", "Create a timer with 0 hours, 60 minutes, and 0 seconds. Do not start the timer.", 10),
            ("ClockStopWatchRunning", "com.google.android.deskclock", "Run the stopwatch.\nOpen settings.", 10),
        ]
        for values in cases:
            with self.subTest(values=values), self.assertRaises(ValueError):
                validate_scope(*values)
        with self.assertRaises(ValueError):
            get_spec("SystemWifiTurnOff")


class BatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.args = argparse.Namespace(profile=PROFILE, output_root=self.temp.name, tasks=list(DEFAULT_TASKS), seed=30,
            model="gpt-5.6-sol", effort="xhigh", timeout_seconds=900,
            dedicated_emulator=True, serial="emulator-5554", console_port=5554,
            grpc_port=8554, adb_path="/sdk/adb", avd_name="AndroidWorldAvd", key_stdin=False)
        self.calls = []

    def fake_run(self, args, *, api_key):
        self.calls.append((args.task, api_key, args.setup_app))
        output = Path(args.output_root) / ("aw-test-" + str(len(self.calls)))
        output.mkdir(parents=True)
        failed = args.task == "ClockTimerEntry"
        single.save_json(output / "result.json", {
            "task_name": args.task, "run_id": output.name, "status": "failed" if failed else "passed",
            "profile": PROFILE, "target_package": get_spec(args.task).target_package,
            "passed": not failed, "score": 0 if failed else 1, "official_raw_score": 0 if failed else 1,
            "agent_done": True, "model_called": True, "step_count": 4, "cleanup_errors": [],
            "safe_to_teardown_task": True, "execution_complete": True,
            "timing_seconds": {"environment_setup": 12, "task_initialization": 3, "agent_episode": 22,
                               "official_evaluation": 2, "task_wall_through_official_score": 27,
                               "host_run_excluding_key_wait": 44},
        })
        single.save_json(output / "phone-result.json", {"model_calls": 4, "control": {"actions": 3},
            "timing": {"model_seconds": 10, "control_seconds": 2, "observation_seconds": 3,
                       "other_seconds": 1, "host_coordination_wait_seconds": 5, "total_wall_seconds": 21}})
        return output

    def run_with(self, run):
        with mock.patch.object(single, "preflight", return_value={}) as pre, \
             mock.patch.object(single, "official_source_hashes", return_value={}), \
             mock.patch.object(single, "read_key", return_value="offline-batch-key") as key, \
             mock.patch.object(single, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            output = batch.run_batch(self.args)
        key.assert_called_once_with(self.args)
        self.assertEqual(pre.call_args.kwargs["task_names"], list(DEFAULT_TASKS))
        for file in output.rglob("*.json"):
            self.assertNotIn("offline-batch-key", file.read_text())
        return json.loads((output / "summary.json").read_text(encoding="utf-8"))

    def test_one_memory_key_independent_tasks_and_scored_failure_continues(self):
        summary = self.run_with(self.fake_run)
        self.assertEqual([x[0] for x in self.calls], list(DEFAULT_TASKS))
        self.assertTrue(all(x[1] == "offline-batch-key" and x[2] for x in self.calls))
        self.assertEqual(summary["success_count"], 3)
        self.assertEqual(summary["scored_count"], 4)
        self.assertEqual(summary["status"], "completed")
        row = summary["rows"][1]
        self.assertFalse(row["success"])
        self.assertEqual(row["model_seconds"], 10)
        self.assertEqual(row["android_agent_total_seconds"], 21)
        self.assertEqual(row["task_through_official_score_seconds"], 27)
        self.assertEqual(row["full_run_excluding_key_wait_seconds"], 44)

    def test_infrastructure_failure_remains_visible_and_stops_reuse(self):
        def broken(args, *, api_key):
            path = self.fake_run(args, api_key=api_key)
            result = json.loads((path / "result.json").read_text())
            result.update(status="infrastructure_error", passed=False, score=None, official_raw_score=None)
            single.save_json(path / "result.json", result)
            return path
        summary = self.run_with(broken)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual([row["status"] for row in summary["rows"]], ["infrastructure_error", "not_run", "not_run", "not_run"])
        self.assertEqual(summary["success_fraction_all_planned"], 0)
        self.assertEqual(summary["status"], "stopped_incomplete")

    def make_closed_invalid_model_attempt(self, path):
        result = json.loads((path / "result.json").read_text())
        result.update(status="infrastructure_error", passed=False, score=None,
                      official_raw_score=None, agent_done=None)
        phone = json.loads((path / "phone-result.json").read_text())
        phone.update({key: result[key] for key in ("run_id", "task_name", "profile", "target_package")})
        phone.update(state="finished", kind="error", stop_reason="model_invalid_decision",
                     failure={"code": "model_invalid_decision"}, model_done=False,
                     official_evaluation_received=False, official_raw_score=None, agent_score=None)
        for component in ("planner", "control", "mcp"):
            phone.setdefault(component, {})["closed"] = True
        single.save_json(path / "result.json", result)
        single.save_json(path / "phone-result.json", phone)
        return result, phone

    def test_known_closed_model_error_continues_without_inventing_score(self):
        def invalid_brightness(args, *, api_key):
            path = self.fake_run(args, api_key=api_key)
            if args.task == "SystemBrightnessMax":
                self.make_closed_invalid_model_attempt(path)
            return path
        summary = self.run_with(invalid_brightness)
        self.assertEqual([x[0] for x in self.calls], list(DEFAULT_TASKS))
        self.assertEqual(summary["executed_count"], 4)
        self.assertEqual(summary["scored_count"], 3)
        self.assertEqual(summary["unscored_model_error_count"], 1)
        self.assertEqual(summary["success_count"], 2)
        self.assertEqual(summary["status"], "completed_with_unscored_failures")
        row = summary["rows"][2]
        self.assertEqual(row["status"], "agent_output_error_unscored")
        self.assertFalse(row["success"])
        self.assertFalse(row["official_evaluation_completed"])
        self.assertIsNone(row["agent_score"])
        self.assertIsNone(row["official_raw_score"])
        original = json.loads((Path(row["output"]) / "result.json").read_text())
        self.assertEqual(original["status"], "infrastructure_error")
        self.assertIsNone(original["score"])

    def test_known_model_error_requires_exact_closed_clean_bound_result(self):
        path = self.fake_run(argparse.Namespace(task="SystemBrightnessMax", output_root=self.temp.name, setup_app=True), api_key="offline")
        result, phone = self.make_closed_invalid_model_attempt(path)
        self.assertTrue(batch._known_unscored_model_error_is_closed("SystemBrightnessMax", result, phone))
        cases = [
            ("host", "safe_to_teardown_task", False),
            ("host", "safe_to_teardown_task", None),
            ("host", "execution_complete", False),
            ("host", "cleanup_errors", ["task:failed"]),
            ("host", "cleanup_errors", None),
            ("host", "score", 0),
            ("host", "official_raw_score", 0),
            ("phone", "run_id", "aw-unrelated"),
            ("phone", "task_name", "CameraTakePhoto"),
            ("phone", "target_package", "com.android.camera2"),
            ("phone", "profile", "wrong"),
            ("phone", "state", "running"),
            ("phone", "stop_reason", "model_transport_error"),
            ("phone", "stop_reason", "model_invalid_decision_unknown"),
            ("phone", "failure", {"code": "unknown"}),
            ("phone", "model_done", True),
            ("phone", "official_evaluation_received", True),
            ("phone", "planner", {"closed": False}),
            ("phone", "control", {}),
            ("phone", "mcp", {"closed": False}),
        ]
        for side, key, value in cases:
            with self.subTest(side=side, key=key, value=value):
                host_case, phone_case = copy.deepcopy(result), copy.deepcopy(phone)
                (host_case if side == "host" else phone_case)[key] = value
                self.assertFalse(batch._known_unscored_model_error_is_closed("SystemBrightnessMax", host_case, phone_case))

    def test_model_error_diagnostics_use_exact_finite_allowlist(self):
        path = self.fake_run(argparse.Namespace(task="SystemBrightnessMax", output_root=self.temp.name, setup_app=True), api_key="offline")
        result, phone = self.make_closed_invalid_model_attempt(path)
        self.assertEqual(len(batch.KNOWN_MODEL_OUTPUT_ERRORS), 26)
        for reason in batch.KNOWN_MODEL_OUTPUT_ERRORS:
            with self.subTest(reason=reason):
                phone["stop_reason"] = reason
                phone["failure"]["code"] = reason
                self.assertTrue(batch._known_unscored_model_error_is_closed("SystemBrightnessMax", result, phone))
        for reason in ("model_invalid_decision_unreviewed", "model_invalid_decision_invalid_json_extra",
                       "model_invalid_decision_invalid_json\n", "model_transport_error"):
            with self.subTest(reason=reason):
                phone["stop_reason"] = reason
                phone["failure"]["code"] = reason
                self.assertFalse(batch._known_unscored_model_error_is_closed("SystemBrightnessMax", result, phone))

    def test_missing_phone_timing_is_unknown_not_zero(self):
        path = self.fake_run(argparse.Namespace(task=DEFAULT_TASKS[0], output_root=self.temp.name, setup_app=True), api_key="offline")
        (path / "phone-result.json").unlink()
        row = batch.result_row(DEFAULT_TASKS[0], path)
        self.assertIsNone(row["model_seconds"])
        self.assertIsNone(row["model_calls"])

    def test_attempted_exception_is_not_mislabeled_unattempted(self):
        def raising(args, *, api_key):
            self.calls.append((args.task, api_key, args.setup_app))
            raise TimeoutError("offline simulated timeout")
        summary = self.run_with(raising)
        self.assertEqual(summary["rows"][0]["status"], "infrastructure_error")
        self.assertIsNone(summary["rows"][0]["model_called"])
        self.assertEqual(summary["executed_count"], 1)
        self.assertEqual(summary["rows"][1]["status"], "not_run")


if __name__ == "__main__":
    unittest.main()
