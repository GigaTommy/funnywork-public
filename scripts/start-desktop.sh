#!/bin/sh
# Launch the Linux source client with the shared account configuration.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
if [ ! -x .venv/bin/python ]; then
    echo 'Create .venv and install requirements.txt first; see README.md.' >&2
    exit 1
fi
exec .venv/bin/python -m funnyworker.desktop "$@"
