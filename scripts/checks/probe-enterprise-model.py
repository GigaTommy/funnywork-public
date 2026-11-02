#!/usr/bin/env python3
"""Bounded enterprise model protocol/capability POC. Python 3.10+, stdlib only."""
import argparse
import getpass
from datetime import datetime, timezone
import json
import http.client
import os
from pathlib import Path
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

LIMIT = 2 * 1024 * 1024
ENV = re.compile(r'\$\{([A-Z][A-Z0-9_]*)\}')


class Failure(Exception):
    def __init__(self, code, status=None):
        self.code, self.status = code, status
        super().__init__(code)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def lookup(value, path):
    for part in path.split('.') if path else []:
        value = value[int(part)] if isinstance(value, list) else value[part]
    return value


def expand(value):
    if isinstance(value, dict):
        return {k: expand(v) for k, v in value.items()}
    if isinstance(value, list):
        return [expand(v) for v in value]
    if isinstance(value, str):
        def replace(match):
            found = os.environ.get(match[1])
            if not found:
                raise Failure('MISSING_ENV_' + match[1])
            return found
        return ENV.sub(replace, value)
    return value


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)


def render(value, slots):
    if isinstance(value, dict):
        return {k: render(v, slots) for k, v in value.items()}
    if isinstance(value, list):
        return [render(v, slots) for v in value]
    if isinstance(value, str) and value in slots:
        return slots[value]
    return value


def validate_url(url, allow_http=False):
    p = urllib.parse.urlsplit(url)
    if (p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password
            or p.query or p.fragment):
        raise Failure('INVALID_URL_USE_NO_QUERY_CREDENTIALS')
    if p.scheme == 'http' and p.hostname not in ('127.0.0.1', '::1', 'localhost') and not allow_http:
        raise Failure('HTTP_REQUIRES_EXPLICIT_ALLOW_HTTP')
    return url.rstrip('/')


class Client:
    def __init__(self, config):
        self.config = config
        self.base = validate_url(config['base_url'], config.get('allow_http', False))
        self.timeout = float(config.get('timeout_seconds', 30))
        if not 1 <= self.timeout <= 120:
            raise Failure('TIMEOUT_MUST_BE_1_TO_120')
        context = ssl.create_default_context(cafile=config.get('ca_file'))
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        # Never inherit shell proxy settings or silently follow an SSO redirect with credentials.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                  urllib.request.HTTPSHandler(context=context))
        self.headers = dict(config.get('headers', {}))
        self.requests = 0

    def endpoint(self, path):
        if not isinstance(path, str) or not path.startswith('/') or path.startswith('//'):
            raise Failure('API_PATH_MUST_START_WITH_SINGLE_SLASH')
        target = validate_url(self.base + path, self.config.get('allow_http', False))
        if urllib.parse.urlsplit(target).netloc != urllib.parse.urlsplit(self.base).netloc:
            raise Failure('API_ORIGIN_CHANGED')
        return target

    def request(self, path=None, body=None, stream=False, url=None, headers=None):
        target = url or self.endpoint(path)
        target = validate_url(target, self.config.get('allow_http', False))
        head = {'Content-Type': 'application/json', 'Accept': 'text/event-stream' if stream else 'application/json',
                'User-Agent': 'funnywork-enterprise-probe/1'}
        head.update(self.headers if headers is None else headers)
        raw = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        started = time.monotonic()
        self.requests += 1
        try:
            with self.opener.open(urllib.request.Request(target, data=raw, headers=head), timeout=self.timeout) as response:
                if stream:
                    if not response.headers.get('Content-Type', '').lower().startswith('text/event-stream'):
                        raise Failure('EXPECTED_SSE_NOT_RETURNED')
                    return read_sse(response, started, self.timeout)
                chunks, size = [], 0
                while True:
                    chunk = response.read1(min(65536, LIMIT + 1 - size))
                    size += len(chunk)
                    if size > LIMIT:
                        raise Failure('RESPONSE_SIZE_LIMIT')
                    if time.monotonic() - started > self.timeout:
                        raise Failure('RESPONSE_DEADLINE')
                    if not chunk:
                        break
                    chunks.append(chunk)
                data = b''.join(chunks)
                if 'text/html' in response.headers.get('Content-Type', '').lower() or data.lstrip().startswith(b'<'):
                    raise Failure('HTML_LOGIN_OR_WEBPAGE_NOT_MODEL_JSON')
                return json.loads(data)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            code = ('AUTH_REQUIRED' if status in (401, 403) else
                    'REDIRECT_LOGIN_OR_ROUTING' if 300 <= status < 400 else
                    'RATE_LIMITED' if status == 429 else 'HTTP_ERROR')
            raise Failure(code, status) from None
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError, http.client.HTTPException):
            raise Failure('NETWORK_OR_TLS_ERROR') from None
        except (ValueError, UnicodeError):
            raise Failure('INVALID_JSON_RESPONSE') from None

    def login(self):
        login = self.config.get('login')
        if not login:
            return
        # Auth-origin credentials are separate from model headers; no cookie jar or implicit relay.
        result = self.request(url=login['url'], body=login['body'], headers=login.get('headers', {}))
        try:
            token = lookup(result, login['token_path'])
            if not isinstance(token, str) or not token or '\n' in token or '\r' in token:
                raise ValueError()
            self.headers[login.get('header', 'Authorization')] = login.get('prefix', 'Bearer ') + token
        except (KeyError, IndexError, TypeError, ValueError):
            raise Failure('LOGIN_TOKEN_NOT_FOUND') from None


