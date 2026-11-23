"""Portable release contract checks; no device, upstream install, or model API."""
import argparse
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import batch_task_runner as batch
import official_task_runner as single
from task_catalog import DEFAULT_RESPONSES_ENDPOINT, STOCK_PROFILE, validate_model_connection
from test_bridge_contract import load_bridge


class PortableConnectionTests(unittest.TestCase):
    def test_model_relay_defaults_and_direct_override(self):
        args = argparse.Namespace(model="fixture-model")
        self.assertEqual(single.connection_settings(args)["model_transport"], "host_relay")
        args.model_transport = "direct"
        self.assertEqual(single.connection_settings(args)["model_transport"], "direct")
        args.model_transport = "other"
        with self.assertRaises(ValueError):
            single.connection_settings(args)

    def test_exact_provider_model_and_full_url_are_preserved(self):
        value = validate_model_connection("provider:model-2026", "default",
                                          "https://gateway.example/custom/responses")
        self.assertEqual(value["model"], "provider:model-2026")
        self.assertEqual(value["responses_endpoint"], "https://gateway.example/custom/responses")
        self.assertEqual(value["effort"], "default")
        self.assertFalse(value["allow_http"])

    def test_http_requires_explicit_transport_permission(self):
        url = "http://10.0.2.2:8080/responses"
        with self.assertRaises(ValueError):
            validate_model_connection("test-model", "default", url)
        self.assertEqual(validate_model_connection("test-model", "default", url, True)["responses_endpoint"], url)

    def test_credentials_queries_fragments_and_invalid_destinations_rejected(self):
        urls = ("https://user:secret@example.org/responses", "https://example.org/responses?key=secret",
                "https://example.org/responses#secret", "https://example.org/responses?",
                "https://example.org/responses#", "ftp://example.org/responses", "file:///responses",
                "https:///responses", "https://example.org:99999/responses",
                "https://example.org/v1", "https://example.org/re\nsponses", "")
        for url in urls:
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_model_connection("test-model", "default", url, True)

    def test_invalid_model_and_effort_are_rejected_before_launch(self):
        for model, effort in ((None, "default"), ("", "default"), ("model\nother", "default"),
                              ("test-model", "unsupported")):
            with self.subTest(model=model, effort=effort), self.assertRaises(ValueError):
                validate_model_connection(model, effort, DEFAULT_RESPONSES_ENDPOINT)

    def test_all_documented_efforts_are_accepted(self):
        for effort in ("default", "none", "minimal", "low", "medium", "high", "xhigh"):
            self.assertEqual(validate_model_connection("test-model", effort,
                             DEFAULT_RESPONSES_ENDPOINT)["effort"], effort)


class PortableCliTests(unittest.TestCase):
    def test_plan_needs_neither_sdk_nor_model_config_and_defaults_to_stock(self):
        for script in ("official_task_runner.py", "batch_task_runner.py"):
            result = subprocess.run([sys.executable, str(single.HERE / script)],
                                    input=b"", capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            value = json.loads(result.stdout)
            rows = value if isinstance(value, list) else [value]
            self.assertTrue(all(row["profile"] == STOCK_PROFILE for row in rows))
            self.assertTrue(all(row["model_called"] is False for row in rows))

    def test_local_batch_preflight_uses_cli_adb_and_grpc_without_old_transport(self):
        argv = ["batch_task_runner.py", "--preflight-only", "--dedicated-emulator",
                "--adb-path", "/sdk/platform-tools/adb", "--grpc-port", "8557",
                "--serial", "emulator-5556", "--console-port", "5556",
                "--avd-name", "MyDedicatedAvd"]
        with mock.patch.object(sys, "argv", argv), \
             mock.patch.object(single, "preflight", return_value={}) as pre, \
             mock.patch.object(single, "official_source_hashes", return_value={}), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(batch.main(), 0)
        args = pre.call_args.args[0]
        self.assertEqual(args.adb_path, "/sdk/platform-tools/adb")
        self.assertEqual(args.grpc_port, 8557)
        self.assertEqual(args.profile, STOCK_PROFILE)
        self.assertIsNone(args.model)

    def test_run_requires_model_and_explicit_dedicated_device(self):
        for module in (single, batch):
            for extra in (["--dedicated-emulator"], ["--model", "test-model"]):
                with self.subTest(module=module.__name__, args=extra), \
                     mock.patch.object(sys, "argv", ["runner", "--run", *extra]), \
                     mock.patch.object(module, "run" if module is single else "run_batch") as run, \
                     contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    module.main()
                self.assertEqual(error.exception.code, 2)
                run.assert_not_called()

    def test_bootstrap_endpoint_and_key_are_transmitted_only_in_stdin(self):
        bridge = load_bridge()
        with tempfile.TemporaryDirectory() as directory:
            context = dict(profile=STOCK_PROFILE, adb_path="/sdk/adb", serial="emulator-5554",
                run_id="aw-portable-test", output_dir=directory, task_name="ClockStopWatchRunning",
                target_package="com.google.android.deskclock", goal="Run the stopwatch.",
                seed=1, max_steps=10, model="test-model", effort="default", timeout_seconds=30,
                responses_endpoint="http://10.0.2.2:8080/custom/responses", allow_http=True)
            agent = bridge.PhoneBridge(mock.Mock(), context=context, api_key="fake-memory-key")
            launches = []
            def adb(args, **kwargs):
                if args == ["shell", "id", "-u"]:
                    return subprocess.CompletedProcess(args, 0, b"2000\n", b"")
                launches.append((args, json.loads(kwargs["data"])))
                receipt = dict(launched=True, run_id=agent.run_id, output=agent.remote, profile=STOCK_PROFILE)
                return subprocess.CompletedProcess(args, 0, json.dumps(receipt).encode(), b"")
            agent._adb = adb
            agent._read_json = lambda name: dict(run_id=agent.run_id, profile=STOCK_PROFILE,
                state="ready", expected_sequence=1) if name == "state.json" else None
            agent._verify_process_identity = mock.Mock()
            agent._start()
            args, payload = launches[0]
            self.assertEqual(payload["responses_endpoint"], context["responses_endpoint"])
            self.assertTrue(payload["allow_http"])
            self.assertEqual(payload["api_key"], "fake-memory-key")
            self.assertNotIn("fake-memory-key", " ".join(args))
            self.assertEqual(agent.api_key, "")
            for path in Path(directory).glob("*.json"):
                self.assertNotIn("fake-memory-key", path.read_text())


if __name__ == "__main__":
    unittest.main()
