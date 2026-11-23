#!/usr/bin/env python3
"""Portable entry point for the on-device AndroidWorld baseline.

Only explicit prepare/deploy/run commands change a dedicated emulator.
No model key is accepted in config, argv, or generated files.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
HOST = ROOT / "androidworld-integration"
UPSTREAM = HOST / "upstream"
COMMIT = "55ddaf5edc6f6c25673aed343907d5b9dca66f84"
UPSTREAM_URL = "https://github.com/google-research/android_world.git"
PROFILE = "stock_android_mcp_baseline_v1"
TASKS = ("ClockStopWatchRunning", "ClockTimerEntry", "SystemBrightnessMax", "CameraTakePhoto")
EFFORTS = ("default", "none", "minimal", "low", "medium", "high", "xhigh")


def invoke(args, *, capture=False, input_text=None, timeout=None, cwd=ROOT):
    return subprocess.run([str(x) for x in args], cwd=cwd, check=True,
                          text=True, encoding="utf-8", capture_output=capture,
                          input=input_text, timeout=timeout)


def read_config(path=None):
    config = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
    path = Path(path).expanduser().resolve() if path else ROOT / "config.local.json"
    if path.is_file():
        custom = json.loads(path.read_text(encoding="utf-8-sig"))
        if not isinstance(custom, dict):
            raise ValueError("Config must be a JSON object")
        unknown = set(custom) - set(config)
        if unknown:
            raise ValueError("Unknown config fields; API keys must never be stored in config")
        config.update(custom)
    if not isinstance(config["model"], str) or not re.fullmatch(r"[A-Za-z0-9_.:/-]{0,128}", config["model"]):
        raise ValueError("Invalid model name")
    if config["effort"] not in EFFORTS or type(config["allow_http"]) is not bool:
        raise ValueError("Invalid reasoning effort or allow_http")
    if config["model_transport"] not in ("host_relay", "direct"):
        raise ValueError("model_transport must be host_relay or direct")
    endpoint = config["responses_endpoint"]
    if not isinstance(endpoint, str) or not endpoint or any(c.isspace() for c in endpoint):
        raise ValueError("Invalid Responses endpoint")
    parsed = urlsplit(endpoint)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or not parsed.path.endswith("/responses")):
        raise ValueError("responses_endpoint must be a full HTTP(S) /responses URL without credentials/query")
    if parsed.scheme == "http" and not config["allow_http"]:
        raise ValueError("HTTP requires explicit allow_http=true; use HTTPS where available")
    # Share exact wire validation with the host runner and Android-side contract.
    sys.path.insert(0, str(HOST)) if str(HOST) not in sys.path else None
    from task_catalog import validate_model_connection
    validate_model_connection(config["model"] or "configuration-check", config["effort"], endpoint, config["allow_http"])
    for name in ("compile_sdk", "console_port", "grpc_port", "seed", "timeout_seconds"):
        if type(config[name]) is not int:
            raise ValueError("Numeric config fields must be integers")
    if config["compile_sdk"] < 35 or not 0 <= config["seed"] <= 2147483647:
        raise ValueError("compile_sdk must be >=35 and seed must fit a nonnegative 32-bit integer")
    if not 5554 <= config["console_port"] <= 5682 or config["console_port"] % 2:
        raise ValueError("console_port must be an even emulator console port from 5554 to 5682")
    if not 1024 <= config["grpc_port"] <= 65535 or not 30 <= config["timeout_seconds"] <= 1800:
        raise ValueError("Invalid gRPC port or timeout")
    if config["serial"] != "emulator-" + str(config["console_port"]):
        raise ValueError("serial must match the dedicated emulator console port")
    if not isinstance(config["avd_name"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", config["avd_name"]):
        raise ValueError("Invalid AVD name")
    if not isinstance(config["build_tools_version"], str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", config["build_tools_version"]):
        raise ValueError("Invalid build-tools version")
    for name in ("android_sdk_root", "java_home"):
        if not isinstance(config[name], str):
            raise ValueError("SDK and JDK paths must be strings")
    return config


def sdk_root(config):
    value = config["android_sdk_root"] or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not value:
        candidates = [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
        if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
            candidates.insert(0, Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
        value = next((str(p) for p in candidates if p.is_dir()), "")
    if not value:
        raise RuntimeError("Set android_sdk_root in config.local.json or ANDROID_SDK_ROOT")
    return Path(value).expanduser().resolve()


def java_home(config):
    value = config["java_home"] or os.environ.get("JAVA_HOME")
    if not value:
        compiler = shutil.which("javac")
        if compiler:
            value = str(Path(compiler).resolve().parent.parent)
    if not value:
        raise RuntimeError("Install JDK 17+ and set java_home or JAVA_HOME")
    return Path(value).expanduser().resolve()


def executable(path, *, batch=False):
    suffix = ".bat" if os.name == "nt" and batch else ".exe" if os.name == "nt" else ""
    return Path(str(path) + suffix)


def env_python():
    path = ROOT / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not path.is_file():
        raise RuntimeError("Run 'python3 run.py setup' first to create this repository's .venv")
    return path


def require_linux_runtime():
    if sys.platform != "linux":
        raise RuntimeError("Run benchmark/setup in Linux or WSL2; native Windows supports build/plan only")


def verify_upstream():
    if not (UPSTREAM / ".git").exists():
        raise RuntimeError("Pinned AndroidWorld checkout missing; run setup")
    actual = invoke(["git", "-C", UPSTREAM, "rev-parse", "HEAD"], capture=True).stdout.strip()
    dirty = invoke(["git", "-C", UPSTREAM, "status", "--porcelain", "--untracked-files=no"], capture=True).stdout.strip()
    if actual != COMMIT or dirty:
        raise RuntimeError("Pinned upstream revision must match and tracked sources must be unmodified")


def setup(python=None):
    require_linux_runtime()
    if not shutil.which("git"):
        raise RuntimeError("Install git first")
    if not UPSTREAM.exists():
        invoke(["git", "init", UPSTREAM])
        invoke(["git", "-C", UPSTREAM, "remote", "add", "origin", UPSTREAM_URL])
    if not (UPSTREAM / ".git").exists():
        raise RuntimeError("Upstream path exists without Git metadata; preserve it and choose a clean checkout")
    remote = invoke(["git", "-C", UPSTREAM, "remote", "get-url", "origin"], capture=True).stdout.strip()
    if remote != UPSTREAM_URL:
        raise RuntimeError("Existing upstream origin differs; it will not be changed automatically")
    head = subprocess.run(["git", "-C", str(UPSTREAM), "rev-parse", "--verify", "HEAD"], capture_output=True)
    if head.returncode != 0:
        # Resume a failed first fetch only when there is no commit or unrelated file.
        if any(p.name != ".git" for p in UPSTREAM.iterdir()):
            raise RuntimeError("Uninitialized upstream contains other files; automatic checkout refused")
        invoke(["git", "-C", UPSTREAM, "fetch", "--depth", "1", "origin", COMMIT])
        invoke(["git", "-C", UPSTREAM, "checkout", "--detach", "FETCH_HEAD"])
    verify_upstream()
    base_python = python or (sys.executable if sys.version_info[:2] == (3, 11) else shutil.which("python3.11"))
    if not base_python:
        raise RuntimeError("Python 3.11 is required; pass setup --python /path/to/python3.11")
    invoke([base_python, "-c", "import sys; assert sys.version_info[:2] == (3,11), 'Python 3.11 required'"])
    if not (ROOT / ".venv").exists():
        invoke([base_python, "-m", "venv", ROOT / ".venv"])
    interpreter = env_python()
    invoke([interpreter, "-c", "import sys; assert sys.version_info[:2] == (3,11), 'Existing .venv must be Python 3.11'"])
    invoke([interpreter, "-m", "pip", "install", "-r", UPSTREAM / "requirements.txt",
            "-r", HOST / "requirements-restored-20261122.txt"])
    invoke([interpreter, "-m", "pip", "install", "--no-deps", UPSTREAM])
    invoke([interpreter, "-m", "pip", "check"])
    print("Python dependencies and pinned AndroidWorld sources are ready.")


def adb_path(config):
    path = executable(sdk_root(config) / "platform-tools/adb")
    if not path.is_file():
        raise RuntimeError("Missing SDK platform-tools/adb")
    return path


def verify_device(config):
    adb = adb_path(config)
    def query(*command):
        return invoke([adb, "-s", config["serial"], *command], capture=True, timeout=25).stdout.strip()
    if query("get-state") != "device":
        raise RuntimeError("Dedicated emulator is not connected")
    if query("shell", "getprop", "ro.kernel.qemu") != "1" or query("shell", "getprop", "ro.build.version.sdk") != "33":
        raise RuntimeError("Only a dedicated API 33 emulator is supported; physical devices are refused")
    avd = query("shell", "getprop", "ro.boot.qemu.avd_name") or query("shell", "getprop", "ro.kernel.qemu.avd_name")
    if avd != config["avd_name"]:
        raise RuntimeError("AVD identity differs from the configured dedicated emulator")
    if query("shell", "getprop", "sys.boot_completed") != "1":
        raise RuntimeError("Emulator is still booting; retry after the home screen appears")
    return adb


def dedicated(args):
    if not args.dedicated_emulator:
        raise RuntimeError("Add --dedicated-emulator to confirm this is a disposable test AVD; tasks reset app data")


def build(config):
    sdk = sdk_root(config)
    invoke([sys.executable, HOST / "build_android_agent.py", "--stock-baseline",
            "--jdk", java_home(config), "--android-jar", sdk / "platforms" / ("android-" + str(config["compile_sdk"])) / "android.jar",
            "--build-tools", sdk / "build-tools" / config["build_tools_version"]])


def deploy(config):
    import hashlib
    adb = verify_device(config)
    folder = HOST / "build-stock-baseline"
    manifest = json.loads((folder / "build.json").read_text(encoding="utf-8"))
    for name, key in (("stock-android-agent.jar", "jar_sha256"), ("androidworld-planner.apk", "apk_sha256")):
        if hashlib.sha256((folder / name).read_bytes()).hexdigest() != manifest[key]:
            raise RuntimeError("Build hash mismatch; rebuild before deploying")
    def device(*cmd):
        return invoke([adb, "-s", config["serial"], *cmd], capture=True, timeout=60).stdout
    processes = device("shell", "ps", "-A", "-o", "ARGS")
    if "com.lab.mobileagent.benchmark.AndroidWorldMain" in processes or "com.lab.mobileagent.standard.StockControlMain" in processes:
        raise RuntimeError("Agent/Control is active; wait for its cleanup before deploying")
    remote = "/data/local/tmp/mobile-ondevice-agent"
    uid = device("shell", "id", "-u").strip()
    if uid not in ("0", "2000"):
        raise RuntimeError("Unexpected emulator ADB identity")
    device("shell", "mkdir", "-p", remote)
    device("shell", "test", "!", "-L", remote)
    if uid == "0":
        device("shell", "chown", "2000:2000", remote)
    device("shell", "chmod", "700", remote)
    staging = remote + "/.stock-android-agent.upload"
    device("push", folder / "stock-android-agent.jar", staging)
    if device("shell", "sha256sum", staging).split()[0] != manifest["jar_sha256"]:
        raise RuntimeError("Emulator JAR transfer hash mismatch")
    if uid == "0":
        device("shell", "chown", "2000:2000", staging)
    device("shell", "chmod", "600", staging)
    print(device("install", "-r", folder / "androidworld-planner.apk").strip())
    device("shell", "mv", staging, remote + "/stock-android-agent.jar")
    print("Deployment complete. Existing APK signatures are not bypassed; builds use a local test signer.")


def connection_args(config):
    return ["--profile", PROFILE, "--adb-path", str(adb_path(config)),
            "--serial", config["serial"], "--avd-name", config["avd_name"],
            "--console-port", str(config["console_port"]), "--grpc-port", str(config["grpc_port"]),
            "--seed", str(config["seed"]), "--timeout-seconds", str(config["timeout_seconds"]),
            "--effort", config["effort"], "--responses-url", config["responses_endpoint"],
            "--model-transport", config["model_transport"],
            *(["--allow-http"] if config["allow_http"] else [])]


def prepare(config):
    require_linux_runtime()
    verify_upstream()
    adb = verify_device(config)
    invoke([env_python(), ROOT / "scripts/prepare_emulator.py", "--adb-path", adb,
            "--console-port", config["console_port"], "--grpc-port", config["grpc_port"],
            "--avd-name", config["avd_name"], "--serial", config["serial"], "--dedicated-emulator"])


def emulator(config, action):
    require_linux_runtime()
    sdk = sdk_root(config)
    if action == "create":
        avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
        if not avdmanager.is_file():
            raise RuntimeError("Install Android SDK command-line tools (latest) first")
        # No --force: an existing AVD is never overwritten.
        invoke([avdmanager, "create", "avd", "-n", config["avd_name"], "-k", "system-images;android-33;google_apis;x86_64", "-d", "pixel_6"], input_text="no\n")
    else:
        print("Keep this terminal open; continue setup/run in another terminal.", flush=True)
        invoke([sdk / "emulator/emulator", "-avd", config["avd_name"], "-port", config["console_port"],
                "-grpc", config["grpc_port"], "-no-snapshot"])


def doctor(config):
    checks = []
    def add(name, callback):
        try:
            result = callback()
            if result is False:
                raise RuntimeError("missing")
            checks.append((name, True, "ready"))
        except Exception as error:
            checks.append((name, False, str(error)))
    add("Linux/WSL runtime", require_linux_runtime)
    add("Git", lambda: bool(shutil.which("git")))
    add("Python environment", lambda: invoke([env_python(), "-c", "import sys; assert sys.version_info[:2]==(3,11); import android_world, android_env"], capture=True))
    add("Pinned upstream", verify_upstream)
    add("ADB", lambda: adb_path(config).is_file())
    add("JDK compiler", lambda: executable(java_home(config) / "bin/javac").is_file())
    add("Compile SDK", lambda: (sdk_root(config) / "platforms" / ("android-" + str(config["compile_sdk"])) / "android.jar").is_file())
    add("Build tools", lambda: executable(sdk_root(config) / "build-tools" / config["build_tools_version"] / "aapt2").is_file())
    add("Model configured", lambda: bool(config["model"]))
    for name, ok, message in checks:
        print(("[OK] " if ok else "[MISSING] ") + name + ": " + message)
    return 0 if all(ok for _, ok, _ in checks) else 2


def selected_tasks(text):
    result = text.split(",")
    if not result or len(set(result)) != len(result) or any(t not in TASKS for t in result):
        raise ValueError("Choose distinct task names from: " + ", ".join(TASKS))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, help="Local configuration file (never contains API keys)")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init", help="Create config.local.json without overwriting existing settings")
    commands.add_parser("doctor", help="Check local dependencies; does not connect to a device")
    install = commands.add_parser("setup", help="Fetch pinned upstream and install into .venv (Linux/WSL)")
    install.add_argument("--python", help="Python 3.11 executable")
    commands.add_parser("build", help="Compile JAR and Planner APK locally")
    emulator_parser = commands.add_parser("emulator")
    emulator_parser.add_argument("action", choices=("create", "start"))
    for name in ("prepare", "deploy", "run"):
        command = commands.add_parser(name)
        command.add_argument("--dedicated-emulator", action="store_true")
        if name == "run":
            command.add_argument("--tasks", default=",".join(TASKS))
    plan = commands.add_parser("plan", help="Print task scope without connecting to the model or emulator")
    plan.add_argument("--tasks", default=",".join(TASKS))
    report = commands.add_parser("report")
    report.add_argument("summary", type=Path)
    report.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if args.command == "init":
        path = args.config or ROOT / "config.local.json"
        with Path(path).open("x", encoding="utf-8") as handle:
            handle.write((ROOT / "config.example.json").read_text(encoding="utf-8"))
        print("Created local config. Fill model and SDK/JDK paths; keep the API key out of this file.")
        return 0
    config = read_config(args.config)
    if args.command == "doctor":
        return doctor(config)
    if args.command == "setup":
        setup(args.python)
    elif args.command == "build":
        build(config)
    elif args.command == "emulator":
        emulator(config, args.action)
    elif args.command in ("prepare", "deploy"):
        dedicated(args)
        (prepare if args.command == "prepare" else deploy)(config)
    elif args.command == "plan":
        sys.path.insert(0, str(HOST))
        from official_task_runner import task_plan
        print(json.dumps([task_plan(config["seed"], task, PROFILE) for task in selected_tasks(args.tasks)], ensure_ascii=False, indent=2))
    elif args.command == "run":
        dedicated(args)
        require_linux_runtime()
        if not config["model"]:
            raise RuntimeError("Fill the exact vision-capable Responses API model name in config.local.json")
        selected_tasks(args.tasks)
        verify_upstream()
        verify_device(config)
        invoke([env_python(), HOST / "batch_task_runner.py", "--run", "--dedicated-emulator", "--tasks", *selected_tasks(args.tasks),
                *connection_args(config), "--model", config["model"], "--setup-app"])
    elif args.command == "report":
        command = [sys.executable, HOST / "report/build_stock_report.py", args.summary.resolve()]
        if args.output:
            command += ["--output-dir", args.output.resolve()]
        invoke(command)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as failure:
        print("Error: " + str(failure), file=sys.stderr)
        raise SystemExit(2)
