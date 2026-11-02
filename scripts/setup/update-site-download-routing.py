#!/usr/bin/env python3
"""Preview or safely add version-independent APK routing to an existing website."""
import argparse
from datetime import datetime, timezone
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ANCHOR = 'location ^~ /dsh-desk/releases/'
START = '# BEGIN FunnyWork Android downloads\n'
END = '# END FunnyWork Android downloads\n'
APK_PATH = '^/dsh-desk/android/[0-9][A-Za-z0-9._-]*/FunnyWork-[0-9][A-Za-z0-9._-]*-android[.]apk$'


def prepare(source, host):
    """Insert one bounded download location; retain every existing byte and policy."""
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', host):
        raise ValueError('Expected a DNS hostname')
    if source.count(ANCHOR) != 1:
        raise ValueError('Expected one existing public installer location')
    anchor = source.index(ANCHOR)
    servers = list(re.finditer(r'(?m)^[ \t]*server[ \t]*\{', source[:anchor]))
    if not servers:
        raise ValueError('Missing public server block')
    server = source[servers[-1].start():anchor]
    if not re.search(r'listen\s+443\s+ssl\b', server) or f'server_name {host};' not in server:
        raise ValueError('Download location is not in the expected HTTPS server')
    if 'root /var/www/funnyworker-updates;' not in source[anchor:anchor + 400]:
        raise ValueError('Unexpected installer storage root')
    # Nginx does not inherit parent add_header declarations when a child adds headers.
    parent = re.split(r'(?m)^[ \t]*location\s', server, maxsplit=1)[0]
    headers = []
    for line in parent.splitlines():
        match = re.match(r'\s*add_header\s+(\S+)\s+.*;\s*$', line)
        if match and match.group(1).lower() not in {'cache-control', 'content-disposition', 'x-content-type-options'}:
            headers.append('    ' + line.strip() + '\n')
        elif 'add_header' in line and not match and not line.lstrip().startswith('#'):
            raise ValueError('Review multiline parent headers before deployment')
    block = (
        START + 'location ^~ /dsh-desk/android/ {\n'
        '    root /var/www/funnyworker-updates;\n'
        f'    if ($uri !~ "{APK_PATH}") {{ return 404; }}\n'
        '    try_files $uri =404;\n'
        '    autoindex off;\n'
        '    limit_except GET { deny all; }\n'
        '    default_type application/vnd.android.package-archive;\n'
        + ''.join(headers)
        + '    add_header X-Content-Type-Options nosniff always;\n'
        '    add_header Content-Disposition attachment;\n'
        '    add_header Cache-Control "public, max-age=31536000, immutable";\n'
        '}\n' + END
    )
    if START in source or END in source:
        if source.count(block) != 1:
            raise ValueError('Existing managed APK location differs')
        return source
    if re.search(r'location\s+(?:\^~\s+)?/dsh-desk/android/\s*\{', source):
        raise ValueError('Existing APK prefix requires review')
    position = source.rfind('\n', 0, anchor) + 1
    updated = source[:position] + block + source[position:]
    assert updated.replace(block, '', 1) == source
    return updated


def replace(path, body, metadata):
    fd, name = tempfile.mkstemp(prefix='.funnywork-apk-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(body)
            output.flush()
            os.fsync(output.fileno())
            os.fchmod(output.fileno(), metadata.st_mode & 0o7777)
            os.fchown(output.fileno(), metadata.st_uid, metadata.st_gid)
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--backup-dir', type=Path)
    parser.add_argument('--expect-sha256')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    path = args.config.resolve(strict=True)
    original = path.read_bytes()
    source = original.decode('utf8')
    updated = prepare(source, args.host)
    digest = hashlib.sha256(original).hexdigest()
    if source == updated:
        print(json.dumps({'host': args.host, 'unchanged': True, 'sha256': digest}))
        return
    print(''.join(difflib.unified_diff(source.splitlines(True), updated.splitlines(True), fromfile=str(path), tofile=str(path))), end='')
    if not args.apply:
        print('Preview only; use --apply with an expected hash and backup directory.')
        return
    if os.geteuid() != 0 or args.expect_sha256 != digest or not args.backup_dir or not args.backup_dir.is_absolute():
        parser.error('Apply requires root, the current --expect-sha256 and an absolute --backup-dir')
    subprocess.run(['nginx', '-t'], check=True)
    args.backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = args.backup_dir / (path.name + '.before-android-routing-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    shutil.copy2(path, backup)
    metadata = path.stat()
    assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'Configuration changed concurrently'
    try:
        replace(path, updated.encode('utf8'), metadata)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except BaseException:
        replace(path, original, metadata)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
        raise
    print(json.dumps({'host': args.host, 'applied': True, 'beforeSha256': digest, 'afterSha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'backup': str(backup), 'existingLocationsUnchanged': True}), flush=True)


if __name__ == '__main__':
    main()
