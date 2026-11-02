#!/usr/bin/env python3
"""Run isolated model-management acceptance checks or serve the real-component UI fixture."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / 'apps/desktop'
MANAGEMENT = ROOT / 'apps/management'
DESKTOP_TESTS = [
    'packages/llm/llm-pi-ai/tests/enterprise-presets.spec.ts',
    'packages/preset/agent-preset-registry/tests/registry.spec.ts',
    'packages/client/ui-settings-models/tests',
    'packages/client/ui-agent-preset/tests',
    'packages/llm/llm-pi-ai/tests/enterprise-runtime.spec.ts',
    'packages/llm/llm-pi-ai/tests/enterprise-store.spec.ts',
    'packages/llm/llm-pi-ai/tests/admission.spec.ts',
    'packages/llm/llm-pi-ai/tests/adapter.spec.ts',
    'packages/llm/llm-pi-ai/tests/dynamic-config.spec.ts',
    'packages/llm/llm-deepseek/tests/prepared-call.spec.ts',
    'packages/llm/llm/tests/topology.spec.ts',
    'packages/util/timeout/tests/timeout.spec.ts',
    'packages/compaction/compaction-basic/tests',
    'packages/client/ui-model-selection/tests',
    'apps/desktop/tests/funnyworker-inference.spec.ts',
    'apps/desktop/tests/funnyworker-policy.spec.ts',
    'apps/desktop/tests/funnyworker-account.spec.ts',
    'apps/desktop/tests/funnyworker-login.client.spec.tsx',
]


def utc():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('regression', 'serve'), default='regression')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/uat' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    parser.add_argument('--skip-build', action='store_true', help='Skip production management build/type checks; never a full acceptance result.')
    args = parser.parse_args()
    node = shutil.which('node')
    if not node:
        parser.error('Node.js is required; use the repository development setup.')
    out = args.output.resolve()
    # Evidence and logs may contain test identifiers; never put them in tracked source directories.
    try:
        out.relative_to((ROOT / '.local').resolve())
    except ValueError:
        parser.error('--output must be under repository .local/.')
    out.mkdir(parents=True, exist_ok=True)
    if args.mode == 'serve':
        print('Isolated UI fixture: http://127.0.0.1:19192/ (management) and /client.html (Harness model selector).')
        print('No production service or model API is called. Stop with Ctrl+C.')
        return subprocess.call([node, str(MANAGEMENT / 'node_modules/vite/bin/vite.js'), '--config',
                                str(MANAGEMENT / 'uat/model-middleware/vite.config.mjs')], cwd=ROOT)
    for target in DESKTOP_TESTS:
        if not (DESKTOP / target).exists():
            parser.error('Missing required acceptance suite: ' + target)
    results = []
    environment = os.environ.copy()
    environment.pop('FUNNYWORK_MODEL_TEST_DB_URL', None)  # Default acceptance must use isolated H2, never an inherited DB target.
    environment.update(NO_COLOR='1', CI='1')

    def run(case, description, command, cwd=ROOT, timeout=300):
        started = time.monotonic()
        log = out / (case + '.log')
        item = dict(id=case, name=description, startedAt=utc(), log=log.name)
        try:
            with log.open('w', encoding='utf-8') as stream:
                completion = subprocess.run(command, cwd=cwd, env=environment, stdout=stream,
                                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
            item['status'] = 'PASS' if completion.returncode == 0 else 'FAIL'
            item['exitCode'] = completion.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            item['status'] = 'FAIL'
            item['error'] = type(error).__name__ + ': ' + str(error)
        item['durationSeconds'] = round(time.monotonic() - started, 2)
        # Only summarize pass counts, never echo arbitrary logs or credentials.
        content = log.read_text(encoding='utf-8', errors='replace') if log.exists() else ''
        match = re.search(r'Tests\s+(\d+) passed', content)
        if match:
            item['passedTests'] = int(match.group(1))
        results.append(item)
        print(case + ' ' + item['status'] + ': ' + description, flush=True)
        return item['status'] == 'PASS'

    maven = ROOT / '.local/tools/apache-maven-3.9.9/bin' / ('mvn.cmd' if os.name == 'nt' else 'mvn')
    maven_command = str(maven) if maven.exists() else shutil.which('mvn')
    if maven_command is None:
        parser.error('Maven is required; first run the repository setup or scripts/mvn.sh -version.')
    run('R01', 'Actual business HTTP, capacity, scope and usage contracts (isolated H2)',
        [maven_command, '-q', '-f', str(ROOT / 'services/business/pom.xml'),
         '-Dtest=ModelMiddlewareIntegrationTest,ModelUsageTest,EnterpriseAgentPresetsTest', '-DskipTests=false', '-Dmaven.test.skip=false', 'test'])
    java_counts = dict(tests=0, failures=0, errors=0, skipped=0)
    fresh_java = True
    for name in ('ModelMiddlewareIntegrationTest', 'ModelUsageTest', 'EnterpriseAgentPresetsTest'):
        report = ROOT / 'services/business/target/surefire-reports' / ('TEST-com.funnywork.' + name + '.xml')
        if report.exists() and report.stat().st_mtime >= datetime.fromisoformat(results[0]['startedAt']).timestamp():
            suite = ET.parse(report).getroot()
            for key in java_counts:
                java_counts[key] += int(suite.attrib.get(key, '0'))
            shutil.copyfile(report, out / report.name)
        else:
            fresh_java = False
    results[0]['reportedTests'] = java_counts
    if results[0]['status'] == 'PASS' and (not fresh_java or java_counts['tests'] == 0 or java_counts['failures'] or java_counts['errors']):
        results[0]['status'] = 'FAIL'
        results[0]['error'] = 'Fresh Java test evidence is missing or reports failures.'
    run('R02', 'Harness private models, credentials, admission, compaction and client behavior',
        [node, 'node_modules/vitest/vitest.mjs', 'run', *DESKTOP_TESTS], DESKTOP)
    run('R03', 'Management business feedback, forms and permission helpers',
        [node, 'node_modules/vitest/vitest.mjs', 'run'], MANAGEMENT)
    if not args.skip_build:
        built = run('R04', 'Production management build', [node, 'node_modules/vite/bin/vite.js', 'build', '--mode', 'production'], MANAGEMENT)
        if built:
            run('R05', 'Management type contracts', [node, 'node_modules/vue-tsc/bin/vue-tsc.js', '--noEmit'], MANAGEMENT)
        else:
            results.append(dict(id='R05', status='BLOCKED', name='Management type contracts', reason='Production declaration generation failed.'))
        run('R06', 'Desktop Host and private model type contracts',
            [node, 'node_modules/typescript/bin/tsc', '-b', 'packages/llm/llm-pi-ai', 'packages/compaction/compaction-basic',
             'apps/desktop/tsconfig.host.json', 'packages/client/ui-model-selection'], DESKTOP)
    else:
        results.extend(dict(id=case, status='NOT_RUN', name='Build/type acceptance skipped') for case in ('R04', 'R05', 'R06'))
    run('R07', 'Repository layout and maintained imports', [sys.executable, 'scripts/checks/check-repository.py'])
    run('R08', 'Real-component UAT fixture builds', [node, 'node_modules/vite/bin/vite.js', 'build', '--config',
                                                 'uat/model-middleware/vite.config.mjs'], MANAGEMENT)
    failed = any(item['status'] in ('FAIL', 'BLOCKED') for item in results)
    report = dict(startedScope='isolated-regression', completedAt=utc(),
                  status='FAIL' if failed else 'REGRESSION_CHECKS_PASSED', results=results,
                  pendingAcceptance=['Browser UAT must be executed with browser-uat.mjs and screenshots',
                                     'Installed Electron on macOS and Windows', 'Real RuoYi login/menu/role integration',
                                     'Actual Ascend engine compatibility and capacity', 'PostgreSQL multi-replica soak test'])
    (out / 'regression-results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Evidence: ' + str(out))
    print('Regression results do not constitute production UAT sign-off.')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
