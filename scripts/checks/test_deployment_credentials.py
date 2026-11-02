#!/usr/bin/env python3
"""Validate Compose password requirements without starting services."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'services/identity/script/docker'
REQUIREMENTS = {
    'docker-compose.yml': ['FUNNYWORK_MYSQL_ROOT_PASSWORD', 'FUNNYWORK_REDIS_PASSWORD', 'FUNNYWORK_MINIO_ROOT_PASSWORD'],
    'database.yml': ['FUNNYWORK_SQLSERVER_PASSWORD', 'FUNNYWORK_POSTGRES_PASSWORD', 'FUNNYWORK_POSTGRES13_PASSWORD'],
}


class DeploymentCredentialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('docker'):
            raise unittest.SkipTest('Docker Compose CLI is required')
        probe = subprocess.run(['docker', 'compose', 'version'], capture_output=True)
        if probe.returncode:
            raise unittest.SkipTest('Docker Compose CLI is required')

    def config(self, filename, values, structured=False):
        environment = {k: v for k, v in os.environ.items() if not k.startswith('FUNNYWORK_')}
        environment.update(values)
        args = ['docker', 'compose', '--env-file', os.devnull, '-f', str(DIRECTORY / filename), 'config']
        args += ['--format', 'json'] if structured else ['--quiet']
        return subprocess.run(args, env=environment, cwd=ROOT, capture_output=True, text=True)

    def test_missing_and_empty_passwords_stop_configuration(self):
        for filename, variables in REQUIREMENTS.items():
            complete = {name: 'synthetic-config-only-password-42' for name in variables}
            self.assertEqual(self.config(filename, complete).returncode, 0)
            for name in variables:
                for value in [None, '']:
                    with self.subTest(file=filename, variable=name, empty=value == ''):
                        selected = dict(complete)
                        if value is None:
                            selected.pop(name)
                        else:
                            selected[name] = value
                        result = self.config(filename, selected)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(name, result.stderr)

    def test_redis_password_is_injected_at_runtime(self):
        values = {name: 'synthetic-config-only-password-42' for name in REQUIREMENTS['docker-compose.yml']}
        result = self.config('docker-compose.yml', values, structured=True)
        self.assertEqual(result.returncode, 0)
        redis = json.loads(result.stdout)['services']['redis']
        self.assertEqual(redis['environment']['REDIS_PASSWORD'], values['FUNNYWORK_REDIS_PASSWORD'])
        # Compose serializes the escaped dollar sign for round-tripping config.
        command = ' '.join(redis['command'])
        self.assertTrue(any(reference in command for reference in ('"$REDIS_PASSWORD"', '"$$REDIS_PASSWORD"')))
        self.assertNotIn('synthetic-config-only-password-42', ' '.join(redis['command']))


if __name__ == '__main__':
    unittest.main()
