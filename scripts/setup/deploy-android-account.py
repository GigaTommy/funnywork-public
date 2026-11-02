#!/usr/bin/env python3
"""Run on the verified FunnyWork international host; preview unless --apply is given.

Uses the existing iOS Docker deployment layout. Pins the observed release and both
JAR digests, refuses any database migration change, backs up both databases and JAR,
adds the independent Android client, and restores the prior service on failure.
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
import time
import urllib.request
import zipfile

ROOT = Path('/opt/funnyworker')
CLIENT = 'b12c16d37fb30716a9315e197290c56e'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def migrations(path):
    with zipfile.ZipFile(path) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if name.startswith('BOOT-INF/classes/db/migration/') and name.endswith('.sql')}


def validate(current, candidate, expected_current, expected_candidate):
    if sha(current) != expected_current or sha(candidate) != expected_candidate:
        raise RuntimeError('JAR digest differs from pinned deployment plan')
    before, after = migrations(current), migrations(candidate)
    if not before or before != after:
        raise RuntimeError('Migration set differs; prepare a separate reviewed release before deployment')
    with zipfile.ZipFile(current) as previous, zipfile.ZipFile(candidate) as archive:
        if 'BOOT-INF/classes/com/funnywork/AccountClient.class' not in archive.namelist():
            raise RuntimeError('Candidate lacks Android account adaptation')
        names = set(previous.namelist()) | set(archive.namelist())
        old_names, new_names = set(previous.namelist()), set(archive.namelist())
        for name in names:
            if name.endswith('/'):
                continue
            if name in old_names and name in new_names and hashlib.sha256(previous.read(name)).digest() == hashlib.sha256(archive.read(name)).digest():
                continue
            allowed = name == 'META-INF/MANIFEST.MF' or any(
                name == 'BOOT-INF/classes/com/funnywork/' + cls + '.class'
                or (name.startswith('BOOT-INF/classes/com/funnywork/' + cls + '$') and name.endswith('.class'))
                for cls in ('AccountClient', 'EmployeeAccountController', 'ManagementDesktopAccounts', 'ManagementIdentity'))
            if not allowed:
                raise RuntimeError('Candidate includes changes outside Android account classes; rebuild on the current release')


def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)


def sql(query):
    return subprocess.check_output(['docker', 'exec', 'funnyworker-postgres-1', 'psql',
        '-U', 'funnyworker', '-d', 'funnyworker_management', '-v', 'ON_ERROR_STOP=1', '-Atc', query], text=True).strip()


def client_state():
    return sql("select row_to_json(c)::text from sys_client c where client_id='" + CLIENT + "'")


def healthy(wait=False):
    for attempt in range(60 if wait else 1):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8765/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError):
            pass
        if wait:
            time.sleep(1)
    raise RuntimeError('Business health is not UP')


def restart():
    run(['docker', 'compose', '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'compose.yml'),
         'up', '-d', '--no-deps', '--force-recreate', 'business'], cwd=ROOT)


def replace(source, destination):
    temporary = destination.with_name(destination.name + '.android-next')
    if temporary.exists():
        raise RuntimeError('An earlier deployment temporary file exists; inspect it first')
    try:
        shutil.copy2(source, temporary)
        with temporary.open('rb') as stream:
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--sql', type=Path, required=True)
    parser.add_argument('--expected-release', required=True)
    parser.add_argument('--release', required=True, help='Unique release directory name from the deployment plan')
    parser.add_argument('--expected-current-sha256', required=True)
    parser.add_argument('--candidate-sha256', required=True)
    parser.add_argument('--sql-sha256', required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{0,95}', args.release):
        parser.error('Release must be a single directory name')
    stage = ROOT / 'staging' / args.release
    backup = ROOT / 'backups' / args.release
    if stage.exists() or backup.exists():
        raise RuntimeError('Release staging or backup already exists; inspect before retrying')
    for digest in (args.expected_current_sha256, args.candidate_sha256, args.sql_sha256):
        if not re.fullmatch('[a-f0-9]{64}', digest):
            parser.error('Require full lowercase SHA-256 values from the reviewed release plan')
    if (ROOT / 'current-release').read_text().strip() != args.expected_release:
        raise RuntimeError('Current release changed; inspect it again')
    validate(ROOT / 'business.jar', args.candidate, args.expected_current_sha256, args.candidate_sha256)
    if sha(args.sql) != args.sql_sha256:
        raise RuntimeError('Identity SQL digest differs from plan')
    healthy()
    state = client_state()
    run(['docker', 'compose', '--env-file', str(ROOT / '.env'), '-f', str(ROOT / 'compose.yml'), 'config', '--quiet'], cwd=ROOT)
    plan = {'previousRelease': args.expected_release, 'currentSha256': args.expected_current_sha256,
            'candidateSha256': args.candidate_sha256, 'migrationChanges': False,
            'release': args.release, 'androidClientExists': bool(state), 'applied': False}
    if not args.apply:
        print(json.dumps(plan))
        return
    if os.geteuid() != 0:
        parser.error('--apply requires root on the verified international host')
    # Existing root directory is required; never create a guessed installation.
    with (ROOT / 'android-deployment.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if (ROOT / 'current-release').read_text().strip() != args.expected_release:
            raise RuntimeError('Release changed while acquiring lock')
        validate(ROOT / 'business.jar', args.candidate, args.expected_current_sha256, args.candidate_sha256)
        stage.mkdir(parents=True, mode=0o700)
        shutil.copy2(args.candidate, stage / 'business.jar')
        shutil.copy2(args.sql, stage / 'ruoyi-android-session.sql')
        plan['businessSha256'] = sha(stage / 'business.jar')
        plan['identitySha256'] = sha(ROOT / 'identity.jar')
        plan['expectedMigration'] = subprocess.check_output(['docker', 'exec', 'funnyworker-postgres-1',
            'psql', '-U', 'funnyworker', '-d', 'funnyworker_business', '-Atc',
            'select version from flyway_schema_history where success order by installed_rank desc limit 1'], text=True).strip()
        with zipfile.ZipFile(stage / 'business.jar') as archive:
            plan['adminIndexSha256'] = hashlib.sha256(archive.read('BOOT-INF/classes/static/admin/index.html')).hexdigest()
        (stage / 'plan.json').write_text(json.dumps(plan, indent=2) + '\n')
        backup.mkdir(parents=True, mode=0o700)
        for name in ('business.jar', 'current-release'):
            shutil.copy2(ROOT / name, backup / name)
        for database in ('funnyworker_business', 'funnyworker_management'):
            dump = backup / ('business.dump' if database == 'funnyworker_business' else 'management.dump')
            with dump.open('wb') as output:
                run(['docker', 'exec', 'funnyworker-postgres-1', 'pg_dump', '-U', 'funnyworker', '-Fc', database], stdout=output)
            dump.chmod(0o600)
            with dump.open('rb') as source:
                run(['docker', 'exec', '-i', 'funnyworker-postgres-1', 'pg_restore', '--list'], stdin=source, stdout=subprocess.DEVNULL)
        plan['backup'] = str(backup)
        switched = False
        try:
            if (ROOT / 'current-release').read_text().strip() != args.expected_release:
                raise RuntimeError('Release changed during backup; no service replacement attempted')
            validate(ROOT / 'business.jar', stage / 'business.jar', args.expected_current_sha256, args.candidate_sha256)
            if sha(stage / 'ruoyi-android-session.sql') != args.sql_sha256:
                raise RuntimeError('Staged identity SQL digest differs')
            with (stage / 'ruoyi-android-session.sql').open('rb') as source:
                run(['docker', 'exec', '-i', 'funnyworker-postgres-1', 'psql', '-U', 'funnyworker',
                     '-d', 'funnyworker_management', '-v', 'ON_ERROR_STOP=1', '--single-transaction'], stdin=source, stdout=subprocess.DEVNULL)
            first = client_state()
            # Verify idempotence in the real schema without printing the client row.
            with (stage / 'ruoyi-android-session.sql').open('rb') as source:
                run(['docker', 'exec', '-i', 'funnyworker-postgres-1', 'psql', '-U', 'funnyworker',
                     '-d', 'funnyworker_management', '-v', 'ON_ERROR_STOP=1', '--single-transaction'], stdin=source, stdout=subprocess.DEVNULL)
            if not first or first != client_state() or (state and first != state):
                raise RuntimeError('Client policy changed or SQL was not idempotent')
            validate(ROOT / 'business.jar', stage / 'business.jar', args.expected_current_sha256, args.candidate_sha256)
            if (ROOT / 'current-release').read_text().strip() != args.expected_release:
                raise RuntimeError('Release changed during identity setup')
            switched = True
            replace(stage / 'business.jar', ROOT / 'business.jar')
            restart()
            healthy(wait=True)
            marker = stage / 'new-release'
            marker.write_text(args.release + '\n')
            replace(marker, ROOT / 'current-release')
            plan.update(applied=True, health='UP', identitySqlIdempotent=True)
        except BaseException:
            if switched:
                replace(backup / 'business.jar', ROOT / 'business.jar')
                replace(backup / 'current-release', ROOT / 'current-release')
                restart()
                healthy(wait=True)
            plan.update(applied=False, serviceRestored=True,
                        identityRollback='Additive client row retained; no account data deleted')
            (backup / 'result.json').write_text(json.dumps(plan, indent=2) + '\n')
            raise
        (backup / 'result.json').write_text(json.dumps(plan, indent=2) + '\n')
        (stage / 'journal.json').write_text(json.dumps(plan, indent=2) + '\n')
        print(json.dumps(plan))


if __name__ == '__main__':
    main()
