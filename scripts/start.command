#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p .local .local/prototype/client
if [ ! -s .local/java-admin.token ]; then openssl rand -hex 32 > .local/java-admin.token; fi
export FUNNYWORK_BOOTSTRAP_TOKEN="$(cat .local/java-admin.token)"
export FUNNYWORK_RUOYI_ACCOUNT_ENABLED=true
export FUNNYWORK_PORT="${FUNNYWORK_PORT:-8765}"
if [ ! -f services/business/target/funnyworker-server-0.1.0-SNAPSHOT.jar ]; then
 sh scripts/build-management.command
 sh scripts/mvn.sh -q -DskipTests package
fi
node --input-type=module -e 'import fs from "node:fs"; const url=`http://127.0.0.1:${process.env.FUNNYWORK_PORT}`; fs.writeFileSync(".local/prototype/client/control-connection.json",JSON.stringify({url}),{mode:0o600});'
mkdir -p .local/java-runtime
RUN_DIR=$(mktemp -d .local/java-runtime/run.XXXXXX)
cp services/business/target/funnyworker-server-0.1.0-SNAPSHOT.jar "$RUN_DIR/server.jar"
sh scripts/start-ruoyi.command > .local/ruoyi-server.log 2>&1 &
RUOYI_PID=$!
java -jar "$RUN_DIR/server.jar" &
AGENT_PID=$!
trap 'kill "$AGENT_PID" "$RUOYI_PID" 2>/dev/null || true' EXIT INT TERM
wait "$AGENT_PID"
