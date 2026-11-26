"""Official AndroidWorld task lifecycle with our on-device Agent/Control.

Default mode is plan (read-only). Run mode is dedicated-emulator-only.
No M3A/T3A fallback. The host scores tasks and optionally relays model networking;
the decision loop, screenshots and Control execution remain on Android.
"""
from __future__ import annotations

import argparse
import dataclasses
from datetime import datetime, timezone
import getpass
import hashlib
import importlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
from typing import Any
from task_catalog import (DEFAULT_TASKS, EXPECTED_COMMIT, PROFILE, STOCK_PROFILE, PROFILES, TASKS,
                          get_spec, planned_instance, validate_scope, validate_profile,
                          profile_artifacts, profile_metadata, DEFAULT_PROFILE, DEFAULT_RESPONSES_ENDPOINT,
                          MODEL_EFFORTS, validate_model_connection)


HERE = Path(__file__).resolve().parent
TASK_NAME = "ClockStopWatchRunning"
PACKAGE = "com.google.android.deskclock"
os.environ.setdefault("PYGAME_HIDE_SUPPORT_PROMPT", "1")


def save_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def task_plan(seed: int, task_name: str = TASK_NAME, profile: str = DEFAULT_PROFILE) -> dict[str, Any]:
    validate_profile(profile)
    spec, params, goal = planned_instance(task_name, seed)
    return {
        "task_name": task_name, "goal": goal, "suite_seed": seed, "params": params,
        "instance_seed": params["seed"], "target_package": spec.target_package,
        "max_steps": spec.budget, "screen": "dedicated_emulator_display_0",
        **profile_metadata(profile),
        "authorization": "dedicated benchmark baseline; no per-task production user grant",
        "upstream_commit": EXPECTED_COMMIT,
        "score": None, "model_called": False,
        "requires": ["Pixel 6 API 33 Google APIs AVD", "emulator gRPC 8554", "AndroidWorld pinned Python dependencies", "our AndroidWorld Agent jar and isolated Planner APK", "provider API key via hidden input"],
        "preparation_changes": [f"official {spec.app_name} App snapshot setup", "official fixed October 2023 UTC device time", spec.preparation],
    }


def adb(args: argparse.Namespace, command: list[str], timeout: float = 20) -> str:
    proc = subprocess.run([args.adb_path, "-s", args.serial, *command], input=b"", capture_output=True, timeout=timeout)
    if proc.returncode:
        raise RuntimeError(f"Preflight ADB failed (exit {proc.returncode})")
    return proc.stdout.decode("utf-8", errors="replace").strip()


