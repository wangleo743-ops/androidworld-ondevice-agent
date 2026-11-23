"""Local-only API 33 benchmark build. Does not deploy or contact any server/device."""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent / "mobile-ondevice-agent"
PLANNER_PACKAGE = "com.lab.androidworldplanner"


def invoke(*args):
    p = subprocess.run([str(x) for x in args], capture_output=True, timeout=180)
    if p.returncode:
        raise RuntimeError((p.stdout + p.stderr).decode("utf8", "replace")[-6000:])
    return (p.stdout + p.stderr).decode("utf8", "replace")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(jdk, sdk, build_tools, *, stock_baseline=False):
    # A separate output directory keeps the previous deployed benchmark artifacts intact.
    output = ROOT / ("build-stock-baseline" if stock_baseline else "build")
    agent_name = "stock-android-agent.jar" if stock_baseline else "androidworld-agent.jar"
    output.mkdir(exist_ok=True)
    java, javac, keytool = [jdk / "bin" / (name + (".exe" if os.name == "nt" else "")) for name in ("java", "javac", "keytool")]
    aapt, zipalign = [build_tools / (name + (".exe" if os.name == "nt" else "")) for name in ("aapt2", "zipalign")]
    d8, signer = [build_tools / "lib" / (name + ".jar") for name in ("d8", "apksigner")]
    for path in (java, javac, keytool, sdk, aapt, zipalign, d8, signer):
        if not path.is_file():
            raise RuntimeError(f"Missing local build dependency: {path}")
    planner = [PROJECT / "androidworld-planner-app/src/com/lab/androidworldplanner/PlannerProvider.java"]
    planner.extend(PROJECT / "src/com/lab/mobileagent" / path for path in (
        "l1/ResponsesClient.java", "l1/ModelConnection.java", "l1/Decision.java", "l1/StrictJson.java", "l1/PlannerMessages.java",
        "model/Observation.java", "model/Action.java", "benchmark/BenchmarkContract.java"))
    manifest = PROJECT / "androidworld-planner-app/AndroidManifest.xml"
    inputs = {str(path.relative_to(PROJECT)): digest(path) for path in [manifest, *planner]}
    with tempfile.TemporaryDirectory(prefix="benchmark-build-", dir=output) as scratch:
        stage = Path(scratch)

        def compile_sources(sources, name):
            classes = stage / (name + "-classes")
            classes.mkdir()
            args = stage / (name + "-sources.args")
            args.write_text("\n".join('"' + path.as_posix() + '"' for path in sources), encoding="utf8")
            invoke(javac, "-J-Dfile.encoding=UTF-8", "--release", "8", "-encoding", "UTF-8", "-implicit:none", "-cp", sdk,
                   "-d", classes, "@" + str(args))
            jar = stage / (name + ".jar")
            with ZipFile(jar, "w") as archive:
                for item in sorted(classes.rglob("*.class")):
                    archive.write(item, item.relative_to(classes).as_posix())
            dex = stage / (name + "-dex")
            dex.mkdir()
            invoke(java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "33", "--lib", sdk,
                   "--output", dex, jar)
            return dex

        planner_dex = compile_sources(planner, "planner")
        unsigned, aligned = stage / "unsigned.apk", stage / "aligned.apk"
        invoke(aapt, "link", "--manifest", manifest, "-I", sdk, "-o", unsigned,
               "--min-sdk-version", "33", "--target-sdk-version", "33", "--version-code", "1", "--version-name", "0.1")
        with ZipFile(unsigned, "a", ZIP_STORED) as archive:
            for item in sorted(planner_dex.glob("classes*.dex")):
                archive.write(item, item.name)
        invoke(zipalign, "-f", "4", unsigned, aligned)
        # Disposable benchmark-only signing identity; never a production authorization key.
        # Keep the benchmark-only signing identity so the existing Planner APK remains compatible.
        key_directory = ROOT / "build/test-signers"
        key_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        test_key = key_directory / (PLANNER_PACKAGE + ".p12")
        if not test_key.exists():
            invoke(keytool, "-genkeypair", "-keystore", test_key, "-storetype", "PKCS12", "-storepass", "android",
                   "-keypass", "android", "-alias", "localfixture", "-keyalg", "RSA", "-keysize", "2048",
                   "-validity", "3650", "-dname", "CN=Local AndroidWorld Test Fixture")
        signed = stage / "planner.apk"
        invoke(java, "-jar", signer, "sign", "--ks", test_key, "--ks-pass", "pass:android",
               "--key-pass", "pass:android", "--out", signed, aligned)
        verified = invoke(java, "-jar", signer, "verify", "--verbose", "--print-certs", signed)
        match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", verified)
        if not match or f"package: name='{PLANNER_PACKAGE}'" not in invoke(aapt, "dump", "badging", signed):
            raise RuntimeError("Benchmark APK verification failed")
        pin = match[1]
        # Copy a stable snapshot; generated public pin is confined to this benchmark build.
        sources = []
        for path in sorted((PROJECT / "src").rglob("*.java")):
            relative = path.relative_to(PROJECT)
            inputs[str(relative)] = digest(path)
            target = stage / "sources" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            content = path.read_text(encoding="utf8")
            if path.name == "AndroidWorldBuildPin.java":
                content = content.replace("0" * 64, pin)
            target.write_text(content, encoding="utf8")
            sources.append(target)
        agent_dex = compile_sources(sources, "agent")
        agent = stage / agent_name
        with ZipFile(agent, "w", ZIP_STORED) as archive:
            for item in sorted(agent_dex.glob("classes*.dex")):
                archive.write(item, item.name)
        if any(digest(PROJECT / relative) != expected for relative, expected in inputs.items()):
            raise RuntimeError("Sources changed during benchmark build; rebuild")
        shutil.copyfile(agent, output / agent_name)
        shutil.copyfile(signed, output / "androidworld-planner.apk")
        evidence = {
            "build_location": "local_" + platform.system().lower(), "source_uploaded": False, "min_api": 33,
            "planner_package": PLANNER_PACKAGE, "planner_signer_sha256": pin,
            "jar_sha256": digest(output / agent_name),
            "apk_sha256": digest(output / "androidworld-planner.apk"),
            "sources_sha256": inputs, "compile_sdk_jar_sha256": digest(sdk),
            "d8_sha256": digest(d8), "javac": invoke(javac, "-version").strip(),
            "source_distribution": "standalone_androidworld_baseline",
            "agent_filename": agent_name,
            "supported_profiles": ["androidworld_api33_isolated_baseline_v2", "stock_android_mcp_baseline_v1"],
            "build_variant": "stock_android_mcp_baseline" if stock_baseline else "legacy_androidworld",
        }
        (output / "build.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf8")
        return {k: v for k, v in evidence.items() if k != "sources_sha256"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--stock-baseline", action="store_true", help="Build the separate stock-android-agent.jar without replacing legacy artifacts")
    args = parser.parse_args()
    print(json.dumps(build(args.jdk, args.android_jar, args.build_tools, stock_baseline=args.stock_baseline), indent=2))
