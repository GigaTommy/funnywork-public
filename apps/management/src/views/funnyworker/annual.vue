<template>
  <div class="app-container annual">
    <div class="toolbar">
      <div>
        <h2>年报</h2>
        <p class="hint">指定终端编制候选，后端核验后自动交付。</p>
      </div>
      <div class="toolbar">
        <el-button v-if="returnTaskCenter" @click="router.push(returnTaskCenter)">返回任务中心</el-button>
        <span>执行平台</span
        ><el-select v-model="reportPlatform" aria-label="年报执行平台" style="width: 130px"
          ><el-option label="macOS" value="macos" /><el-option label="Windows" value="windows" /><el-option label="Linux" value="linux" /></el-select
        ><el-button :loading="busy" @click="load">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <el-card
      ><template #header
        ><div class="toolbar">
          <strong>资料与交付</strong><el-button v-if="canWrite" type="primary" :disabled="busy" @click="openCreate">新增资料</el-button>
        </div></template
      >
      <el-table :data="rows" v-loading="busy" empty-text="暂无年报资料">
        <el-table-column prop="name" label="企业" min-width="150" /><el-table-column prop="report_year" label="年度" width="90" />
        <el-table-column label="项目" min-width="150"
          ><template #default="{ row }">{{ projectName(row.project_id) }}</template></el-table-column
        >
        <el-table-column label="状态" min-width="140"
          ><template #default="{ row }">{{ !row.enabled ? '资料已停用' : labels[row.state] || '待编制' }}</template></el-table-column
        >
        <el-table-column label="操作" min-width="280"
          ><template #default="{ row }"
            ><el-button text @click="inspect(row.id)">详情</el-button
            ><el-button v-if="canWrite && !row.task_id && row.enabled" text :disabled="busy" @click="compile(row)">编制</el-button
            ><el-button v-if="row.task_id" text @click="router.push({ path: '/funnyworker/tasks', query: { taskId: row.task_id } })"
              >执行记录</el-button
            ><el-button v-if="canWrite" text :disabled="busy" @click="toggle(row)">{{ row.enabled ? '停用资料' : '启用资料' }}</el-button></template
          ></el-table-column
        >
      </el-table>
      <p class="hint">
        首版为固定模板的年度经营摘要，金额单位为元，经营利润按收入减成本计算。新增资料后在“执行资源”配置编制用途、资料授权和路由。无合格终端时保持等待。
      </p>
    </el-card>
    <el-dialog v-model="creating" title="新增年报资料" width="min(820px,95vw)" :close-on-click-modal="false">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-width="90px"
        ><el-form-item label="企业名称"><el-input v-model="form.name" maxlength="120" placeholder="企业名称" /></el-form-item
        ><el-form-item label="年度"><el-input-number v-model="form.year" :min="2000" :max="2100" /></el-form-item
        ><el-form-item label="所属项目"
          ><el-select v-model="form.projectId" filterable style="width: 100%"
            ><el-option v-for="p in projects.filter((p) => p.enabled)" :key="p.id" :label="p.name" :value="p.id" /></el-select></el-form-item
      ></el-form>
      <el-table :data="form.materials"
        ><el-table-column label="资料编号" width="120"
          ><template #default="{ row }"><el-input v-model="row.id" maxlength="64" /></template></el-table-column
        ><el-table-column label="资料名称" min-width="150"
          ><template #default="{ row }"><el-input v-model="row.label" maxlength="120" /></template></el-table-column
        ><el-table-column label="指标" width="155"
          ><template #default="{ row }"
            ><el-select v-model="row.metric"
              ><el-option label="营业收入" value="REVENUE" /><el-option label="营业成本" value="COST" /><el-option
                label="上年收入"
                value="PRIOR_REVENUE" /></el-select></template></el-table-column
        ><el-table-column label="金额（元）" width="195"
          ><template #default="{ row }"
            ><el-input v-model="row.amount" inputmode="decimal" :aria-label="row.label + '金额'" maxlength="15" /></template></el-table-column
      ></el-table>
      <el-button :disabled="form.materials.length >= 100" @click="addMaterial">添加资料</el-button>
      <p class="hint">资料仅形成版本快照。编号使用小写字母、数字和连字符；名称使用文字、数字和常见分隔符。修改数据时创建新资料集。</p>
      <template #footer
        ><el-button @click="creating = false">取消</el-button
        ><el-button type="primary" :loading="busy" :disabled="!form.name.trim() || !form.projectId" @click="create">保存资料</el-button></template
      >
    </el-dialog>
    <el-drawer v-model="showDetail" title="年报详情" size="min(850px,95vw)">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="selected"
        ><h3>{{ selected.name }} · {{ selected.report_year }}</h3>
        <el-descriptions :column="1" border
          ><el-descriptions-item label="资料版本">{{ selected.revision }}</el-descriptions-item
          ><el-descriptions-item label="快照指纹"
            ><span class="identifier">{{ selected.snapshot_hash }}</span></el-descriptions-item
          ><el-descriptions-item label="交付状态">{{ labels[job?.state] || '待编制' }}</el-descriptions-item></el-descriptions
        >
        <h4>来源资料</h4>
        <el-table :data="materials"
          ><el-table-column prop="id" label="编号" /><el-table-column prop="label" label="名称" /><el-table-column label="指标"
            ><template #default="{ row }">{{ metrics[row.metric] }}</template></el-table-column
          ><el-table-column prop="amount" label="金额（元）"
        /></el-table>
        <TaskPlacement v-if="job" :key="job.task_id" :task-id="job.task_id" />
        <template v-if="evidence"
          ><h4>后端核验</h4>
          <p>{{ evidence.verified ? '全部核验通过，已自动交付' : '候选未通过核验，未发布正式文件' }}</p>
          <ul>
            <li v-for="check in evidenceRows" :key="check.code">{{ check.label }}：{{ check.status }}<p class="hint">{{ check.reason }}</p></li>
          </ul>
          <p class="hint">核验时间：{{ new Date(evidence.verifiedAt).toLocaleString() }}</p></template
        >
        <template v-if="job?.state === 'DELIVERED' && job.artifact_id"
          ><el-button @click="readDelivery">查看交付文件</el-button><el-button v-if="delivery" @click="download">下载报告</el-button>
          <pre v-if="delivery" class="report-text">{{ delivery }}</pre>
        </template>
      </template>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import { businessRequest as request } from '@/utils/funnyworker-api';
