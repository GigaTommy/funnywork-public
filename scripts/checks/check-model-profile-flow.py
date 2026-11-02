#!/usr/bin/env python3
"""Run reference node model scenarios against this repository's JAR."""
import argparse
import importlib
import os
from pathlib import Path
import runpy
import secrets
import socket
import subprocess
import sys
import tempfile
import time


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-root', required=True, type=Path)
    parser.add_argument('--scenario', choices=('profile', 'attachments'), default='profile')
    parser.add_argument('--server-jar', type=Path, default=root / 'services/business/target/funnyworker-server-0.1.0-SNAPSHOT.jar')
    args = parser.parse_args()
    runtime = args.runtime_root.resolve()
    jar = args.server_jar.resolve()
    scenario = runtime / 'scripts' / {
        'profile': 'test-model-profile.py', 'attachments': 'test-model-artifacts.py'
    }[args.scenario]
    if not jar.is_file() or not scenario.is_file():
        parser.error('Build the current service JAR and provide the reference runtime directory.')
    sys.path.insert(0, str(runtime / 'scripts'))
    local_server = importlib.import_module('local_server')
    client = importlib.import_module('worker').Client
    (root / '.local').mkdir(exist_ok=True)

    class CurrentServer(local_server.LocalServer):
        def __enter__(self):
            self.root = root
            self.temporary = tempfile.TemporaryDirectory(prefix='model-profile-flow-', dir=root / '.local')
            self.state = Path(self.temporary.name)
            self.token = secrets.token_urlsafe(32)
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                port = probe.getsockname()[1]
            self.url = f'http://127.0.0.1:{port}'
            env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME', 'SYSTEMROOT', 'TMPDIR') if key in os.environ}
            env.update(FUNNYWORK_BOOTSTRAP_TOKEN=self.token, FUNNYWORK_PORT=str(port),
                       FUNNYWORK_BIND_ADDRESS='127.0.0.1', FUNNYWORK_DB_USER='sa', FUNNYWORK_DB_PASSWORD='',
                       FUNNYWORK_DB_URL=f'jdbc:h2:file:{self.state / "db"};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0')
            env.update(self.extra_env)
            self.log = (self.state / 'server.log').open('wb')
            self.process = subprocess.Popen(['java', '-jar', str(jar)], cwd=root, env=env,
                                            stdout=self.log, stderr=subprocess.STDOUT)
            self.admin = client(self.url, self.token)
            try:
                deadline = time.monotonic() + 30
                while time.monotonic() < deadline:
                    if self.process.poll() is not None:
                        raise RuntimeError('Isolated current service exited before readiness.')
                    try:
                        self.admin.call('/me')
                        return self
                    except Exception:
                        time.sleep(.1)
                raise TimeoutError('Isolated current service readiness timed out.')
            except BaseException:
                self.__exit__()
                raise

        def __exit__(self, *exc):
            try:
                super().__exit__(*exc)
            finally:
                self.temporary.cleanup()

    local_server.LocalServer = CurrentServer
    previous_args = sys.argv
    try:
        sys.argv = [str(scenario)]
        runpy.run_path(str(scenario), run_name='__main__')
    finally:
        sys.argv = previous_args


if __name__ == '__main__':
    main()
