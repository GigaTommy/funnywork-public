#!/usr/bin/env python3
"""Run the native PowerShell probe against synthetic local HTTP fixtures.

Python is a developer-only test runner, not a dependency of the delivered .ps1.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('fixture', HERE / 'test-enterprise-model-probe.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)
POWERSHELL = 'pwsh'


class PowerShellTests(unittest.TestCase):
    # Reuse fixture lifecycle only; every test invokes the .ps1 in a child process.
    setUpClass = classmethod(fixture.ProbeTests.setUpClass.__func__)
    tearDownClass = classmethod(fixture.ProbeTests.tearDownClass.__func__)
    setUp = fixture.ProbeTests.setUp
    config = fixture.ProbeTests.config


def run_probe(test, config=None, active=False, checks=None, expected=0, extra=None):
    config = test.config() if config is None else config
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory)
        settings, output = path / 'config.json', path / 'report.json'
        settings.write_text(json.dumps(config))
        command = [POWERSHELL, '-NoProfile', '-NonInteractive', '-File', str(HERE / 'probe-enterprise-model.ps1'),
                   '-Config', str(settings), '-Output', str(output)]
        if active:
            command.append('-Active')
        if checks is not None:
            command.extend(['-Checks', checks])
        command.extend(extra or [])
        result = subprocess.run(command, capture_output=True, text=True, timeout=25)
        report = json.loads(output.read_text(encoding='utf-8-sig')) if output.exists() and output.stat().st_size else None
        test.assertEqual(result.returncode, expected, (result.stdout, result.stderr, report))
        test.assertNotIn(fixture.SECRET, result.stdout + result.stderr + json.dumps(report))
        return report, result


def test_passive(test):
    report, _ = run_probe(test)
    test.assertEqual(report['models'], ['test-model'])
    test.assertEqual(report['request_count'], 1)
    test.assertFalse(report['inference_verified'])


def test_full_chat(test):
    report, _ = run_probe(test, active=True)
    test.assertTrue(all(c['status'] == 'PASS' for c in report['checks']), report)
    test.assertEqual(report['request_count'], 7)
    test.assertEqual(report['protocol'], 'chat_completions')


def test_full_messages(test):
    report, _ = run_probe(test, test.config('messages'), True)
    test.assertTrue(all(c['status'] == 'PASS' for c in report['checks']), report)


def test_auto_and_empty_checks(test):
    fixture.Handler.mode = 'chat404'
    report, _ = run_probe(test, test.config('auto'), True, '', 2)
    test.assertEqual(report['protocol'], 'messages')
    test.assertEqual(report['request_count'], 3)


def test_basic_only(test):
    report, _ = run_probe(test, active=True, checks='')
    test.assertEqual(report['request_count'], 2)


def test_login(test):
    config = test.config()
    config['login'] = {'url': test.base + '/login', 'body': {'password': fixture.SECRET}, 'token_path': 'data.access_token'}
    report, _ = run_probe(test, config, True)
    test.assertEqual(fixture.Handler.requests[0][3], None)
    test.assertEqual(fixture.Handler.requests[1][3], 'Bearer ' + fixture.SECRET)
    test.assertEqual(report['request_count'], 8)


def test_bad_login(test):
    fixture.Handler.mode = 'bad_login'
    config = test.config()
    config['login'] = {'url': test.base + '/login', 'body': {}, 'token_path': 'data.access_token'}
    report, _ = run_probe(test, config, True, expected=2)
    test.assertEqual(report['request_count'], 1)
    test.assertEqual(report['checks'][0]['status'], 'BLOCKED')


def test_redirect_html_denied(test):
    for mode in ('redirect', 'html', 'denied'):
        fixture.Handler.mode = mode
        fixture.Handler.requests = []
        report, _ = run_probe(test, expected=2)
        test.assertEqual(report['checks'][-1]['status'], 'BLOCKED', report)
        test.assertEqual(len(fixture.Handler.requests), 1)


def test_rate_block(test):
    fixture.Handler.mode = 'rate'
    report, _ = run_probe(test, active=True, expected=2)
    test.assertEqual(report['checks'][-1]['status'], 'BLOCKED')
    test.assertEqual(report['request_count'], 2)


def test_bad_sse(test):
    for mode in ('truncated', 'sse_error'):
        fixture.Handler.mode = mode
        report, _ = run_probe(test, active=True, checks='streaming', expected=2)
        test.assertEqual(report['checks'][-1]['status'], 'NOT_CONFIRMED')


def test_bad_tools(test):
    for mode in ('bad_tool', 'bad_roundtrip'):
        fixture.Handler.mode = mode
        report, _ = run_probe(test, active=True, checks='tools', expected=2)
        test.assertEqual(report['checks'][-1]['status'], 'NOT_CONFIRMED')


def test_custom(test):
    config = test.config('custom')
    config.update(base_url=test.base, models_path=None, custom={'path': '/custom',
                  'body': {'history': '{{messages}}', 'model': '{{model}}'}, 'text_path': 'data.answer'})
    report, _ = run_probe(test, config, True)
    test.assertEqual(report['protocol'], 'custom')
    test.assertEqual([c['status'] for c in report['checks'][-3:]], ['NOT_TESTED'] * 3)


def test_validation_before_network(test):
    for change in ({'model': ''}, {'base_url': 'http://intranet/v1'}, {'base_url': 'https://test/?secret=x'}):
        config = test.config()
        config.update(change)
        run_probe(test, config, True, expected=1)
        test.assertEqual(fixture.Handler.requests, [])


def test_env_and_reflection(test):
    config = test.config()
    config['headers'] = {'Authorization': 'Bearer ${FW_POC_FIXTURE_TOKEN}'}
    with fixture.patch.dict(os.environ, {'FW_POC_FIXTURE_TOKEN': fixture.SECRET}):
        fixture.Handler.mode = 'reflected_secret'
        report, _ = run_probe(test, config)
    test.assertEqual(report['models'], [])


def test_missing_env_and_prompt(test):
    config = test.config()
    config['headers'] = {'Authorization': 'Bearer ${FW_MISSING_FIXTURE_123}'}
    run_probe(test, config, expected=1)
    run_probe(test, expected=1, extra=['-PromptEnv', 'FW_POC_FIXTURE_TOKEN'])
    test.assertEqual(fixture.Handler.requests, [])


def test_existing_output(test):
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory)
        settings, output = path / 'config.json', path / 'report.json'
        settings.write_text(json.dumps(test.config()))
        output.write_text('original report')
        result = subprocess.run([POWERSHELL, '-NoProfile', '-NonInteractive', '-File',
                                 str(HERE / 'probe-enterprise-model.ps1'), '-Config', str(settings),
                                 '-Output', str(output)], capture_output=True, text=True, timeout=20)
        test.assertEqual(result.returncode, 1)
        test.assertEqual(output.read_text(), 'original report')
        test.assertEqual(fixture.Handler.requests, [])
        test.assertIn('OUTPUT_ALREADY_EXISTS', result.stderr)


for name, value in list(globals().items()):
    if name.startswith('test_') and callable(value):
        setattr(PowerShellTests, name, value)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--powershell', default='pwsh')
    args, rest = parser.parse_known_args()
    POWERSHELL = args.powershell
    unittest.main(argv=[sys.argv[0]] + rest)
