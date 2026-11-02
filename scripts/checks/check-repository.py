#!/usr/bin/env python3
"""Check maintained repository boundaries and source references without dependencies."""
from pathlib import Path
import ast
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
errors = []

# Run in existing repository CI on both Linux and Windows; inspect the Git index,
# since .gitignore alone does not protect files that are already tracked.
subprocess.run([sys.executable, str(ROOT / 'scripts/checks/check-git-hygiene.py')], check=True)
subprocess.run([sys.executable, str(ROOT / 'scripts/checks/check-config-secrets.py')], check=True)


def require(condition, message):
    if not condition:
        errors.append(message)


for relative in (
    'apps/desktop/package.json', 'apps/desktop/pnpm-lock.yaml',
    'apps/management/package.json', 'apps/management/pnpm-lock.yaml',
    'packages/management-react/package.json', 'services/business/pom.xml',
    'services/identity/pom.xml', 'funnyworker/desktop.py',
    'LICENSE', 'THIRD_PARTY_NOTICES.md', 'CONTRIBUTING.md',
    'scripts/setup/compose.windows.yml', 'scripts/setup/ruoyi-business-menus.sql',
):
    require((ROOT / relative).is_file(), f'Missing component or entry: {relative}')

for component in ('apps/desktop', 'apps/management', 'services/identity'):
    require(not (ROOT / component / '.git').exists(), f'Nested Git checkout: {component}')

for old in ('harness', 'management', 'management-ruoyi', 'server', 'upstream'):
    require(not (ROOT / old).exists(), f'Obsolete source directory: {old}')

# Check actual cross-component imports, including extensionless TypeScript imports.
for source in (ROOT / 'apps/management/src/views/funnyworker').glob('*'):
    if not source.is_file():
        continue
    for reference in re.findall(r"(?:from\s+|import\s*)['\"]([^'\"]+)['\"]", source.read_text(encoding="utf-8")):
        if not reference.startswith('.'):
            continue
        target = source.parent / reference.split('?')[0]
        require(any(p.exists() for p in (target, Path(str(target) + '.ts'), Path(str(target) + '.tsx'))),
                f'Unresolved source import in {source.relative_to(ROOT)}: {reference}')

# Check maintained project documentation against the Git index. A local archive
# can exist on the author's machine while being absent from a fresh clone.
tracked = set(filter(None, subprocess.check_output(
    ['git', '-C', str(ROOT), 'ls-files', '-z']).decode('utf-8').split('\0')))
maintained_docs = {
    'README.md', 'README.en.md', 'CONTRIBUTING.md', 'THIRD_PARTY_NOTICES.md', 'DESIGN.md',
    'apps/desktop/FUNNYWORKER.md',
}
maintained_docs.update(path for path in tracked if path.endswith('.md') and (
    path.startswith(('apps/android/', 'apps/ios/', 'services/business/', 'deploy/',
                     'apps/management/', 'manuals/', 'designs/',
                     'packages/management-react/'))))
# Project-owned .en.md documents use reciprocal language navigation. Harness
# retains its own .md/.zh.md pairing rules inside the desktop workspace.
for english in sorted(path for path in tracked if path.endswith('.en.md')
                      and not path.startswith('apps/desktop/')):
    chinese = english[:-6] + '.md'
    maintained_docs.update((english, chinese))
    require(chinese in tracked, f'Missing Chinese document for {english}')
    if (ROOT / chinese).is_file() and (ROOT / english).is_file():
        zh_header = ' '.join((ROOT / chinese).read_text(encoding='utf-8').splitlines()[:8])
        en_header = ' '.join((ROOT / english).read_text(encoding='utf-8').splitlines()[:8])
        require(f'[English]({Path(english).name})' in zh_header,
                f'Missing English navigation in {chinese}')
        require(f'[简体中文]({Path(chinese).name})' in en_header,
                f'Missing Chinese navigation in {english}')

for relative in sorted(maintained_docs):
    source = ROOT / relative
    require(source.is_file(), f'Missing maintained documentation: {relative}')
    if not source.is_file():
        continue
    for reference in re.findall(r'\]\(([^)]+)\)', source.read_text(encoding="utf-8")):
        if re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', reference) or reference.startswith(('#', '/')):
            continue
        target = (source.parent / unquote(urlsplit(reference).path)).resolve()
        try:
            target_relative = target.relative_to(ROOT).as_posix()
        except ValueError:
            require(False, f'Documentation link leaves the repository in {relative}: {reference}')
            continue
        in_index = target_relative in tracked or any(
            path.startswith(target_relative.rstrip('/') + '/') for path in tracked)
        require(target.exists() and in_index,
                f'Broken or untracked documentation link in {relative}: {reference}')

# Validate the launcher's source workspace without executing model preparation.
module = ast.parse((ROOT / 'funnyworker/desktop.py').read_text(encoding="utf-8"))
harness = next(node.value for node in module.body
               if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'HARNESS' for t in node.targets))
require(isinstance(harness, ast.BinOp) and isinstance(harness.right, ast.Constant)
        and harness.right.value == 'apps/desktop', 'Desktop launcher points outside apps/desktop')

ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
pom = ET.parse(ROOT / 'services/business/pom.xml')
working = pom.find('.//m:workingDirectory', ns)
require(working is not None and working.text == '${project.basedir}/../..',
        'Business launch must keep repository-root runtime data')
for component in ('apps/desktop', 'apps/management', 'packages/management-react'):
    json.loads((ROOT / component / 'package.json').read_text(encoding="utf-8"))

# Entry wrappers must point to existing scripts; do not execute stateful setup checks.
for source in (ROOT / 'scripts').iterdir():
    if not source.is_file() or source.suffix not in {'.py', '.sh', '.command', '.ps1', '.mjs'}:
        continue
    for relative in re.findall(r'(?:setup|build|checks)/[\w][\w.-]*', source.read_text(encoding="utf-8").replace('\\', '/')):
        require((ROOT / 'scripts' / relative).is_file(), f'Broken wrapper: {source.name}: {relative}')

if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print('Repository layout, component imports, entry wrappers and maintained documentation: OK')
