<template>
  <el-card class="intake-panel">
    <template #header
      ><div class="toolbar">
        <strong>{{ c.feedback }}</strong>
        <div>
          <el-button :disabled="busy" @click="load">{{ c.refresh }}</el-button
          ><el-button v-hasPermi="['funnyworker:agents:write']" :disabled="busy" @click="configureOpen = true">{{ c.configure }}</el-button>
        </div>
      </div></template
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="automationPolicies.some((p) => p.enabled)" :title="c.automatic" type="info" :closable="false" />
    <p v-for="policy in automationPolicies.filter((p) => p.enabled)" :key="policy.repository_id">
      {{ policy.slug }} · {{ policy.auto_merge ? c.autoDelivery : c.deliveryPending }}
    </p>
    <div class="toolbar">
      <el-input v-model="query" :placeholder="c.search" :disabled="busy" clearable @keyup.enter="applyFilters" />
      <el-select v-model="stateFilter" :placeholder="c.statusFilter" :disabled="busy" clearable @change="applyFilters">
        <el-option v-for="(label, key) in states" :key="key" :label="label" :value="key" />
      </el-select>
      <el-button :disabled="busy" @click="applyFilters">{{ c.search }}</el-button>
    </div>
    <el-table :data="rows" v-loading="busy"
      ><el-table-column :label="c.title" prop="title" min-width="180" /><el-table-column
        :label="c.project"
        prop="slug"
        min-width="160"
      /><el-table-column :label="c.state"
        ><template #default="{ row }"
          ><span v-if="row.state !== 'ADMITTED' || !row.problem_id">{{ states[row.state] || c.review }}</span>
          <div v-if="row.problem_id">{{ repairState(row.problem_id) }}</div></template
        ></el-table-column
      ><el-table-column :label="c.actions"
        ><template #default="{ row }"
          ><el-button text @click="inspect(row.id)">{{ c.view }}</el-button></template
        ></el-table-column
      ></el-table
    >
    <div class="toolbar">
      <el-button :disabled="busy || cursorHistory.length === 0" @click="previousPage">{{ c.previous }}</el-button>
      <el-button :disabled="busy || !nextCursor" @click="nextPage">{{ c.next }}</el-button>
    </div>
  </el-card>
  <el-dialog v-model="configureOpen" :title="c.configure" width="min(640px,95vw)">
    <p>{{ c.configHint }}</p>
    <el-form label-width="110px"
      ><el-form-item :label="c.project"
        ><el-select v-model="config.repositoryId"
          ><el-option v-for="r in repositories" :key="r.id" :label="r.slug" :value="r.id" /></el-select></el-form-item
      ><el-form-item :label="c.base"><el-input v-model="config.baseSha" maxlength="64" /></el-form-item
      ><el-form-item :label="c.scope"><el-input v-model="paths" type="textarea" :rows="2" /></el-form-item
      ><el-form-item :label="c.platform"
        ><el-select v-model="config.defaultPlatform"
          ><el-option label="macOS" value="macos" /><el-option label="Windows" value="windows" /><el-option
            label="Linux"
            value="linux" /></el-select></el-form-item
      ><el-form-item
        ><el-checkbox v-model="config.autoDispatch">{{ c.autoDispatch }}</el-checkbox></el-form-item
      ><el-form-item
        ><el-checkbox v-model="config.githubEnabled">{{ c.github }}</el-checkbox></el-form-item
      ><el-form-item :label="c.credential"><el-input v-model="config.githubTokenRef" placeholder="FUNNYWORK_GITHUB_MAIN" /></el-form-item
    ></el-form>
    <el-table :data="configs"
      ><el-table-column prop="slug" :label="c.project" /><el-table-column :label="c.github"
        ><template #default="{ row }">{{
          row.github_error ||
          (row.source_failure_count ? `${c.sourceFailures}: ${row.source_failure_count}` : row.github_enabled ? c.enabled : c.disabled)
        }}</template></el-table-column
      ><el-table-column :label="c.actions"
        ><template #default="{ row }"
          ><el-button :disabled="busy" @click="editConfig(row.repository_id)">{{ c.edit }}</el-button
          ><el-button :disabled="busy" @click="inspectFailures(row.repository_id)">{{ c.sourceFailures }}</el-button
          ><el-button :disabled="busy || !row.github_enabled" @click="syncGithub(row.repository_id)">{{ c.sync }}</el-button></template
        ></el-table-column
      ></el-table
    >
    <el-table v-if="sourceFailures.length" :data="sourceFailures">
      <el-table-column prop="source_key" label="GitHub Issue" />
      <el-table-column :label="c.state"
        ><template #default="{ row }">{{ row.resolved ? c.resolvedSource : c.invalidSource }}</template></el-table-column
      >
    </el-table>
    <template #footer
      ><el-button :disabled="busy" @click="configureOpen = false">{{ c.cancel }}</el-button
      ><el-button type="primary" :disabled="busy" @click="save">{{ c.save }}</el-button></template
    >
  </el-dialog>
  <el-dialog v-model="viewing" :title="c.feedback" width="min(760px,95vw)"
    ><template v-if="detail"
      ><h3>{{ detail.title }}</h3>
      <p>{{ detail.problem_id ? repairState(detail.problem_id) : states[detail.state] || c.review }}</p>
      <p class="body">{{ detail.body }}</p>
      <MaintenanceStages
        v-if="detail.automationStages?.length"
        :key="detail.id"
        :stages="detail.automationStages"
        :language="useAppStore().language"
      />
      <h4>{{ c.sources }}</h4>
      <p v-for="(source, index) in detail.sources" :key="index">
        <a v-if="source.source_url" :href="source.source_url" target="_blank" rel="noopener noreferrer">GitHub Issue</a
        ><span v-else>{{ c.client }}</span
        ><span v-if="source.client_version"> · {{ source.client_version }}</span>
      </p>
      <p v-for="(message, index) in detail.messages || []" :key="index" class="body">
        <el-tag v-if="message.review_state === 'HELD'" type="info">{{ c.heldNote }}</el-tag> {{ message.content }}
      </p>
      <template v-if="detail.analysis"
        ><p v-if="detail.analysis?.analysis?.category">{{ categories[detail.analysis.analysis.category] || detail.analysis.analysis.category }}</p>
        <h4>{{ c.requirement }}</h4>
        <p class="body">{{ detail.analysis.analysis.requirement }}</p>
        <h4>{{ c.acceptance }}</h4>
        <p class="body">{{ detail.analysis.analysis.acceptance }}</p>
        <p class="body">{{ detail.analysis.analysis.reason }}</p></template
      >
      <p v-if="detail.problem_id">
        <el-button @click="viewProblem(detail.problem_id)">{{ c.viewRepair }} · {{ repairState(detail.problem_id) }}</el-button>
      </p>
      <el-button
        v-if="!detail.problem_id && ['NEEDS_INFORMATION', 'READY_FOR_REVIEW', 'RUNTIME_FAILED', 'SOURCE_CHANGED', 'DECLINED'].includes(detail.state)"
        v-hasPermi="['funnyworker:tasks:write']"
        :disabled="busy"
        @click="retry"
        >{{ c.retry }}</el-button
      ></template
    ></el-dialog
  >
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import MaintenanceStages from './MaintenanceStages.vue';
import { useAppStore } from '@/store/modules/app';
import { businessRequest } from '@/utils/funnyworker-api';
import { businessAccessLost } from '@/utils/business-feedback';
const en = {
  search: 'Search feedback',
  statusFilter: 'Status',
  previous: 'Previous',
  next: 'Next',
  edit: 'Edit',
  sourceFailures: 'Source issues',
  invalidSource: 'Invalid source; update the Issue and sync again',
  resolvedSource: 'Source corrected',
  automatic: 'Backend agents handle analysis and acceptance. Administrators inspect results afterwards.',
  autoDelivery: 'Automatic delivery enabled',
  deliveryPending: 'Automatic merge is not enabled',
  stage: 'Stage',
  decision: 'Decision',
  completed: 'Completed',
  blocker: 'Blocker',
  rejected: 'Rejected',
  restricted: 'Feedback restricted',
  deferred: 'Deferred',
  heldNote: 'Not added to requirement',
  feedback: 'Feedback',
  refresh: 'Refresh',
  configure: 'Intake settings',
  title: 'Title',
  project: 'Project',
  state: 'Status',
  actions: 'Actions',
  view: 'View',
  review: 'Awaiting review',
  configHint:
    'Enable raw feedback for an authorized project. Automatic repair uses the configured baseline and change scope. GitHub reads are incremental; enter a server credential reference, not a secret.',
  base: 'Baseline commit',
  scope: 'Allowed paths',
  platform: 'Default platform',
  autoDispatch: 'Automatically schedule validated requirements',
  github: 'GitHub Issues',
  credential: 'Credential reference',
  enabled: 'Enabled',
  disabled: 'Disabled',
  sync: 'Sync now',
  cancel: 'Cancel',
  save: 'Save',
  sources: 'Sources',
  client: 'Original client',
  requirement: 'Repair requirement',
  acceptance: 'Acceptance',
  viewRepair: 'View repair',
  linked: 'Linked repair is listed under Problems.',
  retry: 'Analyze again',
  error: 'Unable to complete. Check project permissions and configuration.',
  received: 'Received',
  analyzing: 'Analyzing',
  admitted: 'Repair scheduled',
  duplicate: 'Linked duplicate',
  needInfo: 'More information needed',
  declined: 'Outside scope',
  changed: 'Source changed',
  failed: 'Analysis failed'
};
const zh: Record<keyof typeof en, string> = {
  search: '搜索反馈',
  statusFilter: '状态',
  previous: '上一页',
  next: '下一页',
  edit: '编辑',
  sourceFailures: '异常来源',
  invalidSource: '来源不合规，请修改 Issue 后重新同步',
  resolvedSource: '来源已修正',
  automatic: '后端自动分析与验收，管理员事后查看结果。',
  autoDelivery: '自动交付已启用',
  deliveryPending: '自动合并尚未启用',
  stage: '阶段',
  decision: '判定',
  completed: '已执行',
  blocker: '阻塞原因',
  rejected: '已拒绝',
  restricted: '反馈受限',
  deferred: '已延期',
  heldNote: '未加入需求',
  feedback: '反馈',
  refresh: '刷新',
  configure: '受理配置',
  title: '标题',
  project: '项目',
  state: '状态',
  actions: '操作',
  view: '查看',
  review: '待确认',
  configHint: '为获准项目接入原始反馈。自动修复使用配置的基线与改动范围。GitHub 按增量读取；凭据只填写服务端引用，不填写密钥。',
  base: '基线提交',
  scope: '允许改动路径',
  platform: '默认平台',
  autoDispatch: '自动派发通过校验的修复需求',
  github: 'GitHub Issues',
  credential: '凭据引用',
  enabled: '已启用',
  disabled: '未启用',
  sync: '立即同步',
  cancel: '取消',
  save: '保存',
  sources: '来源',
  client: '原客户端',
  requirement: '修复需求',
  acceptance: '验收标准',
  viewRepair: '查看修复',
  linked: '关联修复任务已显示在下方问题列表。',
  retry: '重新分析',
  error: '操作未完成，请检查项目权限与受理配置。',
  received: '已受理',
  analyzing: '分析中',
  admitted: '已安排修复',
  duplicate: '已归并',
  needInfo: '待补充资料',
  declined: '超出范围',
  changed: '来源已更新',
  failed: '分析失败'
};
const c = computed(() => (useAppStore().language.startsWith('zh') ? zh : en));
const categories = computed<Record<string, string>>(() =>
  useAppStore().language.startsWith('en')
    ? { BUG: 'Bug', IMPROVEMENT: 'Improvement', QUESTION: 'Question', OTHER: 'Other' }
    : { BUG: '缺陷', IMPROVEMENT: '优化', QUESTION: '咨询', OTHER: '其他' }
);
const states = computed<Record<string, string>>(() => ({
  RECEIVED: c.value.received,
  ANALYZING: c.value.analyzing,
  ADMITTED: c.value.admitted,
  DUPLICATE: c.value.duplicate,
  NEEDS_INFORMATION: c.value.needInfo,
  DECLINED: c.value.declined,
  REJECTED: c.value.rejected,
  RESTRICTED: c.value.restricted,
  DEFERRED: c.value.deferred,
  READY_FOR_REVIEW: c.value.review,
  SOURCE_CHANGED: c.value.changed,
  RUNTIME_FAILED: c.value.failed
}));
interface Item {
  id: string;
  title: string;
  state: string;
  slug: string;
  problem_id: string | null;
  repair_state?: string | null;
}
interface Detail extends Item {
  automationStages?: { id: string; stage: string; state: string; result_json: string | null; error_code: string | null }[];
  body: string;
  messages?: { content: string; created_at: number; review_state?: string }[];
  analysis: { analysis: { category?: string; requirement: string; acceptance: string; reason: string } } | null;
  sources: { source_kind: string; source_url: string | null; client_version?: string }[];
}
const emit = defineEmits<{ viewProblem: [id: string] }>();
function viewProblem(id: string) {
  viewing.value = false;
  emit('viewProblem', id);
}
const repairs = ref<{ id: string; state: string }[]>([]);

