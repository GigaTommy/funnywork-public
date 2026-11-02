import {BusinessError} from '@/utils/business-feedback';
export function modelOperationError(error:unknown,english:boolean):string {
  if(!(error instanceof BusinessError))return english?'The result is unconfirmed. Refresh before trying again.':'操作结果未确认，请先刷新核对。';
  if(error.code==='SESSION_CHANGED')return english?'Your session changed. Reopen the page.':'账号会话已变化，请重新打开页面。';
  if(error.code==='SCOPE_CHANGED')return english?'Authorization scope changed. Run the query again.':'授权范围已变化，请重新查询。';
  if(error.status===401)return english?'Sign in again.':'登录已失效，请重新登录。';
  if(error.status===403)return english?'Check your permissions and authorized zones.':'请核对当前权限与授权区域。';
  if(error.code==='PRESET_CATALOG_LIMIT')return english?'Enterprise presets exceed the catalog limit (512 items / 4 MiB). Shorten instructions or update an existing preset.':'企业预设目录超出容量上限（512 项 / 4 MiB），请精简指令或修改已有预设。';
  if(error.status===409)return english?'The record changed. Refresh before submitting again.':'记录已变化，请刷新核对后再提交。';
  if(error.status===400)return english?'Check the filters, required fields, and the 93-day limit.':'请检查筛选条件、必填项与最长 93 天的时间范围。';
  if(error.status===404)return english?'The record is unavailable. Refresh the list.':'记录已不可用，请刷新列表。';
  return english?'The service is unavailable. Refresh to confirm the result.':'服务暂时不可用，请刷新核对操作结果。';
}
export const modelOperationsCopy = {
  zh: {
    activity:'业务任务活动',taskMeteringHint:'此区域统计业务任务服务的记录，无法从任务次数推算 token。经过企业入口的实际模型调用用量见上方报表。',
    overview: '用量概览', members: '成员用量', hourly: '小时热点', calls: '调用明细', tasks: '任务与会话', retries:'重试执行数',
    all: '全部', choose:'请选择', owner: '成员标识', deployment: '部署标识', zone: '授权区域', state: '状态', purpose: '调用用途',
    filterHint: '按提交时间查询，最多 93 天。已知 token 合计可能只是部分用量，请同时查看不完整调用数。',
    scopeChanged: '授权范围已变化，请重新查询。', changed: '条件已修改，请重新查询。',
    knownInput: '已知输入 token', knownOutput: '已知输出 token', cache: '缓存 token（包含于输入）',
    unknown: '未知', failed: '失败', succeeded: '成功', unresolved: '未知执行', membersCount: '活跃成员',
    queueTime: '排队耗时', inferenceTime: '推理耗时', firstByte: '平均首字延迟', averageQueue: '平均排队',
    hour: '小时（本地时间）', details: '详情', identity: '成员', model: '模型', usageQuality: '计量完整性',
    outcome: '结果原因', session: '会话标识', task: '任务标识', parent: '父任务标识', device: '设备标识',
    logicalCall: '逻辑调用', attempt: '执行票据', firstCall: '首次调用', lastCall: '最近调用',
    taskHint: '按成员、会话与任务标识关联当前查询区间内的调用；任务标识为空时仅代表会话。这里只统计模型调用，工具耗时尚无数据。',
    waiting: '等待', granted: '已获准', running: '推理中', stopping: '停止中', cancelled: '已取消', expired: '已过期',
    more: '加载更多', showCalls: '查看调用', clear: '清空筛选', queueTotal: '符合筛选的请求', occupied: '占用容量', available: '可用容量',
    autoRefresh: '每 5 秒刷新首页', paused: '准入已暂停', pause: '暂停准入', resume: '恢复准入', elapsed: '等待 / 执行时长',
    queueHint: '容量统计覆盖整个资源池，列表支持筛选与分页。未知执行继续占用容量；等待时间不是执行顺序承诺。', clone: '复制为新部署',
  },
  en: {
    activity:'Business task activity',taskMeteringHint:'This section counts business task records; task counts do not determine token usage. Actual enterprise entry consumption appears in the report above.',
    overview: 'Usage overview', members: 'Member usage', hourly: 'Hourly activity', calls: 'Call ledger', tasks: 'Tasks and sessions', retries:'Retry executions',
    all: 'All', choose:'Choose', owner: 'Member ID', deployment: 'Deployment ID', zone: 'Authorized zone', state: 'State', purpose: 'Purpose',
    filterHint: 'Query submission time, up to 93 days. Known token totals may be partial; inspect incomplete call counts.',
    scopeChanged: 'Authorization scope changed. Run the query again.', changed: 'Filters changed. Run the query again.',
    knownInput: 'Known input tokens', knownOutput: 'Known output tokens', cache: 'Cached tokens (included in input)',
    unknown: 'Unknown', failed: 'Failed', succeeded: 'Succeeded', unresolved: 'Unknown executions', membersCount: 'Active members',
    queueTime: 'Queue duration', inferenceTime: 'Inference duration', firstByte: 'Average first byte', averageQueue: 'Average queue time',
    hour: 'Hour (local time)', details: 'Details', identity: 'Member', model: 'Model', usageQuality: 'Metering completeness',
    outcome: 'Outcome', session: 'Session ID', task: 'Task ID', parent: 'Parent task ID', device: 'Device ID',
    logicalCall: 'Logical call', attempt: 'Execution ticket', firstCall: 'First call', lastCall: 'Latest call',
    taskHint: 'Groups calls in this query by member, session and task. An empty task ID represents a session only. Tool durations are not available.',
    waiting: 'Waiting', granted: 'Granted', running: 'Running', stopping: 'Stopping', cancelled: 'Cancelled', expired: 'Expired',
    more: 'Load more', showCalls: 'View calls', clear: 'Clear filters', queueTotal: 'Matching requests', occupied: 'Occupied slots', available: 'Available slots',
    autoRefresh: 'Refresh first page every 5 seconds', paused: 'Admission paused', pause: 'Pause admission', resume: 'Resume admission', elapsed: 'Waiting / execution time',
    queueHint: 'Capacity statistics cover the whole pool. Filter and page the list. Unknown executions occupy capacity; waiting time is not an ordering guarantee.', clone: 'Copy as new deployment',
  }
} as const;
