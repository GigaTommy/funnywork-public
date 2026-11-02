#!/usr/bin/env python3
"""Preview or apply the streaming locations to an existing regional Nginx site."""
import argparse
from datetime import datetime, timezone
import difflib
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile


ANCHOR = '    location ^~ /api/account/ {'


def stream_location(path):
    return (
        f'    location ^~ /api/account/{path}/ {{\n'
        '        client_max_body_size 16m;\n'
        f'        limit_except {"GET POST" if path == "inference" else "GET POST PUT PATCH DELETE"} {{ deny all; }}\n'
        '        proxy_read_timeout 660s;\n'
        '        proxy_send_timeout 660s;\n'
        '        proxy_pass https://$fw_cn_origin$request_uri;\n'
        '    }\n'
    )


def prepare(source):
    """Refuse unfamiliar sites and conflicting locations instead of replacing them."""
    host = os.environ.get('FUNNYWORK_REGIONAL_HOST', '')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)*', host):
        raise ValueError('Set FUNNYWORK_REGIONAL_HOST to the reviewed regional hostname')
    required = (
        'server_name ' + host + ';',
        'set $fw_cn_origin funnywork.funnypixelsapp.com;',
        'proxy_ssl_verify on;',
        'proxy_buffering off;',
        'proxy_request_buffering off;',
        'proxy_next_upstream off;',
    )
    if not all(item in source for item in required) or source.count(ANCHOR) != 1:
        raise ValueError('Unrecognized site configuration; inspect the active site before applying')
    additions = ''
    for path in ('inference', 'chats'):
        block = stream_location(path)
        if f'/api/account/{path}/' in source:
            if source.count(block) != 1:
                raise ValueError(f'Existing {path} location differs; manual review required')
        else:
            additions += block
    # A proxy_pass prefix ending in / otherwise redirects its bare root before proxying.
    root_block = stream_location('chats').replace('location ^~ /api/account/chats/', 'location = /api/account/chats', 1)
    if 'location = /api/account/chats ' in source:
        if source.count(root_block) != 1:
            raise ValueError('Existing chats root location differs; manual review required')
    else:
        additions += root_block
    return source.replace(ANCHOR, additions + ANCHOR, 1)


def replace(path, data, metadata):
    """Atomically replace the resolved file while retaining owner and permissions."""
    fd, temporary = tempfile.mkstemp(prefix='.funnywork-cn-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
            os.fchmod(output.fileno(), metadata.st_mode & 0o7777)
            os.fchown(output.fileno(), metadata.st_uid, metadata.st_gid)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path,
                        default=Path('/etc/nginx/sites-enabled/funnywork-cn.conf'))
    parser.add_argument('--apply', action='store_true', help='Back up, validate and reload Nginx')
    args = parser.parse_args()
    path = args.config.resolve(strict=True)
    original = path.read_bytes()
    source = original.decode('utf-8')
    updated = prepare(source)
    if source == updated:
        print('Streaming locations already match; no files changed or service reloaded')
        return
    print(''.join(difflib.unified_diff(source.splitlines(True), updated.splitlines(True),
                                     fromfile=str(path), tofile=str(path))), end='')
    if not args.apply:
        print('Preview only. Add --apply on the regional server to deploy.')
        return
    if os.geteuid() != 0:
        parser.error('--apply requires root on the regional server')
    subprocess.run(['nginx', '-t'], check=True)
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    backup_directory = Path('/var/backups/funnywork-nginx')
    backup_directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup = backup_directory / (path.name + '.before-streams-' + stamp)
    shutil.copy2(path, backup)
    metadata = path.stat()
    print(f'Backup: {backup}')
    try:
        replace(path, updated.encode('utf-8'), metadata)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    except BaseException:
        replace(path, original, metadata)
        subprocess.run(['nginx', '-t'], check=True)
        subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
        print('Original configuration restored')
        raise
    print('Streaming locations deployed; verify public APIs and authenticated streaming next')


if __name__ == '__main__':
    main()
