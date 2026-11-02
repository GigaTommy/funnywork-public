<template>
  <section class="sync-checks">
    <p class="hint">使用所选仓库的 VERIFY 授权及 Codex 只读检查能力；DEVELOP 权限不能替代。只检查已安装 FunnyWork 的构建版本与 Codex 初始化能力。选择三个已授权终端；不会注册设备、修改代码或合并 GitHub。</p>
    <el-alert v-if="!enabled" title="项目门禁未启用，三端检查已禁用。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="pending" title="此请求的结果尚未确认。重试沿用同一请求编号，避免重复创建；字段暂时锁定。" type="warning" :closable="false" />
    <el-form label-position="top" :disabled="busy || !!pending || !enabled" @submit.prevent="submit">
      <el-form-item label="已配置仓库">
        <el-select v-model="selection.repositoryId" placeholder="选择仓库" @change="resetCandidates">
          <el-option v-for="repository in repositories" :key="repository.id" :label="repository.slug" :value="repository.id" />
        </el-select>
      </el-form-item>
      <div class="fields">
        <el-form-item label="固定基线 commit SHA"
          ><el-input v-model="selection.baseSha" maxlength="64" placeholder="40 或 64 位小写十六进制，不接受 main"
        /></el-form-item>
        <el-form-item label="预期 FunnyWork 应用版本"
          ><el-input v-model="selection.expectedVersion" maxlength="80" placeholder="例如 1.2.3；不是 Codex CLI 版本"
        /></el-form-item>
      </div>
      <el-button :disabled="!selection.repositoryId" :loading="candidateBusy" @click="loadCandidates">读取授权终端</el-button>
      <div v-for="platform in syncPlatforms" :key="platform" class="platform">
        <el-form-item :label="executionPlatform(platform)">
          <el-select v-model="selection.devices[platform]" clearable placeholder="选择已授权且在线的终端" @change="checkCapability(platform)">
            <el-option
              v-for="device in candidates[platform]"
              :key="device.id"
              :value="device.id"
              :label="device.name + ' · ' + device.id"
              :disabled="!device.authorized || !device.available"
            />
          </el-select>
        </el-form-item>
        <p v-if="queried && !candidates[platform].some((device) => device.authorized && device.available)" role="status" class="hint">
          当前已加载终端中，{{ executionPlatform(platform) }} 没有可选设备。请检查设备注册、维护绑定、项目授权与在线状态；此处不会自动补注册。
        </p>
        <p v-if="selection.devices[platform]" role="status">
          {{
            capabilityBusy[platform]
              ? '正在核对 Codex 能力…'
              : capabilities[platform]?.available
                ? '已核对 Codex 只读检查能力'
                : 'Codex 能力不可用或设备已离线'
          }}
        </p>
        <p v-for="device in candidates[platform].filter((item) => !item.available)" :key="device.id" class="hint">
          {{ device.name }}：{{ device.reasons.map((reason: string) => executionReasons[reason] || '当前条件未满足').join('；') }}
        </p>
        <el-button v-if="pages[platform]?.hasMore" :loading="candidateBusy" @click="loadMore(platform)"
          >加载更多{{ executionPlatform(platform) }}终端</el-button
        >
      </div>
    </el-form>
    <div class="toolbar">
      <el-button type="primary" :loading="busy" :disabled="!enabled || busy || (!pending && !prepared)" @click="submit">{{
        pending ? '重试确认同一检查' : '创建三端只读检查'
      }}</el-button>
      <el-button :loading="historyBusy" @click="loadHistory">刷新检查记录</el-button>
    </div>
    <h3>最近可见检查（最多 25 项）</h3>
    <p v-if="observedAt" class="hint">读取时间：{{ observedAt }}。状态以刷新后的服务端记录为准。</p>
    <el-empty v-if="!history.length" description="暂无可见检查记录" />
    <el-card v-for="job in history" :key="job.id" class="job">
      <p class="identifier">检查 {{ job.id }} · {{ repositoryName(job.repository_id) }}</p>
      <p class="identifier hint">基线 {{ job.base_sha }} · 应用版本 {{ job.expected_version }}</p>
      <el-table :data="job.targets">
        <el-table-column label="平台／设备" min-width="200"
          ><template #default="{ row }"
            >{{ executionPlatform(row.platform) }}
            <div class="identifier hint">{{ row.device_id }}</div></template
          ></el-table-column
        >
        <el-table-column label="状态" min-width="180"
          ><template #default="{ row }"
            >{{ syncStates[row.state] || '未知状态' }}
            <div v-if="row.receipt">{{ row.aligned ? '版本与基线一致' : '尚未确认一致' }}</div></template
          ></el-table-column
        >
        <el-table-column label="结构化证据" min-width="310"
          ><template #default="{ row }"
            ><template v-if="row.receipt"
              ><div>应用 {{ row.receipt.clientVersion || '未知' }} · Codex {{ row.receipt.pluginVersion || '未知' }}</div>
              <div class="identifier">已安装 commit：{{ row.receipt.observedSha || '未知' }}</div>
              <div class="identifier">
                构建 tree：{{ row.receipt.buildTree || '未知' }} · {{ row.receipt.buildDirty === false ? '干净构建' : '未确认干净' }}
              </div>
              <div>进程：{{ row.receipt.processStopped ? '已停止' : '未确认停止' }} · 事件 {{ row.receipt.eventCount }}</div>
              <div class="identifier hint">证据摘要：{{ row.receipt.evidenceDigest }}</div>
              <div>{{ row.receipt.summary }}</div></template
            ><span v-else>尚无回执；UNKNOWN 会保持隔离，不能据此认定检查完成。</span></template
          ></el-table-column
        >
      </el-table>
    </el-card>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { ElMessageBox } from 'element-plus';
