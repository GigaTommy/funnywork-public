"""Launch the customized Desktop with authenticated model provisioning."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess

from .model_config import private_write, protect_directory
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / 'apps/desktop'


def prepare_environment(root=ROOT, server=None):
    """Configure the current account service without client-side provider credentials."""
    root = Path(root)
    home = root / '.local/desktop/home'
    protect_directory(home)
    connection = root / '.local/prototype/client/control-connection.json'
    url = server if server is not None else (
        json.loads(connection.read_text(encoding='utf-8'))['url'] if connection.is_file()
        else 'https://funnywork.funnypixelsapp.com')
    validate_server(url)
    private_write(home / 'funnyworker-backend.json', json.dumps({'url': url}).encode())
    skills = root / '.local/desktop/skills'
    skills.mkdir(parents=True, mode=0o700, exist_ok=True)
    # These rows use the official profile and never put credentials in YAML.
    patch = [
        {'id': 'skill-filesystem', 'config': {'includeDefaultRoots': False, 'customSkillDirs': [str(skills)]}},
        {'id': 'ui-settings-account', 'disabled': True},
        {'id': 'llm-deepseek-account', 'disabled': True},
        {'id': 'locale', 'config': {'preference': 'zh'}},
        {'id': 'ui-theme', 'config': {'preference': 'light'}},
        {'id': 'session-telemetry-otel', 'disabled': True},
        {'id': 'desktop-product-telemetry', 'disabled': True},
        {'id': 'product-analytics', 'disabled': True},
    ]
    # JSON is a YAML subset; no JS expressions or escaping ambiguity.
    private_write(home / 'cordis.patch.yml', json.dumps(patch, ensure_ascii=False).encode())
    env = os.environ.copy()
    env.pop('DEEPSEEK_API_KEY', None)
    env.pop('DEEPSEEK_BASE_URL', None)
    env.update({
        'DSH_HOME': str(home),
        'DSH_DESKTOP_USER_DATA_DIR': str(root / '.local/desktop/user-data'),
        'DSH_DESKTOP_OPEN_DEVTOOLS': '0',
        'DSH_CLIENT_TITLE': 'FunnyWork',
        'DSH_DESKTOP_APP_ID': 'com.funnyworker.desktop',
    })
    return env


def validate_server(url):
    """Accept HTTPS management services and the existing loopback HTTP service."""
    if not isinstance(url, str):
        raise ValueError('服务地址无效')
    parsed = urlsplit(url)
    if (not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment
        or parsed.path not in {'', '/'}
        or not (parsed.scheme == 'https' or (parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'))):
        raise ValueError('服务地址无效')
    try:
        parsed.port
    except ValueError as error:
        raise ValueError('服务地址无效') from error


def run_package_script(pnpm, script, env):
    """Launch reviewed package scripts, including Windows package-manager shims."""
    command = [pnpm, 'run', script]
    if os.name == 'nt' and Path(pnpm).suffix.lower() in {'.cmd', '.bat'}:
        # cmd.exe cannot directly execute a batch file through CreateProcess.
        # Only fixed script names and the discovered executable enter the shell.
        if script not in {'build', 'build:desktop', 'start:desktop'} or any(c in pnpm for c in '\"%\r\n'):
            raise ValueError('构建命令无效')
        command = f'"{os.environ.get("COMSPEC", "cmd.exe")}" /d /s /c ""{pnpm}" run {script}"'
    subprocess.run(command, cwd=HARNESS, env=env, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build', action='store_true')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--server', help='管理服务根地址；远程服务必须使用 HTTPS')
    args = parser.parse_args()
    env = prepare_environment(server=args.server)
    if args.check:
        print('服务地址及桌面配置已准备；登录后使用计量模型中间层。')
        return
    pnpm = shutil.which('pnpm')
    if pnpm is None:
        raise SystemExit('桌面构建工具不可用，请查看项目开发说明。')
    # Build never inherits the credential: public bundles only receive product facts.
    if args.build:
        build_env = {k: v for k, v in env.items() if k not in {'DEEPSEEK_API_KEY', 'DEEPSEEK_BASE_URL'}}
        run_package_script(pnpm, 'build', build_env)
        run_package_script(pnpm, 'build:desktop', build_env)
    run_package_script(pnpm, 'start:desktop', env)


if __name__ == '__main__':
    main()