function updateRepairState(value: { id: string; state: string }) {
  const row = repairs.value.find((item) => item.id === value.id);
  if (row) row.state = value.state;
  else repairs.value.push(value);
}

function repairState(id: string) {
  const state = repairs.value.find((item) => item.id === id)?.state || rows.value.find((item) => item.problem_id === id)?.repair_state;
  const labels: Record<string, string> = useAppStore().language.startsWith('zh')
    ? { DELIVERED: '已合并并回告', ACCEPTED: '已验收', IN_PROGRESS: '修复中', BLOCKED: '需处理', AWAITING_ACCEPTANCE: '待验收' }
    : {
        DELIVERED: 'Merged and notified',
        ACCEPTED: 'Accepted',
        IN_PROGRESS: 'In progress',
        BLOCKED: 'Needs attention',
        AWAITING_ACCEPTANCE: 'Awaiting acceptance'
      };
  return state ? labels[state] || state : '—';
}
const automationPolicies = ref<{ repository_id: string; slug: string; enabled: boolean; auto_merge: boolean }[]>([]);
const rows = ref<Item[]>([]),
  repositories = ref<{ id: string; slug: string }[]>([]),
  configs = ref<
    {
      repository_id: string;
      slug: string;
      github_enabled: boolean;
      github_error: string | null;
      source_failure_count: number;
      base_sha: string;
      default_platform: string;
      auto_dispatch: boolean;
      github_token_ref: string;
      allowed_paths: string;
    }[]
  >([]),
  detail = ref<Detail>();
