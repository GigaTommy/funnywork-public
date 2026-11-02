"""Exercise the source-export boundary using real Git commits and private local data."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('public_source', Path(__file__).resolve().parents[1] / 'build/build-public-source.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PublicSourceTests(unittest.TestCase):
    def test_forbidden_private_paths(self):
        for name in ['.git/config', '.local/token.json', '../outside', '/absolute', 'deploy/live.env', 'key.p12', 'node_modules/a.js']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                module.check_member(name)
        module.check_member('deploy/mobile-push.env.example')
        module.check_member('apps/desktop/LICENSE')

    def test_exports_current_commit_without_history_untracked_files_or_outside_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE, text=True).strip()
            git('init', '-q')
            git('config', 'user.name', 'Source fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            notices = ['LICENSE', 'COMMERCIAL-LICENSE.txt', 'THIRD_PARTY_NOTICES.md', 'apps/desktop/LICENSE',
                       'services/identity/LICENSE', 'apps/management/LICENSE', 'deploy/magpie/MAGPIE-LICENSE.txt']
            for name in notices:
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('notice fixture')
            (root / 'old-private-record.txt').write_text('historical-only fixture')
            workflow = root / '.github/workflows/linux-fixed-preview.yml'
            workflow.parent.mkdir(parents=True)
            workflow.write_text('private historical commit fixture')
            (root / 'license-link').symlink_to('LICENSE')
            git('add', '.')
            git('commit', '-qm', 'historical fixture')
            git('rm', '-q', 'old-private-record.txt')
            git('commit', '-qm', 'remove historical fixture')
            (root / 'untracked-password.txt').write_text('local-only fixture')
            candidate = root / '.local/releases/public-test'
            module.export_source(root, 'HEAD', candidate)
            self.assertTrue((candidate / 'source/license-link').is_symlink())
            self.assertFalse((candidate / 'source/.github/workflows/linux-fixed-preview.yml').exists())
            self.assertFalse((candidate / 'source/.git').exists())
            self.assertFalse((candidate / 'source/old-private-record.txt').exists())
            self.assertFalse((candidate / 'source/untracked-password.txt').exists())
            self.assertEqual((candidate / 'source/LICENSE').read_text(), 'notice fixture')
            with self.assertRaises(FileExistsError):
                module.export_source(root, 'HEAD', candidate)
            with self.assertRaises(ValueError):
                module.export_source(root, 'HEAD', root / 'outside-output')
            (root / 'escape').symlink_to('../outside')
            git('add', 'escape')
            git('commit', '-qm', 'bad source symlink')
            with self.assertRaises((ValueError, __import__('tarfile').FilterError)):
                module.export_source(root, 'HEAD', root / '.local/releases/unsafe')


if __name__ == '__main__':
    unittest.main()
