<template>
  <div class="app-container">
    <el-card
      ><template #header
        ><strong>{{ c.usage }}</strong></template
      >
      <p class="hint">{{ c.usageHint }} {{ o.filterHint }}</p>
      <el-form inline @submit.prevent="load(false)">
        <el-form-item :label="c.dates"><el-date-picker v-model="dates" type="datetimerange" :disabled="busy" /></el-form-item>
        <el-form-item :label="o.owner"><el-input v-model="filters.owner" maxlength="64" clearable /></el-form-item>
        <el-form-item :label="o.deployment"><el-input v-model="filters.deployment" maxlength="64" clearable /></el-form-item>
        <el-form-item :label="o.zone"
          ><el-select :placeholder="o.all" v-model="filters.zone" clearable
            ><el-option v-for="zone in zones" :key="zone" :value="zone" :label="zone" /></el-select
        ></el-form-item>
        <el-form-item :label="o.state"
          ><el-select :placeholder="o.all" v-model="filters.state" clearable
            ><el-option v-for="state in states" :key="state" :value="state" :label="stateLabel(state)" /></el-select
        ></el-form-item>
        <el-form-item :label="o.purpose"><el-input v-model="filters.purpose" maxlength="40" clearable /></el-form-item>
        <el-form-item :label="o.session"><el-input v-model="filters.session" maxlength="128" clearable /></el-form-item
        ><el-form-item :label="o.task"><el-input v-model="filters.task" maxlength="128" clearable /></el-form-item>
        <el-form-item
          ><el-button native-type="submit" :loading="busy">{{ c.query }}</el-button
          ><el-button :disabled="busy" @click="clear">{{ o.clear }}</el-button></el-form-item
        >
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="changed" class="hint">{{ o.changed }}</p>
      <div v-if="summary" class="metrics">
        <div v-for="metric in metrics" :key="metric.key">
          <span>{{ metric.label }}</span
          ><strong>{{ metric.duration ? duration(summary[metric.key]) : number(summary[metric.key]) }}</strong>
        </div>
      </div>
      <template v-if="cost"
        ><h3>{{ costCopy.title }}</h3>
        <p class="hint">{{ costCopy.hint }}</p>
        <div class="metrics">
          <div v-for="metric in costMetrics" :key="metric.key">
            <span>{{ metric.label }}</span
            ><strong>{{ formatCostMicros(cost[metric.key], o.unknown) }}</strong>
          </div>
        </div>
        <el-alert v-if="Number(cost.unpriced_execution_calls) > 0" :title="costCopy.gap" type="warning" :closable="false"
      /></template>
      <el-tabs v-model="tab" @tab-change="load(false)"
        ><el-tab-pane :label="o.members" name="members" :disabled="busy" /><el-tab-pane
          :label="o.hourly"
          name="hourly"
          :disabled="busy" /><el-tab-pane :label="o.calls" name="calls" :disabled="busy" /><el-tab-pane
          :label="o.tasks"
          name="tasks"
          :disabled="busy"
      /></el-tabs>
      <p v-if="tab === 'tasks'" class="hint">{{ o.taskHint }}</p>
      <el-table v-if="tab === 'hourly'" :data="hourly" :empty-text="c.empty" v-loading="busy">
        <el-table-column :label="o.hour" min-width="180"
          ><template #default="{ row }">{{ time(row.hour_start) }}</template></el-table-column
        ><el-table-column prop="calls" :label="c.calls" /><el-table-column prop="attempts" :label="c.attempts" /><el-table-column
          :label="o.knownInput"
          min-width="130"
          ><template #default="{ row }">{{ number(row.input_tokens) }}</template></el-table-column
        ><el-table-column :label="o.knownOutput" min-width="130"
          ><template #default="{ row }">{{ number(row.output_tokens) }}</template></el-table-column
        ><el-table-column prop="incomplete_usage_calls" :label="c.incomplete" />
      </el-table>
      <el-table v-else :data="rows" :empty-text="c.empty" v-loading="busy">
        <el-table-column :label="o.identity" min-width="160"
          ><template #default="{ row }"
            >{{ row.display_name || row.owner_id }}<small>{{ row.owner_id }}</small></template
          ></el-table-column
        >
        <template v-if="tab === 'calls'"
          ><el-table-column :label="o.model" min-width="150"
            ><template #default="{ row }">{{ row.deployment_name || row.deployment_id }}</template></el-table-column
          ><el-table-column :label="o.state"
            ><template #default="{ row }">{{ stateLabel(row.state) }}</template></el-table-column
          ><el-table-column prop="purpose" :label="o.purpose" /><el-table-column :label="c.created" min-width="180"
            ><template #default="{ row }">{{ time(row.created_at) }}</template></el-table-column
          ></template
        >
        <template v-else
          ><el-table-column v-if="tab === 'tasks'" :label="o.task" min-width="180"
            ><template #default="{ row }"
              >{{ row.task_id || o.session }}<small>{{ row.session_id }}</small></template
            ></el-table-column
          ><el-table-column prop="calls" :label="c.calls" /><el-table-column prop="attempts" :label="c.attempts"
        /></template>
        <el-table-column :label="o.knownInput" min-width="130"
          ><template #default="{ row }">{{ number(row.input_tokens) }}</template></el-table-column
        ><el-table-column :label="o.knownOutput" min-width="130"
          ><template #default="{ row }">{{ number(row.output_tokens) }}</template></el-table-column
        >
        <el-table-column v-if="tab !== 'calls'" prop="incomplete_usage_calls" :label="c.incomplete" /><el-table-column
          v-if="tab === 'calls'"
          prop="usage_quality"
          :label="o.usageQuality"
        />
        <el-table-column v-if="tab !== 'calls'" prop="retry_attempts" :label="o.retries" min-width="100" />
        <el-table-column v-if="tab === 'tasks'" :label="o.firstCall" min-width="180"
          ><template #default="{ row }">{{ time(row.first_call_at) }}</template></el-table-column
        ><el-table-column v-if="tab === 'tasks'" :label="o.lastCall" min-width="180"
          ><template #default="{ row }">{{ time(row.last_call_at) }}</template></el-table-column
        >
        <el-table-column :label="tab === 'members' ? o.averageQueue : o.queueTime"
          ><template #default="{ row }">{{ duration(tab === 'members' ? row.average_queue_ms : row.queue_ms) }}</template></el-table-column
        ><el-table-column v-if="tab !== 'members'" :label="o.inferenceTime"
          ><template #default="{ row }">{{ duration(row.inference_ms) }}</template></el-table-column
        >
        <el-table-column min-width="120" fixed="right"
          ><template #default="{ row }"
            ><el-button :disabled="busy" @click="tab === 'calls' ? (detail = row) : viewCalls(row)">{{
              tab === 'calls' ? o.details : o.showCalls
            }}</el-button></template
          ></el-table-column
        >
      </el-table>
      <el-button v-if="hasMore && tab !== 'hourly'" :disabled="busy || changed" @click="load(true)">{{ o.more }}</el-button>
      <el-drawer :model-value="!!detail" :title="o.details" size="min(640px, 95vw)" @close="detail = null"
        ><el-descriptions v-if="detail" :column="1" border
          ><el-descriptions-item v-for="field in detailFields" :key="field.key" :label="field.label">{{
            costDetail(field.key, detail[field.key])
          }}</el-descriptions-item></el-descriptions
        ></el-drawer
      >
    </el-card>
  </div>
