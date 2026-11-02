#!/usr/bin/env python3
"""Export a fixed Git commit without repository history or local files (Python 3.12+)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
# A one-off private build references commits intentionally absent from the public history.
EXCLUDED_PATHS = {'.github/workflows/linux-fixed-preview.yml'}
BLOCKED_PARTS = {'.git', '.local', '.venv', 'node_modules', '.desktop-build', '__pycache__'}
BLOCKED_SUFFIXES = {'.p12', '.pfx', '.jks', '.keystore', '.sqlite', '.sqlite3', '.db', '.dmg', '.apk', '.ipa'}


def check_member(name):
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or BLOCKED_PARTS.intersection(path.parts):
        raise ValueError('Non-public archive path: ' + name)
    if path.suffix.lower() in BLOCKED_SUFFIXES or path.name == '.env' or path.name.endswith('.env'):
        raise ValueError('Private configuration or generated artifact: ' + name)


def export_source(repository, ref, output):
    repository, output = Path(repository).resolve(), Path(output).resolve()
    releases = repository / '.local/releases'
    if not output.is_relative_to(releases) or output == releases:
        raise ValueError('Output must be a new directory below .local/releases')
    if output.exists():
        raise FileExistsError('Refusing to overwrite an existing source candidate')
    commit = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', '--verify', ref + '^{commit}'], text=True).strip()
    if len(commit) != 40 or any(c not in '0123456789abcdef' for c in commit):
        raise ValueError('Expected a full commit object')
    # git archive reads the commit, never the worktree, index, ignored files, or other refs.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='public-source-', dir=releases) as temporary:
        stage = Path(temporary)
        raw = stage / 'source.tar'
        subprocess.run(['git', '-C', str(repository), 'archive', '--format=tar', '--output', str(raw), commit], check=True)
        source = stage / 'source'
        source.mkdir()
        with tarfile.open(raw) as archive:
            members = [member for member in archive.getmembers() if member.name not in EXCLUDED_PATHS]
            for member in members:
                check_member(member.name)
            archive.extractall(source, members=members, filter='data')
        # A dangling or out-of-tree symlink cannot be shipped as an apparently complete source tree.
        records = []
        for path in sorted(source.rglob('*')):
            if path.is_symlink():
                if not path.resolve().is_relative_to(source) or not path.exists():
                    raise ValueError('Invalid source symlink: ' + str(path.relative_to(source)))
                target = os.readlink(path)
                records.append({'path': path.relative_to(source).as_posix(), 'symlink': target, 'sha256': hashlib.sha256(target.encode()).hexdigest()})
                continue
            if path.is_file():
                records.append({'path': path.relative_to(source).as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
        for required in ['LICENSE', 'COMMERCIAL-LICENSE.txt', 'THIRD_PARTY_NOTICES.md',
                         'apps/desktop/LICENSE', 'services/identity/LICENSE', 'apps/management/LICENSE',
                         'deploy/magpie/MAGPIE-LICENSE.txt']:
            if not (source / required).is_file():
                raise ValueError('Missing distribution notice: ' + required)
        metadata = {'schema': 1, 'sourceCommit': commit, 'historyIncluded': False, 'excludedPaths': sorted(EXCLUDED_PATHS), 'files': records}
        (stage / 'SOURCE-MANIFEST.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
        raw.unlink()
        with tarfile.open(stage / 'funnywork-source.tar.gz', 'w:gz') as archive:
            archive.add(source, arcname='funnywork', recursive=True)
        digest = hashlib.sha256((stage / 'funnywork-source.tar.gz').read_bytes()).hexdigest()
        (stage / 'SHA256SUMS').write_text(digest + '  funnywork-source.tar.gz\n')
        stage.rename(output)
    return commit


def main():
    if sys.version_info < (3, 12):
        raise SystemExit('Python 3.12+ is required for the data-only archive extraction filter')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', required=True, help='Reviewed committed source revision')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    print(export_source(ROOT, args.ref, args.output))


if __name__ == '__main__':
    main()