import { businessErrorMessage } from '@/utils/business-feedback';
import { checkPermi } from '@/utils/permission';
import { decodeTaskText } from '@/utils/task-result';
import TaskPlacement from './TaskPlacement.vue';
import { annualCheckRows } from '@/utils/annual-evidence';
import {onBeforeRouteLeave} from 'vue-router';
const reportPlatform = ref('macos');
const route=useRoute();
const returnTaskCenter=computed(()=>typeof route.query.returnTaskCenter==='string'&&route.query.returnTaskCenter.length<=4096&&/^\/funnyworker\/tasks(?:\?|$)/.test(route.query.returnTaskCenter)?route.query.returnTaskCenter:'');
onBeforeRouteLeave(async()=>{if(!creating.value)return true;try{await ElMessageBox.confirm('离开后未提交的年报资料将被放弃，是否继续？','放弃草稿',{confirmButtonText:'放弃并离开',cancelButtonText:'继续编辑'});return true;}catch{return false;}});
const router = useRouter(),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  rows = ref<any[]>([]),
  projects = ref<any[]>([]),
  creating = ref(false),
  showDetail = ref(false),
  selected = ref<any>(),
  delivery = ref('');
const canWrite = computed(() => checkPermi(['funnyworker:annual:write']));
const metrics: Record<string, string> = { REVENUE: '营业收入', COST: '营业成本', PRIOR_REVENUE: '上年收入' };
const labels: Record<string, string> = {
  WAITING: '等待合格终端',
  COMPILING: '编制中',
  DELIVERED: '已交付',
  REJECTED: '核验未通过',
  RUNTIME_FAILED: '终端执行失败',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  BLOCKED_AUTH: '授权已撤销'
};
const blank = () => ({
  name: '',
  year: new Date().getFullYear() - 1,
  projectId: '',
  materials: [
    { id: 'revenue', label: '年度收入汇总', metric: 'REVENUE', amount: '0.00' },
    { id: 'cost', label: '年度成本汇总', metric: 'COST', amount: '0.00' },
    { id: 'prior-revenue', label: '上年收入汇总', metric: 'PRIOR_REVENUE', amount: '0.00' }
  ]
});
const form = reactive(blank()),
  job = computed(() => selected.value?.job?.[0]),
  materials = computed(() => JSON.parse(selected.value?.materials_json || '[]')),
  evidence = computed(() => (job.value?.evidence_json ? JSON.parse(job.value.evidence_json) : null)),
  evidenceRows = computed(() => annualCheckRows(evidence.value));
