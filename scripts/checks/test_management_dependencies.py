"""Checks the offline-first dependency preparation decisions without accessing a registry."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('management_dependencies', Path(__file__).resolve().parents[1] / 'setup/prepare-management.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CachedDependenciesTest(unittest.TestCase):
    def invoke(self, run):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            module.install_dependencies(['pnpm'], {}, run)

    def test_verified_cache_never_requests_online_install(self):
        run = Mock(return_value=subprocess.CompletedProcess(['pnpm'], 0, '', ''))
        self.invoke(run)
        self.assertEqual(run.call_count, 1)
        self.assertIn('--offline', run.call_args.args[0])
        self.assertIn('--frozen-lockfile', run.call_args.args[0])
        self.assertIn('--config.verify-store-integrity=true', run.call_args.args[0])

    def test_missing_cache_allows_only_one_online_install(self):
        for code in ['NO_OFFLINE_META', 'NO_OFFLINE_TARBALL', 'CANNOT_DOWNLOAD_BINARY_OFFLINE']:
            with self.subTest(code=code):
                run = Mock(side_effect=[subprocess.CompletedProcess(['pnpm'], 1, '', '[ERR_PNPM_' + code + '] missing'), subprocess.CompletedProcess(['pnpm'], 0)])
                self.invoke(run)
                self.assertEqual(run.call_count, 2)
                self.assertNotIn('--offline', run.call_args.args[0])
                self.assertTrue(run.call_args.kwargs['check'])

    def test_integrity_and_lock_mismatches_do_not_request_network(self):
        for code in ['TARBALL_INTEGRITY', 'OUTDATED_LOCKFILE', 'VERIFY_DEPS_BEFORE_RUN']:
            with self.subTest(code=code):
                run = Mock(return_value=subprocess.CompletedProcess(['pnpm'], 1, '[ERR_PNPM_' + code + '] invalid', ''))
                with self.assertRaises(subprocess.CalledProcessError):
                    self.invoke(run)
                self.assertEqual(run.call_count, 1)


if __name__ == '__main__':
    unittest.main()
