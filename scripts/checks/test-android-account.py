"""Account probe cleanup/redirect boundaries, with no credentials or network access."""
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('account_check', ROOT / 'scripts/checks/check-android-account.py')
account = importlib.util.module_from_spec(spec)
spec.loader.exec_module(account)


class FakeEntry:
    origin = account.OFFICIAL[0]
    def __init__(self, login_error=False, history_error=False, principal='same'):
        self.calls, self.revoked = [], False
        self.login_error, self.history_error, self.principal = login_error, history_error, principal

    def request(self, path, token='', body=None):
        self.calls.append((path, token, body))
        if path == 'login':
            if self.login_error:
                raise account.Failure('HTTP 400')
            return {'token': 'test-only-token'}
        if path == 'logout':
            self.revoked = True
            return {'ok': True}
        if path == 'session':
            if self.revoked:
                raise account.Failure('HTTP 401')
            return {'tenant': 'test', 'principalId': self.principal}
        if path == 'chats?history=true' and self.history_error:
            raise account.Failure('HTTP 301')
        return [] if path.startswith('sync/sessions') else {}


class AccountProbeTests(unittest.TestCase):
    def check(self, entry, peer=None):
        return account.check(entry, peer or FakeEntry(), 'android', {'username': 'test', 'password': 'test-only'})

    def test_array_directory_is_accepted(self):
        entry = account.Entry(account.OFFICIAL[0])
        response = io.BytesIO(b'[]')
        response.code = 200
        entry.opener = Mock()
        entry.opener.open.return_value = response
        self.assertEqual([], entry.request('sync/sessions?limit=1'))

    def test_success_checks_peer_identity_and_revokes_only_own_login(self):
        entry = FakeEntry()
        row = self.check(entry)
        self.assertTrue(row['passed'])
        self.assertTrue(row['logoutPassed'])
        self.assertTrue(row['revokedSessionRejected'])
        self.assertEqual(['login', 'logout'], [path for path, _, body in entry.calls if body is not None])
        self.assertNotIn('test-only-token', str(row))

    def test_unsupported_android_does_not_try_another_client(self):
        entry = FakeEntry(login_error=True)
        row = self.check(entry)
        self.assertFalse(row['passed'])
        self.assertEqual(1, len(entry.calls))
        self.assertEqual('android', entry.calls[0][2]['client'])

    def test_history_redirect_and_peer_identity_mismatch_still_logout(self):
        for entry, peer in ((FakeEntry(history_error=True), FakeEntry()), (FakeEntry(), FakeEntry(principal='different'))):
            with self.subTest():
                row = self.check(entry, peer)
                self.assertFalse(row['passed'])
                self.assertTrue(row['logoutPassed'])
                self.assertTrue(entry.revoked)


if __name__ == '__main__':
    unittest.main()
