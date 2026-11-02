#!/bin/sh
exec sh "$(dirname "$0")/build/mvn.sh" "$@"
