"""Prepare only the three apps used by this release, using official setup code."""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb-path", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True)
    parser.add_argument("--console-port", type=int, required=True)
    parser.add_argument("--grpc-port", type=int, required=True)
    parser.add_argument("--dedicated-emulator", action="store_true")
    args = parser.parse_args()
    run.dedicated(args)
    run.require_linux_runtime()
    run.verify_upstream()
    # The parent already checked its selected config. Only device fields are
    # needed here; an unrelated default config must not replace --config values.
    import json
    config = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
    config.update(android_sdk_root=str(Path(args.adb_path).resolve().parent.parent),
                  serial=args.serial, avd_name=args.avd_name, console_port=args.console_port,
                  grpc_port=args.grpc_port)
    run.verify_device(config)
    from android_world.env import env_launcher
    from android_world.env.setup_device import apps, setup
    env = None
    try:
        env = env_launcher._get_env(args.console_port, args.adb_path, args.grpc_port)
        env_launcher.setup_env(env, emulator_setup=False)
        setup.setup_apps(env, app_list=(apps.ClockApp, apps.SettingsApp, apps.CameraApp))
        print("Official setup completed for Clock, Settings and Camera; no model was called.")
    finally:
        if env is not None:
            env.close()


if __name__ == "__main__":
    main()
