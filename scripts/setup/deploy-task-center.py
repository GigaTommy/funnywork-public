#!/usr/bin/env python3
"""Pinned task-center rollout on the verified Docker production host.

Prepare staging/<release> with deployment-plan.json, business.jar, admin.tar.gz and
permissions SQL. Defaults to read-only validation. --apply takes final DB dumps,
recreates only business and atomically switches the existing admin symlink.
--rollback restores the prior binary/UI without discarding post-release data.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

ROOT = Path('/opt/funnyworker')
CONFIG = Path('/etc/nginx/sites-available/funnyworker')
ADMIN = Path('/srv/funnywork/admin-current')


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sql(database, statement):
    return subprocess.check_output(['docker', 'exec', 'funnyworker-postgres-1', 'psql',
        '-U', 'funnyworker', '-d', database, '-v', 'ON_ERROR_STOP=1', '-Atc', statement], text=True).strip()


def version():
    return int(sql('funnyworker_business', 'select max(version::int) from flyway_schema_history where success'))


def health(wait=False):
    for _ in range(100 if wait else 1):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8765/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        if wait:
            time.sleep(1)
    raise RuntimeError('Business health is not UP')


def compose(*args):
    run(['docker', 'compose', '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'compose.yml'), *args], cwd=ROOT)


def replace(source, destination):
    pending = destination.with_name(destination.name + '.task-center-next')
    if pending.exists() or pending.is_symlink():
        raise RuntimeError('Inspect leftover replacement: ' + str(pending))
    try:
        shutil.copy2(source, pending)
        with pending.open('rb') as stream:
            os.fsync(stream.fileno())
        os.replace(pending, destination)
    finally:
        pending.unlink(missing_ok=True)


def switch_admin(target):
    pending = ADMIN.with_name('admin-task-center-next')
    if pending.exists() or pending.is_symlink():
        raise RuntimeError('Inspect leftover admin link')
    if not target.is_dir():
        raise RuntimeError('Missing admin release')
    try:
        pending.symlink_to(target, target_is_directory=True)
        os.replace(pending, ADMIN)
    finally:
        pending.unlink(missing_ok=True)


def task_fingerprint():
    return sql('funnyworker_business', "select count(*)::text||':'||md5(coalesce(string_agg(md5(row_to_json(t)::text),'' order by id),'')) from tasks t")


def role_fingerprint():
    return sql('funnyworker_management', "select count(*)::text||':'||md5(coalesce(string_agg(role_id::text||':'||menu_id::text,',' order by role_id,menu_id),'')) from sys_role_menu")


def backup_database(database, path):
    with path.open('wb') as output:
        run(['docker', 'exec', 'funnyworker-postgres-1', 'pg_dump', '-U', 'funnyworker', '-Fc', database], stdout=output)
    path.chmod(0o600)
    with path.open('rb') as source:
        run(['docker', 'exec', '-i', 'funnyworker-postgres-1', 'pg_restore', '--list'], stdin=source, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    choice = parser.add_mutually_exclusive_group()
    choice.add_argument('--apply', action='store_true')
    choice.add_argument('--rollback', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', args.release):
        parser.error('Release must be one safe directory name')
    if not ROOT.is_dir() or os.geteuid() != 0:
        raise RuntimeError('Run only as root on the verified production host')
    stage = ROOT / 'staging' / args.release
    backup = ROOT / 'backups' / args.release
    plan = json.loads((stage / 'deployment-plan.json').read_text())
    if plan['release'] != args.release:
        raise RuntimeError('Plan release mismatch')
    public = Path('/srv/funnywork/admin-shell-releases') / args.release
    with (ROOT / 'deployment.lock').open('a') as lock, (ROOT / 'android-deployment.lock').open('a') as android_lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(android_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.rollback:
            if (ROOT / 'current-release').read_text().strip() != args.release or version() != plan['targetMigration']:
                raise RuntimeError('Rollback release/schema changed; inspect before proceeding')
            if sha(backup / 'business.jar') != plan['previousBusinessSha256']:
                raise RuntimeError('Rollback binary hash mismatch')
            replace(backup / 'business.jar', ROOT / 'business.jar')
            compose('up', '-d', '--no-deps', '--force-recreate', 'business')
            health(wait=True)
            switch_admin(Path(plan['previousAdmin']))
            replace(backup / 'current-release', ROOT / 'current-release')
            print(json.dumps({'rolledBack': True, 'databasePreservedAt': version(), 'admin': str(ADMIN.readlink())}))
            return
        if ((ROOT / 'current-release').read_text().strip() != plan['expectedRelease']
            or sha(ROOT / 'business.jar') != plan['previousBusinessSha256']
            or str(ADMIN.readlink()) != plan['previousAdmin']
            or sha(CONFIG) != plan['expectedNginxSha256'] or version() != plan['initialMigration']):
            raise RuntimeError('Production baseline changed')
        for name, key in [('business.jar', 'candidateSha256'), ('admin.tar.gz', 'adminArchiveSha256'), ('task-center-permissions.sql', 'permissionsSha256')]:
            if sha(stage / name) != plan[key]:
                raise RuntimeError('Staged checksum mismatch: ' + name)
        health()
        compose('config', '--quiet')
        if sql('funnyworker_business', "select count(*) from tasks where state='RUNNING'") != '0':
            raise RuntimeError('Active tasks exist; drain them before rollout')
        # Refuse collisions instead of overwriting an unrelated permission definition.
        for menu in range(8124, 8129):
            if sql('funnyworker_management', 'select count(*) from sys_menu where menu_id=' + str(menu)) != '0':
                raise RuntimeError('Permission ID already exists; inspect its definition')
        if backup.exists() or public.exists():
            raise RuntimeError('Existing release output; inspect before retry')
        if not args.apply:
            print(json.dumps({'validated': True, 'release': args.release, 'from': version(), 'to': plan['targetMigration'], 'backupsRequired': True}))
            return
        backup.mkdir(mode=0o700)
        for name in ('business.jar', 'current-release', 'business.env', 'compose.yml', '.env', 'identity.yml'):
            shutil.copy2(ROOT / name, backup / name)
            (backup / name).chmod(0o600)
        shutil.copy2(CONFIG, backup / 'nginx.conf')
        (backup / 'previous-admin').write_text(plan['previousAdmin'] + '\n')
        original_identity = sha(ROOT / 'identity.jar')
        old_roles = role_fingerprint()
        stopped = switched = False
        try:
            # Only synthetic or existing terminal tasks are present; stop writers for final backup.
            compose('stop', '-t', '30', 'business')
            stopped = True
            baseline_tasks = task_fingerprint()
            backup_database('funnyworker_business', backup / 'business.dump')
            backup_database('funnyworker_management', backup / 'management.dump')
            baseline = {'tasks': baseline_tasks, 'roles': old_roles, 'identitySha256': original_identity,
                'files': {p.name: sha(p) for p in backup.iterdir() if p.is_file()}}
            (backup / 'manifest.json').write_text(json.dumps(baseline, indent=2) + '\n')
            # Keep old hashed resources so pre-release tabs can still lazy-load their components.
            shutil.copytree(Path(plan['previousAdmin']), public)
            with tarfile.open(stage / 'admin.tar.gz') as archive:
                archive.extractall(public, filter='data')
            for path in public.rglob('*'):
                if path.is_symlink():
                    raise RuntimeError('Unexpected frontend symlink')
                path.chmod(0o755 if path.is_dir() else 0o644)
            public.chmod(0o755)
            if sha(public / 'index.html') != plan['adminIndexSha256']:
                raise RuntimeError('Frontend index hash mismatch')
            with (stage / 'task-center-permissions.sql').open('rb') as source:
                run(['docker', 'exec', '-i', 'funnyworker-postgres-1', 'psql', '-U', 'funnyworker', '-d',
                    'funnyworker_management', '-v', 'ON_ERROR_STOP=1', '--single-transaction'], stdin=source, stdout=subprocess.DEVNULL)
            replace(stage / 'business.jar', ROOT / 'business.jar')
            switched = True
            compose('up', '-d', '--no-deps', '--force-recreate', 'business')
            health(wait=True)
            if version() != plan['targetMigration'] or role_fingerprint() != old_roles or task_fingerprint() != baseline_tasks:
                raise RuntimeError('Migration, original tasks or role grants differ from rollout expectations')
            if sha(ROOT / 'identity.jar') != original_identity:
                raise RuntimeError('Identity binary changed')
            switch_admin(public)
            (stage / 'release-marker').write_text(args.release + '\n')
            replace(stage / 'release-marker', ROOT / 'current-release')
            result = {'release': args.release, 'health': 'UP', 'migration': version(), 'oldTasksPreserved': True,
                'roleGrantsPreserved': True, 'identityJarPreserved': True, 'businessSha256': sha(ROOT / 'business.jar'),
                'admin': str(ADMIN.readlink()), 'backup': str(backup)}
            (backup / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps(result))
        except BaseException:
            if switched:
                replace(backup / 'business.jar', ROOT / 'business.jar')
            if stopped:
                compose('up', '-d', '--no-deps', '--force-recreate', 'business')
                health(wait=True)
            switch_admin(Path(plan['previousAdmin']))
            replace(backup / 'current-release', ROOT / 'current-release')
            (backup / 'failure.json').write_text(json.dumps({'oldServiceRestored': True, 'databaseVersion': version(),
                'databaseRestore': 'Not automatic: additive schema retained to preserve subsequent data'}) + '\n')
            raise


if __name__ == '__main__':
    main()
