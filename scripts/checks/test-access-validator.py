#!/usr/bin/env python3
"""Exercise access assertion checks and burst handling on an isolated loopback port.

Requires the validator host's existing PyJWT/cryptography packages. Uses generated
keys and synthetic claims; never uses or modifies production assertions/config.
"""
import argparse
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import threading
import time
import urllib.error
import urllib.request
from unittest.mock import patch

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validator', type=Path, required=True)
    args = parser.parse_args()
    config = {'issuer': 'https://access.invalid', 'audience': 'acceptance-only', 'email': 'acceptance@example.invalid'}
    spec = importlib.util.spec_from_file_location('access_validator_test', args.validator)
    module = importlib.util.module_from_spec(spec)
    # The module's startup config read is isolated from the production config.
    with patch('pathlib.Path.read_text', return_value=json.dumps(config)):
        spec.loader.exec_module(module)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    module.KEYS = {'fixture': key.public_key()}
    module.FETCHED = time.monotonic()
    base = dict(config, iss=config['issuer'], aud=config['audience'], exp=int(time.time()) + 600)
    base.pop('issuer')
    base.pop('audience')

    def signed(claims, signing=key, kid='fixture'):
        return jwt.encode(claims, signing, algorithm='RS256', headers={'kid': kid})

    good = signed(base)
    missing_exp = dict(base)
    missing_exp.pop('exp')
    cases = [
        ('valid', good, 204),
        ('wrong issuer', signed(dict(base, iss='https://other.invalid')), 403),
        ('wrong audience', signed(dict(base, aud='other')), 403),
        ('expired', signed(dict(base, exp=int(time.time()) - 60)), 403),
        ('missing expiry', signed(missing_exp), 403),
        ('wrong email', signed(dict(base, email='other@example.invalid')), 403),
        ('wrong signature', signed(base, rsa.generate_private_key(public_exponent=65537, key_size=2048)), 403),
        ('unknown key', signed(base, kid='absent'), 403),
        ('wrong algorithm', jwt.encode(base, 'synthetic-test-key', algorithm='HS256', headers={'kid': 'fixture'}), 403),
        ('empty', '', 403),
        ('oversized', 'x' * 16385, 403),
    ]
    active = peak = 0
    lock = threading.Lock()
    validate = module.validate

    def observed(token):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
        try:
            # Make a burst sustain enough concurrency to exercise admission.
            time.sleep(.05)
            return validate(token)
        finally:
            with lock:
                active -= 1

    module.validate = observed
    server = module.AccessServer(('127.0.0.1', 0), module.Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = 'http://127.0.0.1:' + str(server.server_port) + '/verify'

    def request(token):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'Cf-Access-Jwt-Assertion': token}), timeout=2) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    try:
        for name, token, expected in cases:
            assert request(token) == expected, name
        inputs = [cases[index % len(cases)] for index in range(160)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=80) as pool:
            responses = list(pool.map(lambda case: request(case[1]), inputs))
        assert responses == [case[2] for case in inputs]
        assert 1 < peak <= 24, peak
        print(json.dumps({'jwtCasesPassed': len(cases), 'burstRequests': len(inputs), 'parallelClients': 80,
            'peakValidationWorkers': peak, 'allStatusesExpected': True, 'productionConfigUntouched': True}))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
