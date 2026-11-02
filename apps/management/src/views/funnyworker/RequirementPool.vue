<template>
  <el-card class="requirements">
    <template #header
      ><div class="toolbar">
        <strong>{{ c.pool }}</strong>
        <div>
          <el-button v-if="canWrite" :disabled="busy" @click="importExisting">{{ c.importExisting }}</el-button>
          <el-button :disabled="busy" @click="load">{{ c.refresh }}</el-button
          ><el-button v-if="canWrite" :disabled="busy" @click="edit()">{{ c.create }}</el-button
          ><el-button v-if="canConfigure" :disabled="busy || !repositoryId" @click="openDependencies">{{ c.dependencies }}</el-button>
        </div>
      </div></template
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="filters">
      <el-input v-model="query" :placeholder="c.search" clearable @keyup.enter="filter" />
      <el-select v-model="repositoryId" :placeholder="c.project" clearable @change="filter"
        ><el-option v-for="r in repositories" :key="r.id" :label="r.slug" :value="r.id"
      /></el-select>
      <el-select v-model="state" :placeholder="c.state" clearable @change="filter"
        ><el-option v-for="(label, key) in states" :key="key" :label="label" :value="key"
      /></el-select>
      <el-button :disabled="busy" @click="filter">{{ c.search }}</el-button>
    </div>
    <el-table :data="rows" v-loading="busy"
      ><el-table-column prop="title" :label="c.title" min-width="200" /><el-table-column
        prop="slug"
        :label="c.project"
        min-width="150"
      /><el-table-column prop="priority" :label="c.priority" width="90" /><el-table-column :label="c.state" min-width="120"
        ><template #default="{ row }">{{ states[row.state] || row.state }}</template></el-table-column
      ><el-table-column prop="version" :label="c.version" width="90" /><el-table-column :label="c.actions" width="100"
        ><template #default="{ row }"
          ><el-button text :disabled="busy" @click="inspect(row.id)">{{ c.view }}</el-button></template
        ></el-table-column
      ></el-table
    >
    <el-empty v-if="!busy && !rows.length" :description="c.empty" />
    <div class="toolbar">
      <el-button :disabled="busy || !previous.length" @click="pageBack">{{ c.previous }}</el-button
      ><el-button :disabled="busy || !next" @click="pageNext">{{ c.next }}</el-button>
    </div>
  </el-card>
  <el-drawer v-model="viewing" :title="c.detail" size="min(900px,96vw)">
    <template v-if="detail">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <h3>{{ detail.title }}</h3>
      <p v-if="detail.canonicalRequirement">
        {{ c.linkedRequirement }}: {{ detail.canonicalRequirement.title }}
        <el-button text :disabled="busy" @click="inspect(detail.canonicalRequirement.id)">{{ c.view }}</el-button>
      </p>
      <p>{{ states[detail.state] || detail.state }} · {{ detail.priority }} · {{ c.version }} {{ detail.version }}</p>
      <div class="toolbar actions">
        <el-button v-if="editable && canWrite" :disabled="busy" @click="edit(detail)">{{ c.edit }}</el-button
        ><el-button v-if="!detail.intake_id && canWrite" :disabled="busy" @click="queue">{{ c.queue }}</el-button
        ><el-button v-if="canWrite" :disabled="busy" @click="analyze">{{ c.analyze }}</el-button
        ><el-button v-if="detail.intake_id" :disabled="busy" @click="emit('view-intake', detail.intake_id)">{{ c.feedback }}</el-button>
      </div>
      <MaintenanceStages v-if="detail.stages?.length" :key="detail.id" :stages="detail.stages" :language="chinese ? 'zh' : 'en'" />
      <h4>{{ c.requirement }}</h4>
      <p class="body">{{ detail.spec.requirement }}</p>
      <h4>{{ c.criteria }}</h4>
      <div v-for="criterion in detail.spec.criteria" :key="criterion.id" class="criterion">
        <code>{{ criterion.id }}</code>
        <p class="body">{{ criterion.expected }}</p>
      </div>
      <h4>{{ c.scope }}</h4>
      <p class="body">{{ detail.spec.platform }} · {{ detail.spec.paths.join(', ') }}</p>
      <h4>{{ c.impact }}</h4>
      <el-empty v-if="!detail.impacts.length" :description="c.noImpact" />
      <section v-for="impact in detail.impacts" :key="impact.id" class="impact">
        <p>
          {{ c.version }} {{ impact.requirement_version }} · {{ time(impact.created_at) }}
          <el-tag v-if="impact.stale" type="warning">{{ c.stale }}</el-tag>
        </p>
        <p class="hash">{{ c.baseline }}: {{ impact.result.baseSha }}</p>
        <el-alert :title="c.partial" type="info" :closable="false" />
        <p>{{ c.inventory }}: {{ impact.result.coverage.inventoryFiles }} · {{ c.samples }}: {{ impact.result.coverage.sampledFiles }}</p>
        <p v-for="warning in impact.result.warnings" :key="warning">{{ warnings[warning] || warning }}</p>
        <p v-if="impact.result.unmatchedPaths.length">{{ c.unmatched }}: {{ impact.result.unmatchedPaths.join(', ') }}</p>
        <el-table :data="impact.result.components"
          ><el-table-column prop="name" :label="c.component" /><el-table-column :label="c.platform"
            ><template #default="{ row }">{{ row.platforms.join(', ') }}</template></el-table-column
          ><el-table-column :label="c.evidence"
            ><template #default="{ row }">{{ reasons[row.reason] || row.reason }}: {{ row.path || row.via }}</template></el-table-column
          ></el-table
        >
        <details v-if="impact.result.edges.length">
          <summary>{{ c.dependencies }}</summary>
          <p v-for="(edge, index) in impact.result.edges" :key="index" class="hash">
            {{ edge.consumer }} → {{ edge.dependency }} · {{ reasons[edge.kind] || edge.kind
            }}<span v-if="edge.file"> · {{ edge.file }} → {{ edge.target }}</span>
          </p>
        </details>
      </section>
      <h4>{{ c.execution }}</h4>
      <el-empty v-if="!detail.links.length" :description="c.noExecution" />
      <p v-for="link in detail.links" :key="link.problem_id">
        {{ c.version }} {{ link.requirement_version }} · {{ states[link.state] || link.state }}
        <el-button text @click="emit('view-problem', link.problem_id)">{{ c.execution }}</el-button>
      </p>
      <h4>{{ c.sources }}</h4>
      <p v-for="(source, index) in detail.sources" :key="index">
        {{ source.source_kind }} · {{ source.client_version || '—' }} · {{ time(source.updated_at) }}
      </p>
      <h4>{{ c.history }}</h4>
      <details v-for="revision in detail.revisions" :key="revision.version">
        <summary>{{ c.version }} {{ revision.version }} · {{ revision.title }} · {{ time(revision.created_at) }}</summary>
        <p>{{ revision.reason }}</p>
        <p class="body">{{ revisionSpec(revision.spec_json).requirement }}</p>
        <p v-for="criterion in revisionSpec(revision.spec_json).criteria" :key="criterion.id" class="body">
          {{ criterion.id }} · {{ criterion.expected }}
        </p>
      </details>
    </template>
  </el-drawer>
  <el-dialog
    v-model="editing"
    :title="editingId ? c.edit : c.create"
    width="min(750px,96vw)"
    :close-on-click-modal="false"
    :before-close="closeEditor"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top">
      <el-form-item :label="c.project"
        ><el-select v-model="form.repositoryId" :disabled="!!editingId || busy" @change="chooseProject"
          ><el-option v-for="r in repositories" :key="r.id" :label="r.slug" :value="r.id" /></el-select
      ></el-form-item>
      <el-form-item :label="c.title"><el-input v-model="form.title" maxlength="160" /></el-form-item>
      <div class="filters">
        <el-form-item :label="c.priority"
          ><el-select v-model="form.priority"
            ><el-option v-for="p in ['P0', 'P1', 'P2', 'P3']" :key="p" :label="p" :value="p" /></el-select></el-form-item
        ><el-form-item :label="c.platform"
          ><el-select v-model="form.platform"><el-option v-for="p in ['macos', 'windows', 'linux']" :key="p" :label="p" :value="p" /></el-select
        ></el-form-item>
      </div>
      <el-form-item :label="c.requirement"><el-input v-model="form.requirement" type="textarea" :rows="4" maxlength="10000" /></el-form-item>
      <el-form-item :label="c.scope"><el-input v-model="pathText" type="textarea" :rows="2" :placeholder="c.lineHint" /></el-form-item>
      <el-form-item v-for="(criterion, index) in form.criteria" :key="criterion.id || index" :label="`${c.criteria} ${index + 1}`"
        ><el-input v-model="criterion.expected" type="textarea" :rows="2" maxlength="4000" /><code v-if="criterion.id">{{ criterion.id }}</code
        ><el-button text :disabled="form.criteria.length === 1" @click="form.criteria.splice(index, 1)">{{ c.remove }}</el-button></el-form-item
      >
      <el-button :disabled="form.criteria.length >= 30" @click="form.criteria.push({ expected: '' })">{{ c.addCriterion }}</el-button>
      <el-form-item :label="c.reason"><el-input v-model="form.reason" maxlength="1000" /></el-form-item> </el-form
    ><template #footer
      ><el-button :disabled="busy" @click="closeEditor(() => (editing = false))">{{ c.cancel }}</el-button
      ><el-button :disabled="busy || !validForm" @click="save">{{ c.save }}</el-button></template
    >
  </el-dialog>
  <el-dialog
    v-model="dependenciesOpen"
    :title="c.dependencies"
    width="min(950px,96vw)"
    :close-on-click-modal="false"
    :before-close="closeDependencies"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p>{{ c.dependencyHint }}</p>
    <el-form v-for="(component, index) in components" :key="index" label-position="top" class="component">
      <div class="filters">
        <el-form-item :label="c.key"><el-input v-model="component.key" maxlength="64" /></el-form-item
        ><el-form-item :label="c.component"><el-input v-model="component.name" maxlength="100" /></el-form-item>
      </div>
      <el-form-item :label="c.scope"><el-input v-model="component.pathText" type="textarea" :rows="2" :placeholder="c.lineHint" /></el-form-item>
      <el-form-item :label="c.platform"
        ><el-select v-model="component.platforms" multiple><el-option v-for="p in platforms" :key="p" :label="p" :value="p" /></el-select
      ></el-form-item>
      <el-form-item :label="c.dependsOn"
        ><el-select v-model="component.dependsOn" multiple
          ><el-option
            v-for="other in components.filter((x) => x.key && x.key !== component.key)"
            :key="other.key"
            :label="other.name || other.key"
            :value="other.key" /></el-select></el-form-item
      ><el-button text @click="components.splice(index, 1)">{{ c.remove }}</el-button>
    </el-form>
    <el-button :disabled="components.length >= 80" @click="components.push({ key: '', name: '', pathText: '', platforms: [], dependsOn: [] })">{{
      c.addComponent
    }}</el-button>
    <template #footer
      ><el-button :disabled="busy" @click="closeDependencies(() => (dependenciesOpen = false))">{{ c.cancel }}</el-button
      ><el-button :disabled="busy || !validDependencies" @click="saveDependencies">{{ c.save }}</el-button></template
    >
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useAppStore } from '@/store/modules/app';
import MaintenanceStages from './MaintenanceStages.vue';
import { checkPermi } from '@/utils/permission';
import { businessRequest } from '@/utils/funnyworker-api';
import { businessAccessLost, businessErrorMessage } from '@/utils/business-feedback';
const props = withDefaults(defineProps<{ canConfigure?: boolean }>(), { canConfigure: false });
const canWrite = computed(() => checkPermi(['funnyworker:tasks:write'])),
  canConfigure = computed(() => props.canConfigure && checkPermi(['funnyworker:agents:write']));
