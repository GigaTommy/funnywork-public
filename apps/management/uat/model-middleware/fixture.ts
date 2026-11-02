import { ref, computed } from 'vue';
import { BusinessError } from '../../src/utils/business-feedback';
type Row = Record<string, any>;
export const fixtureRevision = ref(0);
let budgetCredits: Record<string, Row> = {};
let agentPresets: Row[] = [], agentPolicies: Row[] = [];
let budgetAccounts: Record<string, Row> = {}, budgetPrices: Record<string, Row> = {}, budgetGlobal: Row, budgetPromotion: Row;
let pools: Row[],
  deployments: Row[],
  queue: Row[],
  members: Row[],
  calls: Row[],
  scenario = 'basic',
  fault = 'none';
const requests = ref<Row[]>([]);
export const auditText = computed(() => JSON.stringify(requests.value, null, 2));
export function setFault(value: string) {
  fault = value;
}
export function resetFixture(value = 'basic') {
  agentPresets = value === 'empty' ? [] : [{ id: 'reviewer', zone: 'east', version: 1, enabled: true, name: '企业审查助手', description: '审查代码', persona: '认真审查代码。' }];
  agentPolicies = [];
  budgetPromotion = { tenant_id: '', mode: 'DISABLED', grant_micros: 1000000, balance_cap: 5000000, total_limit: 0, daily_limit: 0, issued: 0, issued_today: 0, user_daily_limit: 5000000, validity_days: 365, concurrency_limit: 1, calls_per_minute: 5, max_output_tokens: 2048, version: 0 };
  budgetCredits = {};
  budgetAccounts = {};budgetPrices = {};budgetGlobal = { total_limit: 0, daily_limit: 0, spent: 0, held: 0, daily_spent: 0, paused: true, version: 0 };
  scenario = value;
  fault = 'none';
  requests.value = [];
  pools = [
    {
      id: 'ascend-east',
      name: '华东昇腾池',
      zone: 'east',
      concurrency_limit: 2,
      queue_limit: 1000,
      user_concurrency: 1,
      user_queue: 5,
      wait_ms: 300000,
      paused: false,
      version: 1
    }
  ];
  deployments = [
    {
      id: 'qwen-private',
      name: '企业千问',
      model: 'qwen-private',
      protocol: 'CHAT_COMPLETIONS',
      enabled: true,
      pool_id: 'ascend-east',
      base_url: 'https://models.invalid/v1',
      credential_ref: '',
      context_tokens: 8192,
      max_output_tokens: 2048,
      capabilities_json: '{"tools":true,"streamUsage":true}'
    }
  ];
  queue = Array.from({ length: value === 'large' ? 1000 : 3 }, (_, i) => ({
    id: 'ticket-' + String(i + 1).padStart(4, '0'),
    owner_id: 'employee-' + String(i + 1).padStart(4, '0'),
    display_name: '测试成员' + (i + 1),
    deployment_id: 'qwen-private',
    deployment_name: '企业千问',
    state: i === 1 ? 'RUNNING' : i === 2 ? 'UNKNOWN' : 'WAITING',
    purpose: 'agent',
    created_at: Date.now() - 3600000 + i * 1000
  }));
  members = Array.from({ length: value === 'large' ? 60 : 2 }, (_, i) => ({
    owner_id: 'employee-' + String(i + 1).padStart(4, '0'),
    display_name: '测试成员' + (i + 1),
    calls: 3,
    attempts: 2,
    input_tokens: value === 'unknown' ? null : 100 * (i + 1),
    output_tokens: value === 'unknown' ? null : 20 * (i + 1),
    cached_tokens: value === 'unknown' ? null : 10,
    incomplete_usage_calls: value === 'unknown' ? 2 : 1,
    retry_attempts: 1,
    average_queue_ms: 2000,
    average_first_byte_ms: 500,
    succeeded: 1,
    failed: 0,
    unknown_calls: 1
  }));
  calls = members.map((m, i) => ({
    ...m,
    id: 'call-' + String(i + 1).padStart(4, '0'),
    zone: 'east',
    deployment_id: 'qwen-private',
    deployment_name: '企业千问',
    pool_id: 'ascend-east',
    call_id: 'attempt-' + i,
    logical_call_id: 'logical-' + i,
    session_id: 'session-' + i,
    task_id: 'task-' + i,
    parent_task_id: '',
    device_id: 'device-' + i,
    state: value === 'unknown' ? 'UNKNOWN' : 'SUCCEEDED',
    purpose: 'agent',
    created_at: Date.now() - 60000 + i,
    queue_ms: 2000,
    inference_ms: 8000,
    first_call_at: Date.now() - 60000,
    last_call_at: Date.now(),
    usage_quality: value === 'unknown' ? 'UNKNOWN' : 'PARTIAL',
    outcome: value === 'unknown' ? 'UPSTREAM_UNCONFIRMED' : 'COMPLETED'
  }));
  if (value === 'empty') {
    pools = [];
    deployments = [];
    queue = [];
    members = [];
    calls = [];
  }
  fixtureRevision.value++;
}
resetFixture();
const copy = (value: any) => structuredClone(value);
const sum = (rows: Row[], key: string) =>
  rows.some((r) => r[key] != null) ? rows.reduce((n, r) => n + (r[key] ?? 0), 0) : null;
