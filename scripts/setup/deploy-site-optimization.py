#!/usr/bin/env python3
"""Deploy public website assets with a backed-up, reversible Nginx route update."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, re, shutil, subprocess, tarfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('mode',choices=['stage','activate','status','rollback'])
parser.add_argument('region',choices=['cn','com'])
parser.add_argument('--bundle',type=Path)
args=parser.parse_args()
tag='opt-20261027'
base=Path('/srv/funnywork');site=base/'site'
backup=base/'backups'/(tag+'-'+args.region)
release=base/'releases'/('site-'+tag+'-'+args.region)
config=Path('/etc/nginx/sites-available')/('funnywork-cn.conf' if args.region=='cn' else 'funnyworker')
state=backup/'state.json'

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def snapshot(path):return {str(p.relative_to(path)):sha(p) for p in sorted(path.rglob('*')) if p.is_file()}
def save(value):state.write_text(json.dumps(value,indent=2)+'\n')
def switch(target):
    temp=base/('.site-'+tag+'-'+args.region)
    assert not temp.exists() and not temp.is_symlink()
    os.symlink(target,temp);os.replace(temp,site)
def nginx():subprocess.run(['nginx','-t'],check=True)
def reload():subprocess.run(['systemctl','reload','nginx'],check=True)

if args.mode=='stage':
    assert args.bundle and site.is_symlink() and config.is_file()
    assert not backup.exists() and not release.exists()
    with tarfile.open(args.bundle) as archive:
        members=archive.getmembers();assert all(m.isfile() for m in members)
        assert len({m.name for m in members})==len(members)
        plan=json.load(archive.extractfile('release.json'))
        assert plan['region']==args.region and plan['manifestSha256']==sha(site/'downloads.json')
        files=plan['files'];assert {m.name for m in members}==set(files)|{'release.json'}
        allowed={n+s+'.html' for n in ['index','solutions','quarterly-review','security','privacy','download','guide'] for s in ['','.en']}|{'site.js','search.js','downloads.js','styles.css','download.css','robots.txt','sitemap.xml'}
        assert all(n in allowed or re.fullmatch(r'static/[A-Za-z0-9_-]+[.][a-f0-9]{16}[.](js|css|png|jpg|webp|mp4)',n) for n in files)
        assert allowed.issubset(files)
        content={n:archive.extractfile(n).read() for n in files}
        assert all(hashlib.sha256(b).hexdigest()==files[n] for n,b in content.items())
    spec=importlib.util.spec_from_file_location('public_routes',Path(__file__).with_name('update-site-performance.py'))
    updater=importlib.util.module_from_spec(spec);spec.loader.exec_module(updater)
    before=config.read_bytes();candidate=updater.render(before.decode()).encode()
    assert candidate!=before,'Optimization already present; inspect before redeploying'
    backup.mkdir(parents=True)
    shutil.copytree(site.resolve(),backup/'site-snapshot',symlinks=True)
    shutil.copytree(site.resolve(),release,symlinks=True)
    (backup/'nginx.conf').write_bytes(before);(backup/'nginx-candidate.conf').write_bytes(candidate)
    shutil.copy2(__file__,backup/'deploy.py');shutil.copy2(Path(__file__).with_name('update-site-performance.py'),backup/'update-site-performance.py')
    for n,b in content.items():
        p=release/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);p.chmod(0o644)
    data={'status':'staged','region':args.region,'previousSite':os.readlink(site),'release':str(release),'configBefore':sha(config),'configAfter':sha(backup/'nginx-candidate.conf'),'original':snapshot(site.resolve()),'overlay':files,'originalBytes':sum(p.stat().st_size for p in site.resolve().rglob('*') if p.is_file()),'manifestSha256':plan['manifestSha256']}
    save(data);print('OPT_STAGED',args.region,'files='+str(len(files)),'downloadsPreserved=true')
elif args.mode=='activate':
    data=json.loads(state.read_text());assert data['status']=='staged'
    assert sha(config)==data['configBefore'] and snapshot(site.resolve())==data['original']
    assert all(sha(release/n)==h for n,h in data['overlay'].items())
    try:
        config.write_bytes((backup/'nginx-candidate.conf').read_bytes());nginx()
        switch(str(release));reload()
        assert all(sha(site/n)==h for n,h in data['original'].items() if n not in data['overlay'])
    except BaseException:
        config.write_bytes((backup/'nginx.conf').read_bytes())
        if site.resolve()==release.resolve():switch(data['previousSite'])
        nginx();reload();raise
    data['status']='active';data['siteDeltaBytes']=sum(p.stat().st_size for p in site.resolve().rglob('*') if p.is_file())-data['originalBytes'];save(data)
    print('OPT_ACTIVE',args.region,'siteDeltaBytes='+str(data['siteDeltaBytes']))
elif args.mode=='rollback':
    data=json.loads(state.read_text());assert data['status']=='active' and site.resolve()==release.resolve()
    assert sha(config)==data['configAfter']
    config.write_bytes((backup/'nginx.conf').read_bytes());nginx();switch(data['previousSite']);reload()
    data['status']='rolled-back';save(data);print('OPT_ROLLED_BACK',args.region)
else:
    data=json.loads(state.read_text());assert data['status']=='active' and site.resolve()==release.resolve()
    assert sha(config)==data['configAfter']
    assert all(sha(site/n)==h for n,h in data['overlay'].items())
    assert all(sha(site/n)==h for n,h in data['original'].items() if n not in data['overlay'])
    assert sha(site/'downloads.json')==data['manifestSha256']
    print('OPT_VERIFIED',args.region,'protectedFilesAndManifestPreserved=true')