const en = {
  importExisting: 'Import existing analyses',
  imported: 'Imported / skipped',
  savedRefresh: 'Saved, but the latest state could not be loaded. Refresh before making further changes.',
  linkedRequirement: 'Linked requirement',
  pool: 'Requirements',
  create: 'New requirement',
  refresh: 'Refresh',
  dependencies: 'Component dependencies',
  search: 'Search titles',
  project: 'Project',
  state: 'Status',
  title: 'Title',
  priority: 'Priority',
  version: 'Revision',
  actions: 'Actions',
  view: 'View',
  empty: 'No requirements found.',
  previous: 'Previous',
  next: 'Next',
  detail: 'Requirement',
  edit: 'Edit draft',
  queue: 'Start review',
  analyze: 'Analyze impact',
  feedback: 'Source feedback',
  requirement: 'Desired behavior',
  criteria: 'Acceptance criteria',
  scope: 'Paths',
  impact: 'Impact analysis',
  noImpact: 'No analysis yet.',
  stale: 'Outdated',
  baseline: 'Baseline',
  partial: 'Partial evidence. Unobserved dependencies and platforms still need verification.',
  inventory: 'Inventory files',
  samples: 'Sampled files',
  unmatched: 'Paths absent from inventory',
  component: 'Component',
  platform: 'Platform',
  evidence: 'Evidence',
  execution: 'Execution',
  noExecution: 'No execution task yet.',
  sources: 'Sources',
  history: 'Revisions',
  lineHint: 'One path per line',
  remove: 'Remove',
  addCriterion: 'Add criterion',
  reason: 'Change reason',
  cancel: 'Cancel',
  save: 'Save',
  dependencyHint: 'When a dependency changes, its consumers are included in the impact analysis.',
  key: 'Component key',
  dependsOn: 'Depends on',
  addComponent: 'Add component',
  discard: 'Discard unsaved changes?',
  confirm: 'Confirm',
  queueConfirm: 'Send this revision for automatic review? Its product criteria will be frozen.',
  saved: 'Saved',
  queued: 'Queued for review',
  unknown: 'Unknown'
};
const zh: typeof en = {
  importExisting: '导入已有分析',
  imported: '已导入／跳过',
  savedRefresh: '操作已完成，但最新状态读取失败。请刷新后再继续修改。',
  linkedRequirement: '已关联需求',
  pool: '需求池',
  create: '新建需求',
  refresh: '刷新',
  dependencies: '组件依赖',
  search: '搜索标题',
  project: '项目',
  state: '状态',
  title: '标题',
  priority: '优先级',
  version: '修订',
  actions: '操作',
  view: '查看',
  empty: '暂无符合条件的需求。',
  previous: '上一页',
  next: '下一页',
  detail: '需求详情',
  edit: '编辑草稿',
  queue: '开始审查',
  analyze: '分析影响',
  feedback: '来源反馈',
  requirement: '期望行为',
  criteria: '验收条件',
  scope: '涉及路径',
  impact: '影响分析',
  noImpact: '尚未分析。',
  stale: '已过期',
  baseline: '基线',
  partial: '当前证据不完整，未观察到的依赖和平台仍需验证。',
  inventory: '清单文件',
  samples: '采样文件',
  unmatched: '清单中未找到的路径',
  component: '组件',
  platform: '平台',
  evidence: '依据',
  execution: '执行任务',
  noExecution: '尚未创建执行任务。',
  sources: '来源',
  history: '修订记录',
  lineHint: '每行一个路径',
  remove: '移除',
  addCriterion: '添加验收条件',
  reason: '修改原因',
  cancel: '取消',
  save: '保存',
  dependencyHint: '依赖组件发生变更时，使用它的组件也会列为受影响。',
  key: '组件标识',
  dependsOn: '依赖组件',
  addComponent: '添加组件',
  discard: '放弃未保存的修改？',
  confirm: '确认',
  queueConfirm: '将此修订交由自动流程审查？提交后产品验收条件将冻结。',
  saved: '已保存',
  queued: '已排队审查',
  unknown: '未知'
};
const chinese = computed(() => useAppStore().language.startsWith('zh')),
  c = computed(() => (chinese.value ? zh : en));
