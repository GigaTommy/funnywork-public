#!/usr/bin/env python3
"""Compatibility entry point; implementation lives in checks/."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parent / "checks/check-public-https.py"), run_name="__main__")
