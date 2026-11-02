#!/usr/bin/env python3
"""Reject embedded credential values in tracked deployment configuration.

Only locations and field names are reported. General source/history scanning is
performed separately with a secret scanner; test fixtures and public verification
keys are not treated as deployment credentials by this check.
"""
from pathlib import Path
import re
import json
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
ASSIGNMENT = re.compile(r'''^\s*(?:#\s*)?["']?([A-Za-z_][A-Za-z0-9_.-]*)["']?\s*[:=]\s*(.*?)\s*,?\s*$''')
XML_VALUE = re.compile(r'<([A-Za-z_][A-Za-z0-9_.-]*)>([^<]*)</\1>')
SECRET_SUFFIXES = ('password', 'passwd', 'apikey', 'clientsecret', 'privatekey',
                   'secretkey', 'accesskeysecret', 'accesstoken', 'signingkey',
                   'bootstraptoken', 'webkey')


def configuration(path):
    name = Path(path).name
    if path.startswith('apps/management/'):
        return name.startswith('.env.') and name.endswith('.example')
    if path.startswith('services/identity/'):
        return ((name.startswith('application') and name.endswith('.yml.example'))
                or path == 'services/identity/pom.xml'
                or path.startswith('services/identity/script/docker/')
                and name.endswith(('.yml', '.conf', '.env.example')))
    if path.startswith('services/business/src/main/resources/'):
        return name.startswith('application') and name.endswith(('.yml', '.yaml'))
    if path.startswith(('deploy/', 'scripts/setup/')):
        return name.endswith(('.example', '.example.json', '.yml', '.yaml'))
    return False


def sensitive(name):
    normalized = re.sub(r'[^a-z0-9]', '', name.lower())
    return normalized == 'token' or normalized.endswith(SECRET_SUFFIXES)


def reference_or_placeholder(value):
    value = value.strip().rstrip(',').strip().strip('"\'')
    if not value or value.lower() in ('null', '~'):
        return True
    if value.startswith('${') and value.endswith('}'):
        expression = value[2:-1]
        name, separator, default = expression.partition(':')
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.-]*', name):
            return False
        return not separator or default in ('', '-') or default.startswith('?')
    if re.fullmatch(r'\$[A-Za-z_][A-Za-z0-9_]*', value):
        return True
    if re.fullmatch(r'@[A-Za-z_][A-Za-z0-9_.-]*@', value):
        return True
    return (value.startswith('<') and value.endswith('>')
            or value.upper().startswith(('YOUR_', 'BASE64_', 'REPLACE_', 'GENERATE_'))
            or value.lower().startswith('replace-with-'))


def violations(path, text):
    found = []
    if path.endswith('.json'):
        try:
            document = json.loads(text)
        except json.JSONDecodeError:
            return [(1, 'invalid-json-configuration')]
        def visit(value):
            if isinstance(value, dict):
                for name, item in value.items():
                    if sensitive(name) and item is not None and not reference_or_placeholder(str(item)):
                        location = re.search(r'"' + re.escape(name) + r'"\s*:', text)
                        number = text[:location.start()].count('\n') + 1 if location else 1
                        found.append((number, name))
                    visit(item)
            elif isinstance(value, list):
                for item in value:
                    visit(item)
        visit(document)
    for number, line in enumerate(text.splitlines(), 1):
        if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', line):
            found.append((number, 'private-key-block'))
        match = ASSIGNMENT.match(line)
        fields = [(match[1], match[2])] if match else []
        fields.extend((m[1], m[2]) for m in XML_VALUE.finditer(line))
        redis = re.match(r'^\s*(requirepass|masterauth)\s+(.+)$', line)
        if redis:
            fields.append((redis[1], redis[2]))
        for name, value in fields:
            if (sensitive(name) or name in ('requirepass', 'masterauth')) and not reference_or_placeholder(value):
                found.append((number, name))
    return found


def main():
    paths = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    errors = []
    checked = 0
    for path in paths:
        if not configuration(path) or not (ROOT / path).is_file():
            continue
        checked += 1
        for line, field in violations(path, (ROOT / path).read_text(encoding='utf-8')):
            errors.append(f'{path}:{line}: embedded credential in {field}; use a runtime reference')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Deployment configuration credentials: {checked} tracked files checked; references/placeholders only')
    return 0


if __name__ == '__main__':
    sys.exit(main())
