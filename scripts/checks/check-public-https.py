#!/usr/bin/env python3
"""Read-only, credential-free checks from a host outside the management VPN."""
import argparse
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('origin', help='HTTPS origin, e.g. https://worker.example.com')
    parser.add_argument('--user-agent', help='Identify the probe to an upstream gateway')
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.origin)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.path not in ('', '/') or url.query or url.fragment):
        parser.error('Provide only an HTTPS origin without credentials')
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))
    if args.user_agent:
        opener.addheaders = [('User-Agent', args.user_agent)]
    failed = False
    for path, expected in [('/api/account/session', {401}), ('/admin/index.html', {403}),
                           ('/ruoyi/auth/login', {403}), ('/api/v1/tasks', {403}),
                           ('/actuator/health', {403})]:
        try:
            response = opener.open(args.origin.rstrip('/') + path, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        except (urllib.error.URLError, TimeoutError) as error:
            print(f'FAIL {path}: TLS/network check failed ({type(error).__name__})')
            failed = True
            continue
        with response:
            status = response.code
            hsts = response.headers.get('Strict-Transport-Security', '')
            ok = status in expected and 'max-age=' in hsts and 'max-age=0' not in hsts
            print(f'{"PASS" if ok else "FAIL"} {path}: HTTP {status}, HSTS={bool(hsts)}')
            failed |= not ok
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
