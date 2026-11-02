#!/usr/bin/env python3
"""Validate model UAT evidence and write a scoped report; never auto-sign production acceptance."""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]


def local(value):
    path = value.resolve()
    try:
        path.relative_to((ROOT / '.local').resolve())
    except ValueError:
        raise ValueError('UAT evidence must be under repository .local/.')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--regression', type=Path, default=ROOT / '.local/uat/regression-final')
    parser.add_argument('--browser', type=Path, default=ROOT / '.local/uat/browser')
    parser.add_argument('--manual', type=Path, default=ROOT / '.local/uat/manual-pending.json')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/uat')
    args = parser.parse_args()
    try:
        regression, browser, manual, output = map(local, (args.regression, args.browser, args.manual, args.output))
        r = json.loads((regression / 'regression-results.json').read_text(encoding='utf-8'))
        m = json.loads(manual.read_text(encoding='utf-8'))
        lanes, failures = [], []
        required_regressions = {f'R{i:02}' for i in range(1, 9)}
        if {row['id'] for row in r['results']} != required_regressions:
            failures.append('Regression step coverage is incomplete.')
        for row in r['results']:
            if row['status'] != 'PASS':
                failures.append(row['id'] + ': ' + row['status'])
            if row.get('log') and not (regression / row['log']).is_file():
                failures.append(row['id'] + ': missing log')
        for name, expected in (('management', 36), ('client', 12)):
            lane = json.loads((browser / ('results-' + name + '.json')).read_text(encoding='utf-8'))
            if lane.get('plannedCases') != expected or lane.get('notRun') or len(lane['results']) != expected:
                failures.append(name + ': browser coverage is incomplete')
            ids = [row['id'] for row in lane['results']]
            expected_ids = ([f'M{i:02}' for i in range(1, 13)] + ['M13-401', 'M13-403', 'M13-503'] + [f'M{i:02}' for i in range(14, 19)] + [f'U{i:02}' for i in range(1, 14)] + ['U14-401', 'U14-403', 'V01']) if name == 'management' else [f'C{i:02}' for i in range(1, 12)] + ['V02']
            if set(ids) != set(expected_ids):
                failures.append(name + ': required case IDs are missing or unexpected')
            if len(set(ids)) != len(ids):
                failures.append(name + ': duplicate latest case IDs')
            for row in lane['results']:
                if row['status'] != 'PASS':
                    failures.append(row['id'] + ': ' + row['status'])
                image = (browser / row.get('screenshot', '')).resolve()
                if not image.is_relative_to(browser) or image.suffix not in ('.png', '.jpg', '.jpeg') or not image.is_file():
                    failures.append(row['id'] + ': missing screenshot')
                elif not (image.read_bytes()[:8] == b'\x89PNG\r\n\x1a\n' or image.read_bytes()[:3] == b'\xff\xd8\xff'):
                    failures.append(row['id'] + ': invalid image evidence')
                else:
                    row['evidenceSha256'] = hashlib.sha256(image.read_bytes()).hexdigest()
                    row['screenshotAbsolute'] = str(image)
            lanes.append(lane)
    except (ValueError, KeyError, OSError) as error:
        parser.error(str(error))
    output.mkdir(parents=True, exist_ok=True)
    browser_count = sum(len(lane['results']) for lane in lanes)
    java = next(row for row in r['results'] if row['id'] == 'R01').get('reportedTests', {})
    automatic_count = java.get('tests', 0) + sum(row.get('passedTests', 0) for row in r['results'])
    manual_counts = {status: sum(case['status'] == status for case in m['cases'])
                     for status in ('PASS', 'FAIL', 'NOT_RUN', 'BLOCKED')}
    report = dict(generatedAtUTC=datetime.now(timezone.utc).isoformat(),
                  status='SOURCE_AND_MOCK_UAT_PASSED' if not failures else 'FAIL',
                  automaticTests=automatic_count, browserCases=browser_count,
                  failures=failures, manualStatusCounts=manual_counts,
                  productionAcceptance='MANUAL_SIGNOFF_REQUIRED', lanes=lanes,
                  notes=['Real production components with mock interfaces; not full application acceptance.',
                         'Historical browser script/locator failures and retries are retained.',
                         'Installed Electron, real RuoYi permissions, PostgreSQL replicas and Ascend remain separate acceptance.'])
    (output / 'acceptance-summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# 模型管理与 Harness UAT 测试报告', '', '报告时间：' + datetime.now(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S') + '（Asia/Shanghai）。', '',
             f"结论：**{report['status']}**。自动回归 {automatic_count} 项、页面交互 {browser_count} 项。", '',
             '页面为真实源码组件，接口/目录为隔离模拟；Java测试实际启动本地业务HTTP服务与H2。完整安装包、真实若依权限、PostgreSQL多副本、实际昇腾兼容与容量没有在本次签字。', '',
             '## 自动检查', '', '| 检查 | 结果 | 证据 |', '| --- | --- | --- |']
    for row in r['results']:
        path = regression / row.get('log', '')
        lines.append(f"| {row['id']} {row['name']} | {row['status']} | [日志]({path}) |")
    for lane in lanes:
        lines.extend(['', '## ' + ('管理端页面' if lane['lane'] == 'management' else '客户端模型选择'), '',
                      '| 用例 | 结果 | 截图 |', '| --- | --- | --- |'])
        for row in lane['results']:
            image = row.get('screenshotAbsolute', '')
            lines.append(f"| {row['id']} {row['name']} | {row['status']} | [截图]({image}) |")
    lines.extend(['', '## 尚未签字的完整应用验收', '',
                  f"人工脚本 {len(m['cases'])} 项：" + '、'.join(f'{key}={value}' for key, value in manual_counts.items()) + '。', '',
                  f'[完整人工脚本]({ROOT / "services/business/MODEL_UAT_MANUAL.md"})；[待执行记录]({manual})。', '',
                  '## 执行记录说明', '',
                  '浏览器执行中曾修正测试桩隔离与定位问题：等待新页面/业务结果、关闭上一项抽屉、使用控件实际标签、核对JSON提交字段；整轮管理测试超出工具单次时限后分批续跑。历史失败和每次截图保留在浏览器JSON的history，不作为产品缺陷删除或隐藏。', '',
                  '本轮没有为通过UAT改写生产页面或替换成静态仿制界面。证据校验会拒绝缺失用例、非PASS、缺失截图或跳过构建。', ''])
    if failures:
        lines.extend(['## 未通过项', '', *['- ' + value for value in failures]])
    (output / 'UAT_REPORT.md').write_text('\n'.join(lines), encoding='utf-8')
    print(report['status'] + f': automated={automatic_count}, browser={browser_count}, manual_not_run={manual_counts["NOT_RUN"]}')
    print('Report: ' + str(output / 'UAT_REPORT.md'))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