def preflight(args: argparse.Namespace, *, task_names: list[str] | tuple[str, ...] | None = None) -> dict[str, Any]:
    profile = validate_profile(getattr(args, "profile", DEFAULT_PROFILE))
    build_directory, agent_jar = profile_artifacts(profile)
    specs = [get_spec(name) for name in (task_names or (getattr(args, "task", TASK_NAME),))]
    if not args.dedicated_emulator:
        raise ValueError("Run requires --dedicated-emulator; never target a physical phone")
    match = re.fullmatch(r"emulator-([0-9]{4,5})", args.serial)
    if not match or int(match.group(1)) != args.console_port:
        raise ValueError("Serial must match emulator console port")
    if not args.avd_name:
        raise ValueError("Pass the exact dedicated --avd-name")
    commit = subprocess.check_output(["git", "-C", str(HERE / "upstream"), "rev-parse", "HEAD"], text=True).strip()
    if commit != EXPECTED_COMMIT:
        raise RuntimeError("Upstream version changed; review and repin before benchmarking")
    status = subprocess.check_output(["git", "-C", str(HERE / "upstream"), "status", "--porcelain", "--untracked-files=no"], text=True).strip()
    if status:
        raise RuntimeError("Official tracked task/evaluator sources must be unmodified")
    kernel_qemu = adb(args, ["shell", "getprop", "ro.kernel.qemu"])
    api_level = adb(args, ["shell", "getprop", "ro.build.version.sdk"])
    model = adb(args, ["shell", "getprop", "ro.product.model"])
    # Properties bind the AVD identity to the explicitly selected ADB device.
    avd = adb(args, ["shell", "getprop", "ro.boot.qemu.avd_name"])
    if not avd:
        avd = adb(args, ["shell", "getprop", "ro.kernel.qemu.avd_name"])
    if not avd:
        avd = adb(args, ["emu", "avd", "name"]).splitlines()[0]
    if kernel_qemu != "1" or api_level != "33" or avd != args.avd_name:
        raise RuntimeError("Device is not the explicitly selected dedicated API 33 AVD")
    if importlib.metadata.version("android_env") != "1.2.3":
        raise RuntimeError("AndroidWorld requires pinned android_env 1.2.3")
    packages = {}
    for spec in specs:
        if spec.target_package in packages:
            continue
        package = adb(args, ["shell", "pm", "path", spec.target_package])
        if not package.startswith("package:"):
            raise RuntimeError(f"Official target package is missing: {spec.target_package}")
        packages[spec.target_package] = True
    build = json.loads((HERE / build_directory / "build.json").read_text(encoding="utf-8"))
    if profile == STOCK_PROFILE and (not isinstance(build.get("supported_profiles"), list)
                                    or profile not in build["supported_profiles"]
                                    or build.get("agent_filename") != agent_jar):
        raise RuntimeError("Local build does not declare the requested stock MCP baseline")
    if build.get("planner_package") != "com.lab.androidworldplanner":
        raise RuntimeError("Unexpected local Planner build package")
    planner_path = adb(args, ["shell", "pm", "path", "com.lab.androidworldplanner"])
    paths = [x.removeprefix("package:") for x in planner_path.splitlines() if x.startswith("package:")]
    if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9/_=+.~\-]+\.apk", paths[0]):
        raise RuntimeError("Dedicated AndroidWorld Planner APK is missing or has unexpected path")
    remote_artifacts = {
        "jar": "/data/local/tmp/mobile-ondevice-agent/" + agent_jar,
        "apk": paths[0],
    }
    verified = {}
    for kind, remote in remote_artifacts.items():
        installed_sha = adb(args, ["shell", "sha256sum", remote]).split()[0]
        local_file = HERE / build_directory / (agent_jar if kind == "jar" else "androidworld-planner.apk")
        local_sha = hashlib.sha256(local_file.read_bytes()).hexdigest()
        if installed_sha != build[kind + "_sha256"] or local_sha != installed_sha:
            raise RuntimeError(f"Installed {kind} differs from the verified local build")
        verified[kind + "_sha256"] = installed_sha
    return {
        **profile_metadata(profile),
        "agent_jar": agent_jar,
        "serial": args.serial, "avd_name": avd, "api_level": api_level,
        "kernel_qemu": kernel_qemu, "device_model": model,
        "upstream_commit": commit, "tracked_upstream_clean": True,
        "python": sys.version, "android_env": importlib.metadata.version("android_env"),
        "clock_package_present": packages.get(PACKAGE),
        "target_packages_present": packages,
        "ondevice_artifacts": verified,
    }


def read_key(args: argparse.Namespace) -> str:
    if args.key_stdin:
        # A GUI parent may pipe one key line. No key argument/environment/file.
        key = sys.stdin.readline().strip()
    else:
        if not sys.stdin.isatty():
            raise RuntimeError("No interactive console; use the masked GUI launcher with --key-stdin")
        key = getpass.getpass("API Key (hidden; memory only): ").strip()
    validate_key(key)
    return key


def validate_key(key: str) -> None:
    if not isinstance(key, str) or not key or len(key) > 4096 or any(not 33 <= ord(c) <= 126 for c in key):
        raise ValueError("Invalid or empty API key input")


def official_source_hashes() -> dict[str, str]:
    sources = {
        "android_world.task_evals.single.clock": "android_world/task_evals/single/clock.py",
        "android_world.task_evals.single.system": "android_world/task_evals/single/system.py",
        "android_world.task_evals.single.camera": "android_world/task_evals/single/camera.py",
        "android_world.task_evals.task_eval": "android_world/task_evals/task_eval.py",
        "android_world.episode_runner": "android_world/episode_runner.py",
        "android_world.suite_utils": "android_world/suite_utils.py",
        "android_world.env.env_launcher": "android_world/env/env_launcher.py",
        "android_world.env.setup_device.apps": "android_world/env/setup_device/apps.py",
    }
    hashes = {}
    for name, relative in sources.items():
        module = importlib.import_module(name)
        installed = Path(module.__file__).read_bytes()
        original = (HERE / "upstream" / relative).read_bytes()
        if installed != original:
            raise RuntimeError(f"Installed official source differs from pinned checkout: {name}")
        hashes[relative] = hashlib.sha256(installed).hexdigest()
    return hashes


