#!/usr/bin/env python3
"""Run host/entry unit tests without installing APKs or calling a model."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
scratch = ROOT / ".validation-tmp"
scratch.mkdir(exist_ok=True)
tempfile.tempdir = str(scratch)
host = ROOT / "androidworld-integration"
sys.path.insert(0, str(host))
loader = unittest.TestLoader()
suite = loader.discover(str(host), pattern="test_*.py", top_level_dir=str(host))
suite.addTests(unittest.TestLoader().discover(str(ROOT / "tests"), pattern="test_*.py", top_level_dir=str(ROOT / "tests")))
result = unittest.TextTestRunner(verbosity=2).run(suite)
raise SystemExit(0 if result.wasSuccessful() else 1)
