"""Offline workspace repair: preservation, collisions, CAS and atomic failure."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('repair', ROOT / 'scripts/setup/repair-workspace-path.py')
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)


class RepairTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / '.local/agent/tmp', prefix='workspace-repair-test-')
        self.root = Path(self.temp.name)
        self.target = self.root / 'actual'; self.target.mkdir()
        self.alias = self.root / 'old'; self.alias.symlink_to(self.target, target_is_directory=True)
        self.home = self.root / 'home'; (self.home / 'storages').mkdir(parents=True)
        self.ids = [f'session-{i}' for i in range(8)]
        self.value = {'unit': {'name': 'workspace', 'version': 2}, 'global': {
            'initialized': True, 'defaultWorkspaceId': 'original', 'workspaceIds': ['original'],
            'archivedSessionIds': [], 'pinnedSessionIds': ['session-0']}, 'tables': {'workspaces': {
                'original': {'path': str(self.alias), 'title': 'original name', 'sessionIds': self.ids,
                             'createdAt': 'unchanged', 'updatedAt': 'unchanged'}}}}
        self.path = self.home / 'storages/workspace.json'
        self.raw = json.dumps(self.value).encode(); self.path.write_bytes(self.raw)
        (self.home / 'history-device-id').write_text('original-device')
        for sid in self.ids:
            folder = self.home / 'sessions' / sid; folder.mkdir(parents=True)
            (folder / 'session.v4.jsonl').write_text(f'original history {sid}\n')
        self.backup = self.root / 'backup'

    def tearDown(self):
        self.temp.cleanup()

    def run_repair(self, apply=True):
        with patch.object(repair, 'assert_stopped'):
            return repair.repair(self.home, 'original', repair.digest(self.raw), self.backup, apply)

    def test_repairs_only_path_and_preserves_all_history_with_verified_backup(self):
        before = repair.inventory(self.home)
        result = self.run_repair()
        self.assertTrue(result['applied'])
        value = json.loads(self.path.read_bytes())
        value['tables']['workspaces']['original']['path'] = str(self.alias)
        self.assertEqual(self.value, value)
        after = repair.inventory(self.home)
        for key in before.keys() - {'storages/workspace.json'}:
            self.assertEqual(before[key], after[key])
            self.assertEqual(before[key]['sha256'], repair.digest((self.backup / 'account' / key).read_bytes()))
        self.assertEqual(0o600, self.path.stat().st_mode & 0o777)

    def test_dry_run_does_not_write_or_backup(self):
        self.assertTrue(self.run_repair(False)['changed'])
        self.assertEqual(self.raw, self.path.read_bytes()); self.assertFalse(self.backup.exists())

    def test_repeated_repair_is_noop(self):
        self.run_repair()
        raw = self.path.read_bytes()
        with patch.object(repair, 'assert_stopped'):
            result = repair.repair(self.home, 'original', repair.digest(raw), self.root / 'unused', True)
        self.assertFalse(result['changed']); self.assertEqual(raw, self.path.read_bytes())

    def test_collision_refuses_to_merge(self):
        self.value['global']['workspaceIds'].append('other')
        self.value['tables']['workspaces']['other'] = {'path': str(self.target), 'sessionIds': []}
        with self.assertRaisesRegex(RuntimeError, 'collision'):
            repair.plan(json.dumps(self.value).encode(), 'original')

    def test_missing_directory_refuses_without_creating_replacement(self):
        self.target.rmdir()
        with self.assertRaises(FileNotFoundError): self.run_repair()
        self.assertEqual(self.raw, self.path.read_bytes()); self.assertFalse(self.target.exists())

    def test_unmounted_volume_refuses_write(self):
        with patch.object(repair.os.path, 'ismount', return_value=False):
            with self.assertRaisesRegex(RuntimeError, 'mounted'): self.run_repair()
        self.assertEqual(self.raw, self.path.read_bytes())

    def test_persistence_failure_preserves_original_and_backup(self):
        with patch.object(repair.os, 'replace', side_effect=OSError('test disk failure')):
            with self.assertRaises(OSError): self.run_repair()
        self.assertEqual(self.raw, self.path.read_bytes())
        self.assertEqual(self.raw, (self.backup / 'account/storages/workspace.json').read_bytes())
        self.assertEqual([], list(self.path.parent.glob('.workspace-repair-*')))

    def test_stale_digest_refuses_before_backup(self):
        self.path.write_bytes(self.raw + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'digest changed'): self.run_repair()
        self.assertFalse(self.backup.exists())

    def test_write_during_backup_is_detected(self):
        original = repair.shutil.copy2
        def copy(*args, **kwargs):
            result = original(*args, **kwargs)
            self.path.write_bytes(self.raw + b'\n')
            return result
        with patch.object(repair.shutil, 'copy2', side_effect=copy):
            with self.assertRaisesRegex(RuntimeError, 'changed during backup|Backup verification failed'): self.run_repair()
        self.assertEqual(self.raw + b'\n', self.path.read_bytes())

    def test_running_client_refuses_even_dry_run(self):
        with patch.object(repair.subprocess, 'check_output', return_value='/Volumes/SSD/work/Applications/funnywork.app/Contents/MacOS/funnywork\n'):
            with self.assertRaisesRegex(RuntimeError, 'Close FunnyWork'): repair.assert_stopped()

    def test_second_maintenance_writer_is_refused(self):
        with self.path.open('rb') as held:
            repair.fcntl.flock(held.fileno(), repair.fcntl.LOCK_EX | repair.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError): self.run_repair()
        self.assertEqual(self.raw, self.path.read_bytes())

    def test_missing_original_history_refuses_repair(self):
        (self.home / 'sessions/session-0/session.v4.jsonl').unlink()
        with self.assertRaisesRegex(RuntimeError, 'history is missing'): self.run_repair()
        self.assertEqual(self.raw, self.path.read_bytes()); self.assertFalse(self.backup.exists())


if __name__ == '__main__':
    unittest.main()
