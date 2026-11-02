#!/bin/sh
exec sh "$(dirname "$0")/build/build-server.command" "$@"
