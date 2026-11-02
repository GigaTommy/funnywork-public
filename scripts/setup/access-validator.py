#!/usr/bin/python3
"""Validate Cloudflare Access assertions for the local Nginx auth_request endpoint."""
import json, pathlib, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import jwt
CONFIG = json.loads(pathlib.Path('/etc/funnyworker-access.json').read_text())
LOCK = threading.Lock()
KEYS = {}
FETCHED = 0.0

def signing_key(kid):
    global KEYS, FETCHED
    now = time.monotonic()
    with LOCK:
        if now - FETCHED > 300 or (kid not in KEYS and now - FETCHED > 30):
            req = urllib.request.Request(CONFIG['issuer']+'/cdn-cgi/access/certs', headers={'User-Agent': 'FunnyWorker-Access-Validator/1.0'})
            with urllib.request.urlopen(req, timeout=5) as response:
                data = json.loads(response.read(262145))
            KEYS = {k['kid']: jwt.algorithms.RSAAlgorithm.from_jwk(json.dumps(k)) for k in data['keys'] if k.get('kty') == 'RSA'}
            FETCHED = now
        if kid not in KEYS:
            raise jwt.InvalidTokenError('Unknown signing key')
        return KEYS[kid]

def validate(token):
    if not token or len(token)>16384:
        raise jwt.InvalidTokenError('Missing or oversized assertion')
    header = jwt.get_unverified_header(token)
    if header.get('alg') != 'RS256' or not isinstance(header.get('kid'),str):
        raise jwt.InvalidTokenError('Invalid algorithm or key identifier')
    claims = jwt.decode(token, signing_key(header['kid']), algorithms=['RS256'], issuer=CONFIG['issuer'], audience=CONFIG['audience'], options={'require':['exp','iss','aud','email']})
    if claims['email'] != CONFIG['email']:
        raise jwt.InvalidTokenError('Email is not permitted')
    return claims

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        status = 403
        if self.path == '/verify':
            try:
                validate(self.headers.get('Cf-Access-Jwt-Assertion',''))
                status = 204
            except Exception:
                pass  # Fail closed, including during signing-key retrieval failures.
        self.send_response(status)
        self.send_header('Cache-Control','no-store')
        self.send_header('Content-Length','0')
        self.end_headers()
    def log_message(self,*args):
        pass

class AccessServer(ThreadingHTTPServer):
    # Module preloads can open dozens of auth subrequests at once. Keep accepted
    # connections queued while bounding threads below the unit's TasksMax=64.
    request_queue_size = 128
    daemon_threads = True

    def __init__(self, *args, **kwargs):
        self.worker_slots = threading.BoundedSemaphore(24)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        self.worker_slots.acquire()
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.worker_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.worker_slots.release()

    def get_request(self):
        request, address = super().get_request()
        request.settimeout(8)
        return request, address


if __name__ == '__main__':
    AccessServer(('127.0.0.1',8768),Handler).serve_forever()