import { getToken } from '@/utils/auth';
import { businessRequest as request } from '@/utils/funnyworker-api';
import { BusinessError, businessErrorMessage, businessAccessLost } from '@/utils/business-feedback';
import {
  syncPlatforms,
  syncPlatform,
  syncRequest,
  syncPreviewRequest,
  syncStates,
  type SyncPlatform,
  type SyncSelection,
  type SyncCapability
} from '@/utils/sync-checks';
import { executionPlatform, executionReasons } from './execution-copy';
const props = defineProps<{ repositories: { id: string; slug: string }[]; enabled: boolean; sessionScope: string }>();
const sessionCredential = getToken();
const selection = reactive<SyncSelection>({ repositoryId: '', baseSha: '', expectedVersion: '', devices: { macos: '', windows: '', linux: '' } });
const candidates = reactive<Record<SyncPlatform, any[]>>({ macos: [], windows: [], linux: [] });
const pages = reactive<Partial<Record<SyncPlatform, any>>>({});
const capabilities = reactive<Partial<Record<SyncPlatform, SyncCapability>>>({});
const capabilityBusy = reactive<Record<SyncPlatform, boolean>>({ macos: false, windows: false, linux: false });
const busy = ref(false),
  candidateBusy = ref(false),
  historyBusy = ref(false),
  queried = ref(false),
  error = ref(''),
  history = ref<any[]>([]),
  observedAt = ref('');
const pending = ref<{ key: string; uncertain: boolean; body: NonNullable<ReturnType<typeof syncRequest>> }>();
const prepared = computed(() => syncRequest(selection, capabilities));
const capabilityGeneration: Record<SyncPlatform, number> = { macos: 0, windows: 0, linux: 0 };
let sessionInvalid = false;
let alive = true,
  generation = 0,
  historyGeneration = 0;
