"""Sequential official AndroidWorld tasks; one hidden key held only in memory."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import time
import uuid

import official_task_runner as single
from task_catalog import (DEFAULT_TASKS, EXPECTED_COMMIT, PROFILE, STOCK_PROFILE, PROFILES,
                          get_spec, validate_profile, profile_metadata, DEFAULT_PROFILE)


# Exact diagnostic contract agreed with AndroidWorldPlannerClient. No prefix
# matching: new model/transport/control failures need review before continuation.
KNOWN_MODEL_OUTPUT_ERRORS = frozenset({"model_invalid_decision"} | {
    "model_invalid_decision_" + suffix for suffix in (
        "invalid_decision_size", "invalid_model_authorization_code",
        "invalid_model_authorization", "invalid_final_decision", "invalid_decision_kind",
        "invalid_action", "invalid_action_parameters", "app_not_allowed", "invalid_text",
        "invalid_key", "invalid_wait", "unsupported_action", "invalid_coordinate",
        "invalid_string_field", "unexpected_json_fields", "invalid_json_object",
        "invalid_json_size", "invalid_json_trailing_data", "json_complexity_limit",
        "invalid_json", "duplicate_json_key", "invalid_json_string", "invalid_json_escape",
        "invalid_json_unicode", "invalid_json_number",
    )
})


def _number(value):
    return value if type(value) in (int, float) and math.isfinite(value) and value >= 0 else None


def _object(value):
    """Java startup failures may contain null component receipts."""
    return value if isinstance(value, dict) else {}


def _known_unscored_model_error_is_closed(task_name: str, result: dict, phone: dict) -> bool:
    """Allow only this known model-format error after a proven clean boundary.

    This does not turn an unscored attempt into an official task failure/score.
    Unknown infrastructure errors and missing cleanup evidence remain fatal.
    """
    if result.get("status") != "infrastructure_error" or not result.get("run_id"):
        return False
    profile = result.get("profile")
    if profile not in PROFILES:
        return False
    if (result.get("safe_to_teardown_task") is not True
            or result.get("execution_complete") is not True
            or result.get("cleanup_errors") != []):
        return False
    expected = {"run_id": result["run_id"], "task_name": task_name,
                "profile": profile, "target_package": get_spec(task_name).target_package}
    if any(result.get(key) != value or phone.get(key) != value for key, value in expected.items()):
        return False
    reason = phone.get("stop_reason")
    if (phone.get("state") != "finished" or phone.get("kind") != "error"
            or reason not in KNOWN_MODEL_OUTPUT_ERRORS
            or _object(phone.get("failure")).get("code") != reason
            or phone.get("model_done") is not False
            or phone.get("official_evaluation_received") is not False):
        return False
    if any(_object(phone.get(component)).get("closed") is not True for component in ("planner", "control", "mcp")):
        return False
    control = _object(phone.get("control"))
    if profile == STOCK_PROFILE and (control.get("child_exit_confirmed") is not True
            or control.get("cleanup_confirmed") is not True
            or control.get("transport") != "stdio_jsonrpc"):
        return False
    return (result.get("score") is None and result.get("official_raw_score") is None
            and phone.get("official_raw_score") is None and phone.get("agent_score") is None)


def result_row(task_name: str, output: Path, expected_profile: str | None = None) -> dict:
    if expected_profile is not None:
        validate_profile(expected_profile)
    result = json.loads((output / "result.json").read_text(encoding="utf-8"))
    phone_path = output / "phone-result.json"
    phone = json.loads(phone_path.read_text(encoding="utf-8")) if phone_path.is_file() else {}
    control = _object(phone.get("control"))
    control_server = _object(control.get("server")) if result.get("profile") == STOCK_PROFILE else {}
    control_device = _object(control_server.get("device")) if control_server else control
    host_time, device_time = result.get("timing_seconds", {}), phone.get("timing", {})
    cleanup = result.get("cleanup_errors", [])
    status = result.get("status", "infrastructure_error")
    profile_mismatch = (expected_profile is not None and result.get("profile") != expected_profile)
    if expected_profile == STOCK_PROFILE:
        profile_mismatch = (profile_mismatch or bool(phone) and phone.get("profile") != STOCK_PROFILE
                            or bool(control) and control.get("transport") != "stdio_jsonrpc")
    if profile_mismatch:
        status = "profile_mismatch"
    elif cleanup or result.get("safe_to_teardown_task") is False or (
            expected_profile == STOCK_PROFILE and status in ("passed", "failed") and not control):
        status = "cleanup_error"
    elif _known_unscored_model_error_is_closed(task_name, result, phone):
        status = "agent_output_error_unscored"
    row = {
        "task_name": task_name, "run_id": result.get("run_id"), "status": status,
        "source_status": result.get("status"), "phone_stop_reason": phone.get("stop_reason"),
        "official_evaluation_completed": _number(result.get("official_raw_score")) is not None,
        "safe_to_continue_batch": status in ("passed", "failed", "agent_output_error_unscored"),
        "goal": result.get("goal"), "params": result.get("params"), "target_package": result.get("target_package"),
        "profile": result.get("profile"), "max_steps": result.get("max_steps"),
        "expected_profile": expected_profile,
        "phone_result_available": bool(phone),
        "profile_verification": "phone_receipt" if phone and not profile_mismatch else "mismatch" if profile_mismatch else "not_observed",
        "observed_control_transport": control.get("transport"),
        "success": status == "passed" and result.get("passed") is True,
        "official_raw_score": result.get("official_raw_score"), "agent_score": result.get("score"),
        "agent_done": result.get("agent_done"), "model_called": result.get("model_called"),
        "model_calls": _number(phone.get("model_calls")), "steps": result.get("step_count"),
        "model_transport": result.get("model_transport"),
        "actions": _number(control_device.get("actions")),
        "model_seconds": _number(device_time.get("model_seconds")),
        "control_seconds": _number(device_time.get("control_seconds")),
        "observation_seconds": _number(device_time.get("observation_seconds")),
        "program_other_seconds": _number(device_time.get("other_seconds")),
        "host_coordination_wait_seconds": _number(device_time.get("host_coordination_wait_seconds")),
        "android_agent_total_seconds": _number(device_time.get("total_wall_seconds")),
        "environment_setup_seconds": _number(host_time.get("environment_setup")),
        "task_initialization_seconds": _number(host_time.get("task_initialization")),
        "host_agent_episode_seconds": _number(host_time.get("agent_episode")),
        "official_evaluation_seconds": _number(host_time.get("official_evaluation")),
        "task_through_official_score_seconds": _number(host_time.get("task_wall_through_official_score")),
        "official_evidence_seconds": _number(host_time.get("official_evidence")),
        "evidence_pull_seconds": _number(host_time.get("agent_cleanup_and_evidence_pull")),
        "final_cleanup_seconds": _number(host_time.get("final_cleanup")),
        "full_run_excluding_key_wait_seconds": _number(host_time.get("host_run_excluding_key_wait")),
        "output": str(output), "final_screenshot": str(output / "official-final-screen.png") if (output / "official-final-screen.png").is_file() else None,
        "step_timing_file": str(output / "phone" / "step-timing.json") if (output / "phone" / "step-timing.json").is_file() else None,
        "host_timing_seconds": host_time, "android_timing_seconds": device_time,
        "control_service_timing_seconds": _object(control_server.get("timing")),
        "control_service_timing_definition": "Child service timings overlap parent control/observation wall buckets; do not add to task total",
        "cleanup_errors": cleanup, "error_type": result.get("error_type"),
        "failed_stage": result.get("failed_stage"), "failed_phase": result.get("failed_phase"),
    }
    return row


def run_batch(args) -> Path:
    profile = validate_profile(getattr(args, "profile", DEFAULT_PROFILE))
    connection = single.connection_settings(args)
    batch_id = "batch-" + datetime.now(timezone.utc).strftime("%Y%m%dt%H%M%Sz").lower() + "-" + uuid.uuid4().hex[:8]
    output = Path(args.output_root).resolve() / batch_id
    output.mkdir(parents=True, exist_ok=False)
    summary = {"batch_id": batch_id, **profile_metadata(profile), "upstream_commit": EXPECTED_COMMIT,
               "status": "preflight", "suite_seed": args.seed, **connection,
               "planned_tasks": list(args.tasks), "planned_count": len(args.tasks), "rows": [],
               "api_key_saved": False, "custom_source_uploaded": False,
               "scope": "small reviewed task sample, not full AndroidWorld success rate",
               "timing_definitions": {
                   "model_seconds": "Android-side wall time including IPC, network and inference; not isolated server compute time",
                   "control_seconds": "Android Control action execution wall bucket",
                   "observation_seconds": "Android observations and screenshot wall bucket",
                   "program_other_seconds": "remaining exclusive Android wall bucket; CPU time is a separate metric",
                   "android_agent_total_seconds": "sum of Android exclusive buckets including host coordination/scorer waiting",
                   "task_through_official_score_seconds": "host task initialization through official scoring; excludes environment setup and cleanup",
                   "full_run_excluding_key_wait_seconds": "whole host run including environment, scoring, evidence and cleanup; excludes key input",
                   "non_additivity": "host and Android totals overlap and must not be added together",
               }}
    single.save_json(output / "plan.json", [single.task_plan(args.seed, name, profile) for name in args.tasks])
    single.save_json(output / "summary.json", summary)
    key = ""
    started = time.monotonic()
    try:
        pre = single.preflight(args, task_names=args.tasks)
        pre["official_source_sha256"] = single.official_source_hashes()
        single.save_json(output / "preflight.json", pre)
        summary["batch_preflight_seconds"] = time.monotonic() - started
        start = time.monotonic()
        try:
            key = single.read_key(args)
        finally:
            summary["key_input_wait_seconds"] = time.monotonic() - start
        summary["status"] = "running"
        for position, name in enumerate(args.tasks, 1):
            summary["current_task"] = name
            summary["current_position"] = position
            single.save_json(output / "summary.json", summary)
            print(json.dumps({"event": "batch_task_start", "batch_id": batch_id, "position": position,
                              "count": len(args.tasks), "task": name}), flush=True)
            per_task = argparse.Namespace(**vars(args))
            per_task.task = name
            per_task.profile = profile
            per_task.setup_app = True
            per_task.agent_plugin = "phone_bridge:create_agent"
            per_task.output_root = str(output / "tasks")
            task_output = None
            try:
                task_output = single.run(per_task, api_key=key)
                row = result_row(name, task_output, profile)
            except Exception as error:
                row = {"task_name": name, "status": "infrastructure_error", "success": False,
                       "model_called": None, "error_type": type(error).__name__,
                       "output": str(task_output) if task_output else None,
                       "reason": "attempted_run_did_not_return_complete_result"}
            summary["rows"].append(row)
            single.save_json(output / "summary.json", summary)
            print(json.dumps({"event": "batch_task_finished", "task": name, "status": row["status"],
                              "success": row["success"], "output": row.get("output")}), flush=True)
            # Scored failures and this specifically proven closed model-format
            # failure do not suppress later independent tasks. Unknown states do.
            if row.get("safe_to_continue_batch") is not True:
                summary["stop_reason"] = "infrastructure_or_cleanup_failure"
                break
    except Exception as error:
        summary["stop_reason"] = "batch_infrastructure_error"
        summary["error_type"] = type(error).__name__
    finally:
        key = ""
        completed_names = {row["task_name"] for row in summary["rows"]}
        for name in args.tasks:
            if name not in completed_names:
                summary["rows"].append({"task_name": name, "status": "not_run", "success": False,
                                        "reason": summary.get("stop_reason", "batch_interrupted")})
        summary["executed_count"] = sum(row["status"] != "not_run" for row in summary["rows"])
        summary["scored_count"] = sum(row.get("official_evaluation_completed") is True for row in summary["rows"])
        summary["unscored_model_error_count"] = sum(row["status"] == "agent_output_error_unscored" for row in summary["rows"])
        summary["success_count"] = sum(row["success"] for row in summary["rows"])
        summary["success_fraction_all_planned"] = summary["success_count"] / len(args.tasks)
        if summary.get("stop_reason") or summary["executed_count"] < len(args.tasks):
            summary["status"] = "stopped_incomplete"
        elif summary["unscored_model_error_count"]:
            summary["status"] = "completed_with_unscored_failures"
        else:
            summary["status"] = "completed"
        summary["wall_seconds_including_key_wait"] = time.monotonic() - started
        summary["wall_seconds_excluding_key_wait"] = summary["wall_seconds_including_key_wait"] - summary.get("key_input_wait_seconds", 0)
        single.save_json(output / "summary.json", summary)
    print(json.dumps({"batch_id": batch_id, "status": summary["status"], "success_count": summary["success_count"],
                      "planned_count": len(args.tasks), "summary": str(output / "summary.json")}), flush=True)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--run", action="store_true")
    modes.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--tasks", nargs="+", choices=DEFAULT_TASKS, default=list(DEFAULT_TASKS))
    parser.add_argument("--profile", choices=PROFILES, default=DEFAULT_PROFILE)
    parser.add_argument("--dedicated-emulator", action="store_true")
    parser.add_argument("--setup-app", action="store_true", help="Compatibility option: batches always prepare each official app")
    parser.add_argument("--adb-path", default="adb")
    parser.add_argument("--grpc-port", type=int, default=8554)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--avd-name", default="AndroidWorldAvd")
    parser.add_argument("--console-port", type=int, default=5554)
    parser.add_argument("--seed", type=int, default=30)
    single.add_connection_arguments(parser)
    parser.add_argument("--timeout-seconds", type=int, default=900)
    parser.add_argument("--output-root", default=str(single.HERE / "batches"))
    args = parser.parse_args()
    if len(set(args.tasks)) != len(args.tasks):
        parser.error("Each task appears once; duplicate task names are not allowed")
    if args.seed < 0 or not 30 <= args.timeout_seconds <= 1800:
        parser.error("Seed must be nonnegative; timeout 30..1800")
    single.validate_connection_args(args, parser)
    if not (args.run or args.preflight_only):
        print(json.dumps([single.task_plan(args.seed, name, args.profile) for name in args.tasks], ensure_ascii=False, indent=2))
        return 0
    if not args.dedicated_emulator:
        parser.error("Batch execution requires --dedicated-emulator")
    if args.preflight_only:
        checks = single.preflight(args, task_names=args.tasks)
        checks["official_source_sha256"] = single.official_source_hashes()
        print(json.dumps(checks, ensure_ascii=False, indent=2))
        return 0
    output = run_batch(args)
    status = json.loads((output / "summary.json").read_text(encoding="utf-8"))["status"]
    return 0 if status in ("completed", "completed_with_unscored_failures") else 2


if __name__ == "__main__":
    raise SystemExit(main())
