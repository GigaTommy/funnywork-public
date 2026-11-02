export const executionReasons: Record<string, string> = {
  TENANT_AT_CAPACITY: '组织执行名额已用满',
  AGENT_AT_CAPACITY: '此用途的调度配额已用满',
  RECOVERY_REQUIRED: '旧执行记录需要恢复核对',
  POLICY_REQUIRED: '尚未配置用途路由',
  REPOSITORY_DISABLED: '资源已停用',
  ROUTE_DISABLED: '路由已停用',
  PROJECT_DISABLED: '项目已停用',
  POOL_DISABLED: '资源池已停用',
  PROFILE_DISABLED: '用途已停用',
  PROFILE_PLATFORM_MISMATCH: '用途不支持所选平台',
  NOT_MEMBER: '不在此资源池',
  NO_VALID_GRANT: '没有有效项目授权',
  EXPLICIT_DENY: '已明确拒绝此终端',
  DEVICE_REVOKED: '设备已撤销',
  OWNER_DISABLED: '所属账号已停用',
  OWNER_SCOPE_MISMATCH: '所属账号无此区域权限',
  NOT_BOUND: '未接入任务通道',
  NODE_DISABLED: '任务节点已停用',
  NODE_PAUSED: '终端已暂停接单',
  PLATFORM_MISMATCH: '终端平台不匹配',
  EXECUTOR_MISMATCH: '客户端执行能力不匹配',
  AUTHORIZATION_CHANGED: '当前授权校验未通过',
  OFFLINE: '终端离线或未准备好',
  AT_CAPACITY: '终端没有空闲名额',
  WRITER_BUSY: '仓库已有开发任务或待确认进程',
  ENFORCEMENT_DISABLED: '项目门禁未启用',
  NO_ELIGIBLE_AVAILABLE_RESOURCE: '暂时没有合格且空闲的终端'
};
export const executionStates: Record<string, string> = {
  ASSIGNED: '已分派',
  WAITING_FOR_ELIGIBLE_RESOURCE: '等待合格终端',
  EXECUTION_UNCONFIRMED: '执行停止待确认',
  RUNNING: '执行中',
  RESERVED: '已预留',
  SUCCEEDED: '已完成',
  OUTPUT_READY: '候选已回传',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED: '已失效',
  BLOCKED_AUTH: '授权已撤销',
  RECONCILING: '待核对',
  RECONCILED: '已核对',
  REVOKED: '已撤销'
};
export const executionAction = (value: string) =>
  value === 'DEVELOP' ? '开发' : value === 'VERIFY' ? '验证' : value === 'COMPILE' ? '年报编制' : value;
export const executionState = (value: string) => executionStates[value] || value;
export const executionPlatform = (value: string) =>
  ({ darwin: 'macOS', macos: 'macOS', win32: 'Windows', windows: 'Windows', linux: 'Linux' })[value] || value;