const busy = ref(false),
  error = ref(''),
  configureOpen = ref(false),
  viewing = ref(false),
  paths = ref('apps\nservices\nscripts');
const config = ref({ repositoryId: '', baseSha: '', defaultPlatform: 'macos', autoDispatch: false, githubEnabled: false, githubTokenRef: '' });
let initialLoad: Promise<void> | undefined,
  alive = true,
  inspection = 0;
async function perform(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (failure) {
    if (businessAccessLost(failure)) {
      rows.value = [];
      repairs.value = [];
      repositories.value = [];
      configs.value = [];
      detail.value = undefined;
      viewing.value = false;
      sourceFailures.value = [];
      automationPolicies.value = [];
    }
    error.value = c.value.error;
  } finally {
    busy.value = false;
  }
}
const query = ref(''),
  stateFilter = ref(''),
  after = ref(''),
  nextCursor = ref('');
const cursorHistory = ref<string[]>([]);
const sourceFailures = ref<{ source_key: string; error_code: string; resolved: boolean }[]>([]);
async function refresh() {
  const params = new URLSearchParams({ query: query.value, state: stateFilter.value, after: after.value, limit: '50' });
  const [page, catalog] = await Promise.all([businessRequest('/maintenance/intakes/page?' + params), businessRequest('/maintenance/repositories')]);
  rows.value = page.items;
  nextCursor.value = page.nextCursor;
  repositories.value = catalog;
  repairs.value = rows.value.flatMap((row) => (row.problem_id && row.repair_state ? [{ id: row.problem_id, state: row.repair_state }] : []));
  if (viewing.value && detail.value) {
    const id = detail.value.id;
    const updated = await businessRequest('/maintenance/intakes/' + encodeURIComponent(id));
    if (detail.value?.id === id) detail.value = updated;
  }
  const [configResult, automationResult] = await Promise.allSettled([
    businessRequest('/maintenance/intake-configs'),
    businessRequest('/maintenance/automation')
  ]);
  configs.value = configResult.status === 'fulfilled' ? configResult.value : [];
  if (automationResult.status === 'fulfilled') automationPolicies.value = automationResult.value.policies;
  else {
    automationPolicies.value = [];
    error.value = c.value.error;
  }
}
const load = () => perform(refresh);
const applyFilters = () =>
  perform(async () => {
    after.value = '';
    cursorHistory.value = [];
    await refresh();
  });