const repositoryName = (id: string) => props.repositories.find((repository) => repository.id === id)?.slug || id;
function failure(cause: unknown) {
  error.value = businessErrorMessage(cause);
  if (businessAccessLost(cause)) {
    generation++;
    historyGeneration++;
    history.value = [];
    pending.value = undefined;
    persistPending();
    resetCandidates();
    selection.repositoryId = '';
    selection.baseSha = '';
    selection.expectedVersion = '';
    observedAt.value = '';
  }
}
function resetCandidates() {
  generation++;
  queried.value = false;
  for (const platform of syncPlatforms) {
    candidates[platform] = [];
    delete pages[platform];
    delete capabilities[platform];
    selection.devices[platform] = '';
    capabilityBusy[platform] = false;
    capabilityGeneration[platform]++;
  }
}
async function loadPage(platform: SyncPlatform, more = false) {
  if (!selection.repositoryId || !sameSession()) return;
  const current = generation,
    repositoryId = selection.repositoryId;
  const result = await request('/execution-placement/preview', 'POST', syncPreviewRequest(repositoryId, platform, more ? pages[platform]?.after || '' : ''));
  if (!alive || current !== generation || !sameSession()) return;
  pages[platform] = result;
  const rows = result.items.filter((device: any) => syncPlatform(device.platform) === platform);
  candidates[platform] = more ? [...candidates[platform], ...rows] : rows;
}
async function loadMore(platform: SyncPlatform) {
  if (candidateBusy.value || pending.value || !sameSession()) return;
  candidateBusy.value = true;
  error.value = '';
  const current = generation;
  try {
    await loadPage(platform, true);
  } catch (cause) {
    if (alive && current === generation) failure(cause);
  } finally {
    if (alive) candidateBusy.value = false;
  }
}
async function loadCandidates() {
  if (candidateBusy.value || !props.enabled || pending.value || !sameSession()) return;
  resetCandidates();
  const current = generation;
  candidateBusy.value = true;
  error.value = '';
  try {
    await Promise.all(syncPlatforms.map((platform) => loadPage(platform)));
    if (alive && current === generation) queried.value = true;
  } catch (cause) {
    if (alive && current === generation) failure(cause);
  } finally {
    if (alive) candidateBusy.value = false;
  }
}
async function checkCapability(platform: SyncPlatform) {
  if (!sameSession()) return;
  delete capabilities[platform];
  const device = selection.devices[platform],
    current = generation,
    capabilityRequest = ++capabilityGeneration[platform];
  if (!device) return;
  capabilityBusy[platform] = true;
  try {
    const result = await request('/maintenance/sync-checks/devices/' + encodeURIComponent(device) + '/capabilities');
    if (
      alive &&
      sameSession() &&
      current === generation &&
      capabilityRequest === capabilityGeneration[platform] &&
      selection.devices[platform] === device
    )
      capabilities[platform] = result;
  } catch (cause) {
    if (alive && sameSession() && current === generation && capabilityRequest === capabilityGeneration[platform]) failure(cause);
  } finally {
    if (
      alive &&
      sameSession() &&
      current === generation &&
      capabilityRequest === capabilityGeneration[platform] &&
      selection.devices[platform] === device
    )
      capabilityBusy[platform] = false;
  }
}
async function loadHistory() {
  if (historyBusy.value || busy.value || !sameSession()) return;
  const current = ++historyGeneration;
  historyBusy.value = true;
  try {
    const result = await request('/maintenance/sync-checks');
    if (alive && sameSession() && current === historyGeneration) {
      history.value = result;
      observedAt.value = new Date().toLocaleString();
      error.value = '';
    }
  } catch (cause) {
    if (alive && current === historyGeneration) failure(cause);
  } finally {
    if (alive) historyBusy.value = false;
  }
}
function sameSession() {
  if (!sessionInvalid && sessionCredential && sessionCredential === getToken()) return true;
  if (sessionInvalid) return false;
  sessionInvalid = true;
  failure(new BusinessError('SESSION_CHANGED', 401));
  return false;
}
async function submit() {
  if (busy.value || !props.enabled || !sameSession()) return;
  const current = generation;
  if (!pending.value) {
    const body = prepared.value;
    if (!body) return;
    busy.value = true;
    try {
      await ElMessageBox.confirm(
        `仓库：${repositoryName(body.repositoryId)}\n基线：${body.baseSha}\n应用版本：${body.expectedVersion}\n${body.targets.map((target) => executionPlatform(target.platform) + '：' + target.deviceId).join('\n')}\n仅执行版本与初始化检查。`,
        '确认三端只读检查',
        { confirmButtonText: '创建检查', cancelButtonText: '取消', type: 'info' }
      );
    } catch {
      busy.value = false;
      return;
    }
    if (!alive || current !== generation || !props.enabled || !sameSession()) {
      busy.value = false;
      return;
    }
    pending.value = { key: crypto.randomUUID(), uncertain: false, body };
  }
  busy.value = true;
  historyGeneration++;
  error.value = '';
  try {
    if (!sameSession()) return;
    const identity = await request('/me');
    if (!alive || current !== generation || !sameSession()) return;
    if (identity.tenant + ':' + identity.principalId !== props.sessionScope || !identity.roles?.includes('ADMIN')) {
      sessionInvalid = true;
      failure(new BusinessError('SESSION_CHANGED', 401));
      return;
    }
    if (!persistPending() || !sameSession()) return;
    const result = await request('/maintenance/sync-checks', 'POST', pending.value.body, pending.value.key);
    if (alive && current === generation) {
      pending.value = undefined;
      persistPending();
      history.value = [result, ...history.value.filter((job) => job.id !== result.id)].slice(0, 25);
      observedAt.value = new Date().toLocaleString();
    }
  } catch (cause) {
    if (alive && current === generation) {
      failure(cause);
      if (pending.value) {
        const status = cause instanceof BusinessError ? cause.status : 0;
        if (!pending.value.uncertain && status >= 400 && status < 500 && status !== 429) pending.value = undefined;
        else pending.value.uncertain = true;
        persistPending();
      }
    }
  } finally {
    if (alive) busy.value = false;
  }
}

