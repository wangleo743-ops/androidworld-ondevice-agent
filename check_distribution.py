#!/usr/bin/env python3
"""Check distributable source files, excluding ignored local build/experiment data."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP = {".git", ".venv", ".cache", "__pycache__", ".validation-tmp", "build", ".build", "build-stock-baseline",
        "upstream", "batches", "runs", "evidence", "generated", "dist", "artifacts", "test-signers"}
FORBIDDEN_SUFFIXES = {".p12", ".pfx", ".pem", ".key", ".jks", ".keystore", ".apk", ".jar", ".dex", ".class", ".pyc", ".png", ".jpg"}
PATTERNS = {
    "private-key-material": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "api-key-literal": re.compile(r"\bsk-[A-Za-z0-9_-]{20,}"),
    "github-token-literal": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{30,})"),
    "private-network-address": re.compile(r"\b(?:10\.(?!0\.2\.2\b)\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+\.\d+)\b"),
    "personal-absolute-path": re.compile(r"(?:[A-Z]:[/\\]+Users[/\\]+[^/\\\s\"']+|/home/[a-z][a-z0-9_-]+/)", re.I),
}


def source_files():
    for path in sorted(ROOT.rglob("*")):
        rel = path.relative_to(ROOT)
        if any(part in SKIP for part in rel.parts) or path.name == "config.local.json" or path.name.endswith(".local.json"):
            continue
        if path.is_symlink():
            raise RuntimeError("Symlink is not distributable: " + rel.as_posix())
        if path.is_file():
            yield path


def inspect():
    errors = []
    count = 0
    for path in source_files():
        count += 1
        rel = path.relative_to(ROOT).as_posix()
        if path.suffix.lower() in FORBIDDEN_SUFFIXES or path.name.startswith(".env"):
            errors.append((rel, "binary-or-credential-file"))
            continue
        try:
            value = path.read_text(encoding="utf-8")
        except UnicodeError:
            errors.append((rel, "unexpected-binary"))
            continue
        # The scanner's own pattern definitions intentionally name the things it rejects.
        if path.resolve() == Path(__file__).resolve():
            continue
        for label, pattern in PATTERNS.items():
            if pattern.search(value):
                errors.append((rel, label))
    # Report paths and categories only, never matching secret text.
    for rel, category in errors:
        print("FAIL " + category + ": " + rel)
    print(f"Source files checked: {count}; findings: {len(errors)}")
    return not errors


if __name__ == "__main__":
    raise SystemExit(0 if inspect() else 1)
