#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VERSION=3.9.9
TOOLS="$ROOT/.local/tools"
MAVEN="$TOOLS/apache-maven-$VERSION/bin/mvn"
if [ ! -x "$MAVEN" ]; then
  mkdir -p "$TOOLS"
  ARCHIVE="$TOOLS/apache-maven-$VERSION-bin.tar.gz"
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz"
  curl --fail --location --silent --show-error "$URL" -o "$ARCHIVE"
  curl --fail --location --silent --show-error "$URL.sha512" -o "$ARCHIVE.sha512"
  python3 - "$ARCHIVE" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
expected = pathlib.Path(str(p)+'.sha512').read_text().split()[0]
if hashlib.sha512(p.read_bytes()).hexdigest() != expected:
    raise SystemExit('Maven archive checksum mismatch')
PY
  tar -xzf "$ARCHIVE" -C "$TOOLS"
fi
# Use the business service by default, while allowing an explicit reactor.
for argument in "$@"; do
  case "$argument" in
    -f|--file|--file=*) exec "$MAVEN" "$@" ;;
  esac
done
exec "$MAVEN" -f "$ROOT/services/business/pom.xml" "$@"