</template>
<script setup lang="ts">
import { computed, reactive, ref, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from 'vue-i18n';
import { businessRequest } from '@/utils/funnyworker-api';
import { BusinessError } from '@/utils/business-feedback';
import { modelMiddlewareCopy } from './model-middleware-copy';
import { modelOperationsCopy, modelOperationError } from './model-operations-copy';
import { formatCostMicros } from '@/utils/cost-format';
import { costReportCopy } from '@/utils/cost-report-copy';
import { readUsageDeployment } from '@/utils/model-usage-context';
import { watch } from 'vue';
const usageRoute = useRoute(),
  usageRouter = useRouter();
type Row = Record<string, any>;
const { locale } = useI18n();
const c = computed(() => (locale.value.startsWith('en') ? modelMiddlewareCopy.en : modelMiddlewareCopy.zh));
const o = computed(() => (locale.value.startsWith('en') ? modelOperationsCopy.en : modelOperationsCopy.zh));
const dates = ref<[Date, Date]>([new Date(Date.now() - 86400000), new Date()]),
  filters = reactive({ owner: '', deployment: '', zone: '', state: '', purpose: '', session: '', task: '' });
const cost = ref<Row | null>(null);
const rows = ref<Row[]>([]),
  hourly = ref<Row[]>([]),
  summary = ref<Row | null>(null),
  zones = ref<string[]>([]),
  detail = ref<Row | null>(null),
  tab = ref('members'),
  busy = ref(false),
  error = ref(''),
  applied = ref(''),
  cursor = ref<Row>({});
let alive = true,
  generation = 0;
const states = ['WAITING', 'GRANTED', 'RUNNING', 'STOPPING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'];
const key = () => JSON.stringify({ dates: dates.value, ...filters });
const changed = computed(() => !!applied.value && key() !== applied.value);
const hasMore = computed(() => (tab.value === 'calls' ? !!cursor.value.nextId : !!cursor.value.nextOwner));
const number = (value: unknown) => (value == null ? o.value.unknown : Number(value).toLocaleString());
const duration = (value: unknown) =>
  value == null ? o.value.unknown : (Number(value) / 1000).toLocaleString(undefined, { maximumFractionDigits: 1 }) + ' s';
const time = (value: unknown) => (value == null ? '—' : new Date(Number(value)).toLocaleString());
function stateLabel(state: string) {
  const labels: Record<string, string> = {
    WAITING: o.value.waiting,
    GRANTED: o.value.granted,
    RUNNING: o.value.running,
    STOPPING: o.value.stopping,
    UNKNOWN: o.value.unresolved,
    SUCCEEDED: o.value.succeeded,
    FAILED: o.value.failed,
    CANCELLED: o.value.cancelled,
    EXPIRED: o.value.expired
  };
  return labels[state] || state;
}
const costCopy = computed(() => (locale.value.startsWith('en') ? costReportCopy.en : costReportCopy.zh));
const costMetrics = computed(() => [
  { key: 'settled_micros', label: costCopy.value.settled },
  { key: 'provider_micros', label: costCopy.value.provider },
  { key: 'estimated_micros', label: costCopy.value.estimated },
  { key: 'legacy_micros', label: costCopy.value.legacy },
  { key: 'held_micros', label: costCopy.value.held },
  { key: 'unknown_held_micros', label: costCopy.value.unknownHeld }
]);
function costDetail(key: string, value: unknown) {
  if (['reserved_micros', 'charged_micros'].includes(key)) return formatCostMicros(value, o.value.unknown);
  if (['charge_state', 'settlement_basis'].includes(key)) return costCopy.value[value as keyof typeof costCopy.value] || value || o.value.unknown;
  return value ?? o.value.unknown;
}
const metrics = computed(() => [
  { key: 'calls', label: c.value.calls },
  { key: 'attempts', label: c.value.attempts },
  { key: 'members', label: o.value.membersCount },
  { key: 'input_tokens', label: o.value.knownInput },
  { key: 'output_tokens', label: o.value.knownOutput },
  { key: 'incomplete_usage_calls', label: c.value.incomplete },
  { key: 'average_queue_ms', label: o.value.averageQueue, duration: true },
  { key: 'average_first_byte_ms', label: o.value.firstByte, duration: true },
  { key: 'succeeded', label: o.value.succeeded },
  { key: 'failed', label: o.value.failed },
  { key: 'unknown_calls', label: o.value.unresolved },
  { key: 'cached_tokens', label: o.value.cache }
]);
const detailFields = computed(() => [
  { key: 'reserved_micros', label: costCopy.value.reserved },
  { key: 'charged_micros', label: costCopy.value.charged },
  { key: 'charge_state', label: costCopy.value.state },
  { key: 'settlement_basis', label: costCopy.value.basis },
  { key: 'id', label: o.value.attempt },
  { key: 'logical_call_id', label: o.value.logicalCall },
  { key: 'owner_id', label: o.value.owner },
  { key: 'zone', label: o.value.zone },
  { key: 'deployment_id', label: o.value.deployment },
  { key: 'session_id', label: o.value.session },
  { key: 'task_id', label: o.value.task },
  { key: 'parent_task_id', label: o.value.parent },
  { key: 'device_id', label: o.value.device },
  { key: 'outcome', label: o.value.outcome },
  { key: 'cached_tokens', label: o.value.cache },
  { key: 'usage_quality', label: o.value.usageQuality }
]);
function clear() {
  if (usageRoute.query.deployment !== undefined) {
    void usageRouter.replace({ query: { ...usageRoute.query, deployment: undefined } });
    return;
  }
  Object.assign(filters, { owner: '', deployment: '', zone: '', state: '', purpose: '', session: '', task: '' });
  void load(false);
}
function viewCalls(row: Row) {
  filters.owner = row.owner_id;
  filters.session = row.session_id || '';
  filters.task = row.task_id || '';
  tab.value = 'calls';
  void load(false);
}
async function load(more = false) {
  if (busy.value || (more && changed.value)) return;
  if (!dates.value || dates.value.length !== 2) {
    error.value = o.value.filterHint;
    return;
  }
  busy.value = true;
  error.value = '';
  const run = ++generation,
    requestedKey = key(),
    requestedTab = tab.value;
  const q = new URLSearchParams({ from: String(dates.value[0].getTime()), until: String(dates.value[1].getTime()), ...filters, limit: '50' });
  if (more) {
    q.set('afterOwner', cursor.value.nextOwner || '');
    q.set('afterSession', cursor.value.nextSession || '');
    q.set('afterTask', cursor.value.nextTask || '');
    q.set('before', String(cursor.value.nextCreatedAt || 0));
    q.set('beforeId', cursor.value.nextId || '');
  }
  try {
    const [overview, page] = await Promise.all([
      businessRequest('/model-middleware/usage/overview?' + q),
      requestedTab === 'hourly' ? Promise.resolve({ items: [] }) : businessRequest('/model-middleware/usage/' + requestedTab + '?' + q)
    ]);
    if (!alive || run !== generation) return;
    if (
      (requestedTab !== 'hourly' && JSON.stringify(page.zones) !== JSON.stringify(overview.zones)) ||
      (more && JSON.stringify(zones.value) !== JSON.stringify(overview.zones))
    ) {
      rows.value = [];
      cursor.value = {};
      throw new BusinessError('SCOPE_CHANGED');
    }
    zones.value = overview.zones || [];
    summary.value = overview.summary;
    cost.value = overview.cost || null;
    hourly.value = overview.hourly;
    rows.value = more ? [...rows.value, ...page.items] : page.items;
    cursor.value = page;
    applied.value = requestedKey;
  } catch (e) {
    if (alive && run === generation) {
      error.value = modelOperationError(e, locale.value.startsWith('en'));
      rows.value = [];
      hourly.value = [];
      summary.value = null;
      cost.value = null;
      cursor.value = {};
      detail.value = null;
    }
  } finally {
    if (alive && run === generation) busy.value = false;
  }
}
function loadRouteDeployment() {
  generation++;
  busy.value = false;
  rows.value = [];
  hourly.value = [];
  summary.value = null;
  cost.value = null;
  detail.value = null;
  cursor.value = {};
  applied.value = '';
  try {
    Object.assign(filters, { owner: '', deployment: readUsageDeployment(usageRoute.query), zone: '', state: '', purpose: '', session: '', task: '' });
    void load(false);
  } catch {
    error.value = locale.value.startsWith('en') ? 'Invalid model link. Reopen it from the model center.' : '模型链接无效，请从模型中心重新打开。';
  }
}
watch(() => usageRoute.query.deployment, loadRouteDeployment);
onMounted(loadRouteDeployment);
onBeforeUnmount(() => {
  alive = false;
  generation++;
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
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 20px 0;
}
.metrics > div {
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.metrics strong {
  font-size: 24px;
}
.el-select {
  min-width: 160px;
}
small {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  overflow-wrap: anywhere;
}
.el-alert {
  margin: 12px 0;
}
@media (max-width: 800px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
