<template>
  <el-card class="maintenance-panel">
    <template #header
      ><div class="toolbar">
        <strong>{{ c.problems }}</strong>
        <div>
          <el-button v-hasPermi="['funnyworker:agents:write']" :disabled="busy" @click="deliveryPolicyOpen = true">{{ c.deliveryPolicy }}</el-button
          ><el-button :disabled="busy" @click="load">{{ c.refresh }}</el-button
          ><el-button v-hasPermi="['funnyworker:agents:write']" :disabled="busy" @click="repositoryOpen = true">{{ c.repository }}</el-button
          ><el-button v-hasPermi="['funnyworker:nodes:write']" :disabled="busy" @click="openTerminals">{{ c.terminal }}</el-button
          ><el-button
            v-hasPermi="['funnyworker:tasks:write']"
            type="primary"
            :disabled="busy || repositories.length === 0"
            @click="creating = true"
            >{{ c.submitProblem }}</el-button
          >
        </div>
      </div></template
    >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="problems" v-loading="busy"
      ><el-table-column :label="c.problems" prop="title" min-width="180" /><el-table-column
        :label="c.repository"
        prop="slug"
        min-width="180"
      /><el-table-column :label="c.platform" prop="platform" /><el-table-column :label="c.state"
        ><template #default="{ row }">{{ states[row.state] || row.state }}</template></el-table-column
      ><el-table-column :label="c.actions"
        ><template #default="{ row }"
          ><el-button text @click="inspect(row.id)">{{ c.view }}</el-button></template
        ></el-table-column
      ></el-table
    >
  </el-card>
  <el-dialog v-model="deliveryPolicyOpen" :title="c.deliveryPolicy" width="min(680px,95vw)">
    <p>{{ c.deliveryHint }}</p>
    <el-form label-width="110px">
      <el-form-item :label="c.repository"
        ><el-select v-model="deliveryPolicy.repositoryId"
          ><el-option v-for="repo in repositories" :key="repo.id" :label="repo.slug" :value="repo.id" /></el-select
      ></el-form-item>
      <el-form-item :label="c.platform"
        ><el-select v-model="deliveryPolicy.platforms" multiple
          ><el-option label="macOS" value="macos" /><el-option label="Windows" value="windows" /><el-option label="Linux" value="linux" /></el-select
      ></el-form-item>
      <el-form-item :label="c.checks"><el-input v-model="verificationChecks" type="textarea" :rows="5" /></el-form-item>
      <el-form-item :label="c.requiredCi"><el-input v-model="requiredCi" type="textarea" :rows="3" /></el-form-item>
      <el-checkbox v-model="deliveryPolicy.enabled">{{ c.enableDelivery }}</el-checkbox>
    </el-form>
    <template #footer
      ><el-button :disabled="busy" @click="saveDeliveryPolicy">{{ c.save }}</el-button></template
    >
  </el-dialog>
  <el-dialog v-model="creating" :title="c.submitProblem" width="min(640px, 95vw)">
    <el-form label-width="90px"
      ><el-form-item :label="c.repository"
        ><el-select v-model="form.repositoryId"
          ><el-option v-for="repo in repositories" :key="repo.id" :label="repo.slug" :value="repo.id" /></el-select></el-form-item
      ><el-form-item :label="c.title"><el-input v-model="form.title" maxlength="160" /></el-form-item
      ><el-form-item :label="c.requirement"><el-input v-model="form.requirement" type="textarea" :rows="4" maxlength="10000" /></el-form-item
      ><el-form-item :label="c.acceptance"><el-input v-model="form.acceptance" type="textarea" :rows="3" maxlength="10000" /></el-form-item
      ><el-form-item :label="c.platform"
        ><el-select v-model="form.platform"
          ><el-option label="macOS" value="macos" /><el-option label="Windows" value="windows" /><el-option
            label="Linux"
            value="linux" /></el-select></el-form-item
      ><el-form-item :label="c.baseCommit"><el-input v-model="form.baseSha" :placeholder="c.fullSha" /></el-form-item
    ></el-form>
    <template #footer
      ><el-button :disabled="busy" @click="creating = false">{{ c.cancel }}</el-button
      ><el-button type="primary" :disabled="busy" @click="submit">{{ c.submit }}</el-button></template
    >
  </el-dialog>
  <el-dialog v-model="repositoryOpen" :title="c.repository" width="min(640px, 95vw)"
    ><p>{{ c.repositoryHint }}</p>
    <el-form label-width="90px"
      ><el-form-item :label="c.repository"><el-input v-model="repository.slug" placeholder="owner/repository" /></el-form-item
      ><el-form-item :label="c.baseBranch"><el-input v-model="repository.baseBranch" /></el-form-item
      ><el-form-item :label="c.zone"><el-input v-model="repository.zone" /></el-form-item
      ><el-form-item :label="c.checks"><el-input v-model="checksJson" type="textarea" :rows="5" /></el-form-item></el-form
    ><template #footer
      ><el-button type="primary" :disabled="busy" @click="configureRepository">{{ c.save }}</el-button></template
    ></el-dialog
  >
  <el-dialog v-model="enrollmentOpen" :title="c.terminal" width="min(640px, 95vw)"
    ><p>{{ c.enrollmentHint }}</p>
    <el-form label-width="90px"
      ><el-form-item :label="c.name"
        ><el-select v-model="terminal.deviceId"
          ><el-option
            v-for="device in devices"
            :key="device.id"
            :label="`${device.name} · ${device.online ? c.online : c.offline} · ${device.id.slice(0, 8)}`"
            :value="device.id" /></el-select></el-form-item
      ><el-form-item :label="c.zone"><el-input v-model="terminal.zone" /></el-form-item></el-form
    ><template #footer
      ><el-button type="primary" :disabled="busy" @click="enroll">{{ c.generateCode }}</el-button></template
    ></el-dialog
  >
  <el-dialog v-model="viewing" :title="c.detail" width="min(800px, 95vw)">
    <template v-if="detail"
      ><el-button :disabled="busy" @click="inspect(detail.id)">{{ c.refresh }}</el-button>
      <h3>{{ detail.title }}</h3>
      <p>
        <strong>{{ states[detail.state] || detail.state }}</strong>
      </p>
      <p v-if="currentReport?.prUrl">
        <a :href="currentReport.prUrl" target="_blank" rel="noopener noreferrer">{{ c.viewPr }}</a>
      </p>
      <p v-if="currentReport?.resultSha">{{ c.commit }}: {{ currentReport.resultSha }}</p>
      <p v-if="detail.acceptance">{{ detail.acceptance.decisionSource === 'BACKEND_AGENT' ? c.backendAgent : c.manualAcceptance }}</p>
      <details v-if="detail.acceptance">
        <summary>{{ c.acceptanceDetail }}</summary>
        <p>{{ detail.acceptance.note }}</p>
        <p>{{ detail.acceptance.candidateSha }}</p>
      </details>
      <details>
        <summary>{{ c.requirementDetail }}</summary>
        <p>{{ detail.requirement.requirement }}</p>
        <p>{{ c.acceptance }}: {{ detail.requirement.acceptance }}</p>
      </details>
      <h4>{{ c.execution }}</h4>
      <el-button v-if="detail.task_id" @click="viewTask(detail.task_id)">{{ c.viewTask }}</el-button>
      <p>{{ c.reportedHint }}</p>
      <details v-for="entry in detail.reports" :key="entry.attempt_id">
        <summary>{{ entry.current ? c.candidateReport : c.historyResult }} · v{{ entry.version }}</summary>
        <p>{{ entry.report.summary }}</p>
        <p v-if="entry.report.prUrl">
          <a :href="entry.report.prUrl" target="_blank" rel="noopener noreferrer">{{ c.viewPr }}</a>
        </p>
        <p v-if="entry.report.resultSha">{{ c.commit }}: {{ entry.report.resultSha }}</p>
        <div v-for="check in entry.report.checks" :key="check.id">
          <strong>{{ check.id }} · {{ check.exitCode === 0 ? c.passed : c.failed }}</strong>
          <pre>{{ check.output }}</pre>
        </div>
      </details>
      <h4>SAT／UAT · {{ c.verification }}</h4>
      <p v-if="detail.automated">{{ c.developmentEvidence }}</p>
      <div v-for="job in detail.deliveryJobs" :key="job.id">
        <p>{{ job.mode === 'VERIFY' ? c.verification : c.delivery }} · {{ job.platform }} · {{ jobStates[job.state] || job.state }}</p>
        <el-button v-if="job.task_id" text @click="viewTask(job.task_id)">{{ c.viewTask }}</el-button>
        <p>{{ c.commit }}: {{ job.candidate_sha }}</p>
        <div v-for="check in deliveryChecks(job.report_json)" :key="check.id">
          <details>
            <summary>{{ check.id }} · {{ check.exitCode === 0 ? c.passed : c.failed }}</summary>
            <pre>{{ check.output }}</pre>
          </details>
        </div>
      </div>
      <el-alert v-if="evidenceError" :title="c.evidenceFailed" type="error" :closable="false" />
      <template v-if="evidence">
        <details>
          <summary>{{ c.criteria }}</summary>
          <el-table :data="evidence.payload.acceptanceCriteria"
            ><el-table-column label="ID" prop="criterion_id" /><el-table-column :label="c.stage" prop="stage" /><el-table-column
              :label="c.platform"
              prop="platform" /><el-table-column :label="c.checks" prop="check_id" /><el-table-column
              :label="c.expected"
              prop="expected"
              min-width="230"
          /></el-table>
        </details>
        <h4>{{ c.deliveryEvidence }}</h4>
        <el-table :data="evidence.payload.externalActions">
          <el-table-column :label="c.action"
            ><template #default="{ row }">{{ actionName(row.action_kind) }}</template></el-table-column
          >
          <el-table-column :label="c.state"
            ><template #default="{ row }">{{ row.state === 'CONFIRMED' ? c.confirmedAction : row.state }}</template></el-table-column
          >
          <el-table-column :label="c.target" min-width="180"
            ><template #default="{ row }"
              ><a :href="row.target" target="_blank" rel="noopener noreferrer">{{ row.target }}</a></template
            ></el-table-column
          >
          <el-table-column type="expand"
            ><template #default="{ row }">
              <pre>{{ actionResult(row.result_json) }}</pre>
            </template></el-table-column
          >
        </el-table>
        <details v-if="evidence.payload.verificationHistory.length">
          <summary>{{ c.historyVerification }} · {{ evidence.payload.verificationHistory.length }}</summary>
          <pre>{{ maintenancePretty(evidence.payload.verificationHistory) }}</pre>
        </details>
        <details>
          <summary>{{ c.rawEvidence }}</summary>
          <p>SHA-256: {{ evidence.digest }}</p>
          <pre>{{ maintenancePretty(evidence) }}</pre>
        </details>
      </template>
      <h4 v-if="detail.automated">{{ c.roleReview }}</h4>
      <el-alert :title="detail.automated ? c.automaticHint : c.reviewHint" type="info" :closable="false" />
      <MaintenanceStages
        v-if="detail.automated"
        :key="detail.id"
        :stages="evidence?.payload.serverStages || detail.automationStages"
        :language="locale" />
      <el-checkbox v-if="!detail.automated && detail.state === 'AWAITING_ACCEPTANCE'" v-model="reviewConfirmed">{{ c.confirmed }}</el-checkbox>
      <el-input
        v-if="!detail.automated && ['AWAITING_ACCEPTANCE', 'BLOCKED', 'ACCEPTED'].includes(detail.state)"
        v-model="reviewNote"
        type="textarea"
        :placeholder="c.reviewNote"
    /></template>
    <template #footer
      ><el-button
        v-if="detail && !detail.automated && ['AWAITING_ACCEPTANCE', 'ACCEPTED'].includes(detail.state)"
        v-hasPermi="['funnyworker:tasks:write']"
        :disabled="busy || !reviewNote.trim()"
        @click="deliveryAction('verify')"
        >{{ c.verify }}</el-button
      >
      <el-button
        v-if="detail && !detail.automated && detail.state === 'ACCEPTED'"
        v-hasPermi="['funnyworker:tasks:write']"
        :disabled="busy || !reviewNote.trim() || !deliveryConfirmed"
        @click="deliveryAction('deliver')"
        >{{ c.merge }}</el-button
      >
      <el-checkbox v-if="detail && !detail.automated && detail.state === 'ACCEPTED'" v-model="deliveryConfirmed">{{
        c.deliveryConfirmed
      }}</el-checkbox>
      <template v-if="detail && !detail.automated && ['AWAITING_ACCEPTANCE', 'BLOCKED'].includes(detail.state)"
        ><el-button v-hasPermi="['funnyworker:tasks:write']" :disabled="busy || !reviewNote.trim()" @click="decide('rework')">{{
          c.rework
        }}</el-button
        ><el-button
          v-hasPermi="['funnyworker:tasks:write']"
          type="primary"
          :disabled="busy || !reviewNote.trim() || detail.state !== 'AWAITING_ACCEPTANCE' || !reviewConfirmed"
          @click="decide('accept')"
          >{{ c.accept }}</el-button
        ></template
      ></template
    >
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from 'vue-i18n';
import MaintenanceStages from './MaintenanceStages.vue';
import { maintenanceCopy, maintenanceJson, maintenancePretty } from './maintenance-copy';
const { locale } = useI18n();
const c = computed(() => maintenanceCopy[locale.value.startsWith('en') ? 'en' : 'zh']);
const emit = defineEmits<{ changed: []; viewTask: [id: string]; problemUpdated: [value: { id: string; state: string }] }>();
defineExpose({ inspect, hasUnsavedDraft: () => creating.value || enrollmentOpen.value || !!reviewNote.value.trim() });
let initialLoad: Promise<void> | undefined,
  alive = true,
  inspection = 0;
