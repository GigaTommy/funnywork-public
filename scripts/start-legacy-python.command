#!/bin/sh
cd "$(dirname "$0")/.." || exit 1
if [ -x .venv/bin/python ]; then
  exec .venv/bin/python -m funnyworker.server
fi
exec python3 -m funnyworker.server
