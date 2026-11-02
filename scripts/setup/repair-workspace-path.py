"""Offline repair of one existing DSH v2 workspace path; never edits session logs.

Close FunnyWork first. Dry run is the default; --apply requires an expected
workspace.json digest and creates a verified, private backup before replacement.
This is a stopped-client data repair, not a session-format migration.
"""
import argparse
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def assert_stopped():
    processes = subprocess.check_output(['ps', '-axo', 'comm='], text=True)
    if any('/funnywork.app/' in line or line.strip() == 'funnywork' for line in processes.splitlines()):
        raise RuntimeError('Close FunnyWork and its Host before offline repair')


def plan(raw, workspace_id):
    value = json.loads(raw)
    if value['unit'] != {'name': 'workspace', 'version': 2}:
        raise RuntimeError('Unsupported workspace storage format')
    state = value['global']
    if state.get('pendingMutation'):
        raise RuntimeError('Resolve pending registry mutation first')
    rows = value['tables']['workspaces']
    if len(set(state['workspaceIds'])) != len(state['workspaceIds']) or set(state['workspaceIds']) != set(rows):
        raise RuntimeError('Registry order and records disagree')
    record = rows[workspace_id]
    source = Path(record['path'])
    if not source.is_absolute():
        raise RuntimeError('Workspace path must be absolute')
    target = source.resolve(strict=True)
    if not target.is_dir():
        raise RuntimeError('Workspace target is not a directory')
    # Resolve every other record before writing: an unavailable volume is not
    # evidence that a potentially colliding workspace is safe to overwrite.
    for other_id, other in rows.items():
        if other_id != workspace_id and Path(other['path']).resolve(strict=True) == target:
            raise RuntimeError('Canonical path collision; no automatic merge')
    seen = set()
    for other in rows.values():
        for session_id in other['sessionIds']:
            if session_id in seen:
                raise RuntimeError('Duplicate session ownership')
            seen.add(session_id)
    result = copy.deepcopy(value)
    result['tables']['workspaces'][workspace_id]['path'] = str(target)
    return result


def inventory(home):
    paths = [p for folder in ('storages', 'sessions') for p in (home / folder).rglob('*') if p.is_file()]
    paths += [home / 'history-device-id']
    return {str(p.relative_to(home)): {'bytes': p.stat().st_size, 'sha256': digest(p.read_bytes())}
            for p in sorted(paths) if p.name != 'session.lock'}


def repair(home, workspace_id, expected, backup, apply=False):
    # Serialize maintenance callers on the original inode without creating a
    # persistent lock file. The Host does not share this lock: stopping it is
    # mandatory, and digest/inventory checks also detect intervening writes.
    with (home / 'storages/workspace.json').open('rb') as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        return repair_locked(home, workspace_id, expected, backup, apply)


def repair_locked(home, workspace_id, expected, backup, apply=False):
    home = home.resolve(strict=True)
    source = home / 'storages/workspace.json'
    assert_stopped()
    raw = source.read_bytes()
    if digest(raw) != expected:
        raise RuntimeError('Workspace digest changed; inspect current state before retrying')
    result = plan(raw, workspace_id)
    previous = json.loads(raw)
    before = previous['tables']['workspaces'][workspace_id]
    after = result['tables']['workspaces'][workspace_id]
    report = {'workspaceId': workspace_id, 'beforePath': before['path'], 'afterPath': after['path'],
              'originalSessionIds': before['sessionIds'], 'beforeSha256': digest(raw),
              'changed': before['path'] != after['path'], 'applied': False}
    if not apply or not report['changed']:
        return report
    session_directories = {p.name for p in (home / 'sessions').rglob('*') if p.is_dir()
                           and any(p.glob('session*.jsonl*'))}
    if not set(before['sessionIds']).issubset(session_directories):
        raise RuntimeError('Original session history is missing; nothing replaced')
    # All output must remain on an already-mounted external volume.
    if not os.path.ismount('/Volumes/SSD') or not backup.resolve().is_relative_to(Path('/Volumes/SSD')):
        raise RuntimeError('Backup must be on the mounted /Volumes/SSD volume')
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = inventory(home)
    for relative in manifest:
        dest = backup / 'account' / relative
        dest.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copy2(home / relative, dest)
        dest.chmod(0o600)
        if digest(dest.read_bytes()) != manifest[relative]['sha256']:
            raise RuntimeError('Backup verification failed')
    (backup / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (backup / 'RESTORE.txt').write_text(
        'Close FunnyWork and its Host. Compare current workspace.json with repair-report.json.\n'
        'For immediate rollback only, atomically restore account/storages/workspace.json, mode 0600.\n'
        'After new tasks exist, restore only the original record path; do not overwrite newer members.\n'
        'Session copies are evidence/last-resort recovery; never replace newer histories blindly.\n')
    assert_stopped()
    if inventory(home) != manifest or source.read_bytes() != raw:
        raise RuntimeError('Account changed during backup; nothing replaced')
    encoded = (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode()
    # Owner-only, same-volume, exclusive temporary file; fail before publication
    # leaves the original intact. A crashed process leaves the verified backup.
    descriptor, temporary = tempfile.mkstemp(prefix='.workspace-repair-', dir=source.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        assert_stopped()
        if source.read_bytes() != raw:
            raise RuntimeError('Concurrent workspace write; nothing replaced')
        os.replace(temporary, source)
        directory = os.open(source.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    report.update(applied=True, afterSha256=digest(source.read_bytes()), backup=str(backup))
    (backup / 'repair-report.json').write_text(json.dumps(report, indent=2) + '\n')
    # Only the path is changed; every original session and global field survives.
    if source.read_bytes() != encoded:
        raise RuntimeError('Published workspace verification failed; retain backup for inspection')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--account-home', required=True, type=Path)
    parser.add_argument('--workspace-id', required=True)
    parser.add_argument('--expected-sha256', required=True)
    parser.add_argument('--backup', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(repair(args.account_home, args.workspace_id, args.expected_sha256, args.backup, args.apply), indent=2))


if __name__ == '__main__':
    main()