onBeforeUnmount(() => {
  alive = false;
  inspection++;
});
function viewTask(id: string) {
  viewing.value = false;
  emit('viewTask', id);
}
import { businessRequest } from '@/utils/funnyworker-api';
import { BusinessError, businessAccessLost } from '@/utils/business-feedback';
interface Repository {
  id: string;
  slug: string;
  zone: string;
}
interface Problem {
  id: string;
  title: string;
  slug: string;
  platform: string;
  state: string;
  version: number;
}
interface Detail extends Problem {
  task_id: string;
  automated: boolean;
  automationStages: { id: string; stage: string; state: string; result_json: string | null; error_code: string | null }[];
  acceptance: { note: string; candidateSha: string; decisionSource?: string } | null;
  deliveryJobs: { id: string; task_id: string; mode: string; platform: string; state: string; candidate_sha: string; report_json: string | null }[];
  requirement: { requirement: string; acceptance: string };
  reports: {
    attempt_id: string;
    version: number;
    current: boolean;
    report: { summary: string; prUrl: string | null; resultSha: string | null; checks: { id: string; exitCode: number; output: string }[] };
  }[];
}
interface Evidence {
  digest: string;
  payload: {
    serverStages: Detail['automationStages'];
    acceptanceCriteria: { criterion_id: string; stage: string; platform: string; check_id: string; expected: string }[];
    externalActions: { action_kind: string; state: string; target: string; result_json: string | null }[];
    verificationHistory: unknown[];
  };
}
const evidence = ref<Evidence>(),
  evidenceError = ref(false);