const states = computed<Record<string, string>>(() =>
  chinese.value
    ? {
        BACKLOG: '待梳理',
        QUEUED: '分析中',
        READY: '待安排',
        NEEDS_INFORMATION: '待补资料',
        IN_PROGRESS: '开发中',
        AWAITING_ACCEPTANCE: '待验收',
        ACCEPTED: '已验收',
        DELIVERED: '已交付',
        BLOCKED: '受阻',
        DECLINED: '暂不开发',
        DUPLICATE: '已归并',
        SOURCE_CHANGED: '来源已变更',
        DEFERRED: '已延期',
        REJECTED: '已拒绝',
        RESTRICTED: '受限',
        RUNTIME_FAILED: '执行异常'
      }
    : {
        BACKLOG: 'Backlog',
        QUEUED: 'Analyzing',
        READY: 'Ready',
        NEEDS_INFORMATION: 'Needs information',
        IN_PROGRESS: 'In progress',
        AWAITING_ACCEPTANCE: 'Awaiting acceptance',
        ACCEPTED: 'Accepted',
        DELIVERED: 'Delivered',
        BLOCKED: 'Blocked',
        DECLINED: 'Declined',
        DUPLICATE: 'Duplicate',
        SOURCE_CHANGED: 'Source changed',
        DEFERRED: 'Deferred',
        REJECTED: 'Rejected',
        RESTRICTED: 'Restricted',
        RUNTIME_FAILED: 'Runtime failed'
      }
);
const reasons = computed<Record<string, string>>(() =>
  chinese.value
    ? { PATH_MATCH: '路径匹配', CONFIGURED_DEPENDENCY: '已配置依赖', SOURCE_IMPORT: '源码引用' }
    : { PATH_MATCH: 'Path match', CONFIGURED_DEPENDENCY: 'Configured dependency', SOURCE_IMPORT: 'Source import' }
);
const warnings = computed<Record<string, string>>(() =>
  chinese.value
    ? {
        BOUNDED_SOURCE_SAMPLE: '源码为有限采样。',
        DEPENDENCY_EDGE_LIMIT: '依赖记录达到本次分析上限，请缩小组件范围。',
        RUNTIME_AND_DYNAMIC_DEPENDENCIES_UNVERIFIED: '运行时及动态依赖尚未验证。',
        NO_DECLARED_COMPONENT_MAP: '尚未配置组件依赖。',
        REQUESTED_PATHS_NOT_IN_INVENTORY: '部分涉及路径不在本次清单中。'
      }
    : {
        BOUNDED_SOURCE_SAMPLE: 'Source sampling is bounded.',
        DEPENDENCY_EDGE_LIMIT: 'The dependency limit was reached. Narrow component paths.',
        RUNTIME_AND_DYNAMIC_DEPENDENCIES_UNVERIFIED: 'Runtime and dynamic dependencies are unverified.',
        NO_DECLARED_COMPONENT_MAP: 'No component dependencies are configured.',
        REQUESTED_PATHS_NOT_IN_INVENTORY: 'Some requested paths are absent from this inventory.'
      }
);
interface Criterion {
  id?: string;
  expected: string;
}
interface Spec {
  requirement: string;
  platform: string;
  paths: string[];
  criteria: Criterion[];
}
interface Item {
  id: string;
  title: string;
  priority: string;
  version: number;
  repository_id: string;
  intake_id: string | null;
  state: string;
  slug: string;
}
interface Impact {
  id: string;
  requirement_version: number;
  created_at: number;
  stale: boolean;
  result: {
    baseSha: string;
    coverage: { inventoryFiles: number; sampledFiles: number };
    warnings: string[];
    unmatchedPaths: string[];
    components: { key: string; name: string; platforms: string[]; reason: string; path?: string; via?: string }[];
    edges: { consumer: string; dependency: string; kind: string; file?: string; target?: string }[];
  };
}
interface Detail extends Item {
  canonicalRequirement?: { id: string; title: string; version: number; state: string };
  stages: { id: string; stage: string; state: string; result_json: string | null; error_code: string | null }[];
  origin: string;
  spec: Spec;
  revisions: { version: number; title: string; reason: string; created_at: number; spec_json: string }[];
  links: { requirement_version: number; problem_id: string; state: string }[];
  sources: { source_kind: string; client_version: string; updated_at: number }[];
  impacts: Impact[];
}
interface Component {
  key: string;
  name: string;
  pathText: string;
  platforms: string[];
  dependsOn: string[];
}
const emit = defineEmits<{ 'view-problem': [id: string]; 'view-intake': [id: string] }>();
const platforms = ['macos', 'windows', 'linux', 'ios', 'android', 'web', 'server', 'shared'];
const rows = ref<Item[]>([]),
  repositories = ref<{ id: string; slug: string }[]>([]),
  configs = ref<{ repository_id: string; allowed_paths: string; default_platform: string }[]>([]),
  detail = ref<Detail>();
