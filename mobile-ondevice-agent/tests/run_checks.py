"""Compile and run JVM contract checks; no emulator, API key or real model needed.

The HTTP transport test opens only a temporary 127.0.0.1 fixture server.
Supply org.json:json:20240303 as --json-jar (MIT licensed, not bundled).
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
CHECKS = (
    "com.lab.mobileagent.benchmark.BenchmarkContractChecks",
    "com.lab.mobileagent.benchmark.AndroidWorldFocusChecks",
    "com.lab.mobileagent.l1.AndroidWorldPayloadChecks",
    "com.lab.mobileagent.l1.AndroidWorldTaskPayloadChecks",
    "com.lab.mobileagent.l1.AndroidWorldDecisionDiagnosticChecks",
    "com.lab.mobileagent.l1.PortableResponsesChecks",
    "com.lab.mobileagent.platform.PlatformContractChecks",
    "com.lab.mobileagent.standard.StockMcpChecks",
)


def executable(name: str, jdk: Path | None) -> str:
    suffix = ".exe" if os.name == "nt" else ""
    path = str(jdk / "bin" / (name + suffix)) if jdk else shutil.which(name)
    if not path or not Path(path).is_file():
        raise RuntimeError(f"Missing {name}; install a JDK or pass --jdk")
    return path


def run(jdk: Path | None, android_jar: Path, json_jar: Path) -> dict:
    for dependency in (android_jar, json_jar):
        if not dependency.is_file():
            raise ValueError(f"Missing compile dependency: {dependency}")
    java, javac = executable("java", jdk), executable("javac", jdk)
    sources = sorted((PROJECT / "src").rglob("*.java"))
    sources += sorted((PROJECT / "androidworld-planner-app" / "src").rglob("*.java"))
    sources += sorted((PROJECT / "tests").rglob("*.java"))
    scratch_root = PROJECT / "tests" / ".build"
    scratch_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="jvm-checks-", dir=scratch_root) as directory:
        stage = Path(directory)
        classes = stage / "classes"
        classes.mkdir()
        argfile = stage / "sources.args"
        argfile.write_text("\n".join('"' + p.resolve().as_posix() + '"' for p in sources), encoding="utf8")
        command = [javac, "-J-Dfile.encoding=UTF-8", "-J-Duser.language=en", "--release", "8", "-encoding", "UTF-8", "-implicit:none",
                   "-cp", os.pathsep.join((str(android_jar.resolve()), str(json_jar.resolve()))),
                   "-d", str(classes), "@" + str(argfile)]
        compile_result = subprocess.run(command, capture_output=True, text=True, encoding="utf8", errors="replace", timeout=120)
        if compile_result.returncode:
            raise RuntimeError("Java compilation failed:\n" + (compile_result.stdout + compile_result.stderr)[-10000:])
        results = []
        classpath = os.pathsep.join((str(classes), str(json_jar.resolve())))
        for name in CHECKS:
            result = subprocess.run([java, "-cp", classpath, name], capture_output=True, text=True,
                                    encoding="utf8", errors="replace", timeout=60)
            if result.returncode:
                raise RuntimeError(f"{name} failed:\n" + (result.stdout + result.stderr)[-10000:])
            results.append({"main_class": name, "passed": True, "output": result.stdout.strip()})
    return {"passed": True, "source_count": len(sources), "checks": results,
            "real_model_calls": 0, "android_device_actions": 0, "network": "loopback_fixture_only"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.jdk, args.android_jar, args.json_jar), indent=2))
