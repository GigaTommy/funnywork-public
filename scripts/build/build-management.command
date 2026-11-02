#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
python3 scripts/prepare-config-templates.py
python3 scripts/setup/prepare-management.py
rm -rf services/business/src/main/resources/static/admin
mkdir -p services/business/src/main/resources/static/admin
cp -R apps/management/dist/. services/business/src/main/resources/static/admin/
