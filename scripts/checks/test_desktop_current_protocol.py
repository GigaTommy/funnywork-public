"""The desktop launcher configures an origin without inheriting provider credentials."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from funnyworker.desktop import prepare_environment


class CurrentDesktopProtocolTest(unittest.TestCase):
    def test_launch_uses_account_service_and_omits_direct_provider_credentials(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict('os.environ', {
            'DEEPSEEK_API_KEY': 'fixture-only', 'DEEPSEEK_BASE_URL': 'https://obsolete.example',
        }):
            root = Path(directory)
            environment = prepare_environment(root, 'https://current.example')
            self.assertNotIn('DEEPSEEK_API_KEY', environment)
            self.assertNotIn('DEEPSEEK_BASE_URL', environment)
            self.assertEqual(environment['DSH_CLIENT_TITLE'], 'FunnyWork')
            self.assertEqual(json.loads((root / '.local/desktop/home/funnyworker-backend.json').read_text())['url'], 'https://current.example')


if __name__ == '__main__':
    unittest.main()
