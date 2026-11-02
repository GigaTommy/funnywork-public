const labels: Record<string, string> = {
  SOURCE_AUTHORIZATION: '资料授权', CANDIDATE_FORMAT: '候选格式', SNAPSHOT_VERSION: '资料版本',
  RECOMPUTED_TOTALS: '收入与成本汇总', PROFIT_RECONCILIATION: '利润勾稽',
  GROWTH_ROUNDING: '同比与舍入', EXACT_SOURCE_COVERAGE: '来源覆盖', SERVER_RENDERING: '正式文件生成'
};
const statuses: Record<string, string> = { PASSED: '通过', FAILED: '失败', NOT_RUN: '未执行', UNRECORDED: '未记录单项结果' };
const reasons: Record<string, string> = {
  CURRENT_AUTHORIZATION_VALID: '当前授权有效', MATCHED: '与后端独立核验结果一致',
  CANDIDATE_FORMAT_INVALID: '候选字段或格式不符合要求', INVALID_CANDIDATE_FORMAT: '候选格式不符，无法核验此项',
  SNAPSHOT_MISMATCH: '候选引用的资料快照不一致', TOTALS_MISMATCH: '汇总金额与后端重算结果不一致',
  PROFIT_MISMATCH: '利润与后端重算结果不一致', GROWTH_MISMATCH: '同比或舍入结果不一致',
  SOURCES_MISMATCH: '来源存在遗漏、重复或额外资料', SERVER_FILE_CREATED: '后端已生成正式文件',
  CANDIDATE_REJECTED: '候选未通过，未生成正式文件', LEGACY_EVIDENCE: '历史证据仅保存整体结论'
};
/** Preserve historical overall verdicts without inventing per-check results. */
export function annualCheckRows(value: unknown) {
  if (!value || typeof value !== 'object' || !('checks' in value) || !Array.isArray(value.checks)) return [];
  return value.checks.flatMap((item: unknown) => {
    const legacy = typeof item === 'string';
    if (!legacy && (!item || typeof item !== 'object' || !('code' in item) || typeof item.code !== 'string')) return [];
    const code = typeof item === 'string' ? item : (item as { code: string }).code;
    const state = !legacy && item && typeof item === 'object' && 'status' in item && typeof item.status === 'string' ? item.status : 'UNRECORDED';
    const reason = !legacy && item && typeof item === 'object' && 'reason' in item && typeof item.reason === 'string' ? item.reason : 'LEGACY_EVIDENCE';
    return [{ code, label: labels[code] || code, status: statuses[state] || statuses.UNRECORDED, reason: reasons[reason] || '核验原因未记录' }];
  });
}
