#!/usr/bin/env python3
"""Check Git tracking and ignore boundaries; this is not a secret-content scanner."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]

# This deliberately tracked snapshot models a user's file with a .local suffix.
TRACKED_IGNORE_EXCEPTIONS = {
    'apps/desktop/snapshots/web/changed-files-turn/workspace.expected/app.local',
}

IGNORED_PROBES = (
    '.local/audit-probe.json', 'docs/audit-probe.md', 'test/audit-probe.py',
    'tests/audit-probe.py', '.venv/audit-probe', 'node_modules/audit-probe',
    'deploy/magpie/magpie.env', 'deploy/mobile-push.env',
    'services/identity/script/docker/runtime.env',
    'deploy/result-files.env', 'apps/management/.env.production',
    'apps/desktop/apps/desktop/.env.macos', 'credentials.json', 'secrets.json',
    '.credentials.yaml', '.aws/credentials', '.ssh/id_ed25519', 'id_rsa',
    'release-private.key', 'signing.pem', 'signing.p12', 'signing.pfx',
    'signing.jks', 'signing.keystore', 'runtime.sqlite3', 'runtime.db-wal',
    'services/business/target/audit-probe.jar',
    'packages/management-react/dist/audit-probe.js',
    'apps/desktop/apps/desktop/.desktop-build/audit-probe',
    '.pnpm-store/audit-probe', '.pytest_cache/audit-probe',
    '.mypy_cache/audit-probe', '.ruff_cache/audit-probe', '.coverage',
    'test-results/audit-probe.json', 'playwright-report/index.html',
    'apps/management/test-results/audit-probe.json',
    'apps/android/local.properties', 'apps/android/app/build/audit-probe',
    'apps/ios/DerivedData/audit-probe',
    '.local/agent/packages/repository-local-materials/migration.json',
    'apps/android/TODO.md', 'apps/android/HISTORY_ALLOWANCE_20261023.md',
    'apps/ios/UX_ALIGNMENT.md', 'apps/ios/UX_OPTIMIZATION.md',
    'apps/management/OPTIMIZATION_PLAN.md',
    'apps/management/MANAGEMENT_SHELL_UAT_20261021.md',
    'services/business/MAINTENANCE_ROLE_PLAN.md',
    'services/business/MAINTENANCE_REQUIREMENTS_REVIEW.md',
    'designs/ios-task-ux-20261010/index.html',
    'assets/brand/concepts/audit-probe.png',
)

SOURCE_PROBES = (
    'apps/management/pnpm-lock.yaml', 'apps/desktop/pnpm-lock.yaml',
    'packages/management-react/pnpm-lock.yaml',
    'apps/desktop/python/sdk/uv.lock',
    'apps/management/.env.production.example',
    'apps/desktop/apps/desktop/.env.macos.example',
    'deploy/magpie/magpie.env.example', 'deploy/mobile-push.env.example',
    'services/identity/script/docker/runtime.env.example',
    'deploy/magpie/MAGPIE-LICENSE.txt',
    'deploy/result-files.env.example', 'deploy/release-public-key.pem',
    'services/business/src/test/resources/certificates/node.pem',
    'services/identity/ruoyi-admin/src/main/resources/application.yml.example',
    'apps/android/gradle/wrapper/gradle-wrapper.jar',
    'apps/management/uat/model-middleware/fixture.ts',
    'services/business/src/test/java/AuditProbeTest.java',
    'apps/desktop/packages/util/time/tests/audit-probe.spec.ts',
    'apps/desktop/docs/development.md',
    'apps/android/README.md', 'apps/ios/MOBILE_CHAT.md',
    'services/business/MODEL_UAT_PLAN.md', 'services/business/MODEL_UAT_MANUAL.md',
    'designs/enterprise-model-poc/index.manual.html',
    'designs/funnywork-site/index.html', 'assets/brand/portrait-v2/app-icon.png',
)


def git(*args, data=None):
    result = subprocess.run(['git', '-C', str(ROOT), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode not in (0, 1) or (result.returncode == 1 and args[0] != 'check-ignore'):
        raise RuntimeError(f'git {args[0]} failed (exit {result.returncode})')
    return set(filter(None, result.stdout.decode('utf-8').split('\0')))


def ignored(paths):
    return git('check-ignore', '--no-index', '-z', '--stdin',
               data=('\0'.join(paths) + '\0').encode('utf-8'))


def main():
    errors = []
    tracked_ignored = git('ls-files', '-c', '-i', '--exclude-standard', '-z')
    for path in sorted(tracked_ignored - TRACKED_IGNORE_EXCEPTIONS):
        errors.append(f'Tracked file matches ignore rules (review index or rule): {path}')
    for path in sorted(set(IGNORED_PROBES) - ignored(IGNORED_PROBES)):
        errors.append(f'Local/private/generated path is not ignored: {path}')
    for path in sorted(ignored(SOURCE_PROBES)):
        errors.append(f'Required source/template/lockfile is ignored: {path}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Git tracking, private/generated path rules and source exceptions: OK')
    return 0


if __name__ == '__main__':
    sys.exit(main())