const currentReport = computed(() => detail.value?.reports.find((entry) => entry.current)?.report);
function actionResult(value: unknown) {
  const parsed = maintenanceJson(value);
  if (!parsed || typeof parsed !== 'object') return maintenancePretty(parsed);
  const summary = Object.fromEntries(
    Object.entries(parsed).filter(([key]) => ['merged', 'sha', 'message', 'state', 'html_url', 'body', 'closed_at'].includes(key))
  );
  return maintenancePretty(Object.keys(summary).length ? summary : parsed);
}
function actionName(action: string) {
  return ({ MERGE: c.value.mergeAction, COMMENT: c.value.commentAction, CLOSE: c.value.closeAction } as Record<string, string>)[action] || action;
}
const states = computed<Record<string, string>>(() => ({
  IN_PROGRESS: c.value.inProgress,
  AWAITING_ACCEPTANCE: c.value.awaitingAcceptance,
  BLOCKED: c.value.blocked,
  DELIVERED: c.value.delivered,
  ACCEPTED: c.value.accepted
}));
const repositories = ref<Repository[]>([]),
  problems = ref<Problem[]>([]),
  detail = ref<Detail>();
const busy = ref(false),
  error = ref(''),
  creating = ref(false),
  repositoryOpen = ref(false),
  enrollmentOpen = ref(false),
  viewing = ref(false),
  reviewNote = ref(''),
  reviewConfirmed = ref(false);
