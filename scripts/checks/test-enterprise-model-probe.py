#!/usr/bin/env python3
"""Offline protocol and credential-boundary regression; no enterprise services used."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('probe', Path(__file__).with_name('probe-enterprise-model.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
SECRET = 'fixture-secret-never-print'


class Handler(BaseHTTPRequestHandler):
    requests = []
    mode = 'ok'
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def send(self, body, status=200, kind='application/json', location=None):
        raw = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(raw)))
        if location:
            self.send_header('Location', location)
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        Handler.requests.append(('GET', self.path, None, self.headers.get('Authorization')))
        if self.mode == 'redirect':
            return self.send('login', 302, 'text/plain', '/target')
        if self.mode == 'html':
            return self.send('<html>login</html>', kind='text/html')
        if self.mode == 'denied':
            return self.send({'error': SECRET}, 401)
        self.send({'data': [{'id': SECRET if self.mode == 'reflected_secret' else 'test-model'}]})

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        Handler.requests.append(('POST', self.path, data, self.headers.get('Authorization')))
        if self.path == '/login':
            if self.mode == 'bad_login':
                return self.send({'message': SECRET})
            return self.send({'data': {'access_token': SECRET}})
        if self.mode == 'denied':
            return self.send({'error': SECRET}, 403)
        if self.mode == 'chat404' and self.path.endswith('/chat/completions'):
            return self.send({}, 404)
        if self.mode == 'rate':
            return self.send({}, 429)
        if self.path == '/custom':
            messages = data['history']
        else:
            messages = data['messages']
        chat = self.path.endswith('/chat/completions')
        if data.get('stream'):
            if self.mode == 'sse_error':
                events = [{'error': {'message': SECRET}}]
            else:
                events = ([{'choices': [{'delta': {'content': 'FW_STREAM_'}}]},
                           {'choices': [{'delta': {'content': 'OK'}}]}, '[DONE]'] if chat else
                          [{'type': 'content_block_delta', 'delta': {'type': 'text_delta', 'text': 'FW_STREAM_OK'}},
                           {'type': 'message_stop'}])
                if self.mode == 'truncated':
                    events = events[:-1]
            raw = ''.join('data: ' + (e if isinstance(e, str) else json.dumps(e)) + '\n\n' for e in events)
            return self.send(raw, kind='text/event-stream')
        tools = data.get('tools')
        if tools and len(messages) == 1:
            if chat:
                call = {'id': 'tool-1', 'type': 'function', 'function': {'name': 'probe_echo', 'arguments': '{"code":"FW_TOOL_OK"}'}}
                if self.mode == 'bad_tool':
                    call['function']['name'] = 'run_shell'
                return self.send({'choices': [{'message': {'content': None, 'tool_calls': [call]}}]})
            return self.send({'content': [{'type': 'tool_use', 'id': 'tool-1', 'name': 'probe_echo', 'input': {'code': 'FW_TOOL_OK'}}]})
        if tools:
            text = messages[-1]['content'] if chat else messages[-1]['content'][0]['content']
            if self.mode == 'bad_roundtrip':
                text = 'I ignored the tool result'
        elif len(messages) == 3:
            text = messages[0]['content'].split(': ')[-1]
        elif 'JSON object' in messages[0]['content']:
            text = '{"probe":"FW_JSON_OK","n":7}'
        else:
            text = 'FW_PROBE_OK'
        if self.path == '/custom':
            return self.send({'data': {'answer': text}})
        result = {'choices': [{'message': {'content': text}}]} if chat else {'content': [{'type': 'text', 'text': text}]}
        result['usage'] = {'input_tokens': 10, 'output_tokens': 4, 'evil': SECRET}
        self.send(result)


class ProbeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = 'http://127.0.0.1:' + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def setUp(self):
        Handler.mode, Handler.requests = 'ok', []

    def config(self, protocol='chat_completions'):
        return {'base_url': self.base + '/v1', 'model': 'test-model', 'protocol': protocol,
                'headers': {'Authorization': 'Bearer ' + SECRET}}

    def test_passive_does_not_infer(self):
        report = probe.Probe(self.config()).run()
        self.assertEqual(report['models'], ['test-model'])
        self.assertEqual([r[0] for r in Handler.requests], ['GET'])
        self.assertIsNone(report['protocol'])

    def test_chat_full_roundtrip_and_redaction(self):
        report = probe.Probe(self.config()).run(True)
        self.assertTrue(all(c['status'] == 'PASS' for c in report['checks']), report)
        self.assertEqual(report['request_count'], 7)
        self.assertNotIn(SECRET, json.dumps(report))
        self.assertEqual(report['protocol'], 'chat_completions')

    def test_messages_full_roundtrip(self):
        report = probe.Probe(self.config('messages')).run(True)
        self.assertTrue(all(c['status'] == 'PASS' for c in report['checks']), report)
        self.assertEqual(report['protocol'], 'messages')

    def test_auto_falls_back_on_404(self):
        Handler.mode = 'chat404'
        report = probe.Probe(self.config('auto')).run(True, [])
        self.assertEqual(report['protocol'], 'messages')
        self.assertEqual(report['request_count'], 3)

    def test_empty_checks_means_basic_only(self):
        report = probe.Probe(self.config()).run(True, [])
        self.assertEqual(report['request_count'], 2)

    def test_login_uses_only_explicit_login_headers(self):
        config = self.config()
        config['login'] = {'url': self.base + '/login', 'body': {'username': 'test', 'password': SECRET}, 'token_path': 'data.access_token'}
        probe.Probe(config).run()
        self.assertEqual(Handler.requests[0][3], None)
        self.assertEqual(Handler.requests[1][3], 'Bearer ' + SECRET)

    def test_failed_login_stops_requests(self):
        Handler.mode = 'bad_login'
        config = self.config()
        config['login'] = {'url': self.base + '/login', 'body': {}, 'token_path': 'data.access_token'}
        report = probe.Probe(config).run(True)
        self.assertEqual(report['checks'][0]['status'], 'BLOCKED')
        self.assertEqual(report['request_count'], 1)

    def test_redirect_not_followed(self):
        Handler.mode = 'redirect'
        report = probe.Probe(self.config()).run()
        self.assertEqual(report['checks'][-1]['reason'], 'REDIRECT_LOGIN_OR_ROUTING')
        self.assertEqual(len(Handler.requests), 1)

    def test_html_login_page_is_not_model(self):
        Handler.mode = 'html'
        report = probe.Probe(self.config()).run()
        self.assertEqual(report['checks'][-1]['status'], 'BLOCKED')
        self.assertEqual(report['checks'][-1]['reason'], 'HTML_LOGIN_OR_WEBPAGE_NOT_MODEL_JSON')

    def test_auth_and_rate_not_unsupported(self):
        for mode in ('denied', 'rate'):
            Handler.mode = mode
            report = probe.Probe(self.config('auto')).run(True)
            self.assertEqual(report['checks'][-1]['status'], 'BLOCKED')
            self.assertNotIn(SECRET, json.dumps(report))
            self.assertEqual(len([r for r in Handler.requests if r[0] == 'POST']), 1)
            Handler.requests = []

    def test_stream_requires_end_and_rejects_error(self):
        for mode in ('truncated', 'sse_error'):
            Handler.mode = mode
            report = probe.Probe(self.config()).run(True, ['streaming'])
            self.assertEqual(report['checks'][-1]['status'], 'NOT_CONFIRMED')
            self.assertNotIn(SECRET, json.dumps(report))

    def test_tool_name_and_roundtrip_are_validated(self):
        for mode in ('bad_tool', 'bad_roundtrip'):
            Handler.mode = mode
            report = probe.Probe(self.config()).run(True, ['tools'])
            self.assertEqual(report['checks'][-1]['status'], 'NOT_CONFIRMED')

    def test_custom_only_claims_mapped_capabilities(self):
        config = self.config('custom')
        config.update(base_url=self.base, models_path=None, custom={'path': '/custom',
                      'body': {'history': '{{messages}}', 'model': '{{model}}'}, 'text_path': 'data.answer'})
        report = probe.Probe(config).run(True)
        self.assertEqual(report['protocol'], 'custom')
        self.assertEqual([c['status'] for c in report['checks'][-3:]], ['NOT_TESTED'] * 3)

    def test_validation_and_env(self):
        for url in ('https://user:password@test/api', 'https://test/api?token=secret', 'file:///etc/passwd', 'http://intranet/v1'):
            with self.assertRaises(probe.Failure):
                probe.validate_url(url)
        self.assertEqual(probe.validate_url('http://intranet', True), 'http://intranet')
        with patch.dict(os.environ, {'FW_TEST_SECRET': SECRET}):
            self.assertEqual(probe.expand({'key': '${FW_TEST_SECRET}'}), {'key': SECRET})
        with self.assertRaises(probe.Failure):
            probe.expand('${FW_TEST_MISSING_123}')

    def test_no_model_fails_before_network(self):
        config = self.config()
        config['model'] = ''
        with self.assertRaises(probe.Failure):
            probe.Probe(config).run(True)
        self.assertEqual(Handler.requests, [])

    def test_multiline_sse_and_size_limit(self):
        raw = b'data: {"choices":\ndata: []}\n\ndata: [DONE]\n\n'
        result = probe.read_sse(io.BytesIO(raw), probe.time.monotonic(), 10)
        self.assertEqual(result['events'][0], {'choices': []})
        with self.assertRaises(probe.Failure):
            probe.read_sse(io.BytesIO(b'data: ' + b'x' * 65537), probe.time.monotonic(), 10)

    def test_catalog_does_not_record_reflected_credential(self):
        Handler.mode = 'reflected_secret'
        report = probe.Probe(self.config()).run()
        self.assertEqual(report['models'], [])
        self.assertNotIn(SECRET, json.dumps(report))

    def test_partial_line_deadline_and_malformed_response(self):
        class Drip:
            def read1(self, size):
                return b'data: '
        with patch.object(probe.time, 'monotonic', return_value=12):
            with self.assertRaisesRegex(probe.Failure, 'STREAM_DEADLINE'):
                probe.read_sse(Drip(), 0, 10)
        instance = probe.Probe(self.config('messages'))
        with patch.object(instance.client, 'request', return_value={'content': ['bad']}):
            self.assertFalse(instance.record('basic', lambda: instance.basic('messages')))
        self.assertEqual(instance.report['checks'][0]['status'], 'NOT_CONFIRMED')

    def test_cli_private_output_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            output = Path(directory) / 'report.json'
            config.write_text(json.dumps(self.config()))
            argv = ['probe', '--config', str(config), '--output', str(output)]
            captured = io.StringIO()
            with patch('sys.argv', argv), contextlib.redirect_stdout(captured), contextlib.redirect_stderr(captured):
                self.assertEqual(probe.main(), 0)
                request_count = len(Handler.requests)
                self.assertEqual(probe.main(), 1)
            self.assertEqual(len(Handler.requests), request_count)
            self.assertNotIn(SECRET, output.read_text() + captured.getvalue())
            if os.name != 'nt':
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()
