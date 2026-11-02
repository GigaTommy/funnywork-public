<template>
  <div class="app-container">
    <el-card>
      <template #header
        ><strong>{{ c.title }}</strong></template
      >
      <p class="hint">{{ c.notice }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button :loading="busy" @click="refresh">{{ c.refresh }}</el-button>
      <el-tabs v-model="tab">
        <el-tab-pane :label="c.pools" name="pools">
          <el-table :data="pools" :empty-text="c.empty"
            ><el-table-column prop="name" :label="c.name" /><el-table-column prop="zone" :label="c.zone" /><el-table-column
              prop="concurrency_limit"
              :label="c.concurrency"
            /><el-table-column prop="queue_limit" :label="c.queueLimit" /><el-table-column :label="c.paused"
              ><template #default="{ row }">{{ row.paused ? '✓' : '—' }}</template></el-table-column
            ><el-table-column
              ><template #default="{ row }"
                ><el-button :disabled="busy || !canWrite" @click="edit(row)">{{ c.edit }}</el-button></template
              ></el-table-column
            ></el-table
          >
          <el-form v-if="canWrite" label-position="top" class="config-form" @submit.prevent="savePool">
            <el-form-item :label="c.name"><el-input v-model="poolForm.name" maxlength="120" /></el-form-item
            ><el-form-item :label="c.zone"><el-input v-model="poolForm.zone" :disabled="!!editing" maxlength="64" /></el-form-item>
            <el-form-item :label="c.concurrency"><el-input-number v-model="poolForm.concurrencyLimit" :min="1" :max="1024" /></el-form-item
            ><el-form-item :label="c.queueLimit"><el-input-number v-model="poolForm.queueLimit" :min="1" :max="10000" /></el-form-item
            ><el-form-item :label="c.userConcurrency"
              ><el-input-number v-model="poolForm.userConcurrency" :min="1" :max="poolForm.concurrencyLimit" /></el-form-item
            ><el-form-item :label="c.userQueue"><el-input-number v-model="poolForm.userQueue" :min="1" :max="1000" /></el-form-item
            ><el-form-item :label="c.wait"><el-input-number v-model="poolForm.waitMs" :min="1000" :max="3600000" /></el-form-item
            ><el-form-item :label="c.paused"><el-switch v-model="poolForm.paused" /></el-form-item>
            <el-form-item
              ><el-button native-type="submit" :disabled="busy || !poolForm.name || !poolForm.zone">{{ editing ? c.save : c.create }}</el-button
              ><el-button v-if="editing" @click="resetPool">{{ c.cancel }}</el-button></el-form-item
            >
          </el-form>
        </el-tab-pane>
        <el-tab-pane :label="c.models" name="models">
          <p class="hint">{{ c.deployHint }}</p>
          <el-table :data="deployments" :empty-text="c.empty"
            ><el-table-column prop="name" :label="c.name" /><el-table-column prop="model" :label="c.model" /><el-table-column
              prop="protocol"
              :label="c.protocol"
              min-width="210"
            /><el-table-column prop="base_url" :label="c.baseUrl" min-width="220" /><el-table-column
              ><template #default="{ row }"
                ><el-button :disabled="busy || !canWrite" @click="toggle(row)">{{ row.enabled ? c.disable : c.enable }}</el-button
                ><el-button :disabled="busy || !canWrite" @click="clone(row)">{{ o.clone }}</el-button
                ><el-button
                  v-if="canReadUsage"
                  :disabled="busy"
                  @click="modelRouter.push({ path: '/funnyworker/usage', query: { deployment: row.id } })"
                  >{{ locale.startsWith('en') ? 'Usage and cost' : '用量与费用' }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <p class="hint">{{ c.presetHint }}</p>
          <el-form v-if="canWrite" label-position="top" class="config-form" @submit.prevent="saveDeployment">
            <el-form-item :label="c.preset"
              ><el-select v-model="preset" :placeholder="o.choose" @change="applyPreset"
                ><el-option v-for="p in deploymentPresets" :key="p.id" :label="p.label" :value="p.id" /></el-select
            ></el-form-item>
            <el-form-item :label="c.pool"
              ><el-select :placeholder="o.choose" v-model="deploymentForm.poolId"
                ><el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" /></el-select></el-form-item
            ><el-form-item :label="c.name"><el-input v-model="deploymentForm.name" maxlength="120" /></el-form-item
            ><el-form-item :label="c.model"><el-input v-model="deploymentForm.model" maxlength="120" /></el-form-item
            ><el-form-item :label="c.protocol"
              ><el-select :placeholder="o.choose" v-model="deploymentForm.protocol"
                ><el-option label="OpenAI Chat Completions" value="CHAT_COMPLETIONS" /><el-option
                  label="DeepSeek Messages"
                  value="MESSAGES" /></el-select></el-form-item
            ><el-form-item :label="c.baseUrl"><el-input v-model="deploymentForm.baseUrl" maxlength="1000" /></el-form-item
            ><el-form-item :label="c.credential"><el-input v-model="deploymentForm.credentialRef" maxlength="100" /></el-form-item
            ><el-form-item :label="c.context"><el-input-number v-model="deploymentForm.contextTokens" :min="128" :max="2000000" /></el-form-item
            ><el-form-item :label="c.output"><el-input-number v-model="deploymentForm.maxOutputTokens" :min="1" :max="262144" /></el-form-item
            ><el-form-item
              ><el-checkbox v-model="deploymentForm.capabilities.tools">{{ c.tools }}</el-checkbox
              ><el-checkbox v-model="deploymentForm.capabilities.reasoning">{{ c.reasoning }}</el-checkbox
              ><el-checkbox v-model="deploymentForm.capabilities.images">{{ c.images }}</el-checkbox
              ><el-checkbox v-model="deploymentForm.capabilities.streamUsage">{{ c.streamUsage }}</el-checkbox
              ><el-checkbox v-model="deploymentForm.enabled">{{ c.enabled }}</el-checkbox></el-form-item
            ><el-form-item
              ><el-button
                native-type="submit"
                :disabled="busy || !deploymentForm.poolId || !deploymentForm.name || !deploymentForm.model || !deploymentForm.baseUrl"
                >{{ c.create }}</el-button
              ></el-form-item
            >
          </el-form>
        </el-tab-pane>
        <el-tab-pane :label="c.queue" name="queue">
          <el-form inline @submit.prevent="loadQueue(false)"
            ><el-form-item :label="c.pool"
              ><el-select :placeholder="o.choose" v-model="selectedPool" :disabled="busy"
                ><el-option v-for="p in pools" :key="p.id" :label="p.name" :value="p.id" /></el-select></el-form-item
            ><el-form-item :label="o.state"
              ><el-select :placeholder="o.all" v-model="queueFilters.state" clearable
                ><el-option
                  v-for="state in ['WAITING', 'GRANTED', 'RUNNING', 'STOPPING', 'UNKNOWN']"
                  :key="state"
                  :value="state"
                  :label="stateLabel(state)" /></el-select></el-form-item
            ><el-form-item :label="o.owner"><el-input v-model="queueFilters.owner" maxlength="64" clearable /></el-form-item
            ><el-form-item :label="o.model"
              ><el-select :placeholder="o.all" v-model="queueFilters.deployment" clearable
                ><el-option
                  v-for="d in deployments.filter((d) => d.pool_id === selectedPool)"
                  :key="d.id"
                  :value="d.id"
                  :label="d.name" /></el-select></el-form-item
            ><el-button native-type="submit" :disabled="busy || !selectedPool">{{ c.refresh }}</el-button
            ><el-checkbox v-model="autoRefresh">{{ o.autoRefresh }}</el-checkbox></el-form
          >
          <p class="hint">{{ o.queueHint }}</p>
          <div v-if="queueStats" class="metrics">
            <div>
              <span>{{ o.occupied }}</span
              ><strong>{{ queueStats.occupied }} / {{ queueStats.limit }}</strong>
            </div>
            <div>
              <span>{{ o.available }}</span
              ><strong>{{ queueStats.available }}</strong>
            </div>
            <div>
              <span>{{ o.queueTotal }}</span
              ><strong>{{ queueStats.total }}</strong>
            </div>
            <div v-for="count in queueStats.counts" :key="count.state">
              <span>{{ stateLabel(count.state) }}</span
              ><strong>{{ count.calls }}</strong>
            </div>
          </div>
          <p v-if="queueStats?.paused" class="hint">{{ o.paused }}</p>
          <el-button v-if="queueStats && canWrite" :disabled="busy" @click="pauseQueue">{{ queueStats.paused ? o.resume : o.pause }}</el-button>
          <el-table :data="queue" :empty-text="c.empty"
            ><el-table-column prop="id" :label="c.ticket" min-width="220" /><el-table-column :label="o.identity" min-width="160"
              ><template #default="{ row }"
                >{{ row.display_name || row.owner_id }}<small>{{ row.owner_id }}</small></template
              ></el-table-column
            ><el-table-column prop="deployment_name" :label="o.model" /><el-table-column :label="c.state"
              ><template #default="{ row }">{{ stateLabel(row.state) }}</template></el-table-column
            ><el-table-column :label="c.purpose"
              ><template #default="{ row }">{{ row.purpose === 'mobile-chat' ? c.mobileChat : row.purpose }}</template></el-table-column
            ><el-table-column :label="c.created" min-width="180"
              ><template #default="{ row }">{{ new Date(row.created_at).toLocaleString() }}</template></el-table-column
            ><el-table-column min-width="130"
              ><template #default="{ row }"
                ><el-button
                  v-if="['WAITING', 'GRANTED', 'RUNNING'].includes(row.state)"
                  :disabled="busy || !canWrite"
                  @click="cancelTicket(row.id)"
                  >{{ c.cancelTicket }}</el-button
                ><el-button v-if="row.state === 'UNKNOWN'" :disabled="busy || !canWrite" @click="reconciling = row.id">{{
                  c.reconcile
                }}</el-button></template
              ></el-table-column
            ></el-table
          >
          <el-button v-if="queueCursor.nextId" :disabled="busy || queueChanged" @click="loadQueue(true)">{{ o.more }}</el-button>
          <p v-if="queueChanged" class="hint">{{ o.changed }}</p>
          <el-form v-if="reconciling && canWrite" @submit.prevent="reconcile"
            ><p>{{ c.unknown }}</p>
            <el-form-item :label="c.evidence"><el-input v-model="evidence" maxlength="1000" /></el-form-item
            ><el-button native-type="submit" :disabled="busy || !evidence.trim()">{{ c.reconcile }}</el-button
            ><el-button
              @click="
                reconciling = '';
                evidence = '';
              "
              >{{ c.cancel }}</el-button
            ></el-form
          >
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { useUserStore } from '@/store/modules/user';
const modelUser = useUserStore();
const modelRouter = useRouter();
const canReadUsage = computed(() => modelUser.permissions.includes('*:*:*') || modelUser.permissions.includes('funnyworker:usage:read'));
const canWrite = computed(() => modelUser.permissions.includes('*:*:*') || modelUser.permissions.includes('funnyworker:models:write'));
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { businessRequest } from '@/utils/funnyworker-api';
import { modelMiddlewareCopy } from './model-middleware-copy';
import { modelOperationsCopy, modelOperationError } from './model-operations-copy';
import { deploymentPresets } from './model-deployment-presets';
import { businessAccessLost } from '@/utils/business-feedback';
interface PoolRow {
  id: string;
  name: string;
  zone: string;
  concurrency_limit: number;
  queue_limit: number;
  user_concurrency: number;
  user_queue: number;
  wait_ms: number;
  paused: boolean;
  version: number;
}
interface DeploymentRow {
  id: string;
  name: string;
  model: string;
  protocol: string;
  enabled: boolean;
  pool_id: string;
  base_url: string;
  credential_ref: string;
  context_tokens: number;
  max_output_tokens: number;
  capabilities_json: string;
}
interface QueueRow {
  id: string;
  owner_id: string;
  state: string;
  purpose: string;
  created_at: number;
}
const { locale } = useI18n();
const c = computed(() => (locale.value.startsWith('en') ? modelMiddlewareCopy.en : modelMiddlewareCopy.zh));
const o = computed(() => (locale.value.startsWith('en') ? modelOperationsCopy.en : modelOperationsCopy.zh));
const pools = ref<PoolRow[]>([]),
  deployments = ref<DeploymentRow[]>([]),
  queue = ref<QueueRow[]>([]),
  selectedPool = ref(''),
  tab = ref('pools'),
  error = ref(''),
  busy = ref(false),
  editing = ref(''),
  reconciling = ref(''),
  evidence = ref('');
let alive = true;
const defaults = () => ({
  name: '',
  zone: '',
  concurrencyLimit: 1,
  queueLimit: 100,
  userConcurrency: 1,
  userQueue: 5,
  waitMs: 300000,
  paused: false,
  expectedVersion: 0
});
const poolForm = reactive(defaults());
const preset = ref('');
function applyPreset(id: string) {
  const p = deploymentPresets.find((p) => p.id === id);
  if (!p) return;
  Object.assign(deploymentForm, {
    name: p.label,
    model: '',
    baseUrl: '',
    credentialRef: '',
    protocol: 'CHAT_COMPLETIONS',
    contextTokens: 1000000,
    maxOutputTokens: 65536,
    capabilities: { tools: false, reasoning: false, images: false, streamUsage: false },
    enabled: false
  });
}
const deploymentForm = reactive({
  poolId: '',
  name: '',
  model: '',
  protocol: 'CHAT_COMPLETIONS',
  baseUrl: '',
  credentialRef: '',
  contextTokens: 1000000,
  maxOutputTokens: 65536,
  capabilities: { tools: false, reasoning: false, images: false, streamUsage: false },
  enabled: false
});
let originalPool = JSON.stringify(poolForm),
  originalDeployment = JSON.stringify(deploymentForm);
defineExpose({
  hasUnsavedDraft: () =>
    canWrite.value &&
    (busy.value || JSON.stringify(poolForm) !== originalPool || JSON.stringify(deploymentForm) !== originalDeployment || !!evidence.value.trim())
});
const queueFilters = reactive({ state: '', owner: '', deployment: '' }),
  queueStats = ref<any>(null),
  queueCursor = ref<any>({}),
  autoRefresh = ref(true),
  queueApplied = ref('');
let queuePaged = false;
const queueKey = () => JSON.stringify({ pool: selectedPool.value, ...queueFilters });
const queueChanged = computed(() => !!queueApplied.value && queueKey() !== queueApplied.value);
function stateLabel(state: string) {
  const labels: Record<string, string> = {
    WAITING: o.value.waiting,
    GRANTED: o.value.granted,
    RUNNING: o.value.running,
    STOPPING: o.value.stopping,
    UNKNOWN: o.value.unresolved
  };
  return labels[state] || state;
}
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await action();
  } catch (e) {
    if (alive) {
      error.value = modelOperationError(e, locale.value.startsWith('en'));
      if (businessAccessLost(e)) {
        pools.value = [];
        deployments.value = [];
        queue.value = [];
        queueStats.value = null;
        queueCursor.value = {};
        resetPool();
        Object.assign(deploymentForm, { poolId: '', name: '', model: '', baseUrl: '', credentialRef: '' });
        reconciling.value = '';
        evidence.value = '';
      }
    }
  } finally {
    if (alive) busy.value = false;
  }
}
async function data() {
  const [p, d] = await Promise.all([businessRequest('/model-middleware/pools'), businessRequest('/model-middleware/deployments')]);
  if (alive) {
    pools.value = p.items;
    deployments.value = d.items;
    if (!pools.value.some((p) => p.id === selectedPool.value)) selectedPool.value = pools.value[0]?.id || '';
  }
}
function refresh() {
  return run(async () => {
    await data();
    if (tab.value === 'queue') await queueData();
  });
}
function resetPool() {
  editing.value = '';
  Object.assign(poolForm, defaults());
  originalPool = JSON.stringify(poolForm);
}
function edit(p: PoolRow) {
  editing.value = p.id;
  Object.assign(poolForm, {
    name: p.name,
    zone: p.zone,
    concurrencyLimit: p.concurrency_limit,
    queueLimit: p.queue_limit,
    userConcurrency: p.user_concurrency,
    userQueue: p.user_queue,
    waitMs: p.wait_ms,
    paused: p.paused,
    expectedVersion: p.version
  });
  originalPool = JSON.stringify(poolForm);
}
function savePool() {
  if (!canWrite.value) return;
  return run(async () => {
    await businessRequest(
      '/model-middleware/pools' + (editing.value ? '/' + encodeURIComponent(editing.value) : ''),
      editing.value ? 'PUT' : 'POST',
      { ...poolForm }
    );
    if (alive) resetPool();
    await data();
  });
}
function saveDeployment() {
  if (!canWrite.value) return;
  return run(async () => {
    await businessRequest('/model-middleware/deployments', 'POST', { ...deploymentForm, capabilities: { ...deploymentForm.capabilities } });
    originalDeployment = JSON.stringify(deploymentForm);
    await data();
  });
}
function toggle(row: DeploymentRow) {
  if (!canWrite.value) return;
  return run(async () => {
    await businessRequest('/model-middleware/deployments/' + encodeURIComponent(row.id) + '/enabled', 'PUT', { enabled: !row.enabled });
    await data();
  });
}
function clone(row: DeploymentRow) {
  const capabilities = JSON.parse(row.capabilities_json);
  Object.assign(deploymentForm, {
    poolId: row.pool_id,
    name: row.name,
    model: row.model,
    protocol: row.protocol,
    baseUrl: row.base_url,
    credentialRef: row.credential_ref,
    contextTokens: row.context_tokens,
    maxOutputTokens: row.max_output_tokens,
    capabilities: {
      tools: !!capabilities.tools,
      reasoning: !!capabilities.reasoning,
      images: !!capabilities.images,
      streamUsage: !!capabilities.streamUsage
    },
    enabled: false
  });
}
async function queueData(more = false) {
  const requested = queueKey();
  if (!selectedPool.value) return;
  const q = new URLSearchParams({
    poolId: selectedPool.value,
    ...queueFilters,
    limit: '50',
    afterCreated: String(more ? queueCursor.value.nextCreatedAt || 0 : 0),
    afterId: more ? queueCursor.value.nextId || '' : ''
  });
  const result = await businessRequest('/model-middleware/queue/page?' + q);
  if (alive && queueKey() === requested) {
    queue.value = more ? [...queue.value, ...result.items] : result.items;
    queueStats.value = result;
    queueCursor.value = result;
    queueApplied.value = requested;
    queuePaged = more;
  }
}
function loadQueue(more = false) {
  if (more && queueChanged.value) return;
  return run(() => queueData(more));
}
function pauseQueue() {
  if (!canWrite.value) return;
  return run(async () => {
    const p = queueStats.value.pool;
    await businessRequest('/model-middleware/pools/' + encodeURIComponent(p.id), 'PUT', {
      name: p.name,
      zone: p.zone,
      concurrencyLimit: p.concurrency_limit,
      queueLimit: p.queue_limit,
      userConcurrency: p.user_concurrency,
      userQueue: p.user_queue,
      waitMs: p.wait_ms,
      paused: !p.paused,
      expectedVersion: p.version
    });
    await data();
    await queueData();
  });
}
function cancelTicket(id: string) {
  if (!canWrite.value) return;
  return run(async () => {
    await businessRequest('/model-middleware/tickets/' + encodeURIComponent(id) + '/cancel', 'POST', {});
    await queueData();
  });
}
function reconcile() {
  if (!canWrite.value) return;
  return run(async () => {
    await businessRequest('/model-middleware/tickets/' + encodeURIComponent(reconciling.value) + '/reconcile', 'POST', { evidence: evidence.value });
    if (alive) {
      reconciling.value = '';
      evidence.value = '';
    }
    await queueData();
  });
}
watch(selectedPool, () => {
  queue.value = [];
  queueStats.value = null;
  queueCursor.value = {};
  queueApplied.value = '';
  queueFilters.deployment = '';
  reconciling.value = '';
  evidence.value = '';
});
watch(tab, () => {
  if (tab.value === 'queue') void loadQueue(false);
});
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void refresh();
  timer = setInterval(() => {
    if (autoRefresh.value && tab.value === 'queue' && !busy.value && !reconciling.value && !queuePaged && !queueChanged.value) void loadQueue(false);
  }, 5000);
});
onBeforeUnmount(() => {
  alive = false;
  clearInterval(timer);
});
</script>
<style scoped>
.app-container {
  --el-color-primary: var(--fw-accent);
}
.hint {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.config-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 20px;
  margin-top: 20px;
}
.el-select {
  min-width: 200px;
}
.el-button {
  margin-top: 8px;
}
.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 16px 0;
}
.metrics > div {
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.metrics strong {
  font-size: 22px;
}
small {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
</style>