const form = ref({ repositoryId: '', title: '', requirement: '', acceptance: '', platform: 'macos', baseSha: '' });
const repository = ref({ slug: '', baseBranch: 'main', zone: '' }),
  terminal = ref({ deviceId: '', zone: '' });
const devices = ref<{ id: string; name: string; platform: string; online: boolean }[]>([]);
const checksJson = ref('[{"id":"diff","argv":["git","diff","--cached","--check"],"timeoutSeconds":30}]');
const jobStates = computed<Record<string, string>>(() => ({
  QUEUED: c.value.inProgress,
  VERIFIED: c.value.passed,
  DELIVERED: c.value.delivered,
  BLOCKED: c.value.blocked,
  SUPERSEDED: c.value.superseded
}));
function deliveryChecks(encoded: string | null): { id: string; exitCode: number; output: string }[] {
  if (!encoded) return [];
  try {
    const report = JSON.parse(encoded);
    return Array.isArray(report.checks)
      ? report.checks.filter(
          (v: { id?: unknown; exitCode?: unknown; output?: unknown }) =>
            typeof v.id === 'string' && typeof v.exitCode === 'number' && typeof v.output === 'string'
        )
      : [];
  } catch {
    return [];
  }
}
const deliveryPolicyOpen = ref(false),
  deliveryConfirmed = ref(false);