function persistPending() {
  try {
    if (!props.sessionScope) throw new Error('Missing session');
    const key = 'funnywork-sync-check-pending:' + props.sessionScope;
    if (pending.value) sessionStorage.setItem(key, JSON.stringify(pending.value));
    else sessionStorage.removeItem(key);
    return true;
  } catch {
    error.value = '本地请求状态保存失败，请保留页面并先核对检查记录。';
    return false;
  }
}
function restorePending() {
  try {
    if (!props.sessionScope) return;
    const saved = JSON.parse(sessionStorage.getItem('funnywork-sync-check-pending:' + props.sessionScope) || 'null');
    if (
      !saved ||
      !/^[a-f0-9-]{36}$/.test(saved.key) ||
      saved.body?.executor !== 'funnywork-codex-v1' ||
      saved.body?.protocolVersion !== 1 ||
      !Array.isArray(saved.body?.targets) ||
      saved.body.targets.length !== 3
    )
      return;
    const restored: SyncSelection = {
      repositoryId: saved.body.repositoryId,
      baseSha: saved.body.baseSha,
      expectedVersion: saved.body.expectedVersion,
      devices: { macos: '', windows: '', linux: '' }
    };
    const restoredCapabilities: Partial<Record<SyncPlatform, SyncCapability>> = {};
    for (const target of saved.body.targets) {
      if (!syncPlatforms.includes(target.platform) || !/^[A-Za-z0-9_-]{1,64}$/.test(target.deviceId)) return;
      restored.devices[target.platform as SyncPlatform] = target.deviceId;
      restoredCapabilities[target.platform as SyncPlatform] = {
        deviceId: target.deviceId,
        platform: target.platform,
        online: true,
        available: true,
        executors: [{ executor: 'funnywork-codex-v1', protocolVersion: 1, executorVersion: '1', capabilities: ['sync-check'] }]
      };
    }
    const body = syncRequest(restored, restoredCapabilities);
    if (!body || !props.repositories.some((repository) => repository.id === body.repositoryId)) return;
    Object.assign(selection, restored);
    pending.value = { key: saved.key, body, uncertain: true };
  } catch {
    /* An invalid local draft never authorizes a request. */
  }
}
watch(() => getToken(), sameSession, { flush: 'sync' });
onMounted(() => {
  restorePending();
  void loadHistory();
});
onBeforeUnmount(() => {
  alive = false;
  generation++;
  historyGeneration++;
});
</script>
<style scoped>
.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.identifier {
  overflow-wrap: anywhere;
}
.fields {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
}
.platform,
.job {
  margin-top: 18px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
}
.el-select {
  width: 100%;
}
.el-alert {
  margin: 12px 0;
}
@media (max-width: 700px) {
  .fields {
    grid-template-columns: 1fr;
  }
}
</style>
