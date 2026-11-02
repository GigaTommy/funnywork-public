/** Safe, actionable business errors. Server messages may contain implementation details. */
export class BusinessError extends Error {
  constructor(
    public code: string,
    public status = 0
  ) {
    super(code);
  }
}

export function businessErrorMessage(error: unknown): string {
  if (!(error instanceof BusinessError)) return '操作结果未确认，请刷新核对结果后再操作。';
  if (error.code === 'INVALID_AUDIT_FILTER') return '请检查时间区间（最长 93 天）、事件类型和关联编号。';
  if (error.code === 'INVALID_HISTORY_QUERY') return '请检查会话来源、成员标识及分页条件，然后重新查询。';
  if (error.code === 'INVALID_USAGE_FILTER') return '请选择有效的时间区间，最长为 93 天。';
  if (error.code === 'SESSION_CHANGED') return '账号会话已变化，请重新打开页面后核对结果。';
  if (error.code === 'SELF_SCOPE_CHANGE') return '不能修改自己的业务授权，请选择其他账号。';
  if (error.code === 'IDENTITY_UNAVAILABLE') return '身份服务暂时不可用，请稍后刷新核对授权状态再重试。';
  if (error.status === 401) return '登录已失效，请重新登录。';
  if (error.code === 'BUSINESS_SCOPE_REQUIRED') return '当前账号尚未配置业务授权，请联系管理员开通。';
  if (error.code === 'DESKTOP_PERMISSION_REQUIRED') return '当前账号尚未获得桌面使用权限，请联系管理员开通。';
  if (error.code === 'MODEL_NOT_CONFIGURED') return '部署方尚未提供有效的桌面模型与签名配置，请先完成服务端配置。';
  if (error.code === 'MODEL_CREDENTIAL_UNAVAILABLE') return '模型凭证不可用，请由部署管理员检查凭证引用。';
  if (error.status === 403) return '当前账号无权执行此操作，请联系管理员核对权限与授权范围。';
  if (error.code === 'SYNC_CHECK_ENFORCEMENT_REQUIRED') return '项目门禁未启用，三端检查已拒绝；请由管理员核对配置。';
  if (error.code === 'SYNC_CHECK_EXECUTOR_UNAVAILABLE') return '选定终端已离线、平台不符或 Codex 能力不可用，请重新读取终端。';
  if (error.code === 'SYNC_CHECK_TARGETS_REQUIRED') return '必须选择 macOS、Windows、Linux 三个不同的已授权终端。';
  if (error.code === 'EXECUTION_PROJECT_CONFLICT') return '该仓库已属于其他项目；开发与验证路由必须使用同一项目。';
  if (error.code === 'EXECUTION_VERSION_CONFLICT') return '配置已被更新，请刷新后重新编辑，原修改未覆盖新版本。';
  if (error.code === 'INVALID_EXECUTION_QUERY') return '请检查终端查询条件后重试。';
  if (error.code === 'REPORT_MATERIALS_INCOMPLETE') return '资料编号不可重复，且须同时提供营业收入、营业成本和上年收入。';
  if (error.code === 'REPORT_ALREADY_REQUESTED') return '此资料快照已发起编制，请查看执行记录；修订数据请新增资料集。';
  if (error.code === 'REPORT_SOURCE_CHANGED') return '资料版本或授权已变化，旧候选不能交付。';
  if (error.code === 'REQUIREMENT_SCOPE_INVALID') return '涉及路径必须位于项目受理范围内，且不能包含敏感路径。';
  if (error.code === 'REQUIREMENT_FROZEN') return '此需求修订已冻结；请查看执行记录，或新建后续需求。';
  if (error.code === 'REQUIREMENT_CRITERION_INVALID') return '验收条件编号无效或重复，请刷新后重新编辑。';
  if (error.code === 'REQUIREMENT_VERSION_CHANGED' || error.code === 'DEPENDENCY_MAP_CHANGED' || error.code === 'REQUIREMENT_IMPACT_STALE')
    return '需求或依赖配置已变化，请刷新后重新操作。';
  if (error.code === 'REQUIREMENT_REVIEW_ACTIVE') return '需求正在审查中，请等待本轮结束后补充。';
  if (error.code === 'REQUIREMENT_TOO_LARGE') return '需求和验收条件过长，请拆分为可独立验证的需求。';
  if (error.status === 409) return '记录已变化或存在冲突，请刷新核对最新状态后再编辑。';
  if (error.status === 400 || error.status === 422) return '提交内容未通过校验，请检查必填项及配置。';
  if (error.status === 404) return '记录不存在或已移除，请刷新列表。';
  if (error.status === 429) return '请求过于频繁，请稍后再试。';
  return '服务暂时不可用或连接中断，操作结果未确认，请刷新核对后再操作。';
}

/** A confirmed write and its subsequent read have distinct outcomes. Never retry the write here. */
export async function saveAndRefresh(write: () => Promise<void>, refresh: () => Promise<void>): Promise<string> {
  await write();
  try {
    await refresh();
    return '操作已完成。';
  } catch {
    return '操作已完成，但最新状态读取失败，请刷新页面；无需重复提交。';
  }
}

/** Authorization loss invalidates previously readable records and drafts. */
export function businessAccessLost(error: unknown): boolean {
  return error instanceof BusinessError && (error.status === 401 || error.status === 403 || error.code === 'SESSION_CHANGED');
}
