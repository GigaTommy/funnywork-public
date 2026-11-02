#!/usr/bin/env python3
"""Verify local users without a configured directory against the local gateway.

Creates a temporary user, then deletes it and logs out test sessions.
Credentials are read from the protected local deployment file, never printed.
"""
import json
import pathlib
import secrets
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = 'http://127.0.0.1:8765/ruoyi'
CLIENT = 'e5cd7e4891bf95d1d19206ce24a7b32e'


def call(path, method='GET', data=None, token=None):
    headers = {'Content-Type': 'application/json', 'clientid': CLIENT}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
        data=None if data is None else json.dumps(data).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return json.loads(error.read())


def ok(result):
    assert result.get('code') == 200, 'API check failed: ' + str(result.get('code'))
    return result


def login(username, password):
    return call('/auth/login', 'POST', {'tenantId': '000000', 'username': username,
        'password': password, 'clientId': CLIENT, 'grantType': 'password'})


def main():
    credentials = json.loads((ROOT / '.local/ruoyi/credentials.json').read_text())
    admin = ok(login(credentials['username'], credentials['password']))['data']['access_token']
    username = 'local_check_' + secrets.token_hex(5)
    user_id = None
    role_id = None
    sessions = []
    try:
        assert ok(call('/funnyworker/identity-sources', token=admin))['data'] == [], 'Run this check before configuring an external directory'
        ok(call('/system/role', 'POST', {'roleName': '本地账号验收', 'roleKey': username,
            'roleSort': 99, 'status': '0', 'menuCheckStrictly': True,
            'deptCheckStrictly': True, 'dataScope': '5', 'menuIds': [], 'deptIds': []}, admin))
        role_id = ok(call('/system/role/list?roleKey=' + username, token=admin))['rows'][0]['roleId']
        password = secrets.token_urlsafe(18)
        ok(call('/system/user', 'POST', {'userName': username, 'nickName': '本地账号验收',
            'deptId': 103, 'password': password, 'status': '0', 'sex': '0',
            'roleIds': [role_id], 'postIds': []}, admin))
        rows = ok(call('/system/user/list?userName=' + username + '&accountSource=local', token=admin))['rows']
        assert len(rows) == 1 and not rows[0].get('externalSource'), 'Local user missing or incorrectly classified'
        user_id = rows[0]['userId']
        assert not ok(call('/system/user/list?userName=' + username + '&accountSource=external', token=admin))['rows'], 'External filter included local user'
        sessions.append(ok(login(username, password))['data']['access_token'])
        ok(call('/system/user/getInfo', token=sessions[-1]))
        password = secrets.token_urlsafe(18)
        ok(call('/system/user/resetPwd', 'PUT', {'userId': user_id, 'password': password}, admin))
        sessions.append(ok(login(username, password))['data']['access_token'])
        ok(call('/system/user/changeStatus', 'PUT', {'userId': user_id, 'status': '1'}, admin))
        assert login(username, password).get('code') != 200, 'Disabled local user could still log in'
    finally:
        for token in sessions:
            call('/auth/logout', 'POST', {}, token)
        # Recover the ID even if an assertion failed just after creation.
        if user_id is None:
            rows = ok(call('/system/user/list?userName=' + username, token=admin))['rows']
            user_id = next((r['userId'] for r in rows if r['userName'] == username), None)
        if user_id is not None:
            ok(call('/system/user/' + str(user_id), 'DELETE', token=admin))
        if role_id is None:
            roles = ok(call('/system/role/list?roleKey=' + username, token=admin))['rows']
            role_id = next((r['roleId'] for r in roles if r['roleKey'] == username), None)
        if role_id is not None:
            ok(call('/system/role/' + str(role_id), 'DELETE', token=admin))
        ok(call('/auth/logout', 'POST', {}, admin))
    print('Passed: no identity platform; local creation, source filters, login, profile, password reset, disabled login rejection; test user removed.')


if __name__ == '__main__':
    main()