def stream_lines(response, started, timeout):
    buffer, size = b'', 0
    while True:
        chunk = response.read1(4096)
        size += len(chunk)
        if size > LIMIT:
            raise Failure('STREAM_SIZE_LIMIT')
        if time.monotonic() - started > timeout:
            raise Failure('STREAM_DEADLINE')
        buffer += chunk
        while b'\n' in buffer:
            line, buffer = buffer.split(b'\n', 1)
            if len(line) > 65536:
                raise Failure('STREAM_SIZE_LIMIT')
            yield line.decode('utf-8').rstrip('\r')
        if len(buffer) > 65536:
            raise Failure('STREAM_SIZE_LIMIT')
        if not chunk:
            if buffer:
                raise Failure('TRUNCATED_SSE_EVENT')
            break


def read_sse(response, started, timeout):
    events, fields, first_ms = [], [], None
    for line in stream_lines(response, started, timeout):
        if len(events) >= 4096:
            raise Failure('STREAM_SIZE_LIMIT')
        if line == '':
            if fields:
                data = '\n'.join(fields)
                event = '[DONE]' if data == '[DONE]' else json.loads(data)
                if isinstance(event, dict) and (event.get('error') or event.get('type') == 'error'):
                    raise Failure('UPSTREAM_STREAM_ERROR')
                events.append(event)
                first_ms = first_ms if first_ms is not None else round((time.monotonic() - started) * 1000)
                fields = []
                if event == '[DONE]' or isinstance(event, dict) and event.get('type') == 'message_stop':
                    break
        elif line.startswith('data:'):
            fields.append(line[5:].lstrip(' '))
    if fields:
        raise Failure('TRUNCATED_SSE_EVENT')
    return {'events': events, 'first_event_ms': first_ms,
            'elapsed_ms': round((time.monotonic() - started) * 1000)}


def text_of(response, protocol, config):
    if protocol == 'chat_completions':
        text = lookup(response, 'choices.0.message.content')
    elif protocol == 'messages':
        text = ''.join(b.get('text', '') for b in response['content'] if b.get('type') == 'text')
    else:
        text = lookup(response, config['custom']['text_path'])
    if not isinstance(text, str):
        raise Failure('TEXT_RESPONSE_SCHEMA_MISMATCH')
    return text


def usage_of(response):
    usage = response.get('usage') if isinstance(response, dict) else None
    if not isinstance(usage, dict):
        return {}
    return {k: v for k, v in usage.items() if k in (
        'prompt_tokens', 'completion_tokens', 'total_tokens', 'input_tokens', 'output_tokens')
        and isinstance(v, int) and not isinstance(v, bool) and v >= 0}