def run(args: argparse.Namespace, *, api_key: str | None = None) -> Path:
    profile = validate_profile(getattr(args, "profile", DEFAULT_PROFILE))
    connection = connection_settings(args)
    # Import inside run: the default read-only plan needs no AndroidWorld packages.
    from android_world import episode_runner, suite_utils
    from android_world.env import env_launcher
    from android_world.env.setup_device import apps, setup
    task_name = getattr(args, "task", TASK_NAME)
    spec = get_spec(task_name)
    task_class = getattr(importlib.import_module("android_world.task_evals.single." + spec.module), task_name)

    run_id = "aw-" + datetime.now(timezone.utc).strftime("%Y%m%dt%H%M%Sz").lower() + "-" + uuid.uuid4().hex[:8]
    output = Path(args.output_root).resolve() / run_id
    output.mkdir(parents=True, exist_ok=False)
    plan = task_plan(args.seed, task_name, profile)
    result = {**plan, "run_id": run_id, "status": "preflight", "timing_seconds": {}, "model_called": None, **connection}
    save_json(output / "plan.json", plan)
    wall_start = time.monotonic()
    env = None
    agent = None
    task = None
    initialized = False
    key = ""
    relay = None
    relay_token = ""
    def stage(name: str) -> None:
        result["stage"] = name
        save_json(output / "progress.json", result)
        print(json.dumps({"event": "task_stage", "run_id": run_id, "task": task_name, "stage": name}), flush=True)
    try:
        stage("preflight")
        pre = preflight(args)
        pre["official_source_sha256"] = official_source_hashes()
        save_json(output / "preflight.json", pre)
        result["timing_seconds"]["preflight"] = time.monotonic() - wall_start
        # Key acquired only after the environment target has passed preflight.
        if api_key is None:
            start = time.monotonic()
            try:
                key = read_key(args)
            finally:
                result["timing_seconds"]["key_input_wait"] = time.monotonic() - start
        else:
            validate_key(api_key)
            key = api_key
            result["timing_seconds"]["key_input_wait"] = 0.0
            result["key_source"] = "batch_process_memory"
        start = time.monotonic()
        stage("environment_setup")
        from absl import logging
        logging.set_verbosity(logging.WARNING)
        result["transport"] = {"location": "local_android_emulator_local_scorer",
                               "console_port": args.console_port, "grpc_port": args.grpc_port,
                               "official_task_sources_modified": False}
        # Keep an env reference before setup so failed setup is still closed.
        result["environment_phase"] = "connection"
        env = env_launcher._get_env(
            console_port=args.console_port, grpc_port=args.grpc_port, adb_path=args.adb_path,
        )
        result["timing_seconds"]["environment_connection"] = time.monotonic() - start
        phase_start = time.monotonic()
        result["environment_phase"] = "configuration"
        env_launcher.setup_env(env, emulator_setup=False)
        result["timing_seconds"]["environment_configuration"] = time.monotonic() - phase_start
        phase_start = time.monotonic()
        result["environment_phase"] = "app_setup"
        if getattr(args, "setup_app", getattr(args, "setup_clock", False)):
            setup.setup_apps(env, app_list=(getattr(apps, spec.setup_class),))
        result["timing_seconds"]["app_setup"] = time.monotonic() - phase_start
        result["timing_seconds"]["environment_setup"] = time.monotonic() - start
        result["environment_phase"] = "complete"
        suite = suite_utils.create_suite(
            {task_name: task_class}, n_task_combinations=1,
            seed=args.seed, tasks=[task_name],
        )
        task = suite[task_name][0]
        if task.params != plan["params"] or task.goal != plan["goal"]:
            raise RuntimeError("Official generated task differs from reviewed plan")
        result["params"] = task.params
        result["goal"] = task.goal
        max_steps = int(10 * task.complexity)
        if max_steps != plan["max_steps"]:
            raise RuntimeError("Official task budget changed")
        validate_scope(task_name, spec.target_package, task.goal, max_steps)
        stage("task_initialization")
        start = time.monotonic()
        task_wall_start = start
        task.initialize_task(env)
        initialized = True
        result["timing_seconds"]["task_initialization"] = time.monotonic() - start
        bridge_connection = dict(connection)
        if connection["model_transport"] == "host_relay":
            from model_relay import ModelRelay
            stage("model_relay_setup")
            start = time.monotonic()
            relay = ModelRelay(endpoint=connection["responses_endpoint"], model=connection["model"],
                               api_key=key, allow_http=connection["allow_http"],
                               lifetime_seconds=args.timeout_seconds)
            relay.start()
            relay_token = relay.token
            key = relay_token
            bridge_connection.update(responses_endpoint=relay.device_endpoint, allow_http=True)
            result["timing_seconds"]["model_relay_setup"] = time.monotonic() - start
        context = {
            "profile": profile,
            "run_id": run_id, "adb_path": args.adb_path, "serial": args.serial,
            "output_dir": str(output), "task_name": task_name, "goal": task.goal,
            "target_package": spec.target_package,
            "seed": task.params["seed"], "max_steps": max_steps,
            **bridge_connection,
            "timeout_seconds": args.timeout_seconds,
        }
        module_name, factory_name = args.agent_plugin.split(":", 1)
        factory = getattr(importlib.import_module(module_name), factory_name)
        agent = factory(env, context=context, api_key=key)
        key = ""
        stage("agent_episode")
        start = time.monotonic()
        episode = episode_runner.run_episode(
            task.goal, agent, max_n_steps=max_steps,
            start_on_home_screen=task.start_on_home_screen,
        )
        result["timing_seconds"]["agent_episode"] = time.monotonic() - start
        result["agent_done"] = episode.done
        result["step_count"] = len(agent.step_receipts)
        # UI must still exist here; our Agent waits for this scoring handshake.
        stage("official_evaluation")
        start = time.monotonic()
        raw_score = float(task.is_successful(env))
        if not 0.0 <= raw_score <= 1.0:
            raise RuntimeError("Official evaluator returned an invalid score")
        result["timing_seconds"]["official_evaluation"] = time.monotonic() - start
        result["timing_seconds"]["task_wall_through_official_score"] = time.monotonic() - task_wall_start
        result["official_raw_score"] = raw_score
        result["score"] = raw_score if episode.done else 0.0
        result["passed"] = result["score"] > 0.5
        result["status"] = "passed" if result["passed"] else "failed"
        # Record official observation while still in the scored state.
        start = time.monotonic()
        from PIL import Image
        observation = env.get_state()
        Image.fromarray(observation.pixels).save(output / "official-final-screen.png")
        save_json(output / "official-final-ui.json", [dataclasses.asdict(x) for x in observation.ui_elements])
        save_json(output / "official-score.json", {
            "run_id": run_id, "task": task_name, "params": task.params,
            "official_raw_score": raw_score, "agent_done": episode.done,
            "agent_score": result["score"], "upstream_commit": EXPECTED_COMMIT,
            "scored_before_agent_cleanup": True,
        })
        result["timing_seconds"]["official_evidence"] = time.monotonic() - start
        start = time.monotonic()
        agent.evaluated(raw_score)
        result["timing_seconds"]["agent_cleanup_and_evidence_pull"] = time.monotonic() - start
    except Exception as error:
        # Avoid repr/tracebacks of API-related exceptions or subprocess payloads.
        result["status"] = "infrastructure_error"
        result["failed_stage"] = result.get("stage")
        if result.get("stage") == "environment_setup":
            result["failed_phase"] = result.get("environment_phase")
        result["error_type"] = type(error).__name__
        message = str(error)
        if key:
            message = message.replace(key, "[redacted]")
        if api_key:
            message = message.replace(api_key, "[redacted]")
        if relay_token:
            message = message.replace(relay_token, "[redacted]")
        message = re.sub(r"sk-[A-Za-z0-9_-]+", "[redacted]", message)
        result["error"] = message[:1000]
    finally:
        key = ""
        start = time.monotonic()
        cleanup_errors = []
        if agent:
            try:
                agent.close()
            except Exception as error:
                cleanup_errors.append("agent:" + type(error).__name__)
            receipts = getattr(agent, "step_receipts", [])
            final_receipt = getattr(agent, "final_receipt", None) or {}
            counts = [x.get("model_calls") for x in receipts]
            counts += [final_receipt.get("model_calls")]
            known_counts = [x for x in counts if isinstance(x, int) and not isinstance(x, bool)]
            if any(x > 0 for x in known_counts):
                result["model_called"] = True
            elif getattr(agent, "step_commands_sent", 0) == 0:
                result["model_called"] = False
            elif isinstance(final_receipt.get("model_calls"), int):
                result["model_called"] = final_receipt["model_calls"] > 0
            else:
                result["model_called"] = None
            result["step_count"] = len(receipts)
            result["advance_commands_sent"] = getattr(agent, "step_commands_sent", 0)
        else:
            result["model_called"] = False
        if relay is not None:
            try:
                relay.close()
                result["model_relay"] = relay.statistics()
            except Exception as error:
                cleanup_errors.append("model_relay:" + type(error).__name__)
        relay_token = ""
        safe_to_teardown = agent is None or agent.ready_for_task_teardown
        result["safe_to_teardown_task"] = safe_to_teardown
        if initialized and task is not None and env is not None and safe_to_teardown:
            try:
                task.tear_down(env)
            except Exception as error:
                cleanup_errors.append("task:" + type(error).__name__)
        elif initialized and not safe_to_teardown:
            cleanup_errors.append("task:preserved_because_agent_stop_unknown")
        if env is not None:
            try:
                env.close()
            except Exception as error:
                cleanup_errors.append("environment:" + type(error).__name__)
        result["timing_seconds"]["final_cleanup"] = time.monotonic() - start
        result["timing_seconds"]["host_run_total_including_key_wait"] = time.monotonic() - wall_start
        result["timing_seconds"]["host_run_excluding_key_wait"] = result["timing_seconds"]["host_run_total_including_key_wait"] - result["timing_seconds"].get("key_input_wait", 0)
        result["cleanup_errors"] = cleanup_errors
        result["execution_complete"] = safe_to_teardown and not cleanup_errors
        result["stage"] = "finished"
        save_json(output / "result.json", result)
    print(json.dumps({"run_id": run_id, "status": result["status"], "score": result.get("score"), "output": str(output)}, ensure_ascii=False))
    return output


