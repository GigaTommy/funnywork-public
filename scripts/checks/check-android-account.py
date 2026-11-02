#!/usr/bin/env python3
"""Fixed-entry real-account Android/iOS/desktop regression; no inference or write replay.

Reads an authorized private username/password JSON file or one JSON line on stdin.
Only creates and revokes this run's login sessions. Reports never include credentials,
tokens, display names, history text, or raw account identifiers.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from urllib.parse import urlsplit
from pathlib import Path
import ssl
import sys
import urllib.error
import urllib.request
import uuid

OFFICIAL = tuple(dict.fromkeys(filter(None, (os.environ.get('FUNNYWORK_REGIONAL_ORIGIN'), 'https://funnywork.funnypixelsapp.com'))))
for origin in OFFICIAL:
    parsed = urlsplit(origin)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.port or parsed.path or parsed.query or parsed.fragment:
        raise ValueError('Expected a canonical HTTPS service origin')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Failure(Exception):
    pass


class Entry:
    def __init__(self, origin):
        self.origin = origin
        context = ssl.create_default_context()
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=context))

    def request(self, path, token='', body=None):
        headers = {'User-Agent': 'FunnyWork-Android-account-check/1.0', 'Cache-Control': 'no-store'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if body is not None:
            headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(self.origin + '/api/account/' + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            response = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, urllib.error.URLError):
            raise Failure('Network failure') from None
        with response:
            raw = response.read(4 * 1024 * 1024 + 1)
            if len(raw) > 4 * 1024 * 1024:
                raise Failure('Response exceeds limit')
            if response.code < 200 or response.code >= 300:
                # Do not echo arbitrary server error bodies, URLs, or Location headers.
                raise Failure('HTTP ' + str(response.code))
            try:
                payload = json.loads(raw)
            except (ValueError, UnicodeError):
                raise Failure('Invalid JSON') from None
            if not isinstance(payload, (dict, list)):
                raise Failure('Expected JSON object or array')
            return payload


def identity(session):
    if not isinstance(session, dict):
        raise Failure('Invalid session object')
    tenant, principal = session.get('tenant'), session.get('principalId')
    if not isinstance(tenant, str) or not tenant or not isinstance(principal, str) or not principal:
        raise Failure('Missing stable account identity')
    return hashlib.sha256(json.dumps([tenant, principal], separators=(',', ':')).encode()).hexdigest()


def check(entry, peer, client, credentials):
    row = {'origin': entry.origin, 'client': client, 'checks': [], 'passed': False}
    token = ''
    try:
        login = entry.request('login', body={
            'username': credentials['username'], 'password': credentials['password'],
            'client': client, 'device': 'android-parity-check-' + uuid.uuid4().hex})
        token = login.get('token', '') if isinstance(login, dict) else ''
        if not isinstance(token, str) or not token or len(token) > 16000:
            token = ''
            raise Failure('Invalid login token')
        row['loginPassed'] = True
        session = entry.request('session', token)
        account = identity(session)
        row['identityHash'] = account
        row['peerSessionMatches'] = identity(peer.request('session', token)) == account
        for path in ('sync/sessions?limit=1', 'chats/models', 'chats?history=true', 'inference/budget'):
            item = {'path': path}
            try:
                entry.request(path, token)
                item['passed'] = True
            except Failure as error:
                item.update(passed=False, error=str(error))
            row['checks'].append(item)
        row['passed'] = row['peerSessionMatches'] and all(item['passed'] for item in row['checks'])
    except Failure as error:
        row['error'] = str(error)
    finally:
        if token:
            try:
                entry.request('logout', token, {})
                row['logoutPassed'] = True
                try:
                    entry.request('session', token)
                    row['revokedSessionRejected'] = False
                except Failure as error:
                    row['revokedSessionRejected'] = str(error) == 'HTTP 401'
            except Failure:
                row['logoutPassed'] = False
            row['passed'] &= row.get('logoutPassed', False) and row.get('revokedSessionRejected', False)
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--account-file', type=Path)
    group.add_argument('--credentials-stdin', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--client', action='append', choices=('android', 'ios', 'desktop'))
    args = parser.parse_args()
    try:
        credentials = json.loads(sys.stdin.readline() if args.credentials_stdin else args.account_file.read_text())
        if not all(isinstance(credentials.get(key), str) and credentials[key] for key in ('username', 'password')):
            raise ValueError()
    except (OSError, ValueError, AttributeError):
        parser.error('Supply a valid authorized private account JSON; contents are never printed')
    entries = [Entry(origin) for origin in OFFICIAL]
    rows = []
    for client in args.client or ('android', 'ios', 'desktop'):
        for index, entry in enumerate(entries):
            row = check(entry, entries[(index + 1) % len(entries)], client, credentials)
            rows.append(row)
            print(('PASS' if row['passed'] else 'FAIL') + ' ' + row['client'] + ' ' + row['origin'], flush=True)
    identities = {row['identityHash'] for row in rows if 'identityHash' in row}
    complete = len(identities) == 1 and all(row['passed'] for row in rows)
    report = {'time': datetime.now(timezone.utc).isoformat(), 'device': False,
              'scope': 'Host fixed-entry account regression; no inference, no Android device acceptance',
              'verifiedSessionIdentitiesMatch': len(identities) == 1,
              'sameAccountAcrossClients': len(identities) == 1 and all('identityHash' in row for row in rows),
              'passed': complete, 'entries': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return 0 if complete else 1


if __name__ == '__main__':
    raise SystemExit(main())