function page(rows: Row[], query: URLSearchParams, kind: 'owner' | 'call' | 'task') {
  let selected = rows;
  const after = query.get('afterOwner');
  if (kind !== 'call' && after) selected = selected.filter((r) => r.owner_id > after);
  if (kind === 'call' && query.get('beforeId'))
    selected = selected.filter(
      (r) =>
        r.created_at < Number(query.get('before')) ||
        (r.created_at === Number(query.get('before')) && r.id < query.get('beforeId')!)
    );
  const limit = Number(query.get('limit') || 50),
    items = selected.slice(0, limit),
    last = items.at(-1);
  return {
    items,
    ...(selected.length > limit
      ? kind === 'call'
        ? { nextCreatedAt: last.created_at, nextId: last.id }
        : {
            nextOwner: last.owner_id,
            ...(kind === 'task' ? { nextSession: last.session_id, nextTask: last.task_id } : {})
          }
      : {})
  };
}
export async function businessRequest(path: string, method = 'GET', body?: Row) {
  requests.value.push({ path, method, ...(body ? { body: copy(body) } : {}) });
  fixtureRevision.value++;
  const next = fault;
  fault = 'none';
  if (next === 'slow') await new Promise((resolve) => setTimeout(resolve, 800));
  if (['401', '403', '409', '503'].includes(next))
    throw new BusinessError(next === '409' ? path.startsWith('/model-middleware/budgets') ? 'BUDGET_VERSION_CONFLICT' : 'POOL_VERSION_CONFLICT' : 'UAT_ERROR', Number(next));
  const url = new URL(path, 'https://uat.invalid'),
    route = url.pathname,
    q = url.searchParams;
  if (route === '/model-middleware/budgets/users') return copy({ items: members.map(row => ({ id: row.owner_id, display_name: row.display_name, enabled: true })), canManageGlobal: scenario !== 'budget-member-admin', canManagePricing: scenario !== 'budget-member-admin' });
  if (route === '/model-middleware/budgets/deployments') return copy({ items: deployments });
  if (route === '/model-middleware/budgets/promotion') {
    if (scenario === 'budget-member-admin') throw new BusinessError('POLICY_DENIED', 403);
    if (method === 'GET') return copy(budgetPromotion);
    if (!body || body.expectedVersion !== budgetPromotion.version) throw new BusinessError('BUDGET_VERSION_CONFLICT', 409);
    budgetPromotion = { ...budgetPromotion, tenant_id: body.tenantId, mode: body.mode, grant_micros: body.grantMicros, balance_cap: body.balanceCapMicros, total_limit: body.totalMicros, daily_limit: body.dailyMicros, user_daily_limit: body.userDailyMicros, validity_days: body.validityDays, concurrency_limit: body.concurrency, calls_per_minute: body.callsPerMinute, max_output_tokens: body.maxOutputTokens, version: budgetPromotion.version + 1 };
    return copy(budgetPromotion);
  }
  if (/\/users\/[^/]+\/topups\/[^/]+$/.test(route)) {
    const parts = route.split('/'), owner = parts.at(-3)!, id = parts.at(-1)!, credit = budgetCredits[owner + ':' + id];
    return copy({ requestId: id, state: credit ? 'POSTED' : 'ABSENT', amountMicros: credit?.amountMicros || 0, budget: budgetAccounts[owner] });
  }
  if (route.endsWith('/topups')) {
    const key = route.split('/').at(-2)!, previous = budgetAccounts[key], creditKey = key + ':' + body?.requestId;
    if (budgetCredits[creditKey]) return copy(previous);
    if (!previous || !body || body.expectedVersion !== previous.version) throw new BusinessError('BUDGET_VERSION_CONFLICT', 409);
    budgetAccounts[key] = { ...previous, total_limit: previous.total_limit + body.amountMicros, version: previous.version + 1 };
    budgetCredits[creditKey] = { amountMicros: body.amountMicros };
    if (next === 'lost-topup') throw new Error('Mock response lost after commit');
    return copy(budgetAccounts[key]);
  }
  if (route.startsWith('/model-middleware/budgets')) {
    const global = route.endsWith('/global'), user = route.includes('/users/'), key = route.split('/').at(-1)!;
    if (scenario === 'budget-member-admin' && (global || (!user && method !== 'GET'))) throw new BusinessError('POLICY_DENIED', 403);
    const previous = global ? budgetGlobal : user ? budgetAccounts[key] : budgetPrices[key];
    if (method === 'GET') return copy(previous || { configured: false });
    if (!body || body.expectedVersion !== (previous?.version || 0)) throw new BusinessError('BUDGET_VERSION_CONFLICT', 409);
    const value = global ? { total_limit: body.totalMicros, daily_limit: body.dailyMicros, paused: body.paused, spent: previous?.spent || 0, held: previous?.held || 0, daily_spent: previous?.daily_spent || 0 }
      : user ? { configured: true, total_limit: body.totalMicros, daily_limit: body.dailyMicros, concurrency_limit: body.concurrency, calls_per_minute: body.callsPerMinute, max_output_tokens: body.maxOutputTokens, expires_at: body.expiresAt, enabled: body.enabled, spent: previous?.spent || 0, held: previous?.held || 0, daily_spent: previous?.daily_spent || 0 }
      : { input_rate: body.inputMicrosPerMillion, output_rate: body.outputMicrosPerMillion, input_ceiling: body.inputCeilingTokens };
    const result = { ...value, version: (previous?.version || 0) + 1 };
    if (global) budgetGlobal = result;else if (user) budgetAccounts[key] = result;else budgetPrices[key] = result;
    return copy(result);
  }
  if (route.endsWith('/agent-presets') && method === 'GET') return copy({ items: agentPresets, policies: agentPolicies, zones: ['east'] });
  if (route.includes('/agent-presets') && method !== 'GET') {
    const previous = agentPresets.find(row => route.endsWith('/' + row.id));
    if (!body || body.expectedVersion !== (previous?.version || 0)) throw new BusinessError('PRESET_VERSION_CONFLICT', 409);
    if (!body.name?.trim() || !body.persona?.trim() || body.persona.includes('{{')) throw new BusinessError('INVALID_PERSONA', 400);
    const row = { ...body, id: previous?.id || 'preset-' + agentPresets.length, version: (previous?.version || 0) + 1 };
    if (previous) agentPresets = agentPresets.map(old => old.id === previous.id ? row : old);else agentPresets.push(row);
    return copy({ id: row.id, version: row.version });
  }
  if (route.endsWith('/agent-preset-policy') && method === 'PUT') {
    const previous = agentPolicies.find(row => row.zone === body?.zone);
    if (!body || body.expectedVersion !== (previous?.version || 0)) throw new BusinessError('PRESET_POLICY_CONFLICT', 409);
    const row = { zone: body.zone, version: (previous?.version || 0) + 1, allow_personal: body.allowPersonalPresets, default_preset: body.defaultPresetId };
    agentPolicies = [...agentPolicies.filter(old => old.zone !== row.zone), row];return { updated: true };
  }
  if (route.endsWith('/pools') && method === 'GET') return copy({ items: pools });
  if (route.includes('/pools') && method !== 'GET') {
    if (!body?.name?.trim() || !body.zone || body.userConcurrency > body.concurrencyLimit)
      throw new BusinessError('INVALID_POOL_LIMIT', 400);
    const old = pools.find((p) => route.endsWith('/' + p.id));
    if (old && body.expectedVersion !== old.version) throw new BusinessError('POOL_VERSION_CONFLICT', 409);
    const row = {
      id: old?.id || 'pool-' + pools.length,
      name: body.name,
      zone: old?.zone || body.zone,
      concurrency_limit: body.concurrencyLimit,
      queue_limit: body.queueLimit,
      user_concurrency: body.userConcurrency,
      user_queue: body.userQueue,
      wait_ms: body.waitMs,
      paused: body.paused,
      version: (old?.version || 0) + 1
    };
    if (old) Object.assign(old, row);
    else pools.push(row);
    return copy(row);
  }
  if (route.endsWith('/deployments')) {
    if (method === 'GET') return copy({ items: deployments });
    if (!body?.model || !body.baseUrl || body.maxOutputTokens > body.contextTokens)
      throw new BusinessError('INVALID_MODEL_REQUEST', 400);
    const row = {
      id: 'deployment-' + deployments.length,
      name: body.name,
      model: body.model,
      pool_id: body.poolId,
      protocol: body.protocol,
      base_url: body.baseUrl,
      credential_ref: body.credentialRef,
      context_tokens: body.contextTokens,
      max_output_tokens: body.maxOutputTokens,
      capabilities_json: JSON.stringify(body.capabilities),
      enabled: body.enabled
    };
    deployments.push(row);
    return { id: row.id };
  }
  if (route.endsWith('/enabled')) {
    const row = deployments.find((d) => route.includes('/' + d.id + '/'));
    if (!row) throw new BusinessError('MISSING', 404);
    row.enabled = body?.enabled;
    return { updated: true };
  }
  if (route.endsWith('/cancel') || route.endsWith('/reconcile')) {
    const row = queue.find((t) => route.includes('/' + t.id + '/'));
    if (!row) throw new BusinessError('MISSING', 404);
    if (route.endsWith('/reconcile')) {
      if (row.state !== 'UNKNOWN' || !body?.evidence?.trim()) throw new BusinessError('TICKET_NOT_UNKNOWN', 409);
      row.state = 'FAILED';
    } else row.state = row.state === 'RUNNING' ? 'STOPPING' : 'CANCELLED';
    return { updated: true };
  }
  if (route.includes('/queue')) {
    const active = queue.filter((t) => ['WAITING', 'GRANTED', 'RUNNING', 'STOPPING', 'UNKNOWN'].includes(t.state));
    let rows = active.filter(
      (t) =>
        (!q.get('state') || t.state === q.get('state')) &&
        (!q.get('owner') || t.owner_id === q.get('owner')) &&
        (!q.get('deployment') || t.deployment_id === q.get('deployment'))
    );
    const total = rows.length;
    if (q.get('afterId'))
      rows = rows.filter(
        (t) =>
          t.created_at > Number(q.get('afterCreated')) ||
          (t.created_at === Number(q.get('afterCreated')) && t.id > q.get('afterId')!)
      );
    const limit = Number(q.get('limit') || 50),
      items = rows.slice(0, limit),
      last = items.at(-1);
    const occupied = active.filter((t) => t.state !== 'WAITING').length;
    return copy({
      items,
      total,
      occupied,
      available: pools[0]?.paused ? 0 : Math.max(0, pools[0].concurrency_limit - occupied),
      limit: pools[0]?.concurrency_limit,
      paused: pools[0]?.paused,
      pool: pools[0],
      counts: [...new Set(active.map((t) => t.state))].map((state) => ({
        state,
        calls: active.filter((t) => t.state === state).length
      })),
      ...(rows.length > limit ? { nextCreatedAt: last.created_at, nextId: last.id } : {})
    });
  }
  if (route.includes('/usage/')) {
    if (
      Number(q.get('until')) - Number(q.get('from')) > 93 * 86400000 ||
      Number(q.get('until')) <= Number(q.get('from'))
    )
      throw new BusinessError('INVALID_MODEL_FILTER', 400);
    const filtered = calls.filter((r) =>
      ['owner', 'deployment', 'state', 'purpose', 'session', 'task', 'zone'].every(
        (key) =>
          !q.get(key) ||
          String(
            r[{ owner: 'owner_id', deployment: 'deployment_id', session: 'session_id', task: 'task_id' }[key] || key]
          ) === q.get(key)
      )
    );
    const selectedMembers = members.filter((m) => filtered.some((c) => c.owner_id === m.owner_id));
    const zones = scenario === 'scope' && !route.endsWith('/overview') ? ['west'] : ['east'];
    const summary = {
      calls: sum(selectedMembers, 'calls') || 0,
      attempts: sum(selectedMembers, 'attempts') || 0,
      members: selectedMembers.length,
      input_tokens: sum(selectedMembers, 'input_tokens'),
      output_tokens: sum(selectedMembers, 'output_tokens'),
      cached_tokens: sum(selectedMembers, 'cached_tokens'),
      incomplete_usage_calls: sum(selectedMembers, 'incomplete_usage_calls') || 0,
      succeeded: sum(selectedMembers, 'succeeded') || 0,
      failed: 0,
      unknown_calls: sum(selectedMembers, 'unknown_calls') || 0,
      average_queue_ms: selectedMembers.length ? 2000 : null,
      average_first_byte_ms: selectedMembers.length ? 500 : null
    };
    if (route.endsWith('/overview'))
      return copy({
        summary,
        hourly: selectedMembers.length ? [{ ...summary, hour_start: Math.floor(Date.now() / 3600000) * 3600000 }] : [],
        zones
      });
    if (route.endsWith('/members')) return copy({ ...page(selectedMembers, q, 'owner'), zones });
    if (route.endsWith('/calls'))
      return copy({
        ...page(
          [...filtered].sort((a, b) => b.created_at - a.created_at),
          q,
          'call'
        ),
        zones
      });
    if (route.endsWith('/tasks')) return copy({ ...page(filtered, q, 'task'), zones });
  }
  throw new Error('Unexpected UAT route: ' + route);
}