const nextPage = () =>
  perform(async () => {
    const prior = after.value;
    after.value = nextCursor.value;
    try {
      await refresh();
      cursorHistory.value.push(prior);
    } catch (error) {
      after.value = prior;
      throw error;
    }
  });
const previousPage = () =>
  perform(async () => {
    const prior = after.value;
    after.value = cursorHistory.value[cursorHistory.value.length - 1] || '';
    try {
      await refresh();
      cursorHistory.value.pop();
    } catch (error) {
      after.value = prior;
      throw error;
    }
  });
const editConfig = (id: string) => {
  const value = configs.value.find((row) => row.repository_id === id);
  if (!value) return;
  config.value = {
    repositoryId: id,
    baseSha: value.base_sha,
    defaultPlatform: value.default_platform,
    autoDispatch: value.auto_dispatch,
    githubEnabled: value.github_enabled,
    githubTokenRef: value.github_token_ref
  };
  paths.value = (JSON.parse(value.allowed_paths) as string[]).join('\n');
  sourceFailures.value = [];
};
const inspectFailures = (id: string) =>
  perform(async () => {
    sourceFailures.value = await businessRequest('/maintenance/intake-configs/' + encodeURIComponent(id) + '/source-failures');
  });
async function inspect(id: string) {
  const run = ++inspection;
  await initialLoad;
  if (!alive || run !== inspection) return;
  await perform(async () => {
    const response = await businessRequest(`/maintenance/intakes/${id}`);
    if (!alive || run !== inspection) return;
    detail.value = response;
    viewing.value = true;
  });
}
defineExpose({ updateRepairState, inspect });
const save = () =>
  perform(async () => {
    await businessRequest('/maintenance/intake-configs', 'POST', {
      ...config.value,
      allowedPaths: paths.value
        .split('\n')
        .map((x) => x.trim())
        .filter(Boolean)
    });
    await refresh();
    configureOpen.value = false;
  });
const syncGithub = (id: string) =>
  perform(async () => {
    await businessRequest(`/maintenance/intake-configs/${id}/github-sync`, 'POST', {});
    await refresh();
  });
const retry = () =>
  perform(async () => {
    if (!detail.value) return;
    await businessRequest(`/maintenance/intakes/${detail.value.id}/retry`, 'POST', {});
    viewing.value = false;
    await refresh();
  });
let refreshTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  initialLoad = load();
  refreshTimer = setInterval(() => {
    if (document.visibilityState === 'visible') void load();
  }, 15000);
});
onUnmounted(() => {
  alive = false;
  inspection++;
  clearInterval(refreshTimer);
});
</script>
<style scoped>
.intake-panel {
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.body {
  white-space: pre-wrap;
  line-height: 1.6;
}
a {
  color: inherit;
}
</style>
