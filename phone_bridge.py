"""One AndroidWorld step -> one step in our on-Android Agent/Control.

This module never calls env.execute_action or substitutes AndroidWorld's M3A.
The model key is sent only to app_process stdin and is never written to disk.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import shlex
import subprocess
import time
from typing import Any

from android_world.agents import base_agent
from task_catalog import (PROFILE, STOCK_PROFILE, DEFAULT_PROFILE, DEFAULT_RESPONSES_ENDPOINT,
                          validate_scope, validate_profile, profile_artifacts, validate_model_connection)


REMOTE_ROOT = "/data/local/tmp/mobile-ondevice-agent"


class PhoneBridge(base_agent.EnvironmentInteractingAgent):
    def __init__(self, env: Any, *, context: dict[str, Any], api_key: str):
        super().__init__(env, name="our_ondevice_agent_control")
        validate_scope(context["task_name"], context["target_package"], context["goal"], context["max_steps"])
        self.context = dict(context)
        self.context.update(validate_model_connection(context["model"], context["effort"],
            context.get("responses_endpoint", DEFAULT_RESPONSES_ENDPOINT), context.get("allow_http", False)))
        self.profile = validate_profile(context.get("profile", DEFAULT_PROFILE))
        _, self.agent_jar = profile_artifacts(self.profile)
        self.adb_path = context["adb_path"]
        self.serial = context["serial"]
        self.run_id = context["run_id"]
        if not re.fullmatch(r"emulator-[0-9]{4,5}", self.serial):
            raise ValueError("Only a dedicated emulator serial is allowed")
        if not re.fullmatch(r"aw-[a-z0-9_-]{1,70}", self.run_id):
            raise ValueError("Invalid run ID")
        self.remote = f"{REMOTE_ROOT}/androidworld-runs/{self.run_id}"
        self.output_dir = Path(context["output_dir"])
        self.sequence = 1
        self.started = False
        self.bootstrap_sent = False
        self.process_stopped = False
        self.closed = False
        self.step_receipts: list[dict[str, Any]] = []
        self.step_commands_sent = 0
        self.final_receipt: dict[str, Any] | None = None
        self.api_key = api_key
        self.deadline = time.monotonic() + context["timeout_seconds"]

    def _adb(self, args: list[str], *, data: bytes | None = None,
             timeout: float = 30, check: bool = True) -> subprocess.CompletedProcess:
        result = subprocess.run(
            [self.adb_path, "-s", self.serial, *args],
            input=b"" if data is None else data, capture_output=True, timeout=timeout,
        )
        if check and result.returncode:
            # Do not echo subprocess output: it could contain sensitive input.
            raise RuntimeError(f"ADB command failed (exit {result.returncode})")
        return result

    def _read_json(self, name: str) -> dict[str, Any] | None:
        if not re.fullmatch(r"[a-z0-9-]+\.json", name):
            raise ValueError("Invalid receipt file")
        result = self._adb(["exec-out", "cat", f"{self.remote}/{name}"], check=False)
        if result.returncode:
            return None
        try:
            value = json.loads(result.stdout)
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(value, dict):
            raise RuntimeError("Device receipt is not a JSON object")
        return value

    def _wait_json(self, name: str, *, timeout: float = 180) -> dict[str, Any]:
        limit = min(self.deadline, time.monotonic() + timeout)
        while time.monotonic() < limit:
            value = self._read_json(name)
            if value is not None:
                if name.startswith("step-") and value.get("result_file") == "result.json":
                    final = self._read_json("result.json")
                    if final is not None:
                        self._save_early_final(final)
                    raise RuntimeError("Agent terminated instead of returning a normal step")
                return value
            final = self._read_json("result.json") if name != "result.json" else None
            if final is not None:
                self._save_early_final(final)
                raise RuntimeError("On-device Agent ended before the requested step; inspect phone-result.json")
            state = self._read_json("state.json")
            if state and state.get("state") in ("error", "failed"):
                raise RuntimeError("On-device Agent reported failure; inspect sanitized evidence")
            time.sleep(0.2)
        raise TimeoutError(f"Timed out waiting for Agent {name}")

    def _save_early_final(self, final: dict[str, Any]) -> None:
        self._check_receipt_profile(final)
        if final.get("run_id") != self.run_id:
            raise RuntimeError("Mismatched early final receipt")
        self.final_receipt = final
        self._save("phone-result.json", final)
        self.closed = True
        try:
            self.collect()
        except Exception as error:
            self._save("evidence-collection-error.json", {"error_type": type(error).__name__})

    def _check_receipt_profile(self, receipt: dict[str, Any]) -> None:
        # Legacy fixtures predate explicit profile metadata. New stock runs must
        # never silently accept receipts from the previous in-process backend.
        if (("profile" in receipt or self.profile == STOCK_PROFILE)
                and receipt.get("profile") != self.profile):
            raise RuntimeError("On-device receipt execution profile differs from this run")

    def _write_command(self, command: dict[str, Any]) -> None:
        name = f"command-{self.sequence:04d}.json"
        target = f"{self.remote}/{name}"
        # Only allowlisted local command fields; never send API keys here.
        payload = json.dumps(command, ensure_ascii=False).encode("utf-8")
        script = f"cat > {shlex.quote(target + '.tmp')} && mv {shlex.quote(target + '.tmp')} {shlex.quote(target)}"
        self._adb(["shell", "sh", "-c", shlex.quote(script)], data=payload)

    def _start(self) -> None:
        if self.started:
            return
        payload = {
            "profile": self.profile, "run_id": self.run_id,
            "task_name": self.context["task_name"],
            "target_package": self.context["target_package"],
            "goal": self.context["goal"], "seed": self.context["seed"],
            "max_steps": self.context["max_steps"],
            "model": self.context["model"], "effort": self.context["effort"],
            "responses_endpoint": self.context["responses_endpoint"], "allow_http": self.context["allow_http"],
            "timeout_seconds": self.context["timeout_seconds"],
            "api_key": self.api_key,
        }
        try:
            uid = self._adb(["shell", "id", "-u"]).stdout.decode().strip()
            if uid not in ("0", "2000"):
                raise RuntimeError("Unexpected ADB identity")
            launch_command = (
                f"env CLASSPATH={REMOTE_ROOT}/{self.agent_jar} "
                "app_process /system/bin com.lab.mobileagent.benchmark.AndroidWorldMain launch"
            )
            # Official emulator setup uses adb root. Agent itself must stay shell.
            if uid == "0":
                # SELinux permits entering shell:s0 through shell_exec. Running
                # env directly after runcon is rejected on this API 33 image.
                launch_command = (
                    "su 2000 runcon u:r:shell:s0 /system/bin/sh -c "
                    + shlex.quote(launch_command)
                )
            self.bootstrap_sent = True
            result = self._adb(
                ["shell", "sh", "-c", shlex.quote(launch_command)],
                data=(json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"),
                timeout=45,
            )
            # Parse only the exact launch receipt, and persist an allowlist.
            launches = []
            for line in result.stdout.decode("utf-8", errors="replace").splitlines():
                try:
                    item = json.loads(line)
                except ValueError:
                    continue
                if isinstance(item, dict) and item.get("launched") is True:
                    launches.append(item)
            if len(launches) != 1 or launches[0].get("run_id") != self.run_id:
                raise RuntimeError("Missing or mismatched on-device launch receipt")
            launch = {k: launches[0].get(k) for k in ("launched", "run_id", "output", "profile")}
            if launch["output"] != self.remote or launch["profile"] != self.profile:
                raise RuntimeError("On-device launch scope mismatch")
            self._save("launch.json", launch)
            self.started = True
        finally:
            self.api_key = ""
            payload["api_key"] = ""
        ready_deadline = min(self.deadline, time.monotonic() + 60)
        while time.monotonic() < ready_deadline:
            final = self._read_json("result.json")
            if final is not None:
                self._save_early_final(final)
                raise RuntimeError("Agent failed before ready; inspect phone-result.json")
            state = self._read_json("state.json")
            if state is not None:
                self._check_receipt_profile(state)
                if state.get("run_id") != self.run_id:
                    raise RuntimeError("Ready receipt scope mismatch")
                if state.get("state") == "ready" and state.get("expected_sequence") == 1:
                    self._verify_process_identity()
                    return
                if state.get("state") in ("error", "failed", "closed"):
                    raise RuntimeError("Agent failed before ready")
            time.sleep(0.2)
        raise TimeoutError("Agent did not reach ready state")

    def _verify_process_identity(self) -> None:
        info = self._read_json("process.json")
        if (not info or info.get("run_id") != self.run_id or info.get("uid") != 2000
                or type(info.get("pid")) is not int or info["pid"] <= 1):
            raise RuntimeError("Ready Agent has no verified shell process identity")
        if self.profile == STOCK_PROFILE:
            self._check_receipt_profile(info.get("config", {}))
        pid = info["pid"]
        if self._owned_process_state(pid) != "owned":
            raise RuntimeError("Ready Agent UID or exact task command line differs")
        response = self._adb(["exec-out", "cat", f"/proc/{pid}/attr/current"])
        # procfs may terminate this value with NUL. Only allow terminators after
        # the exact expected bytes; embedded NULs or extra contexts still fail.
        raw_context = response.stdout
        expected_context = b"u:r:shell:s0"
        if (response.stderr or len(raw_context) > 64
                or raw_context.rstrip(b"\x00\r\n ") != expected_context):
            raise RuntimeError("Ready Agent is outside the Android shell SELinux domain")
        if self._owned_process_state(pid) != "owned":
            raise RuntimeError("Ready Agent identity changed while checking SELinux")
        context = expected_context.decode("ascii")
        self._save("agent-identity.json", {"run_id": self.run_id, "pid": pid, "uid": 2000,
                                         "selinux_context": context, "verified_before_advance": True})

    def _save(self, name: str, value: Any) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        (self.output_dir / name).write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def reset(self, go_home: bool = False) -> None:
        if self.started:
            raise RuntimeError("A bridge is single-use; create one per episode")
        # Official environment reset is benchmark preparation, not Agent action.
        self.env.reset(go_home=go_home)
        self._start()

    def step(self, goal: str) -> base_agent.AgentInteractionResult:
        if goal != self.context["goal"] or self.closed:
            raise RuntimeError("Goal changed or episode already closed")
        if not self.started:
            raise RuntimeError("reset() must start the on-device process first")
        if len(self.step_receipts) >= self.context["max_steps"]:
            raise RuntimeError("Official step budget exhausted")
        started = time.monotonic()
        self._write_command({"command": "advance", "run_id": self.run_id, "sequence": self.sequence})
        self.step_commands_sent += 1
        receipt = self._wait_json(f"step-{self.sequence:04d}.json")
        self._check_receipt_profile(receipt)
        if (receipt.get("run_id") != self.run_id or type(receipt.get("sequence")) is not int
                or receipt.get("sequence") != self.sequence):
            raise RuntimeError("Mismatched step receipt")
        if not isinstance(receipt.get("done"), bool):
            raise RuntimeError("Step receipt has no boolean done")
        self._save(f"step-{self.sequence:04d}.json", receipt)
        self.step_receipts.append(receipt)
        self.sequence += 1
        data = dict(receipt)
        data["bridge_step_wall_seconds"] = time.monotonic() - started
        return base_agent.AgentInteractionResult(done=receipt["done"], data=data)

    def evaluated(self, score: float) -> None:
        if self.closed or not self.started:
            return
        self._write_command({
            "command": "evaluate", "run_id": self.run_id, "sequence": self.sequence,
            "task_name": self.context["task_name"], "seed": self.context["seed"],
            "score": score, "evaluator": "android_world.task.is_successful",
        })
        result = self._wait_json("result.json", timeout=30)
        self._check_receipt_profile(result)
        if result.get("run_id") != self.run_id:
            raise RuntimeError("Mismatched final receipt")
        self.final_receipt = result
        self._save("phone-result.json", result)
        self.closed = True
        self.collect()

    def close(self) -> None:
        self.api_key = ""
        if not self.bootstrap_sent or self.closed:
            return
        try:
            if self.started:
                # Never overwrite an advance currently in flight. Queue stop at
                # the next sequence, which the device reads after that step.
                if self.step_commands_sent > len(self.step_receipts):
                    self.sequence += 1
                self.deadline = max(self.deadline, time.monotonic() + 20)
                self._write_command({"command": "stop", "run_id": self.run_id, "sequence": self.sequence})
                result = self._wait_json("result.json", timeout=15)
                self._check_receipt_profile(result)
                if result.get("run_id") != self.run_id:
                    raise RuntimeError("Mismatched stop receipt")
                self.final_receipt = result
                self._save("phone-result.json", result)
                self.closed = True
            else:
                self._terminate_owned_process()
        except Exception:
            if not self.closed:
                self._terminate_owned_process()
            raise
        finally:
            try:
                self.collect()
            except Exception as error:
                self._save("evidence-collection-error.json", {"error_type": type(error).__name__})

    @property
    def ready_for_task_teardown(self) -> bool:
        if self.profile == STOCK_PROFILE:
            if not self.bootstrap_sent:
                return True
            # Killing the Agent alone is insufficient: its Control child may
            # still own an in-flight input. Preserve the task on uncertainty.
            receipt = self.final_receipt or {}
            control = receipt.get("control", {})
            if not isinstance(control, dict):
                # A Control constructor failure legitimately produces JSON null.
                # Missing cleanup evidence must preserve the task, not break
                # the host's finally block before its failure result is saved.
                return False
            return (receipt.get("state") == "finished"
                    and receipt.get("profile") == self.profile
                    and control.get("transport") == "stdio_jsonrpc"
                    and control.get("closed") is True
                    and control.get("child_exit_confirmed") is True
                    and control.get("cleanup_confirmed") is True)
        return (not self.bootstrap_sent or self.process_stopped or
                bool(self.final_receipt and self.final_receipt.get("state") == "finished"))

    def _owned_process_state(self, pid: int) -> str:
        def presence() -> str:
            probe = self._adb(["shell", "test", "-d", f"/proc/{pid}"], check=False)
            if probe.stdout.strip() or probe.stderr.strip():
                return "unknown"
            return "present" if probe.returncode == 0 else "gone" if probe.returncode == 1 else "unknown"

        def after_bad_read(fallback: str) -> str:
            state = presence()
            return "gone" if state == "gone" else fallback if state == "present" else "unknown"

        before = presence()
        if before != "present":
            return before
        command = self._adb(["exec-out", "cat", f"/proc/{pid}/cmdline"], check=False)
        # Some ADB exec-out implementations return exit 0 and a `cat:` error
        # on stdout for a missing file. A true cmdline is NUL-delimited.
        if command.returncode or b"\x00" not in command.stdout:
            return after_bad_read("unknown")
        tokens = command.stdout.decode("utf-8", errors="strict").split("\x00")
        tokens = [token for token in tokens if token]
        suffix = ["com.lab.mobileagent.benchmark.AndroidWorldMain", "run", self.run_id]
        if len(tokens) < 3 or tokens[-3:] != suffix:
            return after_bad_read("different_process")
        status = self._adb(["exec-out", "cat", f"/proc/{pid}/status"], check=False)
        match = re.search(rb"^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)", status.stdout, re.M)
        if status.returncode or not match or any(x != b"2000" for x in match.groups()):
            return after_bad_read("unknown")
        return "owned"

    def _terminate_owned_process(self) -> None:
        info = self._read_json("process.json")
        if (not info or info.get("run_id") != self.run_id or info.get("uid") != 2000
                or type(info.get("pid")) is not int or info["pid"] <= 1):
            self._save("forced-cleanup.json", {"state": "unknown", "reason": "No verified process identity; no process killed"})
            raise RuntimeError("Agent state unknown; dedicated emulator is preserved for inspection")
        pid = info["pid"]
        attempts = []
        for signal in ("TERM", "KILL"):
            state = self._owned_process_state(pid)
            attempts.append({"before_signal": signal, "state": state})
            if state == "gone":
                self.process_stopped = True
                break
            if state != "owned":
                break
            # Fresh exact argv, run ID and UID check immediately before signal.
            self._adb(["shell", "kill", "-" + signal, str(pid)], check=False)
            for _ in range(10):
                time.sleep(0.2)
                if self._owned_process_state(pid) == "gone":
                    self.process_stopped = True
                    break
            if self.process_stopped:
                break
        self._save("forced-cleanup.json", {"run_id": self.run_id, "pid": pid, "attempts": attempts, "agent_process_stopped": self.process_stopped})
        if not self.process_stopped:
            raise RuntimeError("Could not confirm Agent stop; emulator state preserved, no task reset")

    def collect(self) -> None:
        # The per-run directory contains evidence only; the key was stdin-only.
        destination = self.output_dir / "phone"
        destination.mkdir(exist_ok=True)
        self._adb(["pull", self.remote + "/.", str(destination)], timeout=45)


def create_agent(env: Any, *, context: dict[str, Any], api_key: str) -> PhoneBridge:
    return PhoneBridge(env, context=context, api_key=api_key)