def connection_settings(args: argparse.Namespace) -> dict:
    settings = validate_model_connection(getattr(args, "model", None), getattr(args, "effort", "default"),
        getattr(args, "responses_endpoint", DEFAULT_RESPONSES_ENDPOINT), getattr(args, "allow_http", False))
    transport = getattr(args, "model_transport", "host_relay")
    if transport not in ("host_relay", "direct"):
        raise ValueError("model_transport must be host_relay or direct")
    return {**settings, "model_transport": transport}


def add_connection_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--model", help="Exact image-capable Responses model name; required for --run")
    parser.add_argument("--effort", choices=MODEL_EFFORTS, default="default",
                        help="default omits the API reasoning parameter")
    parser.add_argument("--responses-url", dest="responses_endpoint", default=DEFAULT_RESPONSES_ENDPOINT,
                        help="Complete endpoint URL, including /responses")
    parser.add_argument("--allow-http", action="store_true", help="Explicitly allow a trusted HTTP endpoint")
    parser.add_argument("--model-transport", choices=("host_relay", "direct"), default="host_relay",
                        help="host_relay validates TLS on the host; AndroidWorld fixes the device date to 2023")
    parser.add_argument("--key-stdin", action="store_true", help="Read one API key line from stdin instead of hidden prompt")


