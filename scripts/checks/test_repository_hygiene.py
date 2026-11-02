#!/usr/bin/env python3
"""Exercise repository hygiene failures in disposable Git repositories."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class GitHygieneTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='repository-hygiene-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith('GIT_')}
        self.git('init', '-q')
        for relative in ('.gitignore', 'apps/management/.gitignore',
                         'apps/desktop/.gitignore', 'apps/android/.gitignore',
                         'services/identity/.gitignore',
                         'scripts/checks/check-git-hygiene.py'):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)

    def git(self, *args):
        return subprocess.run(['git', '-C', str(self.root), *args],
                              env=self.env, capture_output=True, text=True, check=True)

    def check(self):
        return subprocess.run(
            [sys.executable, str(self.root / 'scripts/checks/check-git-hygiene.py')],
            env=self.env, cwd=self.temp.name, capture_output=True, text=True)

    def track(self, relative):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('synthetic fixture\n', encoding='utf-8')
        self.git('add', '-f', '--', relative)

    def test_clean_rules_allow_maintained_source_and_templates(self):
        self.track('apps/management/pnpm-lock.yaml')
        self.track('deploy/magpie/magpie.env.example')
        self.track('apps/management/uat/model-middleware/fixture.ts')
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_force_added_private_material_is_rejected(self):
        self.track('.local/private-account.json')
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('Tracked file matches ignore rules', result.stderr)
        self.assertIn('.local/private-account.json', result.stderr)

    def test_force_added_archived_prototype_is_rejected(self):
        self.track('designs/ios-task-ux-20261010/index.html')
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('Tracked file matches ignore rules', result.stderr)

    def test_lockfile_ignore_regression_is_rejected(self):
        with (self.root / 'apps/management/.gitignore').open('a') as output:
            output.write('\npnpm-lock.yaml\n')
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('Required source/template/lockfile is ignored: '
                      'apps/management/pnpm-lock.yaml', result.stderr)

    def test_deployment_environment_ignore_regression_is_rejected(self):
        target = self.root / '.gitignore'
        target.write_text(target.read_text(encoding='utf-8').replace('**/*.env\n', ''),
                          encoding='utf-8')
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn('Local/private/generated path is not ignored: '
                      'deploy/magpie/magpie.env', result.stderr)


if __name__ == '__main__':
    unittest.main()
