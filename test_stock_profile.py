"""Offline profile, artifact, receipt, and child-lifecycle checks. No ADB/API."""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

import batch_task_runner as batch
import official_task_runner as single
from task_catalog import (DEFAULT_TASKS, PROFILE, STOCK_PROFILE, profile_artifacts,
                          profile_metadata, validate_profile)
from test_bridge_contract import load_bridge

bridge = load_bridge()


class StockProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.context = {
            "profile": STOCK_PROFILE, "adb_path": "/sdk/adb", "serial": "emulator-5554",
            "run_id": "aw-stock-offline", "output_dir": self.temp.name,
            "task_name": "ClockStopWatchRunning", "goal": "Run the stopwatch.",
            "target_package": "com.google.android.deskclock", "seed": 3429360255,
            "max_steps": 10, "model": "gpt-5.6-sol", "effort": "xhigh", "timeout_seconds": 900,
        }

    def agent(self):
        return bridge.PhoneBridge(mock.Mock(), context=self.context, api_key="offline-test-key")

    def test_release_default_and_fixed_artifact_mapping(self):
        self.assertEqual(single.task_plan(30)["profile"], STOCK_PROFILE)
        self.assertEqual(profile_artifacts(PROFILE), ("build", "androidworld-agent.jar"))
        self.assertEqual(profile_artifacts(STOCK_PROFILE), ("build-stock-baseline", "stock-android-agent.jar"))
        del self.context["profile"]
        self.assertEqual(self.agent().profile, STOCK_PROFILE)

    def test_same_official_instances_and_budgets_on_both_backends(self):
        for task in DEFAULT_TASKS:
            legacy, stock = single.task_plan(30, task, PROFILE), single.task_plan(30, task, STOCK_PROFILE)
            for key in ("goal", "params", "instance_seed", "target_package", "max_steps", "upstream_commit"):
                self.assertEqual(legacy[key], stock[key])
            self.assertEqual(stock["profile"], STOCK_PROFILE)
            self.assertFalse(stock["mobiclaw_system_backend"])
            self.assertFalse(stock["formal_user_grants"])
            self.assertEqual(stock["requested_control_transport"], "stdio_jsonrpc")

    def test_unknown_or_mobiclaw_profile_rejected_before_execution(self):
        for profile in ("mobiclaw", "stock_android_mcp_baseline_v2", "../build", None, 3):
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                validate_profile(profile)
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                single.task_plan(30, profile=profile)
            self.context["profile"] = profile
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                self.agent()

    def test_stock_bootstrap_uses_own_jar_and_profile_key_stays_in_stdin(self):
        agent = self.agent()
        launches = []
        def adb(args, **kw):
            if args == ["shell", "id", "-u"]:
                return subprocess.CompletedProcess(args, 0, b"2000\n", b"")
            launches.append((args, json.loads(kw["data"])))
            receipt = {"launched": True, "run_id": agent.run_id, "output": agent.remote, "profile": STOCK_PROFILE}
            return subprocess.CompletedProcess(args, 0, json.dumps(receipt).encode(), b"")
        agent._adb = adb
        agent._read_json = lambda name: {"run_id": agent.run_id, "profile": STOCK_PROFILE,
            "state": "ready", "expected_sequence": 1} if name == "state.json" else None
        agent._verify_process_identity = mock.Mock()
        agent._start()
        command, payload = launches[0]
        self.assertIn("/stock-android-agent.jar", command[-1])
        self.assertNotIn("offline-test-key", " ".join(command))
        self.assertEqual(payload["profile"], STOCK_PROFILE)
        self.assertEqual(payload["api_key"], "offline-test-key")
        self.assertEqual(agent.api_key, "")
        for path in Path(self.temp.name).rglob("*.json"):
            self.assertNotIn("offline-test-key", path.read_text())

    def test_stock_cannot_accept_legacy_or_missing_profile_receipts(self):
        agent = self.agent()
        for profile in (None, PROFILE, "mobiclaw"):
            receipt = {"run_id": agent.run_id}
            if profile is not None:
                receipt["profile"] = profile
            with self.subTest(profile=profile), self.assertRaises(RuntimeError):
                agent._save_early_final(receipt)
        self.assertIsNone(agent.final_receipt)

    def test_stock_step_receipt_profile_is_checked_before_saved(self):
        agent = self.agent()
        agent.started = True
        agent._write_command = mock.Mock()
        agent._wait_json = mock.Mock(return_value={"run_id": agent.run_id,
            "profile": PROFILE, "sequence": 1, "done": False})
        with self.assertRaises(RuntimeError):
            agent.step(self.context["goal"])
        self.assertEqual(agent.sequence, 1)
        self.assertEqual(agent.step_receipts, [])

    def test_agent_exit_alone_does_not_allow_task_reset(self):
        agent = self.agent()
        agent.bootstrap_sent = True
        agent.process_stopped = True
        self.assertFalse(agent.ready_for_task_teardown)
        agent.final_receipt = {"state": "finished", "profile": STOCK_PROFILE,
            "control": {"closed": True, "transport": "stdio_jsonrpc"}}
        self.assertFalse(agent.ready_for_task_teardown)
        agent.final_receipt["control"]["child_exit_confirmed"] = True
        self.assertFalse(agent.ready_for_task_teardown)
        agent.final_receipt["control"]["cleanup_confirmed"] = True
        self.assertTrue(agent.ready_for_task_teardown)
        agent.final_receipt["control"]["transport"] = "session_local_jsonrpc"
        self.assertFalse(agent.ready_for_task_teardown)

    def test_batch_marks_wrong_backend_result_and_stops(self):
        output = Path(self.temp.name)
        single.save_json(output / "result.json", {"profile": PROFILE, "status": "passed",
            "passed": True, "score": 1, "official_raw_score": 1, "cleanup_errors": []})
        row = batch.result_row("ClockStopWatchRunning", output, STOCK_PROFILE)
        self.assertEqual(row["status"], "profile_mismatch")
        self.assertFalse(row["success"])
        self.assertFalse(row["safe_to_continue_batch"])

    def test_setup_failure_before_agent_is_infrastructure_not_profile_mismatch(self):
        output = Path(self.temp.name)
        single.save_json(output / "result.json", {"profile": STOCK_PROFILE,
            "status": "infrastructure_error", "model_called": False,
            "safe_to_teardown_task": True, "cleanup_errors": [],
            "error_type": "AdbControllerError", "failed_stage": "environment_setup",
            "failed_phase": "configuration"})
        row = batch.result_row(DEFAULT_TASKS[0], output, STOCK_PROFILE)
        self.assertEqual(row["status"], "infrastructure_error")
        self.assertFalse(row["safe_to_continue_batch"])
        self.assertFalse(row["phone_result_available"])
        self.assertEqual(row["profile_verification"], "not_observed")
        self.assertEqual(row["failed_phase"], "configuration")

    def test_scored_result_without_control_receipt_cannot_continue(self):
        output = Path(self.temp.name)
        single.save_json(output / "result.json", {"profile": STOCK_PROFILE,
            "status": "passed", "passed": True, "safe_to_teardown_task": True, "cleanup_errors": []})
        row = batch.result_row(DEFAULT_TASKS[0], output, STOCK_PROFILE)
        self.assertEqual(row["status"], "cleanup_error")
        self.assertFalse(row["safe_to_continue_batch"])

    def test_control_constructor_failure_preserves_task_and_writes_batch_summary(self):
        # Match AndroidWorldMain's real failure schema: its constructor did not
        # return, so control and mcp are JSON null, even though state is finished.
        # Execute the actual host finally + batch aggregation; mock only the
        # external AndroidWorld environment and ADB evidence transport.
        name = DEFAULT_TASKS[0]
        plan = single.task_plan(30, name, STOCK_PROFILE)
        task = mock.Mock(params=plan["params"], goal=plan["goal"], complexity=1,
                         start_on_home_screen=True)
        env = mock.Mock()
        episode_runner = mock.Mock()
        suite_utils = mock.Mock()
        suite_utils.create_suite.return_value = {name: [task]}
        launcher = mock.Mock()
        launcher._get_env.return_value = env
        modules = {
            "android_world": types.SimpleNamespace(episode_runner=episode_runner, suite_utils=suite_utils),
            "android_world.env": types.SimpleNamespace(env_launcher=launcher),
            "android_world.env.setup_device": types.SimpleNamespace(apps=mock.Mock(), setup=mock.Mock()),
            "absl": types.SimpleNamespace(logging=mock.Mock()),
        }
        agents = []
        def factory(environment, *, context, api_key):
            self.assertNotEqual(api_key, "offline-regression-key")
            self.assertTrue(context["responses_endpoint"].startswith("http://10.0.2.2:"))
            self.assertTrue(context["allow_http"])
            agent = bridge.PhoneBridge(environment, context=context, api_key=api_key)
            agent.collect = mock.Mock()
            agents.append(agent)
            return agent
        def failed_episode(goal, agent, **kwargs):
            agent.bootstrap_sent = True
            agent._save_early_final({"run_id": agent.run_id, "profile": STOCK_PROFILE,
                "task_name": name, "target_package": plan["target_package"],
                "state": "finished", "kind": "error", "stop_reason": "stock_control_start_failed",
                "failure": {"code": "stock_control_start_failed", "type": "SecurityException"},
                "control": None, "mcp": None, "planner": {}, "model_calls": 0,
                "model_done": False, "official_evaluation_received": False,
                "official_raw_score": None, "agent_score": None, "timing": {}})
            raise RuntimeError("On-device Agent failed before first model request")
        episode_runner.run_episode.side_effect = failed_episode
        import_modules = {
            "android_world.task_evals.single.clock": types.SimpleNamespace(**{name: object}),
            "phone_bridge": types.SimpleNamespace(create_agent=factory),
        }
        args = argparse.Namespace(profile=STOCK_PROFILE, output_root=self.temp.name,
            tasks=[name, DEFAULT_TASKS[1]], seed=30, model="gpt-5.6-sol", effort="xhigh",
            timeout_seconds=900, console_port=5554, grpc_port=8554,
            adb_path="offline-adb", serial="emulator-5554")
        with mock.patch.dict(sys.modules, modules), \
             mock.patch.object(single.importlib, "import_module", side_effect=lambda key: import_modules[key]), \
             mock.patch.object(single, "preflight", return_value={}), \
             mock.patch.object(single, "official_source_hashes", return_value={}), \
             mock.patch.object(single, "read_key", return_value="offline-regression-key"), \
             contextlib.redirect_stdout(io.StringIO()):
            output = batch.run_batch(args)
        summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(len(agents), 1)
        self.assertFalse(agents[0].ready_for_task_teardown)
        task.tear_down.assert_not_called()
        task.is_successful.assert_not_called()
        env.close.assert_called_once()
        self.assertEqual(summary["status"], "stopped_incomplete")
        self.assertEqual(summary["rows"][0]["status"], "cleanup_error")
        self.assertEqual(summary["rows"][0]["phone_stop_reason"], "stock_control_start_failed")
        self.assertFalse(summary["rows"][0]["safe_to_continue_batch"])
        self.assertEqual(summary["rows"][1]["status"], "not_run")
        result = json.loads((Path(summary["rows"][0]["output"]) / "result.json").read_text(encoding="utf-8"))
        self.assertFalse(result["safe_to_teardown_task"])
        self.assertFalse(result["execution_complete"])
        self.assertFalse(result["model_called"])
        self.assertEqual(result["model_transport"], "host_relay")
        self.assertEqual(result["responses_endpoint"], "https://api.openai.com/v1/responses")
        self.assertEqual(result["model_relay"]["upstream_requests"], 0)
        self.assertFalse(result["model_relay"]["provider_key_sent_to_android"])
        self.assertIn("task:preserved_because_agent_stop_unknown", result["cleanup_errors"])

    def test_stock_nested_action_count_and_timing_preserve_legacy_totals(self):
        output = Path(self.temp.name)
        child_time = {"execution_seconds": 0.2, "observation_seconds": 0.3, "server_wall_seconds": 1.8}
        for profile in (PROFILE, STOCK_PROFILE):
            with self.subTest(profile=profile):
                single.save_json(output / "result.json", {"profile": profile, "status": "passed",
                    "passed": True, "score": 1, "official_raw_score": 1, "cleanup_errors": []})
                control = {"actions": 3, "closed": True} if profile == PROFILE else {
                    "transport": "stdio_jsonrpc", "closed": True,
                    "server": {"device": {"actions": 3}, "timing": child_time}}
                single.save_json(output / "phone-result.json", {"profile": profile, "control": control,
                    "timing": {"total_wall_seconds": 2.0, "control_seconds": 0.4, "observation_seconds": 0.5}})
                row = batch.result_row(name := DEFAULT_TASKS[0], output, profile)
                self.assertEqual(row["actions"], 3)
                self.assertEqual(row["android_agent_total_seconds"], 2.0)
                self.assertEqual(row["control_seconds"], 0.4)
                self.assertEqual(row["control_service_timing_seconds"], child_time if profile == STOCK_PROFILE else {})

    def test_batch_threads_explicit_profile_without_changing_task(self):
        args = argparse.Namespace(profile=STOCK_PROFILE, output_root=self.temp.name,
            tasks=[DEFAULT_TASKS[0]], seed=30, model="gpt-5.6-sol", effort="xhigh", timeout_seconds=900)
        def run(per_task, *, api_key):
            self.assertEqual(per_task.profile, STOCK_PROFILE)
            self.assertEqual(per_task.task, DEFAULT_TASKS[0])
            self.assertEqual(api_key, "offline-only-key")
            path = Path(per_task.output_root) / "aw-stock-fake"
            path.mkdir(parents=True)
            single.save_json(path / "result.json", {"profile": STOCK_PROFILE, "status": "passed",
                "run_id": path.name, "passed": True, "score": 1, "official_raw_score": 1,
                "cleanup_errors": [], "safe_to_teardown_task": True})
            single.save_json(path / "phone-result.json", {"profile": STOCK_PROFILE,
                "control": {"transport": "stdio_jsonrpc", "closed": True,
                            "child_exit_confirmed": True, "cleanup_confirmed": True}})
            return path
        with mock.patch.object(single, "preflight", return_value={}), \
             mock.patch.object(single, "official_source_hashes", return_value={}), \
             mock.patch.object(single, "read_key", return_value="offline-only-key"), \
             mock.patch.object(single, "run", side_effect=run), contextlib.redirect_stdout(io.StringIO()):
            output = batch.run_batch(args)
        summary = json.loads((output / "summary.json").read_text())
        self.assertEqual(summary["profile"], STOCK_PROFILE)
        self.assertEqual(summary["status"], "completed")
        self.assertEqual(summary["rows"][0]["profile"], STOCK_PROFILE)
        self.assertFalse(summary["mobiclaw_system_backend"])
        for path in output.rglob("*.json"):
            self.assertNotIn("offline-only-key", path.read_text())

    def test_cli_plan_has_no_device_or_key_requirement(self):
        result = subprocess.run([sys.executable, str(single.HERE / "batch_task_runner.py"),
            "--profile", STOCK_PROFILE], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = json.loads(result.stdout)
        self.assertEqual(len(rows), 4)
        self.assertTrue(all(row["profile"] == STOCK_PROFILE and row["model_called"] is False for row in rows))

    def _preflight(self, manifest_changes=None, remote_hash=None):
        root = Path(self.temp.name)
        build = root / "build-stock-baseline"
        build.mkdir(exist_ok=True)
        jar, apk = b"offline-stock-jar", b"offline-planner-apk"
        (build / "stock-android-agent.jar").write_bytes(jar)
        (build / "androidworld-planner.apk").write_bytes(apk)
        jar_hash, apk_hash = hashlib.sha256(jar).hexdigest(), hashlib.sha256(apk).hexdigest()
        manifest = {"planner_package": "com.lab.androidworldplanner", "jar_sha256": jar_hash,
            "apk_sha256": apk_hash, "supported_profiles": [PROFILE, STOCK_PROFILE],
            "agent_filename": "stock-android-agent.jar"}
        manifest.update(manifest_changes or {})
        single.save_json(build / "build.json", manifest)
        outputs = {
            ("shell", "getprop", "ro.kernel.qemu"): "1",
            ("shell", "getprop", "ro.build.version.sdk"): "33",
            ("shell", "getprop", "ro.product.model"): "Pixel 6",
            ("shell", "getprop", "ro.boot.qemu.avd_name"): "AndroidWorldAvd",
            ("shell", "pm", "path", single.PACKAGE): "package:/system/product/app/DeskClock/DeskClock.apk",
            ("shell", "pm", "path", "com.lab.androidworldplanner"): "package:/data/app/~~abc123/pkg-xyz==/base.apk",
            ("shell", "sha256sum", "/data/local/tmp/mobile-ondevice-agent/stock-android-agent.jar"): (remote_hash or jar_hash) + "  agent.jar",
            ("shell", "sha256sum", "/data/app/~~abc123/pkg-xyz==/base.apk"): apk_hash + "  base.apk",
        }
        args = argparse.Namespace(profile=STOCK_PROFILE, dedicated_emulator=True,
            serial="emulator-5554", console_port=5554, avd_name="AndroidWorldAvd")
        with mock.patch.object(single, "HERE", root), \
             mock.patch.object(single, "adb", side_effect=lambda _, cmd: outputs[tuple(cmd)]), \
             mock.patch.object(single.subprocess, "check_output", side_effect=[single.EXPECTED_COMMIT, ""]), \
             mock.patch.object(single.importlib.metadata, "version", return_value="1.2.3"):
            return single.preflight(args)

    def test_stock_preflight_uses_only_own_verified_artifact(self):
        result = self._preflight()
        self.assertEqual(result["profile"], STOCK_PROFILE)
        self.assertEqual(result["agent_jar"], "stock-android-agent.jar")
        self.assertEqual(len(result["ondevice_artifacts"]), 2)

    def test_stock_preflight_rejects_legacy_build_declaration(self):
        for change in ({"supported_profiles": [PROFILE]}, {"supported_profiles": STOCK_PROFILE},
                       {"agent_filename": "androidworld-agent.jar"}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                self._preflight(change)
        with self.assertRaises(RuntimeError):
            self._preflight(remote_hash="0" * 64)


if __name__ == "__main__":
    unittest.main()
