"""Reviewed finite task scopes for the pinned official AndroidWorld commit.

Plan generation is stdlib-only. Before execution the runner compares every
parameter and goal against the real official TaskEval instance.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import random
import re
from urllib.parse import urlsplit


PROFILE = "androidworld_api33_isolated_baseline_v2"
STOCK_PROFILE = "stock_android_mcp_baseline_v1"
PROFILES = (PROFILE, STOCK_PROFILE)
DEFAULT_PROFILE = STOCK_PROFILE
DEFAULT_RESPONSES_ENDPOINT = "https://api.openai.com/v1/responses"
MODEL_EFFORTS = ("default", "none", "minimal", "low", "medium", "high", "xhigh")
# Only the trusted launcher selects these paths. Never accept an arbitrary JAR,
# executable, target package, or platform implementation from model tools.
PROFILE_ARTIFACTS = {
    PROFILE: ("build", "androidworld-agent.jar"),
    STOCK_PROFILE: ("build-stock-baseline", "stock-android-agent.jar"),
}
EXPECTED_COMMIT = "55ddaf5edc6f6c25673aed343907d5b9dca66f84"
DEFAULT_TASKS = ("ClockStopWatchRunning", "ClockTimerEntry", "SystemBrightnessMax", "CameraTakePhoto")


def validate_model_connection(model: str, effort: str, endpoint: str, allow_http: bool = False) -> dict:
    """Validate trusted launch configuration, never a model-supplied destination."""
    if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", model):
        raise ValueError("Supply an exact provider model name with --model")
    if effort not in MODEL_EFFORTS:
        raise ValueError("Unsupported reasoning effort")
    if type(allow_http) is not bool:
        raise ValueError("allow_http must be a boolean")
    if not isinstance(endpoint, str) or not 1 <= len(endpoint) <= 2048 or any(ord(c) < 33 or ord(c) > 126 for c in endpoint):
        raise ValueError("Responses endpoint must be an ASCII HTTP(S) URL")
    try:
        parsed = urlsplit(endpoint)
        port = parsed.port
        valid = (parsed.scheme in ("https", "http") and parsed.hostname and
                 parsed.username is None and parsed.password is None and
                 not parsed.query and not parsed.fragment and
                 "?" not in endpoint and "#" not in endpoint and
                 parsed.path.endswith("/responses") and
                 (port is None or 1 <= port <= 65535))
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("Responses endpoint must end in /responses and contain no credentials, query or fragment")
    if parsed.scheme == "http" and not allow_http:
        raise ValueError("HTTP endpoints require explicit --allow-http; HTTPS is the default")
    return {"model": model, "effort": effort, "responses_endpoint": endpoint, "allow_http": allow_http}


def validate_profile(profile: str) -> str:
    if not isinstance(profile, str) or profile not in PROFILES:
        raise ValueError("Execution profile is not an implemented baseline")
    return profile


def profile_artifacts(profile: str) -> tuple[str, str]:
    return PROFILE_ARTIFACTS[validate_profile(profile)]


def profile_metadata(profile: str) -> dict:
    validate_profile(profile)
    return {
        "profile": profile,
        "platform_backend": "standard_android_api33",
        "requested_control_transport": "stdio_jsonrpc" if profile == STOCK_PROFILE else "session_local_jsonrpc",
        "mobiclaw_system_backend": False,
        "formal_user_grants": False,
    }


@dataclass(frozen=True)
class TaskSpec:
    name: str
    module: str
    target_package: str
    app_name: str
    setup_class: str
    template: str
    preparation: str
    budget: int = 10


TASKS = {
    "ClockStopWatchRunning": TaskSpec("ClockStopWatchRunning", "clock", "com.google.android.deskclock", "clock", "ClockApp",
        "Run the stopwatch.", "official task clears dedicated emulator Clock data"),
    "ClockTimerEntry": TaskSpec("ClockTimerEntry", "clock", "com.google.android.deskclock", "clock", "ClockApp",
        "Create a timer with {hours} hours, {minutes} minutes, and {seconds} seconds. Do not start the timer.",
        "official task clears dedicated emulator Clock data; Agent enters timer digits without starting it"),
    "SystemBrightnessMax": TaskSpec("SystemBrightnessMax", "system", "com.android.settings", "settings", "SettingsApp",
        "Turn brightness to the max value.", "official initialization sets brightness to min; evaluator requires 255"),
    "CameraTakePhoto": TaskSpec("CameraTakePhoto", "camera", "com.android.camera2", "camera", "CameraApp",
        "Take one photo.", "official task clears dedicated emulator /sdcard/Pictures and /sdcard/Movies before and after task"),
}


def get_spec(name: str) -> TaskSpec:
    if name not in TASKS:
        raise ValueError("Task is outside the reviewed AndroidWorld catalog")
    return TASKS[name]


def planned_instance(name: str, suite_seed: int) -> tuple[TaskSpec, dict, str]:
    spec = get_spec(name)
    if type(suite_seed) is not int or suite_seed < 0:
        raise ValueError("Suite seed must be a nonnegative integer")
    seed = int(hashlib.sha256(f"{suite_seed}_{name}_0".encode()).hexdigest(), 16) % (2 ** 32)
    params = {}
    if name == "ClockTimerEntry":
        rng = random.Random(seed)
        params = {"hours": rng.randint(0, 23), "minutes": rng.randint(0, 59), "seconds": rng.randint(0, 59)}
    elif name == "SystemBrightnessMax":
        params = {"max_or_min": "max"}
    params["seed"] = seed
    return spec, params, spec.template.format(**params)


def validate_scope(name: str, package: str, goal: str, max_steps: int) -> TaskSpec:
    spec = get_spec(name)
    if package != spec.target_package or type(max_steps) is not int or max_steps != spec.budget:
        raise ValueError("Task target or official budget differs from reviewed scope")
    if name == "ClockTimerEntry":
        match = re.fullmatch(r"Create a timer with (0|[1-9]|1[0-9]|2[0-3]) hours, (0|[1-9]|[1-5][0-9]) minutes, and (0|[1-9]|[1-5][0-9]) seconds\. Do not start the timer\.", goal)
        if match is None:
            raise ValueError("Timer goal is outside the official parameter domain")
    elif goal != spec.template:
        raise ValueError("Task goal differs from the fixed official template")
    return spec
