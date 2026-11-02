#!/usr/bin/env python3
"""Publish a Baidu ownership tag while preserving every other site file."""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, sys

region, code = sys.argv[1:3]
assert region in ('cn', 'com') and re.fullmatch(r'codeva-[A-Za-z0-9_-]+', code)
base = Path('/srv/funnywork')
site = base / 'site'
backup = base / 'backups' / ('verify-20261027-' + region)
release = base / 'releases' / ('site-verify-20261027-' + region)
config = Path('/etc/nginx/sites-available') / ('funnywork-cn.conf' if region == 'cn' else 'funnyworker')
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def snapshot(path):
    return {str(p.relative_to(path)): digest(p) for p in path.rglob('*') if p.is_file()}
def switch(target):
    temp = base / ('.site-verify-20261027-' + region)
    assert not temp.exists() and not temp.is_symlink()
    os.symlink(target, temp)
    os.replace(temp, site)
assert site.is_symlink() and not backup.exists() and not release.exists()
original = snapshot(site.resolve())
previous = os.readlink(site)
config_hash = digest(config)
pages = {}
tag = '<meta name="baidu-site-verification" content="' + code + '">'
for name in ('index.html', 'index.en.html'):
    html = (site / name).read_text()
    assert html.count('</head>') == 1 and 'name="baidu-site-verification"' not in html
    pages[name] = html.replace('</head>', tag + '</head>')
backup.mkdir(parents=True)
shutil.copytree(site.resolve(), backup / 'site-snapshot', symlinks=True)
shutil.copy2(config, backup / 'nginx.conf')
shutil.copytree(site.resolve(), release, symlinks=True)
for name, html in pages.items():
    (release / name).write_text(html)
    assert (release / name).read_text().replace(tag, '') == (site / name).read_text()
assert all(digest(release / n) == h for n, h in original.items() if n not in pages)
assert snapshot(site.resolve()) == original and digest(config) == config_hash
subprocess.run(['nginx', '-t'], check=True)
try:
    switch(str(release))
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    assert digest(config) == config_hash
    assert all(digest(site / n) == h for n, h in original.items() if n not in pages)
except BaseException:
    if site.resolve() == release.resolve():
        switch(previous)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
    raise
state = {'previousSite': previous, 'release': str(release), 'configSha256': config_hash,
         'original': original, 'code': code, 'changedFiles': list(pages),
         'siteDeltaBytes': sum((site / n).stat().st_size - (backup / 'site-snapshot' / n).stat().st_size for n in pages)}
(backup / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
print('VERIFICATION_TAG_ACTIVE', region, 'protectedFilesAndConfigPreserved=true', 'siteDeltaBytes=' + str(state['siteDeltaBytes']))