const deliveryPolicy = ref({ repositoryId: '', platforms: ['macos'], enabled: false });
const verificationChecks = ref(
  JSON.stringify(
    {
      macos: [
        { id: 'repository', argv: ['python3', 'scripts/checks/check-repository.py'], timeoutSeconds: 120 },
        { id: 'diff', argv: ['git', 'diff', '--check'], timeoutSeconds: 30 }
      ]
    },
    null,
    2
  )
);
const requiredCi = ref('python (ubuntu-latest)\npython (windows-latest)\nmanagement-and-business');
async function saveDeliveryPolicy() {
  await perform(async () => {
    await businessRequest('/maintenance/delivery-policies', 'POST', {
      ...deliveryPolicy.value,
      checks: JSON.parse(verificationChecks.value),
      requiredCi: requiredCi.value
        .split('\n')
        .map((v) => v.trim())
        .filter(Boolean)
    });
    deliveryPolicyOpen.value = false;
  });
}
async function deliveryAction(action: 'verify' | 'deliver') {
  await perform(async () => {
    const selected = detail.value;
    if (!selected) return;
    await businessRequest('/maintenance/problems/' + encodeURIComponent(selected.id) + '/' + action, 'POST', {
      expectedVersion: selected.version,
      candidateSha: selected.reports.find((r) => r.current)?.report.resultSha,
      note: reviewNote.value
    });
    detail.value = await businessRequest('/maintenance/problems/' + encodeURIComponent(selected.id));
    await refresh();
  });
}
const requestKeys = new Map<string, string>();
async function perform(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (failure) {
    if (businessAccessLost(failure)) {
      detail.value = undefined;
      problems.value = [];
      repositories.value = [];
      devices.value = [];
      viewing.value = false;
      creating.value = false;
      repositoryOpen.value = false;
      enrollmentOpen.value = false;
      reviewNote.value = '';
      form.value = { repositoryId: '', title: '', requirement: '', acceptance: '', platform: 'macos', baseSha: '' };
    }
    error.value = failure instanceof BusinessError && failure.status === 401 ? c.value.sessionExpired : c.value.operationFailed;
  } finally {
    busy.value = false;
  }
}
async function refresh() {
  [repositories.value, problems.value] = await Promise.all([businessRequest('/maintenance/repositories'), businessRequest('/maintenance/problems')]);
  emit('changed');
}
async function load() {
  await perform(refresh);
}
async function submit() {
  await perform(async () => {
    const body = JSON.stringify(form.value);
    let key = requestKeys.get(body);
    if (!key) {
      key = crypto.randomUUID();
      requestKeys.set(body, key);
    }
    await businessRequest('/maintenance/problems', 'POST', form.value, key);
    requestKeys.delete(body);
    creating.value = false;
    await refresh();
  });
}
async function configureRepository() {
  await perform(async () => {
    await businessRequest('/maintenance/repositories', 'POST', { ...repository.value, checks: JSON.parse(checksJson.value) });
    repositoryOpen.value = false;
    await refresh();
  });
}
async function openTerminals() {
  await perform(async () => {
    const inventory = await businessRequest('/desktop-devices');
    devices.value = inventory.items.filter((device: { revoked: boolean }) => !device.revoked);
    enrollmentOpen.value = true;
  });
}
async function enroll() {
  await perform(async () => {
    await businessRequest('/maintenance/device-bindings', 'POST', terminal.value);
    enrollmentOpen.value = false;
    await refresh();
  });
}
async function inspect(id: string) {
  const run = ++inspection;
  // The shell can request a detail immediately after mount while the directory is loading.
  await initialLoad;
  if (!alive || run !== inspection) return;
  await perform(async () => {
    const response = await businessRequest('/maintenance/problems/' + encodeURIComponent(id));
    if (!alive || run !== inspection) return;
    detail.value = response;
    if (detail.value) {
      const row = problems.value.find((item) => item.id === id);
      if (row) row.state = detail.value.state;
      emit('problemUpdated', { id, state: detail.value.state });
    }
    evidence.value = undefined;
    evidenceError.value = false;
    if (detail.value?.automated) {
      try {
        evidence.value = await businessRequest('/maintenance/automation/problems/' + encodeURIComponent(id) + '/evidence');
      } catch (failure) {
        if (businessAccessLost(failure)) throw failure;
        evidenceError.value = true;
      }
    }
    reviewNote.value = '';
    reviewConfirmed.value = false;
    deliveryConfirmed.value = false;
    viewing.value = true;
  });
}
async function decide(action: 'accept' | 'rework') {
  await perform(async () => {
    const selected = detail.value;
    if (!selected) return;
    await businessRequest('/maintenance/problems/' + encodeURIComponent(selected.id) + '/decision', 'POST', {
      expectedVersion: selected.version,
      action,
      candidateSha: action === 'accept' ? selected.reports.find((entry) => entry.current)?.report.resultSha : null,
      note: reviewNote.value
    });
    detail.value = await businessRequest('/maintenance/problems/' + encodeURIComponent(selected.id));
    reviewNote.value = '';
    reviewConfirmed.value = false;
    await refresh();
  });
}
onMounted(() => {
  initialLoad = load();
});
</script>
<style scoped>
.maintenance-panel {
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
summary {
  cursor: pointer;
  padding: 8px 0;
}
a {
  color: inherit;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 220px;
  overflow: auto;
}
.el-form :deep(.el-select) {
  width: 100%;
}
.toolbar > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.toolbar :deep(.el-button + .el-button) {
  margin-left: 0;
}
p {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
</style>
