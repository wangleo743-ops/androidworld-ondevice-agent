"""Check the release's real configuration and dispatch boundaries without devices."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_entry", ROOT / "run.py")
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)


class ReleaseEntryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "config.local.json"

    def config(self, **updates):
        self.path.write_text(json.dumps(updates), encoding="utf-8")
        return entry.read_config(self.path)

    def test_config_rejects_secrets_and_unknown_fields(self):
        for key in ("api_key", "OPENAI_API_KEY", "password", "unrecognized"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.config(**{key: "not-a-real-secret"})

    def test_http_requires_explicit_opt_in(self):
        with self.assertRaises(ValueError):
            self.config(responses_endpoint="http://127.0.0.1:8000/responses")
        self.assertTrue(self.config(responses_endpoint="http://127.0.0.1:8000/responses", allow_http=True)["allow_http"])

    def test_endpoint_cannot_hide_credentials(self):
        for value in ("https://user:pass@example.com/responses", "https://example.com/responses?key=example",
                      "https://example.com/responses#token", "file:///responses", "https://example.com/chat/completions"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.config(responses_endpoint=value)

    def test_no_implicit_physical_device_target(self):
        with self.assertRaises(ValueError):
            self.config(serial="physical-phone")
        with self.assertRaises(ValueError):
            self.config(serial="emulator-5556", console_port=5554)
        self.assertEqual(self.config(serial="emulator-5556", console_port=5556)["serial"], "emulator-5556")

    def test_task_selection_is_finite_and_unique(self):
        self.assertEqual(entry.selected_tasks("CameraTakePhoto"), ["CameraTakePhoto"])
        for value in ("", "CameraTakePhoto,CameraTakePhoto", "UnknownTask"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                entry.selected_tasks(value)

    def test_plan_needs_no_sdk_no_key_no_model(self):
        with mock.patch.object(entry, "invoke", side_effect=AssertionError("plan must not run external commands")):
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(entry.main(["--config", str(self.path), "plan", "--tasks", "CameraTakePhoto"]), 0)
        data = json.loads(out.getvalue())
        self.assertEqual(data[0]["task_name"], "CameraTakePhoto")
        self.assertFalse(data[0]["model_called"])
        self.assertFalse(data[0]["formal_user_grants"])

    def test_init_refuses_to_overwrite(self):
        with contextlib.redirect_stdout(io.StringIO()):
            entry.main(["--config", str(self.path), "init"])
        original = self.path.read_bytes()
        with self.assertRaises(FileExistsError):
            entry.main(["--config", str(self.path), "init"])
        self.assertEqual(self.path.read_bytes(), original)

    def test_mutating_commands_require_dedicated_ack(self):
        for command in ("prepare", "deploy", "run"):
            with self.subTest(command=command), self.assertRaises(RuntimeError):
                entry.main(["--config", str(self.path), command])

    def test_run_passes_selected_tasks_as_distinct_arguments(self):
        self.config(model="vision-model", responses_endpoint="https://example.com/v1/responses")
        with mock.patch.object(entry, "require_linux_runtime"), mock.patch.object(entry, "verify_upstream"), \
                mock.patch.object(entry, "verify_device"), mock.patch.object(entry, "env_python", return_value=Path("/venv/python")), \
                mock.patch.object(entry, "adb_path", return_value=Path("/sdk/adb")), mock.patch.object(entry, "invoke") as call:
            entry.main(["--config", str(self.path), "run", "--dedicated-emulator", "--tasks", "ClockStopWatchRunning,CameraTakePhoto"])
        argv = call.call_args.args[0]
        offset = argv.index("--tasks")
        self.assertEqual(argv[offset+1:offset+3], ["ClockStopWatchRunning", "CameraTakePhoto"])
        self.assertIn("--responses-url", argv)
        self.assertNotIn("--key", argv)

    def test_report_uses_positional_summary(self):
        with mock.patch.object(entry, "invoke") as call:
            entry.main(["--config", str(self.path), "report", str(self.path)])
        argv = call.call_args.args[0]
        self.assertEqual(argv[2], self.path.resolve())
        self.assertNotIn("--summary", argv)

    def test_compile_version_is_distinct_from_runtime_api(self):
        with self.assertRaises(ValueError):
            self.config(compile_sdk=33)
        self.assertEqual(self.config(compile_sdk=35)["compile_sdk"], 35)


if __name__ == "__main__":
    unittest.main()
