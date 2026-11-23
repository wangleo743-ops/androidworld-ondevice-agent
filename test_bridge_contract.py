"""Offline tests: no device, model request, real key, or production grant."""
from __future__ import annotations

import dataclasses
import importlib.util
import hashlib
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock


HERE = Path(__file__).resolve().parent


def load_bridge():
    base = types.ModuleType("android_world.agents.base_agent")
    class Base:
        def __init__(self, env, **kwargs):
            self.env = env
    @dataclasses.dataclass
    class Result:
        done: bool
        data: dict
    base.EnvironmentInteractingAgent = Base
    base.AgentInteractionResult = Result
    pkg = types.ModuleType("android_world")
    agents = types.ModuleType("android_world.agents")
    agents.base_agent = base
    with mock.patch.dict(sys.modules, {"android_world": pkg, "android_world.agents": agents, "android_world.agents.base_agent": base}):
        spec = importlib.util.spec_from_file_location("bridge_under_test", HERE / "phone_bridge.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module


bridge_module = load_bridge()
spec = importlib.util.spec_from_file_location("runner_under_test", HERE / "official_task_runner.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env = mock.Mock()
        self.context = {
            "profile": bridge_module.PROFILE, "adb_path": "/sdk/adb", "serial": "emulator-5554",
            "run_id": "aw-20261121t000000z-test1234", "output_dir": self.temp.name,
            "task_name": "ClockStopWatchRunning", "goal": "Run the stopwatch.",
            "target_package": "com.google.android.deskclock",
            "seed": 3429360255, "max_steps": 10, "model": "gpt-5.6-sol",
            "effort": "xhigh", "timeout_seconds": 900,
        }
        self.agent = bridge_module.PhoneBridge(self.env, context=self.context, api_key="offline-test-key")

    def test_physical_device_rejected(self):
        self.context["serial"] = "physical-test-device"
        with self.assertRaises(ValueError):
            bridge_module.PhoneBridge(self.env, context=self.context, api_key="unused")

    def test_scope_path_rejected(self):
        self.context["run_id"] = "../../outside"
        with self.assertRaises(ValueError):
            bridge_module.PhoneBridge(self.env, context=self.context, api_key="unused")

    def test_catalog_target_cannot_be_swapped_for_another_app(self):
        self.context["target_package"] = "com.android.settings"
        with self.assertRaises(ValueError):
            bridge_module.PhoneBridge(self.env, context=self.context, api_key="unused")

    def test_new_task_targets_are_preserved_in_bootstrap(self):
        for name in ("ClockTimerEntry", "SystemBrightnessMax", "CameraTakePhoto"):
            with self.subTest(task=name):
                plan = runner.task_plan(30, name)
                context = {**self.context, "task_name": name, "target_package": plan["target_package"],
                           "goal": plan["goal"], "seed": plan["instance_seed"]}
                agent = bridge_module.PhoneBridge(self.env, context=context, api_key="offline-test-key")
                payloads = []
                def adb(args, **kwargs):
                    if args == ["shell", "id", "-u"]:
                        return subprocess.CompletedProcess(args, 0, b"2000\n", b"")
                    payloads.append(json.loads(kwargs["data"]))
                    return subprocess.CompletedProcess(args, 0, json.dumps({"launched": True, "run_id": agent.run_id,
                        "output": agent.remote, "profile": bridge_module.PROFILE}).encode(), b"")
                agent._adb = adb
                agent._read_json = lambda path: {"run_id": agent.run_id, "state": "ready", "expected_sequence": 1} if path == "state.json" else None
                agent._verify_process_identity = mock.Mock()
                agent._start()
                self.assertEqual(payloads[0]["task_name"], name)
                self.assertEqual(payloads[0]["target_package"], plan["target_package"])
                self.assertEqual(payloads[0]["goal"], plan["goal"])
                self.assertTrue(payloads[0]["profile"].endswith("_v2"))

    def test_launch_key_stdin_only_and_root_drops_identity(self):
        calls = []
        def adb(args, **kwargs):
            calls.append((args, kwargs))
            if args == ["shell", "id", "-u"]:
                return subprocess.CompletedProcess(args, 0, b"0\n", b"")
            receipt = {"launched": True, "run_id": self.agent.run_id,
                       "output": self.agent.remote, "profile": bridge_module.PROFILE}
            return subprocess.CompletedProcess(args, 0, json.dumps(receipt).encode(), b"")
        self.agent._adb = adb
        self.agent._read_json = lambda name: {"run_id": self.agent.run_id, "state": "ready", "expected_sequence": 1} if name == "state.json" else None
        self.agent._verify_process_identity = mock.Mock()
        self.agent._start()
        launch_args, launch_options = calls[-1]
        self.assertEqual(launch_args[:3], ["shell", "sh", "-c"])
        outer_command = shlex.split(launch_args[3])
        self.assertEqual(len(outer_command), 1)
        root_command = shlex.split(outer_command[0])
        self.assertEqual(root_command[:6], ["su", "2000", "runcon", "u:r:shell:s0", "/system/bin/sh", "-c"])
        self.assertEqual(len(root_command), 7)
        self.assertEqual(shlex.split(root_command[6]), [
            "env", "CLASSPATH=/data/local/tmp/mobile-ondevice-agent/androidworld-agent.jar",
            "app_process", "/system/bin", "com.lab.mobileagent.benchmark.AndroidWorldMain", "launch",
        ])
        self.agent._verify_process_identity.assert_called_once_with()
        self.assertNotIn("offline-test-key", " ".join(launch_args))
        self.assertEqual(json.loads(launch_options["data"])["api_key"], "offline-test-key")
        self.assertEqual(self.agent.api_key, "")
        for path in Path(self.temp.name).iterdir():
            self.assertNotIn("offline-test-key", path.read_text())

    def test_step_is_exactly_one_advance_without_env_action(self):
        self.agent.started = True
        self.agent._write_command = mock.Mock()
        self.agent._wait_json = mock.Mock(return_value={"run_id": self.agent.run_id, "sequence": 1, "done": False, "model_calls": 1})
        answer = self.agent.step(self.context["goal"])
        self.assertFalse(answer.done)
        self.assertEqual(self.agent.sequence, 2)
        self.assertEqual(self.agent.step_commands_sent, 1)
        self.assertEqual(self.agent._write_command.call_args.args[0]["command"], "advance")
        self.env.execute_action.assert_not_called()

    def test_ready_identity_rejects_wrong_selinux_domain_before_advance(self):
        self.agent._read_json = mock.Mock(return_value={"run_id": self.agent.run_id, "uid": 2000, "pid": 9999})
        self.agent._owned_process_state = mock.Mock(return_value="owned")
        self.agent._adb = mock.Mock(return_value=subprocess.CompletedProcess([], 0, b"u:r:su:s0\n", b""))
        with self.assertRaises(RuntimeError):
            self.agent._verify_process_identity()
        self.assertFalse((Path(self.temp.name) / "agent-identity.json").exists())
        self.agent._adb.return_value = subprocess.CompletedProcess([], 0, b"u:r:shell:s0\n", b"")
        self.agent._verify_process_identity()
        evidence = json.loads((Path(self.temp.name) / "agent-identity.json").read_text())
        self.assertTrue(evidence["verified_before_advance"])

    def test_ready_identity_accepts_only_trailing_procfs_terminators(self):
        self.agent._read_json = mock.Mock(return_value={"run_id": self.agent.run_id, "uid": 2000, "pid": 9999})
        self.agent._owned_process_state = mock.Mock(return_value="owned")
        for raw in (b"u:r:shell:s0", b"u:r:shell:s0\x00", b"u:r:shell:s0\x00\r\n "):
            with self.subTest(raw=raw):
                self.agent._adb = mock.Mock(return_value=subprocess.CompletedProcess([], 0, raw, b""))
                self.agent._verify_process_identity()
        for raw, stderr in ((b"u:r:shell:s0\x00u:r:shell:s0", b""),
                            (b"u:r:shell:s0\nu:r:shell:s0", b""),
                            (b"u:r:shell:\x00s0", b""),
                            (b" u:r:shell:s0", b""),
                            (b"u:r:shell:s0\x00", b"permission denied")):
            with self.subTest(raw=raw, stderr=stderr), self.assertRaises(RuntimeError):
                self.agent._adb = mock.Mock(return_value=subprocess.CompletedProcess([], 0, raw, stderr))
                self.agent._verify_process_identity()

    def test_ready_identity_rechecks_ownership_after_selinux_read(self):
        self.agent._read_json = mock.Mock(return_value={"run_id": self.agent.run_id, "uid": 2000, "pid": 9999})
        self.agent._owned_process_state = mock.Mock(side_effect=["owned", "different_process"])
        self.agent._adb = mock.Mock(return_value=subprocess.CompletedProcess([], 0, b"u:r:shell:s0\x00", b""))
        with self.assertRaises(RuntimeError):
            self.agent._verify_process_identity()
        self.assertEqual(self.agent._owned_process_state.call_count, 2)

    def test_receipt_sequence_mismatch_rejected(self):
        self.agent.started = True
        self.agent._write_command = mock.Mock()
        self.agent._wait_json = mock.Mock(return_value={"run_id": self.agent.run_id, "sequence": 9, "done": True})
        with self.assertRaises(RuntimeError):
            self.agent.step(self.context["goal"])
        self.assertEqual(self.agent.step_receipts, [])

    def test_changed_goal_rejected_before_command(self):
        self.agent.started = True
        self.agent._write_command = mock.Mock()
        with self.assertRaises(RuntimeError):
            self.agent.step("Send a message")
        self.agent._write_command.assert_not_called()

    def test_step_budget_cannot_be_exceeded(self):
        self.agent.started = True
        self.agent.step_receipts = [{}] * 10
        self.agent._write_command = mock.Mock()
        with self.assertRaises(RuntimeError):
            self.agent.step(self.context["goal"])
        self.agent._write_command.assert_not_called()

    def test_early_termination_collects_and_stops_wait(self):
        final = {"run_id": self.agent.run_id, "status": "error", "model_calls": 1}
        self.agent._read_json = lambda name: final if name == "result.json" else None
        self.agent.collect = mock.Mock()
        with self.assertRaises(RuntimeError):
            self.agent._wait_json("step-0001.json")
        self.assertTrue(self.agent.closed)
        self.assertEqual(self.agent.final_receipt["model_calls"], 1)
        self.agent.collect.assert_called_once()

    def test_evaluation_is_explicit_and_scope_bound(self):
        self.agent.started = True
        self.agent.sequence = 4
        self.agent._write_command = mock.Mock()
        self.agent._wait_json = mock.Mock(return_value={"run_id": self.agent.run_id, "model_calls": 3})
        self.agent.collect = mock.Mock()
        self.agent.evaluated(1.0)
        command = self.agent._write_command.call_args.args[0]
        self.assertEqual(command["command"], "evaluate")
        self.assertEqual(command["sequence"], 4)
        self.assertEqual(command["task_name"], "ClockStopWatchRunning")
        self.assertEqual(command["evaluator"], "android_world.task.is_successful")

    def test_stop_after_pending_step_uses_next_sequence(self):
        self.agent.bootstrap_sent = True
        self.agent.started = True
        self.agent.step_commands_sent = 1
        self.agent._write_command = mock.Mock()
        self.agent._wait_json = mock.Mock(return_value={"run_id": self.agent.run_id, "state": "finished", "model_calls": 1})
        self.agent.collect = mock.Mock()
        self.agent.close()
        command = self.agent._write_command.call_args.args[0]
        self.assertEqual(command["sequence"], 2)
        self.assertEqual(command["command"], "stop")
        self.assertTrue(self.agent.ready_for_task_teardown)

    def test_unrelated_pid_never_signalled_or_declared_stopped(self):
        self.agent._read_json = mock.Mock(return_value={"run_id": self.agent.run_id, "uid": 2000, "pid": 9999})
        self.agent._owned_process_state = mock.Mock(return_value="different_process")
        self.agent._adb = mock.Mock()
        with self.assertRaises(RuntimeError):
            self.agent._terminate_owned_process()
        self.agent._adb.assert_not_called()
        self.assertFalse(self.agent.process_stopped)

    def test_exec_out_missing_proc_exit_zero_is_not_a_process_identity(self):
        self.agent._adb = mock.Mock(side_effect=[
            subprocess.CompletedProcess([], 0, b"", b""),
            subprocess.CompletedProcess([], 0, b"cat: /proc/9999/cmdline: No such file or directory\n", b""),
            subprocess.CompletedProcess([], 1, b"", b""),
        ])
        self.assertEqual(self.agent._owned_process_state(9999), "gone")

    def test_proc_probe_transport_error_never_means_gone(self):
        self.agent._adb = mock.Mock(return_value=subprocess.CompletedProcess([], 1, b"", b"error: device offline\n"))
        self.assertEqual(self.agent._owned_process_state(9999), "unknown")

    def test_key_contract_printable_ascii_and_limit(self):
        args = types.SimpleNamespace(key_stdin=True)
        for value in ("", "a" * 4097, "not ascii 密钥", "a\x00b", "a b"):
            with self.subTest(value=value[:10]), mock.patch.object(sys, "stdin", io.StringIO(value + "\n")):
                with self.assertRaises(ValueError):
                    runner.read_key(args)
        with mock.patch.object(sys, "stdin", io.StringIO("offline-key\n")):
            self.assertEqual(runner.read_key(args), "offline-key")

    def test_plan_seed_matches_official_algorithm(self):
        plan = runner.task_plan(30)
        self.assertEqual(plan["instance_seed"], 3429360255)
        self.assertEqual(plan["goal"], "Run the stopwatch.")
        self.assertEqual(plan["max_steps"], 10)
        self.assertIsNone(plan["score"])

    def _preflight_fixtures(self):
        root = Path(self.temp.name)
        (root / "build").mkdir(exist_ok=True)
        jar = b"offline-test-jar"
        apk = b"offline-test-apk"
        (root / "build" / "androidworld-agent.jar").write_bytes(jar)
        (root / "build" / "androidworld-planner.apk").write_bytes(apk)
        jar_hash, apk_hash = hashlib.sha256(jar).hexdigest(), hashlib.sha256(apk).hexdigest()
        (root / "build" / "build.json").write_text(json.dumps({"planner_package": "com.lab.androidworldplanner", "jar_sha256": jar_hash, "apk_sha256": apk_hash}))
        args = types.SimpleNamespace(profile=bridge_module.PROFILE, dedicated_emulator=True, serial="emulator-5554", console_port=5554, avd_name="AndroidWorldAvd")
        outputs = {
            ("shell", "getprop", "ro.kernel.qemu"): "1",
            ("shell", "getprop", "ro.build.version.sdk"): "33",
            ("shell", "getprop", "ro.product.model"): "Pixel 6",
            ("shell", "getprop", "ro.boot.qemu.avd_name"): "AndroidWorldAvd",
            ("emu", "avd", "name"): "AndroidWorldAvd\nOK",
            ("shell", "pm", "path", runner.PACKAGE): "package:/system/product/app/DeskClock/DeskClock.apk",
            ("shell", "pm", "path", "com.lab.androidworldplanner"): "package:/data/app/~~abc123/pkg-xyz==/base.apk",
            ("shell", "sha256sum", "/data/local/tmp/mobile-ondevice-agent/androidworld-agent.jar"): jar_hash + "  agent.jar",
            ("shell", "sha256sum", "/data/app/~~abc123/pkg-xyz==/base.apk"): apk_hash + "  base.apk",
        }
        return root, args, outputs

    def test_preflight_checks_hashes_with_readonly_device_calls(self):
        root, args, outputs = self._preflight_fixtures()
        commands = []
        def read_only(_args, command):
            commands.append(command)
            return outputs[tuple(command)]
        with mock.patch.object(runner, "HERE", root), mock.patch.object(runner, "adb", read_only), mock.patch.object(runner.subprocess, "check_output", side_effect=[runner.EXPECTED_COMMIT, ""]), mock.patch.object(runner.importlib.metadata, "version", return_value="1.2.3"):
            result = runner.preflight(args)
        self.assertTrue(result["clock_package_present"])
        self.assertEqual(len(result["ondevice_artifacts"]), 2)
        self.assertTrue(all(command[0] in ("shell", "emu") for command in commands))
        self.assertFalse(any("clear" in command or "install" in command for command in commands))

    def test_preflight_rejects_wrong_installed_binary(self):
        root, args, outputs = self._preflight_fixtures()
        outputs[("shell", "sha256sum", "/data/local/tmp/mobile-ondevice-agent/androidworld-agent.jar")] = "0" * 64 + "  bad.jar"
        with mock.patch.object(runner, "HERE", root), mock.patch.object(runner, "adb", lambda _args, command: outputs[tuple(command)]), mock.patch.object(runner.subprocess, "check_output", side_effect=[runner.EXPECTED_COMMIT, ""]), mock.patch.object(runner.importlib.metadata, "version", return_value="1.2.3"):
            with self.assertRaises(RuntimeError):
                runner.preflight(args)

    def test_preflight_does_not_report_unchecked_clock_as_missing(self):
        root, args, outputs = self._preflight_fixtures()
        args.task = "SystemBrightnessMax"
        outputs[("shell", "pm", "path", "com.android.settings")] = "package:/system/priv-app/Settings/Settings.apk"
        del outputs[("shell", "pm", "path", runner.PACKAGE)]
        with mock.patch.object(runner, "HERE", root), mock.patch.object(runner, "adb", lambda _args, command: outputs[tuple(command)]), mock.patch.object(runner.subprocess, "check_output", side_effect=[runner.EXPECTED_COMMIT, ""]), mock.patch.object(runner.importlib.metadata, "version", return_value="1.2.3"):
            result = runner.preflight(args)
        self.assertIsNone(result["clock_package_present"])
        self.assertEqual(result["target_packages_present"], {"com.android.settings": True})












if __name__ == "__main__":
    unittest.main()