class Probe:
    def __init__(self, config):
        self.config, self.client = config, Client(config)
        self.model = config.get('model', '')
        self.protocol = config.get('protocol', 'auto')
        if self.protocol not in ('auto', 'chat_completions', 'messages', 'custom'):
            raise Failure('INVALID_PROTOCOL')
        self.report = {'schema_version': 1, 'created_at': datetime.now(timezone.utc).isoformat(),
                       'checks': [], 'models': [], 'protocol': None,
                       'limits': {'context_tokens': 'NOT_TESTED', 'concurrency': 'NOT_TESTED',
                                  'images': 'NOT_TESTED', 'server_cancellation': 'NOT_TESTED'},
                       'notes': ['Results apply only to this account, endpoint, model and deployment at test time.',
                                 'Failure does not prove the underlying model lacks a capability.',
                                 'No business data, generated text, credentials or raw responses are recorded.']}

    def record(self, name, fn):
        started = time.monotonic()
        try:
            evidence = fn()
            self.report['checks'].append({'name': name, 'status': 'PASS', 'evidence': evidence,
                                          'elapsed_ms': round((time.monotonic() - started) * 1000)})
            return True
        except (Failure, KeyError, IndexError, TypeError, ValueError, AttributeError) as error:
            code = error.code if isinstance(error, Failure) else 'RESPONSE_SCHEMA_OR_ASSERTION_MISMATCH'
            status = error.status if isinstance(error, Failure) else None
            self.report['checks'].append({'name': name, 'status': 'BLOCKED' if status in (401, 403, 429) or
                                          code.startswith(('LOGIN_', 'AUTH_', 'REDIRECT_', 'HTML_')) else 'NOT_CONFIRMED',
                                          'reason': code, 'http_status': status})
            return False

    def catalog(self):
        data = self.client.request(self.config.get('models_path', '/models'))
        items = lookup(data, self.config.get('models_items_path', 'data'))
        if not isinstance(items, list):
            raise Failure('MODEL_LIST_SCHEMA_MISMATCH')
        secrets = list(strings(self.client.headers)) + list(strings(self.config.get('login', {})))
        fragments = [part for secret in secrets for part in re.split(r'[\s;=]+', secret) if part]
        ids = []
        for item in items[:200]:
            value = lookup(item, self.config.get('model_id_path', 'id'))
            if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_./:@+-]{1,160}', value):
                if not any(value == secret or len(secret) >= 4 and secret in value for secret in fragments):
                    ids.append(value)
        self.report['models'] = list(dict.fromkeys(ids))
        return {'model_count_returned': len(items), 'saved_ids': len(ids), 'metadata_is_not_capability_proof': True}

    def infer(self, messages, protocol, extra=None, stream=False):
        body = {'model': self.model, 'messages': messages, 'max_tokens': 128, 'stream': stream}
        body.update(self.config.get('request_defaults', {}))
        # Call structure and budgets cannot be overridden by defaults.
        body.update(model=self.model, messages=messages, max_tokens=128, stream=stream)
        body.update(extra or {})
        if protocol == 'custom':
            custom = self.config['custom']
            body = render(custom['body'], {'{{model}}': self.model, '{{messages}}': messages,
                                          '{{max_tokens}}': 128, '{{stream}}': stream})
            path = custom['path']
        else:
            path = self.config.get('chat_path', '/chat/completions') if protocol == 'chat_completions' else self.config.get('messages_path', '/messages')
        return self.client.request(path, body, stream=stream)

    def basic(self, protocol):
        result = self.infer([{'role': 'user', 'content': 'Reply with exactly FW_PROBE_OK.'}], protocol)
        if 'FW_PROBE_OK' not in text_of(result, protocol, self.config):
            raise Failure('BASIC_EXPECTED_TEXT_MISSING')
        return {'response_shape': protocol, 'usage': usage_of(result)}

    def multi_turn(self):
        marker = 'FW_' + uuid.uuid4().hex[:12]
        response = self.infer([{'role': 'user', 'content': 'Remember this code: ' + marker},
                               {'role': 'assistant', 'content': 'I will remember the code.'},
                               {'role': 'user', 'content': 'Return only the code from the first message.'}], self.protocol)
        if marker not in text_of(response, self.protocol, self.config):
            raise Failure('MULTI_TURN_MARKER_MISSING')
        return {'caller_supplied_history_verified': True, 'server_session_storage': 'NOT_TESTED'}

    def structured(self):
        extra = {'response_format': {'type': 'json_object'}} if self.protocol == 'chat_completions' else {}
        response = self.infer([{'role': 'user', 'content': 'Return only a JSON object exactly {"probe":"FW_JSON_OK","n":7}.'}], self.protocol, extra)
        value = json.loads(text_of(response, self.protocol, self.config))
        if value != {'probe': 'FW_JSON_OK', 'n': 7}:
            raise Failure('JSON_CONTENT_MISMATCH')
        return {'valid_json_observed': True, 'schema_enforcement': 'NOT_TESTED',
                'json_mode_requested': self.protocol == 'chat_completions'}

    def streaming(self):
        response = self.infer([{'role': 'user', 'content': 'Reply with exactly FW_STREAM_OK.'}], self.protocol, stream=True)
        text, terminal, chunks, usage = '', False, 0, {}
        for event in response['events']:
            if event == '[DONE]':
                terminal = self.protocol == 'chat_completions'
                continue
            if not isinstance(event, dict):
                raise Failure('STREAM_SCHEMA_MISMATCH')
            if self.protocol == 'chat_completions':
                chunk = ''.join(c.get('delta', {}).get('content') or '' for c in event.get('choices', []))
            else:
                chunk = event.get('delta', {}).get('text', '') if event.get('type') == 'content_block_delta' else ''
                terminal |= event.get('type') == 'message_stop'
            text += chunk
            chunks += bool(chunk)
            usage.update(usage_of(event))
            usage.update(usage_of(event.get('message', {})))
        if not terminal or 'FW_STREAM_OK' not in text:
            raise Failure('STREAM_INCOMPLETE_OR_TEXT_MISSING')
        return {'text_events': chunks, 'terminal_event': terminal, 'usage': usage,
                'first_event_ms': response['first_event_ms'], 'elapsed_ms': response['elapsed_ms'],
                'proxy_buffering': 'NOT_TESTED'}

    def tools(self):
        schema = {'type': 'object', 'properties': {'code': {'type': 'string'}}, 'required': ['code'], 'additionalProperties': False}
        if self.protocol == 'chat_completions':
            extra = {'tools': [{'type': 'function', 'function': {'name': 'probe_echo', 'description': 'Return a test code.', 'parameters': schema}}],
                     'tool_choice': {'type': 'function', 'function': {'name': 'probe_echo'}}}
        else:
            extra = {'tools': [{'name': 'probe_echo', 'description': 'Return a test code.', 'input_schema': schema}],
                     'tool_choice': {'type': 'tool', 'name': 'probe_echo'}}
        messages = [{'role': 'user', 'content': 'Call probe_echo with code FW_TOOL_OK, then repeat the tool result exactly.'}]
        response = self.infer(messages, self.protocol, extra)
        if self.protocol == 'chat_completions':
            message = lookup(response, 'choices.0.message')
            calls = message.get('tool_calls', [])
            if len(calls) != 1 or calls[0].get('type') != 'function':
                raise Failure('EXPECTED_SINGLE_TOOL_CALL')
            call = calls[0]
            name, args = call['function']['name'], json.loads(call['function']['arguments'])
            call_id = call['id']
            assistant = {'role': 'assistant', 'content': message.get('content'), 'tool_calls': calls}
        else:
            calls = [b for b in response['content'] if b.get('type') == 'tool_use']
            if len(calls) != 1:
                raise Failure('EXPECTED_SINGLE_TOOL_CALL')
            call = calls[0]
            name, args, call_id = call['name'], call['input'], call['id']
            assistant = {'role': 'assistant', 'content': response['content']}
        if name != 'probe_echo' or args != {'code': 'FW_TOOL_OK'} or not isinstance(call_id, str) or not call_id:
            raise Failure('TOOL_ARGUMENTS_MISMATCH')
        # No execution of provider-supplied code: only return a local random string.
        marker = 'FW_RESULT_' + uuid.uuid4().hex[:12]
        messages.append(assistant)
        messages.append({'role': 'tool', 'tool_call_id': call_id, 'content': marker} if self.protocol == 'chat_completions' else
                        {'role': 'user', 'content': [{'type': 'tool_result', 'tool_use_id': call_id, 'content': marker}]})
        extra['tool_choice'] = 'none' if self.protocol == 'chat_completions' else {'type': 'auto'}
        final = self.infer(messages, self.protocol, extra)
        if marker not in text_of(final, self.protocol, self.config):
            raise Failure('TOOL_RESULT_ROUNDTRIP_NOT_CONFIRMED')
        return {'forced_tool_roundtrip_verified': True, 'autonomous_tool_selection': 'NOT_TESTED',
                'parallel_tools': 'NOT_TESTED', 'tool_streaming': 'NOT_TESTED'}

    def run(self, active=False, checks=None):
        if active and (not isinstance(self.model, str) or not self.model):
            raise Failure('MODEL_REQUIRED_FOR_ACTIVE_PROBES')
        if not self.record('authentication_setup', lambda: (self.client.login() or {'configured_login_completed': bool(self.config.get('login'))})):
            return self.finish()
        if self.config.get('models_path', '/models') is None:
            self.report['checks'].append({'name': 'model_catalog', 'status': 'NOT_TESTED', 'reason': 'NO_CATALOG_CONFIGURED'})
        else:
            self.record('model_catalog', self.catalog)
        if not active:
            return self.finish()
        if not isinstance(self.model, str) or not self.model:
            raise Failure('MODEL_REQUIRED_FOR_ACTIVE_PROBES')
        candidates = ('chat_completions', 'messages') if self.protocol == 'auto' else (self.protocol,)
        for protocol in candidates:
            if self.record('basic_' + protocol, lambda p=protocol: self.basic(p)):
                self.protocol = protocol
                self.report['protocol'] = protocol
                break
            if self.report['checks'][-1]['status'] == 'BLOCKED':
                return self.finish()
        else:
            return self.finish()
        for name in dict.fromkeys(('multi_turn', 'streaming', 'structured', 'tools') if checks is None else checks):
            if self.protocol == 'custom' and name != 'multi_turn':
                self.report['checks'].append({'name': name, 'status': 'NOT_TESTED', 'reason': 'CUSTOM_ADAPTER_NOT_CONFIGURED'})
            else:
                self.record(name, getattr(self, name))
                if self.report['checks'][-1]['status'] == 'BLOCKED':
                    break
        return self.finish()

    def finish(self):
        self.report['request_count'] = self.client.requests
        self.report['inference_verified'] = self.report['protocol'] is not None
        return self.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, help='Private JSON configuration (credentials via ${ENV_VAR})')
    parser.add_argument('--output', required=True, help='New report file; refuses to overwrite existing files')
    parser.add_argument('--prompt-env', action='append', default=[], help='Read a referenced environment variable without echo (repeatable, interactive terminal only)')
    parser.add_argument('--active', action='store_true', help='Send synthetic inference calls; consumes model quota')
    parser.add_argument('--checks', default='multi_turn,streaming,structured,tools', help='Comma separated subset of optional active checks')
    args = parser.parse_args()
    try:
        selected = [v for v in args.checks.split(',') if v]
        if not set(selected) <= {'multi_turn', 'streaming', 'structured', 'tools'}:
            raise Failure('INVALID_CHECK_NAME')
        output = Path(args.output)
        if output.exists():
            raise Failure('OUTPUT_ALREADY_EXISTS')
        for name in args.prompt_env:
            if not re.fullmatch(r'[A-Z][A-Z0-9_]*', name):
                raise Failure('INVALID_ENV_NAME')
            if not sys.stdin.isatty():
                raise Failure('INTERACTIVE_SECRET_REQUIRES_TTY')
            os.environ[name] = getpass.getpass(name + ': ')
        config = expand(json.loads(Path(args.config).read_text(encoding='utf-8')))
        probe = Probe(config)
        report = probe.run(args.active, selected)
        output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write('\n')
        for check in report['checks']:
            print(check['name'] + ': ' + check['status'] + (' (' + check['reason'] + ')' if 'reason' in check else ''))
        print('Requests: ' + str(report['request_count']) + '; report written.')
        return 0 if all(c['status'] in ('PASS', 'NOT_TESTED') for c in report['checks']) else 2
    except (Failure, OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        print('Probe stopped: ' + (error.code if isinstance(error, Failure) else type(error).__name__) +
              '. No credentials or response body printed.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
