"""Deployment gates and service rollback, without SSH, Docker, or a real database."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('android_deploy', ROOT / 'scripts/setup/deploy-android-account.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = ROOT / '.local/agent/tmp'
        temporary.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=temporary, prefix='android-deploy-test-')
        self.root = Path(self.directory.name)
        self.old = self.root / 'business.jar'
        self.new = self.root / 'candidate.jar'
        self.jar(self.old, android=False)
        self.jar(self.new)

    def tearDown(self):
        self.directory.cleanup()

    def jar(self, path, android=True, migration='original', extra=False):
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('BOOT-INF/classes/db/migration/V1__base.sql', migration)
            archive.writestr('BOOT-INF/classes/static/admin/index.html', '<html>test</html>')
            if android:
                archive.writestr('BOOT-INF/classes/com/funnywork/AccountClient.class', 'test-only')
            if extra:
                archive.writestr('BOOT-INF/classes/db/migration/V2__unexpected.sql', 'select 1;')

    def validate(self):
        deploy.validate(self.old, self.new, deploy.sha(self.old), deploy.sha(self.new))

    def test_same_migrations_and_android_candidate_are_accepted(self):
        self.validate()

    def test_changed_or_added_migrations_are_rejected(self):
        for options in ({'migration': 'modified'}, {'extra': True}):
            with self.subTest(options=options):
                self.jar(self.new, **options)
                with self.assertRaisesRegex(RuntimeError, 'Migration set differs'):
                    self.validate()

    def test_wrong_digest_or_missing_android_class_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'digest differs'):
            deploy.validate(self.old, self.new, '0' * 64, deploy.sha(self.new))
        self.jar(self.new, android=False)
        with self.assertRaisesRegex(RuntimeError, 'lacks Android'):
            self.validate()

    def test_unrelated_classes_and_management_resources_are_rejected(self):
        for name in ('BOOT-INF/classes/com/funnywork/OtherController.class', 'BOOT-INF/classes/static/admin/site.js'):
            with self.subTest(name=name):
                self.jar(self.new)
                with zipfile.ZipFile(self.new, 'a') as archive:
                    archive.writestr(name, 'unrelated change')
                with self.assertRaisesRegex(RuntimeError, 'outside Android account'):
                    self.validate()

    def test_failed_health_restores_jar_and_release_without_database_restore(self):
        (self.root / 'current-release').write_text('old-release\n')
        (self.root / 'identity.jar').write_bytes(b'test-only-identity')
        policy = self.root / 'policy.sql'
        policy.write_text('-- test-only additive policy')
        before = self.old.read_bytes()
        argv = ['deploy', '--candidate', str(self.new), '--sql', str(policy),
                '--expected-release', 'old-release', '--release', 'test-android',
                '--expected-current-sha256', deploy.sha(self.old),
                '--candidate-sha256', deploy.sha(self.new), '--sql-sha256', deploy.sha(policy), '--apply']
        with patch.object(deploy, 'ROOT', self.root), patch.object(sys, 'argv', argv), \
                patch.object(deploy.os, 'geteuid', return_value=0), \
                patch.object(deploy, 'healthy', side_effect=[None, RuntimeError('test health failure'), None]), \
                patch.object(deploy, 'restart') as restart, \
                patch.object(deploy, 'client_state', side_effect=['', 'unchanged-policy', 'unchanged-policy']), \
                patch.object(deploy, 'run') as run, \
                patch.object(deploy.subprocess, 'check_output', return_value='1'):
            with self.assertRaisesRegex(RuntimeError, 'test health failure'):
                deploy.main()
        self.assertEqual(before, self.old.read_bytes())
        self.assertEqual('old-release\n', (self.root / 'current-release').read_text())
        self.assertEqual(2, restart.call_count)
        calls = [call.args[0] for call in run.call_args_list]
        self.assertFalse(any('pg_restore' in call and '--list' not in call for call in calls))
        result = json.loads((self.root / 'backups/test-android/result.json').read_text())
        self.assertTrue(result['serviceRestored'])
        self.assertFalse(result['applied'])
        self.assertFalse((self.root / 'business.jar.android-next').exists())


if __name__ == '__main__':
    unittest.main()