let alive = true,
  generation = 0;
const requestKeys = new Map<string, string>();
onBeforeUnmount(() => {
  alive = false;
  generation++;
});
const projectName = (id: string) => projects.value.find((p) => p.id === id)?.name || id;
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const [items, catalog] = await Promise.all([request('/annual-reports'), request('/annual-reports/projects')]);
    if (alive) {
      rows.value = items;
      projects.value = catalog;
    }
  } catch (e) {
    if (alive) {
      rows.value = [];
      projects.value = [];
      error.value = businessErrorMessage(e);
    }
  } finally {
    if (alive) busy.value = false;
  }
}
function openCreate() {
  Object.assign(form, blank());
  error.value = '';
  notice.value = '';
  creating.value = true;
}
function addMaterial() {
  form.materials.push({ id: 'source-' + (form.materials.length + 1), label: '新增资料', metric: 'REVENUE', amount: '0.00' });
}
async function create() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await request('/annual-reports/datasets', 'POST', form);
    creating.value = false;
    notice.value = '资料快照已保存，请配置编制用途与授权。';
  } catch (e) {
    error.value = businessErrorMessage(e);
  } finally {
    busy.value = false;
  }
  if (!creating.value) await load();
}
async function compile(row: any) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  if (!requestKeys.has(row.id + reportPlatform.value)) requestKeys.set(row.id + reportPlatform.value, crypto.randomUUID());
  try {
    await request(
      '/annual-reports/compile',
      'POST',
      { datasetId: row.id, platform: reportPlatform.value },
      requestKeys.get(row.id + reportPlatform.value)
    );
    notice.value = '已进入自动编制流程。';
  } catch (e) {
    error.value = businessErrorMessage(e);
    return;
  } finally {
    busy.value = false;
  }
  await load();
}
async function toggle(row: any) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await request('/annual-reports/datasets/' + encodeURIComponent(row.id) + '/enabled', 'PUT', {
      enabled: !row.enabled,
      expectedVersion: row.revision
    });
  } catch (e) {
    error.value = businessErrorMessage(e);
    return;
  } finally {
    busy.value = false;
  }
  await load();
}
async function inspect(id: string) {
  const seq = ++generation;
  selected.value = undefined;
  delivery.value = '';
  error.value = '';
  showDetail.value = true;
  try {
    const detail = await request('/annual-reports/datasets/' + encodeURIComponent(id));
    if (alive && seq === generation) selected.value = detail;
  } catch (e) {
    if (alive && seq === generation) error.value = businessErrorMessage(e);
  }
}
async function readDelivery() {
  const seq = generation;
  delivery.value = '';
  error.value = '';
  try {
    const data = await request('/annual-reports/datasets/' + encodeURIComponent(selected.value.id) + '/delivery');
    if (alive && seq === generation) delivery.value = decodeTaskText(data.content_base64);
  } catch (e) {
    if (alive && seq === generation) error.value = businessErrorMessage(e);
  }
}
function download() {
  const url = URL.createObjectURL(new Blob([delivery.value], { type: 'text/markdown;charset=utf-8' })),
    a = document.createElement('a');
  a.href = url;
  a.download = 'annual-report-' + selected.value.report_year + '.md';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
watch(()=>route.query.datasetId,id=>{if(route.path==='/funnyworker/annual'&&typeof id==='string'&&id.length<=64)void inspect(id);},{immediate:true});
onMounted(load);
</script>
<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.identifier {
  overflow-wrap: anywhere;
}
.report-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  padding: 16px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
}
.annual :deep(.el-alert) {
  margin-bottom: 12px;
}
</style>
