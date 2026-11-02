"""Regression for the account chat root: authenticated clients must never follow redirects."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('cn_proxy', ROOT / 'scripts/setup/update-cn-proxy.py')
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)

class ChatRootTests(unittest.TestCase):
    def test_template_is_already_idempotent(self):
        source = (ROOT / 'deploy/nginx/funnywork-cn.conf.example').read_text()
        self.assertEqual(source, proxy.prepare(source))

    def test_older_site_gains_exact_root_without_changing_stream_location(self):
        source = (ROOT / 'deploy/nginx/funnywork-cn.conf.example').read_text()
        exact = proxy.stream_location('chats').replace('location ^~ /api/account/chats/', 'location = /api/account/chats', 1)
        old = source.replace(exact, '')
        updated = proxy.prepare(old)
        self.assertEqual(1, updated.count(exact))
        self.assertEqual(1, updated.count(proxy.stream_location('chats')))
        self.assertEqual(updated, proxy.prepare(updated))

    def test_unknown_exact_root_is_not_overwritten(self):
        source = (ROOT / 'deploy/nginx/funnywork-cn.conf.example').read_text().replace('location = /api/account/chats {', 'location = /api/account/chats {\n        return 301 /other;')
        with self.assertRaises(ValueError): proxy.prepare(source)

if __name__ == '__main__':
    unittest.main()
