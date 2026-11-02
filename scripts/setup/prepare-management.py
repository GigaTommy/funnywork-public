#!/usr/bin/env python3
"""Install locked management dependencies from cache, then build and check the application."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / 'apps/management'
CACHE_MISS = re.compile(r'\bERR_PNPM_(?:NO_OFFLINE_META|NO_OFFLINE_TARBALL|CANNOT_DOWNLOAD_BINARY_OFFLINE)\b')


def install_dependencies(cli, environment, run=subprocess.run):
    args = [*cli, 'install', '--frozen-lockfile', '--prefer-offline', '--config.verify-store-integrity=true']
    cached = run([*args, '--offline'], cwd=PROJECT, env=environment, capture_output=True, text=True, encoding='utf-8', errors='replace')
    print(cached.stdout or '', end='', flush=True)
    print(cached.stderr or '', end='', file=sys.stderr, flush=True)
    if cached.returncode == 0:
        print('Management dependencies: verified locked packages reused offline.', flush=True)
        return
    if not CACHE_MISS.search((cached.stdout or '') + (cached.stderr or '')):
        raise subprocess.CalledProcessError(cached.returncode, cached.args)
    print('Management dependencies: required cache entry missing; fetching from the official registry.', flush=True)
    run(args, cwd=PROJECT, env=environment, check=True)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    local_cli = ROOT / 'apps/desktop/node_modules/pnpm/bin/pnpm.cjs'
    if local_cli.is_file():
        cli = ['node', str(local_cli)]
    else:
        executable = shutil.which('pnpm.cmd' if os.name == 'nt' else 'pnpm')
        if executable is None:
            raise RuntimeError('Install pnpm 11.7.0 before preparing management.')
        cli = [executable]
    environment = {key: value for key, value in os.environ.items() if key.lower() not in {
        'npm_config_registry', 'npm_config_store_dir', 'npm_config_verify_deps_before_run',
        'pnpm_config_verify_deps_before_run',
    }}
    environment.update(CI='true', npm_config_registry='https://registry.npmjs.org',
                       npm_config_verify_deps_before_run='error', pnpm_config_verify_deps_before_run='error')
    # Existing pnpm 11 metadata records the verified store used by this checkout.
    modules = PROJECT / 'node_modules/.modules.yaml'
    selection = ROOT / '.local' / ('management-pnpm-store-' + sys.platform)
    store = os.environ.get('FUNNYWORK_PNPM_STORE_DIR')
    if store is None and selection.is_file():
        store = selection.read_text(encoding='utf-8').strip()
    if store is None and modules.is_file():
        metadata = json.loads(modules.read_text(encoding='utf-8'))
        stored = metadata.get('storeDir')
        if isinstance(stored, str):
            recorded = Path(stored)
            store = str(recorded.parent if re.fullmatch(r'v[0-9]+', recorded.name) else recorded)
    if store is not None:
        if not Path(store).is_absolute():
            raise ValueError('FUNNYWORK_PNPM_STORE_DIR must be an absolute path.')
        environment['npm_config_store_dir'] = store
        selection.parent.mkdir(parents=True, exist_ok=True)
        selection.write_text(store, encoding='utf-8')
    cli += ['--config.registry=https://registry.npmjs.org', '--config.verify-deps-before-run=error']
    if store is not None:
        cli.append('--config.store-dir=' + store)
    version = subprocess.run([*cli, '--version'], cwd=PROJECT, env=environment, check=True, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if version.stdout.strip() != '11.7.0':
        raise RuntimeError('Management requires pnpm 11.7.0.')
    install_dependencies(cli, environment)
    for command in ['build:prod', 'typecheck']:
        subprocess.run([*cli, 'run', command], cwd=PROJECT, env=environment, check=True)
    subprocess.run([sys.executable, str(ROOT / 'scripts/checks/check-management-artifact.py')], cwd=ROOT, env=environment, check=True)


if __name__ == '__main__':
    main()