const busy = ref(false),
  error = ref(''),
  query = ref(''),
  repositoryId = ref(''),
  state = ref(''),
  after = ref(''),
  next = ref(''),
  previous = ref<string[]>([]),
  viewing = ref(false),
  editing = ref(false),
  editingId = ref(''),
  pathText = ref(''),
  dependenciesOpen = ref(false),
  components = ref<Component[]>([]);
let initialLoad: Promise<void> | undefined,
  inspection = 0;
let alive = true,
  editorBaseline = '',
  dependencyBaseline = '',
  dependencyVersion = 0,
  dependencyRepository = '',
  createKey = crypto.randomUUID(),
  importCursor = '';
const blank = () => ({
  repositoryId: '',
  expectedVersion: 0,
  title: '',
  priority: 'P2',
  requirement: '',
  platform: 'macos',
  criteria: [{ expected: '' }] as Criterion[],
  reason: ''
});
const form = ref(blank());
const split = (s: string) =>
  s
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean);
const time = (value: number) => new Date(value).toLocaleString();
const revisionSpec = (value: string): Spec => JSON.parse(value);
const editable = computed(
  () =>
    detail.value &&
    (!detail.value.intake_id ||
      (detail.value.origin === 'MANUAL' && !detail.value.links.length && ['NEEDS_INFORMATION', 'DECLINED', 'DEFERRED'].includes(detail.value.state)))
);
const validForm = computed(
  () =>
    !!form.value.repositoryId &&
    !!form.value.title.trim() &&
    !!form.value.requirement.trim() &&
    !!form.value.reason.trim() &&
    split(pathText.value).length > 0 &&
    form.value.criteria.every((c) => !!c.expected.trim())
);
const validDependencies = computed(() =>
  components.value.every((c) => !!c.key && !!c.name.trim() && split(c.pathText).length > 0 && c.platforms.length > 0)
);
function editorStamp() {
  return JSON.stringify([form.value, pathText.value]);
}
async function discard(dirty: boolean) {
  if (!dirty) return true;
  try {
    await ElMessageBox.confirm(c.value.discard, c.value.confirm, { confirmButtonText: c.value.confirm, cancelButtonText: c.value.cancel });
    return true;
  } catch {
    return false;
  }
}
async function closeEditor(done: () => void) {
  if (!busy.value && (await discard(editorStamp() !== editorBaseline))) done();
}
async function closeDependencies(done: () => void) {
  if (!busy.value && (await discard(JSON.stringify(components.value) !== dependencyBaseline))) done();
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (e) {
    if (!alive) return;
    error.value = businessErrorMessage(e);
    if (businessAccessLost(e)) {
      rows.value = [];
      detail.value = undefined;
      viewing.value = false;
      editing.value = false;
      dependenciesOpen.value = false;
      components.value = [];
      repositories.value = [];
      configs.value = [];
      form.value = blank();
    }
  } finally {
    busy.value = false;
  }
}
async function fetchPage(cursor = after.value) {
  const result = await businessRequest(
    `/maintenance/requirements?${new URLSearchParams({ after: cursor, limit: '30', query: query.value, repositoryId: repositoryId.value, state: state.value })}`
  );
  if (!alive) return;
  rows.value = result.items;
  next.value = result.nextCursor;
  after.value = cursor;
}
async function load() {
  await perform(async () => {
    await fetchPage();
    if (detail.value && viewing.value) await inspectData(detail.value.id);
  });
}
async function filter() {
  await perform(async () => {
    await fetchPage('');
    previous.value = [];
  });
}
async function pageNext() {
  await perform(async () => {
    const prior = after.value;
    await fetchPage(next.value);
    previous.value.push(prior);
  });
}
async function pageBack() {
  await perform(async () => {
    await fetchPage(previous.value.at(-1) || '');
    previous.value.pop();
  });
}
async function inspectData(id: string) {
  const value = await businessRequest(`/maintenance/requirements/${id}`);
  if (alive) {
    detail.value = value;
    viewing.value = true;
  }
}
async function inspect(id: string) {
  const run = ++inspection;
  await initialLoad;
  if (!alive || run !== inspection) return;
  await perform(() => inspectData(id));
}
function chooseProject() {
  const config = configs.value.find((x) => x.repository_id === form.value.repositoryId);
  pathText.value = config ? JSON.parse(config.allowed_paths).join('\n') : '';
  form.value.platform = config?.default_platform || 'macos';
}
function edit(item?: Detail) {
  editingId.value = item?.id || '';
  createKey = crypto.randomUUID();
  form.value = item
    ? {
        repositoryId: item.repository_id,
        expectedVersion: item.version,
        title: item.title,
        priority: item.priority,
        requirement: item.spec.requirement,
        platform: item.spec.platform,
        criteria: item.spec.criteria.map((c) => ({ ...c })),
        reason: ''
      }
    : blank();
  if (!item) {
    form.value.repositoryId = repositoryId.value;
    chooseProject();
  } else pathText.value = item.spec.paths.join('\n');
  editorBaseline = editorStamp();
  editing.value = true;
}
async function refreshAfterSave(action: () => Promise<void>) {
  try {
    await action();
  } catch (e) {
    if (businessAccessLost(e)) throw e;
    if (alive) error.value = c.value.savedRefresh;
  }
}
async function importExisting() {
  await perform(async () => {
    const result = await businessRequest(`/maintenance/requirements/import?after=${encodeURIComponent(importCursor)}`, 'POST', {});
    if (!alive) return;
    importCursor = result.nextCursor;
    await refreshAfterSave(async () => {
      await fetchPage('');
      previous.value = [];
    });
    ElMessage.success(`${c.value.imported}: ${result.imported} / ${result.skipped}`);
  });
}
async function save() {
  await perform(async () => {
    const result = await businessRequest(
      `/maintenance/requirements${editingId.value ? `/${editingId.value}` : ''}`,
      'POST',
      { ...form.value, paths: split(pathText.value) },
      createKey
    );
    if (!alive) return;
    editing.value = false;
    await refreshAfterSave(async () => {
      await fetchPage('');
      previous.value = [];
      await inspectData(result.id);
    });
    ElMessage.success(c.value.saved);
  });
}
async function queue() {
  if (!detail.value || !(await discardQueue())) return;
  await perform(async () => {
    const id = detail.value!.id;
    await businessRequest(`/maintenance/requirements/${id}/queue`, 'POST', { expectedVersion: detail.value!.version });
    await refreshAfterSave(async () => {
      await inspectData(id);
      await fetchPage();
    });
    ElMessage.success(c.value.queued);
  });
}
async function discardQueue() {
  try {
    await ElMessageBox.confirm(c.value.queueConfirm, c.value.confirm, { confirmButtonText: c.value.confirm, cancelButtonText: c.value.cancel });
    return true;
  } catch {
    return false;
  }
}
async function analyze() {
  await perform(async () => {
    if (!detail.value) return;
    const id = detail.value.id;
    await businessRequest(`/maintenance/requirements/${id}/impact`, 'POST', { expectedVersion: detail.value.version });
    await refreshAfterSave(() => inspectData(id));
  });
}
async function openDependencies() {
  await perform(async () => {
    const repo = repositoryId.value;
    const value = await businessRequest(`/maintenance/requirement-dependencies/${repo}`);
    if (!alive) return;
    dependencyRepository = repo;
    dependencyVersion = value.version;
    components.value = value.components.map((x: { key: string; name: string; paths: string[]; platforms: string[]; dependsOn: string[] }) => ({
      ...x,
      pathText: x.paths.join('\n')
    }));
    dependencyBaseline = JSON.stringify(components.value);
    dependenciesOpen.value = true;
  });
}
async function saveDependencies() {
  await perform(async () => {
    await businessRequest(`/maintenance/requirement-dependencies/${dependencyRepository}`, 'POST', {
      expectedVersion: dependencyVersion,
      components: components.value.map(({ pathText, ...c }) => ({ ...c, paths: split(pathText) }))
    });
    if (!alive) return;
    dependenciesOpen.value = false;
    if (detail.value) await refreshAfterSave(() => inspectData(detail.value!.id));
    ElMessage.success(c.value.saved);
  });
}
onMounted(() => {
  initialLoad = perform(async () => {
    const catalog = await businessRequest('/maintenance/requirements/catalog');
    if (!alive) return;
    repositories.value = catalog;
    configs.value = catalog.map((r: { id: string; allowed_paths: string; default_platform: string }) => ({ ...r, repository_id: r.id }));
    await fetchPage();
  });
});
watch(canWrite, (allowed) => {
  if (!allowed) {
    editing.value = false;
    form.value = blank();
    pathText.value = '';
    editingId.value = '';
  }
});
watch(canConfigure, (allowed) => {
  if (!allowed) {
    dependenciesOpen.value = false;
    components.value = [];
  }
});
onBeforeUnmount(() => {
  alive = false;
  inspection++;
});
defineExpose({
  inspect,
  currentId: () => detail.value?.id,
  hasUnsavedDraft: () =>
    (editing.value && editorStamp() !== editorBaseline) || (dependenciesOpen.value && JSON.stringify(components.value) !== dependencyBaseline)
});
</script>
<style scoped>
.requirements {
  margin-bottom: 20px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.actions {
  justify-content: flex-start;
}
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filters > * {
  flex: 1;
  min-width: 140px;
}
.filters > .el-button {
  flex: 0;
}
.body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.hash,
code {
  overflow-wrap: anywhere;
  font-size: 12px;
}
.criterion,
.impact,
.component,
details {
  border-bottom: 1px solid var(--el-border-color);
  padding: 12px 0;
}
h4 {
  margin-top: 24px;
}
summary {
  cursor: pointer;
}
h3 {
  overflow-wrap: anywhere;
}
</style>
