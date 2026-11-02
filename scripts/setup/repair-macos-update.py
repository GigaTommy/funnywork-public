#!/usr/bin/env python3
"""Recover a signed Mac update on its installation volume, retaining a verified rollback.

Uses the shipped, signed Squirrel helper directly rather than its launchd job so
that removable-volume access is attributed to the invoking application. Never
changes macOS privacy settings or disables signature verification.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import subprocess


def version(app):
    with (app / 'Contents/Info.plist').open('rb') as stream:
        info = plistlib.load(stream)
    if info['CFBundleIdentifier'] != 'com.funnyworker.desktop':
        raise ValueError('Unexpected application identity')
    return info['CFBundleShortVersionString']


def verify(app):
    subprocess.run(['codesign', '--verify', '--deep', '--strict', '-R',
                    '=identifier "com.funnyworker.desktop" and certificate leaf[subject.OU] = "VL3HPHK35K"', str(app)], check=True)
    return version(app)


def copy_verified(source, dest):
    if dest.exists():
        raise FileExistsError(dest)
    subprocess.run(['ditto', str(source), str(dest)], check=True)
    if verify(dest) != verify(source):
        raise ValueError('Copied application version mismatch')


def install(source, target, evidence, fail_after_backup=False):
    source, target, evidence = source.resolve(), target.resolve(), evidence.resolve()
    if target == source or target in source.parents or source in target.parents:
        raise ValueError('Source and target must be independent')
    old_version, new_version = verify(target), verify(source)
    if old_version == new_version:
        raise ValueError('Target already has this version')
    if target.stat().st_dev != evidence.parent.stat().st_dev:
        raise ValueError('Backup must be on the installation volume')
    running = subprocess.run(['pgrep', '-fl', str(target / 'Contents/MacOS/funnywork')], capture_output=True, text=True)
    if running.returncode == 0:
        raise RuntimeError('Quit the target application before recovery')
    evidence.mkdir(mode=0o700)
    backup = evidence / 'rollback.app'
    candidate = evidence / 'candidate.app'
    copy_verified(target, backup)
    copy_verified(source, candidate)
    probe = target.parent / ('.funnywork-write-probe-' + str(os.getpid()))
    probe.mkdir(mode=0o700)
    probe.rmdir()
    (evidence / 'state.json').write_text(json.dumps({'source': str(source), 'target': str(target), 'oldVersion': old_version, 'newVersion': new_version, 'backup': str(backup)}, indent=2))
    tmp = evidence / 'tmp'
    tmp.mkdir(mode=0o700)
    request = evidence / 'request.json'
    request.write_text(json.dumps({'launchAfterInstallation': False, 'updateBundleURL': candidate.as_uri() + '/', 'useUpdateBundleName': True, 'bundleIdentifier': 'com.funnyworker.desktop', 'targetBundleURL': target.as_uri() + '/'}))
    helper = backup / 'Contents/Frameworks/Squirrel.framework/Resources/ShipIt'
    subprocess.run(['launchctl', 'remove', 'com.funnyworker.desktop.ShipIt'], capture_output=True)
    try:
        if fail_after_backup:
            # Exercise the real recovery path after the target has been moved.
            target.rename(evidence / 'failure-target.app')
            raise RuntimeError('Injected failure after moving the old application')
        env = dict(os.environ, TMPDIR=str(tmp) + '/', TMP=str(tmp), TEMP=str(tmp))
        with (evidence / 'installer.log').open('w') as log:
            subprocess.run([str(helper), 'com.funnyworker.desktop.externalupdate', str(request)], env=env, stdout=log, stderr=log, check=True, timeout=180)
        if verify(target) != new_version:
            raise RuntimeError('Installer did not install the expected version')
        (evidence / 'result.json').write_text(json.dumps({'status': 'installed', 'version': new_version}))
    except BaseException:
        recovery = evidence / 'restore.app'
        copy_verified(backup, recovery)
        if target.exists():
            target.rename(evidence / 'failed-install.app')
        recovery.rename(target)
        verify(target)
        (evidence / 'result.json').write_text(json.dumps({'status': 'rolled-back', 'version': old_version}))
        raise
    return new_version


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--target', required=True, type=Path)
    p.add_argument('--evidence', required=True, type=Path)
    p.add_argument('--test-rollback', action='store_true', help='Inject a failure and verify recovery; use only with isolated test apps')
    a = p.parse_args()
    print(install(a.source, a.target, a.evidence, a.test_rollback))


if __name__ == '__main__':
    main()
