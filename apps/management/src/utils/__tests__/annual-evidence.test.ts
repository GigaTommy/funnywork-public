import { expect, it } from 'vitest';
import { annualCheckRows } from '../annual-evidence';
it('shows failed checks and skipped delivery without claiming successful rendering', () => {
  expect(annualCheckRows({ checks: [
    { code: 'PROFIT_RECONCILIATION', status: 'FAILED', reason: 'PROFIT_MISMATCH' },
    { code: 'SERVER_RENDERING', status: 'NOT_RUN', reason: 'CANDIDATE_REJECTED' }
  ] })).toMatchInlineSnapshot(`
    [
      {
        "code": "PROFIT_RECONCILIATION",
        "label": "利润勾稽",
        "reason": "利润与后端重算结果不一致",
        "status": "失败",
      },
      {
        "code": "SERVER_RENDERING",
        "label": "正式文件生成",
        "reason": "候选未通过，未生成正式文件",
        "status": "未执行",
      },
    ]
  `);
});
it('does not invent per-check passes for historical evidence', () => {
  expect(annualCheckRows({ verified: false, checks: ['PROFIT_RECONCILIATION', 'SERVER_RENDERING'] }).map(v => v.status)).toEqual(['未记录单项结果', '未记录单项结果']);
});
it('shows successful backend delivery explicitly', () => {
  expect(annualCheckRows({ checks: [{ code: 'SERVER_RENDERING', status: 'PASSED', reason: 'SERVER_FILE_CREATED' }] })[0]).toEqual({ code: 'SERVER_RENDERING', label: '正式文件生成', status: '通过', reason: '后端已生成正式文件' });
});
