#!/usr/bin/env python3
"""Verify security-sensitive runtime dependencies in the assembled identity JAR."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def check_runtime(jar, expected):
    with zipfile.ZipFile(jar) as archive:
        libraries = [Path(name).name for name in archive.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
    errors = []
    for name in ['bcpkix-jdk18on', 'bcprov-jdk18on', 'bcutil-jdk18on']:
        actual = [library for library in libraries if library.startswith(name + '-')]
        if actual != [name + '-' + expected + '.jar']:
            errors.append(name + ': expected ' + expected + '; found ' + ', '.join(actual))
    if any(library.startswith('fory-core-') for library in libraries):
        errors.append('Unused Fory parser must not be included in the identity runtime')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'services/identity/ruoyi-admin/target/ruoyi-admin.jar')
    args = parser.parse_args()
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    expected = ET.parse(ROOT / 'services/identity/pom.xml').findtext('m:properties/m:bouncycastle.version', namespaces=namespace)
    if not expected:
        raise SystemExit('Missing managed Bouncy Castle version')
    errors = check_runtime(args.jar, expected)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Identity runtime dependency checks passed: Bouncy Castle ' + expected + '; unused Fory parser absent')


if __name__ == '__main__':
    main()
