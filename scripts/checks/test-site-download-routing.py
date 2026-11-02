#!/usr/bin/env python3
"""Regression checks for versioned APK routing and preservation of API policies."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('downloads', ROOT / 'scripts/setup/update-site-download-routing.py')
downloads = importlib.util.module_from_spec(spec)
spec.loader.exec_module(downloads)


def fixture(host='regional.example.invalid', hsts='86400'):
    return f'''server {{
    listen 443 ssl http2;
    server_name {host};
    add_header Strict-Transport-Security "max-age={hsts}" always;
    add_header Cache-Control no-store always;
    location ^~ /api/account/ {{ proxy_pass https://trusted; }}
    location = /admin/ {{ return 403; }}
    location ^~ /dsh-desk/releases/ {{ root /var/www/funnyworker-updates; try_files $uri =404; }}
    location = /dsh-desk/android/0.1.0-rc.3/FunnyWork-0.1.0-rc.3-android.apk {{ root /var/www/funnyworker-updates; }}
}}
'''


class DownloadRoutingTests(unittest.TestCase):
    def test_preserves_all_existing_routes_and_old_installers(self):
        before = fixture()
        after = downloads.prepare(before, 'regional.example.invalid')
        start = after.index(downloads.START)
        end = after.index(downloads.END) + len(downloads.END)
        self.assertEqual(after[:start] + after[end:], before)
        self.assertIn('Content-Disposition attachment', after[start:end])
        self.assertIn('limit_except GET { deny all; }', after[start:end])

    def test_repeated_application_is_idempotent(self):
        after = downloads.prepare(fixture(), 'regional.example.invalid')
        self.assertEqual(downloads.prepare(after, 'regional.example.invalid'), after)

    def test_keeps_each_sites_security_header_without_inheriting_no_store(self):
        host = 'funnywork.funnypixelsapp.com'
        after = downloads.prepare(fixture(host, '31536000'), host)
        block = after[after.index(downloads.START):after.index(downloads.END)]
        self.assertIn('"max-age=31536000" always;', block)
        self.assertNotIn('no-store', block)

    def test_rejects_conflicting_prefix_or_modified_managed_block(self):
        with self.assertRaises(ValueError):
            downloads.prepare(fixture().replace('    location = /admin/', '    location /dsh-desk/android/ {}\n    location = /admin/'), 'regional.example.invalid')
        after = downloads.prepare(fixture(), 'regional.example.invalid')
        with self.assertRaises(ValueError):
            downloads.prepare(after.replace('autoindex off', 'autoindex on'), 'regional.example.invalid')

    def test_rejects_wrong_host_or_storage_root(self):
        for source in [fixture('other.example'), fixture().replace('/var/www/funnyworker-updates', '/srv/private')]:
            with self.assertRaises(ValueError):
                downloads.prepare(source, 'regional.example.invalid')


if __name__ == '__main__':
    unittest.main()
