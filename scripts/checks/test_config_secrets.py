#!/usr/bin/env python3
"""Check credential rejection, placeholder handling, and redacted diagnostics."""
from pathlib import Path
import importlib.util
import unittest

spec = importlib.util.spec_from_file_location('config_secrets', Path(__file__).with_name('check-config-secrets.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ConfigurationSecretTests(unittest.TestCase):
    def test_literals_are_rejected_without_returning_values(self):
        value = 'synthetic-credential-for-this-test'
        for text in [f'API_KEY={value}', f'password: "{value}"', f'<monitor.password>{value}</monitor.password>', f'requirepass {value}']:
            result = module.violations('config', text)
            self.assertTrue(result)
            self.assertNotIn(value, repr(result))

    def test_references_and_explicit_placeholders(self):
        for value in ['', "''", '${PASSWORD}', '${PASSWORD:}', '${PASSWORD:-}', '${PASSWORD:?Set PASSWORD}', '${spring.datasource.password}', '$PASSWORD', '@monitor.password@', '<RUNTIME_SECRET>', 'YOUR_API_KEY', 'BASE64_PKCS8_KEY', 'replace-with-a-random-admin-key']:
            self.assertFalse(module.violations('config', 'password: ' + value), value)

    def test_nonempty_environment_defaults_are_rejected(self):
        for value in ['${PASSWORD:weak}', '${PASSWORD:-weak}', '${PASSWORD:+weak}']:
            self.assertTrue(module.violations('config', 'password: ' + value))

    def test_public_keys_and_identifiers_are_not_credentials(self):
        text = 'VITE_APP_CLIENT_ID=public-client\nVITE_APP_RSA_PUBLIC_KEY=public-material\nmaxTokens: 8192\ntoken-name: Authorization\n'
        self.assertFalse(module.violations('config', text))
        self.assertTrue(module.violations('config', 'VITE_APP_RSA_PRIVATE_KEY=synthetic-private-material'))

    def test_private_key_blocks_and_commented_defaults(self):
        self.assertTrue(module.violations('config', '-----BEGIN PRIVATE KEY-----'))
        self.assertTrue(module.violations('config', '# password: synthetic-password'))

    def test_compact_nested_json_and_malformed_configuration(self):
        self.assertTrue(module.violations('config.json', '{"providers":[{"apiKey":"synthetic-secret"}]}'))
        self.assertFalse(module.violations('config.json', '{"providers":[{"apiKey":"${MODEL_API_KEY}"}]}'))
        self.assertTrue(module.violations('config.json', '{invalid-json'))

    def test_relevant_configuration_selection(self):
        for path in ['apps/management/.env.production.example', 'services/identity/pom.xml', 'services/identity/script/docker/redis/conf/redis.conf', 'services/identity/script/docker/runtime.env.example', 'deploy/magpie/magpie.env.example', 'deploy/desktop-model.example.json', 'services/business/src/main/resources/application.yml']:
            self.assertTrue(module.configuration(path), path)
        for path in ['services/business/src/test/resources/fixture.json', 'apps/desktop/snapshots/fixture.yaml', '.local/credentials.json', 'services/identity/ruoyi-admin/src/main/resources/i18n/messages.properties']:
            self.assertFalse(module.configuration(path), path)


if __name__ == '__main__':
    unittest.main()
