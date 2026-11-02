#!/usr/bin/env python3
"""Verify that the public-route patch preserves existing protected routes."""
from pathlib import Path
import importlib.util
import re
import unittest

spec=importlib.util.spec_from_file_location('site_performance',Path(__file__).resolve().parents[1]/'setup/update-site-performance.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ConfigTest(unittest.TestCase):
    def test_public_routes_only_and_idempotent(self):
        original='server {\n location = /solutions.html { root /srv/funnywork/site; limit_except GET { deny all; } add_header Content-Security-Policy "default-src self" always; }\n location /api/ { proxy_pass http://private; }\n location /admin/ { deny all; }\n}\n'
        updated=module.render(original)
        self.assertEqual(module.render(updated),updated)
        self.assertEqual(re.sub(re.escape(module.START)+r'.*?'+re.escape(module.END)+r'\n    ','',updated,flags=re.S),original)
        self.assertEqual(updated.count('add_header Content-Security-Policy'),3)
        self.assertIn('limit_except GET { deny all; }',updated)
        self.assertIn('max-age=31536000, immutable',updated)

    def test_license_allowlist_preserves_other_routes(self):
        original='server {\n location = /solutions.html { root /srv/funnywork/site; }\n location /api/ { deny all; }\n}\n'
        current=module.render(original)
        legacy=current.replace(module.PUBLIC_ASSET_PATTERN,module.LEGACY_ASSET_PATTERN)
        self.assertEqual(module.render(legacy),current)
        self.assertEqual(module.render(current),current)
        for name in ['LICENSE','COMMERCIAL-LICENSE-txt','THIRD_PARTY_NOTICES-md']:
            self.assertRegex('/static/funnywork-'+name+'.'+'a'*16+'.txt',module.PUBLIC_ASSET_PATTERN)
        for path in ['/static/credentials.'+'a'*16+'.txt','/static/funnywork-LICENSE.txt','/static/../LICENSE','/static/funnywork-LICENSE.'+'g'*16+'.txt']:
            self.assertIsNone(re.fullmatch(module.PUBLIC_ASSET_PATTERN,path))
        with self.assertRaises(ValueError):
            module.render(legacy.replace(module.LEGACY_ASSET_PATTERN,'unknown-pattern'))

    def test_unknown_or_conflicting_site_is_rejected(self):
        for config in ['', 'server {}', 'location = /solutions.html { }\nlocation = /solutions.html { }', module.START]:
            with self.assertRaises(ValueError):module.render(config)

unittest.main()
