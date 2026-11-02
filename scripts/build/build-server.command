#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
sh scripts/build-management.command
.local/tools/apache-maven-3.9.9/bin/mvn -f services/identity/pom.xml -pl ruoyi-admin -am -q -DskipTests=false -Dtest=DirectorySyncTest,HttpDirectoryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false package
exec sh scripts/mvn.sh -q verify
