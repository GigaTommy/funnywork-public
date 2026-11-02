"""Check that runtime package verification rejects stale and redundant parsers."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('identity_package', Path(__file__).with_name('check-identity-package.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class IdentityPackageTests(unittest.TestCase):
    def test_rejects_stale_crypto_versions_and_unused_binary_parser(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'runtime.jar'
            for version, extra, errors in [('1.86', [], 0), ('1.84', [], 3), ('1.86', ['fory-core-0.13.0.jar'], 1)]:
                with zipfile.ZipFile(path, 'w') as archive:
                    for name in [item + '-' + version + '.jar' for item in ['bcpkix-jdk18on', 'bcprov-jdk18on', 'bcutil-jdk18on']] + extra:
                        archive.writestr('BOOT-INF/lib/' + name, b'synthetic-runtime-fixture')
                self.assertEqual(len(module.check_runtime(path, '1.86')), errors)


if __name__ == '__main__':
    unittest.main()
