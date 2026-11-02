"""Check regional Nginx configuration without connecting to or changing any server."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('regional_proxy', ROOT / 'scripts/setup/update-cn-proxy.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RegionalConfigurationTests(unittest.TestCase):
    def test_requires_operator_host_and_preserves_reviewed_routes(self):
        source = (ROOT / 'deploy/nginx/funnywork-cn.conf.example').read_text()
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(ValueError):
            module.prepare(source)
        for host in ['regional.example.invalid', 'edge.example.invalid']:
            fixture = source.replace('regional.example.invalid', host)
            with patch.dict(os.environ, {'FUNNYWORK_REGIONAL_HOST': host}, clear=True):
                self.assertEqual(module.prepare(fixture), fixture)
                with self.assertRaises(ValueError):
                    module.prepare(fixture.replace('proxy_read_timeout 660s', 'proxy_read_timeout 601s'))
        with patch.dict(os.environ, {'FUNNYWORK_REGIONAL_HOST': 'host; injected'}, clear=True), self.assertRaises(ValueError):
            module.prepare(source)


if __name__ == '__main__':
    unittest.main()
