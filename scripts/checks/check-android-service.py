"""Check the Android account entry contract using the same unauthenticated checks as iOS.

Never follows redirects, disables TLS validation, sends credentials, or performs inference.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

OFFICIAL = list(dict.fromkeys(filter(None, [os.environ.get('FUNNYWORK_REGIONAL_ORIGIN'), 'https://funnywork.funnypixelsapp.com'])))

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def probe(origin, opener):
    uri = urllib.parse.urlsplit(origin)
    if uri.scheme != 'https' or not uri.hostname or uri.username or uri.password or uri.path not in ('', '/') or uri.query or uri.fragment:
        raise ValueError('Provide an HTTPS origin without credentials, path, query or fragment')
    results = []
    for path in ['session', 'sync/sessions?limit=1', 'chats/models', 'chats?history=true', 'inference/budget']:
        started = time.monotonic()
        row = {'path': path}
        request = urllib.request.Request(origin.rstrip('/') + '/api/account/' + path,
            headers={'User-Agent': 'FunnyWork-Android-service-check/1.0', 'Cache-Control': 'no-store'})
        try:
            try:
                response = opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                raw = response.read(32769)
                try:
                    payload = json.loads(raw) if len(raw) <= 32768 else {}
                except (ValueError, UnicodeError):
                    payload = {}
                code = payload.get('code') if isinstance(payload, dict) else None
                no_store = 'no-store' in response.headers.get('Cache-Control', '').lower()
                row.update(status=response.code, accountResponse=code == 'UNAUTHENTICATED', noStore=no_store)
                row['passed'] = response.code == 401 and code == 'UNAUTHENTICATED' and no_store
        except (OSError, urllib.error.URLError) as error:
            row.update(passed=False, error=type(error).__name__)
        row['elapsedMs'] = round((time.monotonic() - started) * 1000)
        results.append(row)
    return {'origin': origin, 'passed': all(row['passed'] for row in results), 'checks': results}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', action='append')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(), urllib.request.HTTPSHandler(context=context))
    report = {'time': datetime.now(timezone.utc).isoformat(), 'authenticated': False, 'device': False,
              'scope': 'Host network/TLS and account authentication boundary; not Android or production login acceptance',
              'entries': [probe(origin, opener) for origin in args.origin or OFFICIAL]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    for entry in report['entries']:
        print(('PASS' if entry['passed'] else 'FAIL') + ' ' + entry['origin'])
    return 0 if all(entry['passed'] for entry in report['entries']) else 1

if __name__ == '__main__':
    raise SystemExit(main())
