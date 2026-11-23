#!/usr/bin/env python3
"""Run JVM contract and local HTTP tests; no emulator or model API key required.

Uses config.local.json, JAVA_HOME/PATH and ANDROID_SDK_ROOT/ANDROID_HOME.
Downloads the checksum-pinned org.json test dependency from Maven Central if
needed. The tests use a temporary 127.0.0.1 HTTP server, never a real model.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JSON_URL = "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar"
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


def module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def json_dependency(offline: bool) -> Path:
    cached = ROOT / ".cache" / "test-dependencies" / "json-20240303.jar"
    if cached.is_file():
        if hashlib.sha256(cached.read_bytes()).hexdigest() != JSON_SHA256:
            raise RuntimeError("Cached org.json checksum mismatch; remove that cache file and retry")
        return cached
    if offline:
        raise RuntimeError("org.json is not cached; pass --json-jar or run once without --offline")
    with urllib.request.urlopen(JSON_URL, timeout=45) as response:
        content = response.read(2_000_001)
    if len(content) > 2_000_000 or hashlib.sha256(content).hexdigest() != JSON_SHA256:
        raise RuntimeError("Downloaded org.json checksum mismatch")
    cached.parent.mkdir(parents=True, exist_ok=True)
    cached.write_bytes(content)
    return cached


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, help="Optional alternative to config.local.json")
    parser.add_argument("--jdk", type=Path, help="Override JDK home (17+ recommended)")
    parser.add_argument("--android-jar", type=Path, help="Override SDK platform android.jar (API 35+)")
    parser.add_argument("--json-jar", type=Path, help="Existing org.json JAR; avoids the dependency download")
    parser.add_argument("--offline", action="store_true", help="Do not download missing test dependencies")
    args = parser.parse_args()
    entry = module("release_entry", ROOT / "run.py")
    config = entry.read_config(args.config)
    jdk = args.jdk or entry.java_home(config)
    android_jar = args.android_jar or (
        entry.sdk_root(config) / "platforms" / ("android-" + str(config["compile_sdk"])) / "android.jar"
    )
    if not android_jar.is_file():
        raise RuntimeError("Missing Android compile SDK; install platforms;android-35 or pass --android-jar")
    json_jar = args.json_jar or json_dependency(args.offline)
    checks = module("java_contract_checks", ROOT / "mobile-ondevice-agent" / "tests" / "run_checks.py")
    print(json.dumps(checks.run(jdk, android_jar, json_jar), indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, ValueError) as error:
        print(f"Java checks failed: {error}", file=sys.stderr)
        raise SystemExit(1)
