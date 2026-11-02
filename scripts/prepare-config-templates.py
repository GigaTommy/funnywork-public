#!/usr/bin/env python3
"""Compatibility entry point; implementation lives in setup/."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parent / "setup/prepare-config-templates.py"), run_name="__main__")
