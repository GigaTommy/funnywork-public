#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then
    echo 'Usage: sh scripts/checks/check-ios-service.sh HTTPS_ORIGIN BOOTED_SIMULATOR_UDID' >&2
    exit 2
fi
ROOT=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
command -v xcrun >/dev/null 2>&1 || { echo 'Xcode and an iOS Simulator are required.' >&2; exit 1; }
mkdir -p "$ROOT/.local"
PROBE_DIR=$(mktemp -d "$ROOT/.local/ios-service-probe.XXXXXX")
trap 'rm -rf "$PROBE_DIR"' EXIT HUP INT TERM
xcrun --sdk iphonesimulator swiftc -parse-as-library -target "$(uname -m)-apple-ios17.0-simulator" \
    "$ROOT/scripts/checks/ios-service-probe.swift" -o "$PROBE_DIR/probe"
xcrun simctl spawn "$2" "$PROBE_DIR/probe" "$1"
