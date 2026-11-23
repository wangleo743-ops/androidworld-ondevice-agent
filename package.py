#!/usr/bin/env python3
"""Make a source-only ZIP after checking the selected distribution files."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZipFile, ZIP_DEFLATED

from check_distribution import ROOT, inspect, source_files


def main():
    if not inspect():
        raise SystemExit("Source check failed; archive was not created")
    paths = list(source_files())
    if not (ROOT / "README.md").is_file() or not (ROOT / "run.py").is_file():
        raise SystemExit("Repository entry files are missing")
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    archive = output / "androidworld-ondevice-agent-source.zip"
    manifest = {"created_utc": datetime.now(timezone.utc).isoformat(), "kind": "source-only",
                "files": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
    with ZipFile(archive, "w", ZIP_DEFLATED) as zipped:
        for path in paths:
            zipped.write(path, "androidworld-ondevice-agent/" + path.relative_to(ROOT).as_posix())
        zipped.writestr("androidworld-ondevice-agent/SOURCE_MANIFEST.json", json.dumps(manifest, indent=2) + "\n")
    with ZipFile(archive) as zipped:
        if zipped.testzip() is not None:
            raise RuntimeError("Archive checksum test failed")
        for name, expected in manifest["files"].items():
            if hashlib.sha256(zipped.read("androidworld-ondevice-agent/" + name)).hexdigest() != expected:
                raise RuntimeError("Archive content differs from source: " + name)
    sha = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(sha + "  " + archive.name + "\n", encoding="utf-8")
    print(json.dumps({"archive": str(archive), "source_files": len(paths), "bytes": archive.stat().st_size,
                      "sha256": sha}, indent=2))


if __name__ == "__main__":
    main()
