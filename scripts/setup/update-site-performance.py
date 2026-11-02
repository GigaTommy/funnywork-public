#!/usr/bin/env python3
"""Add only public hashed assets and guide routes to an existing website server."""
from pathlib import Path
import argparse, difflib, re

START = '# BEGIN FunnyWork public asset optimization'
END = '# END FunnyWork public asset optimization'
LEGACY_ASSET_PATTERN = r'^/static/[A-Za-z0-9_-]+[.][a-f0-9]{16}[.](js|css|png|jpg|webp|mp4)$'
PUBLIC_ASSET_PATTERN = r'^/static/([A-Za-z0-9_-]+[.][a-f0-9]{16}[.](js|css|png|jpg|webp|mp4)|funnywork-(LICENSE|COMMERCIAL-LICENSE-txt|THIRD_PARTY_NOTICES-md)[.][a-f0-9]{16}[.]txt)$'


def render(original):
    if START in original:
        if original.count(START) != 1 or original.count(END) != 1:
            raise ValueError('Conflicting optimization block')
        if original.count(PUBLIC_ASSET_PATTERN) == 1:
            return original
        if original.count(LEGACY_ASSET_PATTERN) != 1:
            raise ValueError('Expected one known public asset allowlist')
        return original.replace(LEGACY_ASSET_PATTERN, PUBLIC_ASSET_PATTERN, 1)
    anchor = 'location = /solutions.html {'
    if original.count(anchor) != 1:
        raise ValueError('Expected one existing public solutions route')
    start=original.index(anchor)
    depth=0; end=None; quote=None; escaped=False
    for i in range(original.index('{',start),len(original)):
        char=original[i]
        if escaped: escaped=False; continue
        if char=='\\': escaped=True; continue
        if quote:
            if char==quote: quote=None
            continue
        if char in ['"',"'"]: quote=char; continue
        if char=='{': depth+=1
        elif char=='}':
            depth-=1
            if depth==0: end=i+1; break
    if end is None: raise ValueError('Unbalanced public route')
    location=original[start:end]
    guides = '\n'.join(location.replace('/solutions.html', route, 1)
                       for route in ['/guide.html', '/guide.en.html'])
    static = '''location ^~ /static/ {
    root /srv/funnywork/site;
    if ($uri !~ "^/static/[A-Za-z0-9_-]+[.][a-f0-9]{16}[.](js|css|png|jpg|webp|mp4)$") { return 404; }
    try_files $uri =404;
    autoindex off;
    limit_except GET { deny all; }
    gzip on;
    gzip_vary on;
    gzip_min_length 256;
    gzip_types text/css application/javascript application/json application/xml text/xml image/svg+xml;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    add_header Strict-Transport-Security "max-age=31536000" always;
}'''
    static = static.replace(LEGACY_ASSET_PATTERN, PUBLIC_ASSET_PATTERN, 1)
    block = START + '\n' + static + '\n' + guides + '\n' + END + '\n    '
    return original.replace(anchor, block + anchor, 1)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    args = parser.parse_args()
    original = args.config.read_text()
    updated = render(original)
    print(''.join(difflib.unified_diff(original.splitlines(True), updated.splitlines(True),
                                     fromfile=str(args.config), tofile='candidate')), end='')
