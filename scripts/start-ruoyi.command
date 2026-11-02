#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
if [ -x .venv/bin/python ]; then
 .venv/bin/python scripts/prepare-ruoyi.py
else
 python3 scripts/prepare-ruoyi.py
fi
RUOYI_JAR=services/identity/ruoyi-admin/target/ruoyi-admin.jar
if [ ! -f "$RUOYI_JAR" ] || find services/identity/ruoyi-admin/src services/identity/ruoyi-common services/identity/ruoyi-modules -type f \( -path '*/src/*' -o -name pom.xml \) -newer "$RUOYI_JAR" | read -r UPDATED; then
 .local/tools/apache-maven-3.9.9/bin/mvn -f services/identity/pom.xml -q -DskipTests package
fi
RUN_DIR=$(mktemp -d .local/ruoyi/run.XXXXXX)
cp "$RUOYI_JAR" "$RUN_DIR/server.jar"
exec java -jar "$RUN_DIR/server.jar" --spring.profiles.active=dev,funnyworker --spring.config.additional-location="file:$(pwd)/.local/ruoyi/application-funnyworker.yml"
