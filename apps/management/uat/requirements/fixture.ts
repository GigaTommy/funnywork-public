import { BusinessError } from '../../src/utils/business-feedback';
interface Spec {
  requirement: string;
  platform: string;
  paths: string[];
  criteria: { id: string; expected: string }[];
}
interface Row {
  id: string;
  title: string;
  priority: string;
  version: number;
  repository_id: string;
  intake_id: string | null;
  origin: string;
  state: string;
  slug: string;
  spec: Spec;
  revisions: object[];
  sources: object[];
  links: object[];
  impacts: object[];
}
const rows: Row[] = Array.from({ length: 31 }, (_, i) => ({
  id: `req-${i + 1}`,
  title: `报告历史 ${i + 1}`,
  priority: 'P2',
  version: 1,
  repository_id: 'repo',
  intake_id: i === 0 ? 'intake-1' : null,
  origin: 'MANUAL',
  state: i === 0 ? 'IN_PROGRESS' : 'BACKLOG',
  slug: 'example/project',
  spec: {
    requirement: '关闭窗口后仍可查看反馈。',
    platform: 'macos',
    paths: ['shared'],
    criteria: [{ id: `criterion-${i + 1}`, expected: '重新打开后显示原反馈及状态。' }]
  },
  revisions: [],
  sources: [],
  links: i === 0 ? [{ requirement_version: 1, problem_id: 'problem-1', task_id: 'task-1', state: 'IN_PROGRESS' }] : [],
  impacts: []
}));
let map = { version: 0, components: [] as object[] };
export const calls: { path: string; method: string; body?: unknown; requestKey?: string }[] = [];
export const controls = { denied: false, failNextSave: false, failNextRead: false, delayCatalog: new URLSearchParams(location.search).has('shell') };
export async function businessRequest(path: string, method = 'GET', body?: Record<string, unknown>, requestKey?: string) {
  body = body === undefined ? undefined : JSON.parse(JSON.stringify(body));
  calls.push({ path, method, body, requestKey });
  if (controls.denied) throw new BusinessError('FORBIDDEN', 403);
  if (method === 'GET' && controls.failNextRead) {
    controls.failNextRead = false;
    throw new BusinessError('NETWORK', 503);
  }
  const url = new URL(path, 'https://fixture.invalid');
  const task = {
    id: 'task-1',
    title: '需求对应的执行任务',
    owner_id: 'operator',
    owner_name: '测试成员',
    zone: 'sandbox',
    state: 'RUNNING',
    capability: 'repository.maintain.v1',
    created_at: 1792458000000,
    dispatch_paused: false,
    department_id: null,
    project_id: null,
    context_version: 1
  };
  if (url.pathname === '/management-tasks')
    return {
      items: [task],
      summary: [{ state: 'RUNNING', count: 1 }],
      total: 1,
      zones: ['sandbox'],
      observedAt: Date.now(),
      scopeVersion: 0,
      coverage: 'SERVER_TASKS_ONLY'
    };
  if (url.pathname === '/management-tasks/members') return { items: [{ id: 'operator', display_name: '测试成员' }], hasMore: false };
  if (url.pathname === '/management-tasks/options') return { departments: [], projects: [], zones: ['sandbox'], scopeVersion: 0 };
  if (url.pathname === '/management-tasks/task-1')
    return {
      ...task,
      scopeVersion: 0,
      creationAttribution: [],
      placements: {
        items: [
          {
            attempt_id: 'attempt-1',
            device_id: 'device-1',
            device_name: '测试 Mac 执行终端',
            state: 'RUNNING',
            epoch: 1,
            current_attempt: true,
            writer_state: 'HELD'
          }
        ],
        hasMore: false
      },
      units: [{ id: 'unit-1', ordinal: 0, state: 'RUNNING', attempts: 1 }],
      allowedActions: ['READ_METADATA'],
      contentIncluded: false
    };
  if (url.pathname === '/management-tasks/task-1/events') return { items: [], nextCursor: 0 };
  if (url.pathname === '/management-tasks/task-1/content-capabilities') return { readInput: false, readArtifacts: false };
  if (url.pathname === '/management-tasks/task-1/workflow')
    return {
      kind: 'MAINTENANCE',
      state: 'IN_PROGRESS',
      executionState: 'RUNNING',
      validation: 'NOT_CONFIRMED',
      delivery: 'NOT_CONFIRMED',
      automated: true,
      requestedTaskIsCurrent: true,
      stages: [],
      jobs: [],
      workbench: { kind: 'MAINTENANCE', id: 'problem-1', canOpen: true },
      nextStep: '等待后端审查与验收。',
      contentIncluded: false
    };
  if (url.pathname === '/me') return { tenant: 'fixture', principalId: 'operator', roles: ['ADMIN', 'OPERATOR', 'VIEWER'] };
  if (url.pathname === '/maintenance/repositories') return [{ id: 'repo', slug: 'example/project', zone: 'sandbox', base_branch: 'main' }];
  if (url.pathname === '/maintenance/intakes/page') return { items: [], nextCursor: '' };
  if (url.pathname === '/maintenance/intake-configs') return [];
  if (url.pathname === '/maintenance/automation') return { policies: [] };
  if (url.pathname === '/maintenance/problems')
    return [
      { id: 'problem-1', title: '维护执行详情', state: 'IN_PROGRESS', task_id: 'task-1', version: 1, platform: 'macos', slug: 'example/project' }
    ];
  if (url.pathname === '/maintenance/problems/problem-1')
    return {
      id: 'problem-1',
      title: '维护执行详情',
      state: 'IN_PROGRESS',
      task_id: 'task-1',
      version: 1,
      platform: 'macos',
      slug: 'example/project',
      automated: false,
      automationStages: [],
      acceptance: null,
      deliveryJobs: [],
      requirement: { requirement: '保留反馈历史', acceptance: '历史可见' },
      reports: []
    };
  if (url.pathname === '/maintenance/intakes/intake-1')
    return {
      id: 'intake-1',
      title: '原始反馈详情',
      body: '用户的反馈内容',
      state: 'RECEIVED',
      problem_id: null,
      analysis: null,
      automationStages: [],
      sources: [],
      messages: [{ content: '不限token使用', review_state: 'HELD', created_at: Date.now() }]
    };
  if (url.pathname === '/maintenance/requirements/catalog' && controls.delayCatalog) await new Promise((resolve) => setTimeout(resolve, 250));
  if (url.pathname === '/maintenance/requirements/catalog')
    return [{ id: 'repo', slug: 'example/project', allowed_paths: '["shared","apps"]', default_platform: 'macos' }];
  if (url.pathname === '/maintenance/requirements/import') return { imported: 0, skipped: 0, nextCursor: '' };
  if (url.pathname === '/maintenance/requirement-dependencies/repo') {
    if (method === 'POST') {
      map = { version: Number(body?.expectedVersion) + 1, components: body?.components as object[] };
      for (const row of rows) for (const impact of row.impacts) (impact as { stale: boolean }).stale = true;
    }
    return structuredClone(map);
  }
  if (url.pathname === '/maintenance/requirements' && method === 'GET') {
    let items = rows.filter(
      (r) =>
        (!url.searchParams.get('query') || r.title.includes(url.searchParams.get('query')!)) &&
        (!url.searchParams.get('state') || r.state === url.searchParams.get('state'))
    );
    const cursor = url.searchParams.get('after');
    if (cursor) items = items.slice(items.findIndex((r) => r.id === cursor) + 1);
    return { items: structuredClone(items.slice(0, 30)), nextCursor: items.length > 30 ? items[29].id : '' };
  }
  if (method === 'POST' && controls.failNextSave) {
    controls.failNextSave = false;
    throw new BusinessError('NETWORK', 503);
  }
  if (url.pathname === '/maintenance/requirements' && method === 'POST') {
    const id = `new-${rows.length + 1}`;
    rows.unshift({
      ...rows[1],
      id,
      title: String(body?.title),
      priority: String(body?.priority),
      spec: {
        requirement: String(body?.requirement),
        platform: String(body?.platform),
        paths: body?.paths as string[],
        criteria: (body?.criteria as { expected: string }[]).map((c, i) => ({ id: `new-criterion-${i}`, expected: c.expected }))
      }
    });
    return { id, version: 1 };
  }
  const match = url.pathname.match(/^\/maintenance\/requirements\/([^/]+)(?:\/(queue|impact))?$/);
  if (match) {
    const row = rows.find((r) => r.id === match[1]);
    if (!row) throw new BusinessError('NOT_FOUND', 404);
    if (match[2] === 'queue') {
      row.intake_id = 'intake-1';
      row.state = 'QUEUED';
      return { intakeId: row.intake_id };
    }
    if (match[2] === 'impact') {
      const impact = {
        id: 'impact-1',
        requirement_version: row.version,
        created_at: Date.now(),
        stale: false,
        result: {
          baseSha: 'a'.repeat(40),
          coverage: { inventoryFiles: 3, sampledFiles: 1, complete: false },
          warnings: ['BOUNDED_SOURCE_SAMPLE', 'RUNTIME_AND_DYNAMIC_DEPENDENCIES_UNVERIFIED'],
          unmatchedPaths: [],
          components: [
            { key: 'shared', name: '共享协议', platforms: ['shared'], reason: 'PATH_MATCH', path: 'shared' },
            { key: 'desktop', name: '桌面端', platforms: ['macos', 'windows'], reason: 'CONFIGURED_DEPENDENCY', via: 'shared' }
          ],
          edges: [{ consumer: 'desktop', dependency: 'shared', kind: 'CONFIGURED_DEPENDENCY' }]
        }
      };
      row.impacts = [impact];
      return impact;
    }
    if (method === 'POST') {
      row.version++;
      row.title = String(body?.title);
      row.spec.requirement = String(body?.requirement);
      row.spec.criteria = (body?.criteria as { id?: string; expected: string }[]).map((c, i) => ({ id: c.id || `added-${i}`, expected: c.expected }));
      return { id: row.id, version: row.version };
    }
    return structuredClone(row);
  }
  throw new Error(`Unexpected fixture request: ${method} ${path}`);
}