def validate_connection_args(args: argparse.Namespace, parser: argparse.ArgumentParser) -> None:
    try:
        # Plans and read-only preflight need no model name or key.
        if getattr(args, "run", False):
            connection_settings(args)
        elif getattr(args, "model", None):
            connection_settings(args)
    except ValueError as error:
        parser.error(str(error))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--run", action="store_true", help="Default is read-only plan")
    mode.add_argument("--preflight-only", action="store_true", help="Read-only emulator/build/dependency checks; no key or Android mutations")
    parser.add_argument("--dedicated-emulator", action="store_true")
    parser.add_argument("--adb-path", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--console-port", type=int, default=5554)
    parser.add_argument("--grpc-port", type=int, default=8554)
    parser.add_argument("--avd-name", default="AndroidWorldAvd")
    parser.add_argument("--task", choices=DEFAULT_TASKS, default=TASK_NAME)
    parser.add_argument("--profile", choices=PROFILES, default=DEFAULT_PROFILE,
                        help="Independent stock Control is the release default")
    parser.add_argument("--setup-app", "--setup-clock", dest="setup_app", action="store_true")
    parser.add_argument("--seed", type=int, default=30)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    add_connection_arguments(parser)
    parser.add_argument("--agent-plugin", default="phone_bridge:create_agent")
    parser.add_argument("--output-root", default=str(HERE / "runs"))
    args = parser.parse_args()
    if args.seed < 0 or not 30 <= args.timeout_seconds <= 1800:
        parser.error("seed must be nonnegative; timeout 30..1800")
    validate_connection_args(args, parser)
    if (args.run or args.preflight_only) and not args.dedicated_emulator:
        parser.error("Execution and preflight require --dedicated-emulator")
    if args.preflight_only:
        checks = preflight(args)
        checks["official_source_sha256"] = official_source_hashes()
        print(json.dumps(checks, ensure_ascii=False, indent=2))
        return 0
    if not args.run:
        print(json.dumps(task_plan(args.seed, args.task, args.profile), ensure_ascii=False, indent=2))
        return 0
    output = run(args)
    status = json.loads((output / "result.json").read_text(encoding="utf-8"))["status"]
    return 0 if status in ("passed", "failed") else 2


if __name__ == "__main__":
    raise SystemExit(main())
