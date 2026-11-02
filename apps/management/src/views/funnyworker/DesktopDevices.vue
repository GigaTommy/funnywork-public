<template>
  <el-card class="devices" shadow="never">
    <template #header><div class="heading"><strong>成员设备</strong><el-button :disabled="busy || loading" @click="load(true)">刷新设备</el-button></div></template>
    <p class="hint">显示成员登录并注册同步的桌面设备。在线表示最近 30 秒收到运行状态，配置状态来自客户端上报，不代表模型推理已成功。历史归集仅统计服务端已接收的会话与事件，不代表设备全部历史已上传。执行节点在节点管理中维护，远程控制授权在控制会话中维护。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" show-icon />
    <el-form inline label-width="90px" :disabled="busy || loading" @submit.prevent="load(true)">
      <el-form-item label="设备或成员"><el-input v-model="query" maxlength="120" clearable placeholder="输入名称" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="filter" style="width: 130px"><el-option v-for="(label, value) in filters" :key="value" :value="value" :label="label" /></el-select></el-form-item>
      <el-form-item><el-button native-type="submit" :loading="loading">查询</el-button></el-form-item>
    </el-form>
    <el-table :data="items" v-loading="loading" :empty-text="error || !observedAt ? '本次设备查询未完成，请根据提示重试。' : '当前页没有符合条件且在授权范围内的设备'">
      <el-table-column label="设备" min-width="180"><template #default="{ row }">{{ row.name }}<div class="hint">归集 {{ row.historySessionCount }} 个会话 / {{ row.historyEventCount }} 条事件</div></template></el-table-column>
      <el-table-column prop="owner_name" label="成员" min-width="130" />
      <el-table-column label="系统" width="110"><template #default="{ row }">{{ platforms[row.platform] || row.platform }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }">{{ status(row) }}</template></el-table-column>
      <el-table-column label="最近上报" min-width="170"><template #default="{ row }">{{ time(row.state_seen) }}</template></el-table-column>
      <el-table-column prop="client_version" label="客户端版本" min-width="120"><template #default="{ row }">{{ row.client_version || '尚未上报' }}<br /><span class="hint">{{ versionSources[row.clientVersionSource] || versionSources.UNKNOWN }}</span></template></el-table-column>
      <el-table-column label="模型状态" min-width="170"><template #default="{ row }">{{ states[row.modelStatus] }}<br /><span class="hint">下发 {{ row.model_issued_version ?? '未知' }} / 最后确认 {{ row.model_applied_version ?? '未知' }}</span></template></el-table-column>
      <el-table-column label="组织策略" min-width="170"><template #default="{ row }">{{ states[row.policyStatus] }}<br /><span class="hint">当前 {{ row.policyTargetVersion }} / 最后确认 {{ row.policy_applied_version ?? '未知' }}</span></template></el-table-column>
      <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button link @click="selected = row">详情</el-button><el-button link :disabled="busy || loading || !canWrite" @click="change(row)">{{ row.revoked ? '恢复访问' : '撤销访问' }}</el-button></template></el-table-column>
    </el-table>
    <div class="footer"><span v-if="observedAt" class="hint">已显示 {{ items.length }} 台设备；状态截至 {{ time(observedAt) }}。{{ hasMore ? '还有数据可继续读取。' : '' }}</span><el-button v-if="hasMore" :disabled="busy || loading || query !== appliedQuery || filter !== appliedFilter" @click="load(false)">继续读取</el-button></div>
    <el-drawer :model-value="!!selected" title="设备详情" size="min(560px, 100%)" @close="selected = undefined">
      <template v-if="selected"><el-descriptions :column="1" border>
        <el-descriptions-item label="设备">{{ selected.name }}</el-descriptions-item><el-descriptions-item label="设备标识">{{ selected.id }}</el-descriptions-item>
        <el-descriptions-item label="成员">{{ selected.owner_name }}</el-descriptions-item><el-descriptions-item label="成员标识">{{ selected.principal_id }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ status(selected) }}</el-descriptions-item><el-descriptions-item label="注册时间">{{ time(selected.created_at) }}</el-descriptions-item><el-descriptions-item label="最近同步">{{ time(selected.last_seen) }}</el-descriptions-item><el-descriptions-item label="最近运行状态">{{ time(selected.state_seen) }}</el-descriptions-item>
        <el-descriptions-item label="历史归集">{{ selected.historySessionCount }} 个会话 / {{ selected.historyEventCount }} 条事件；最近更新 {{ time(selected.historyLastUpdatedAt) }}</el-descriptions-item>
        <el-descriptions-item label="客户端版本">{{ selected.client_version || '尚未上报' }}</el-descriptions-item>
        <el-descriptions-item label="版本来源">{{ versionSources[selected.clientVersionSource] || versionSources.UNKNOWN }}</el-descriptions-item>
        <el-descriptions-item label="构建提交">{{ selected.clientBuild?.commit || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="构建树">{{ selected.clientBuild?.tree || '未知' }}</el-descriptions-item>
        <el-descriptions-item label="构建状态">{{ selected.clientBuild == null ? '未知' : selected.clientBuild.dirty ? '含未提交更改' : '干净构建' }}</el-descriptions-item>
        <el-descriptions-item label="模型配置">{{ states[selected.modelStatus] }} · 当前配置 {{ selected.modelTargetVersion ?? '目标版本未知' }} / 下发 {{ selected.model_issued_version ?? '未知' }} / 最后确认 {{ selected.model_applied_version ?? '未知' }}</el-descriptions-item>
        <el-descriptions-item v-if="selected.modelSettings" label="下发模型">{{ selected.modelSettings.model }} · {{ selected.modelSettings.thinking === 'enabled' ? '开启思考' : '关闭思考' }} · 最大输出 {{ selected.modelSettings.maxTokens }}</el-descriptions-item>
        <el-descriptions-item label="模型状态上报">{{ time(selected.model_reported_at) }}</el-descriptions-item><el-descriptions-item label="下发配置有效期至">{{ time(selected.model_expires_at) }}</el-descriptions-item>
        <el-descriptions-item label="组织策略">{{ states[selected.policyStatus] }} · 当前 {{ selected.policyTargetVersion }} / 下发 {{ selected.policy_issued_version ?? '未知' }} / 最后确认 {{ selected.policy_applied_version ?? '未知' }}</el-descriptions-item><el-descriptions-item label="策略应用确认">{{ time(selected.policy_applied_at) }}</el-descriptions-item>
      </el-descriptions><p class="hint">模型新版本在客户端下一次续期时获取（通常一小时），重新启动可立即获取；组织策略约每分钟刷新。</p><p class="hint">撤销后拒绝该设备同步与远程控制，使排队命令过期；已领取命令标记为结果不确定，不自动重放。不会删除本地数据，也不能保证中断已经开始的操作。恢复后需设备重新上报才显示在线。</p></template>
    </el-drawer>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessageBox } from 'element-plus';
import { useUserStore } from '@/store/modules/user';
import { businessRequest } from '@/utils/funnyworker-api';
import { BusinessError, businessErrorMessage } from '@/utils/business-feedback';
interface Device { historySessionCount: number; historyEventCount: number; historyLastUpdatedAt: number | null; id: string; principal_id: string; owner_name: string; owner_enabled: boolean; name: string; platform: string; revoked: boolean; online: boolean; created_at: number; last_seen: number; state_seen: number; access_revision: number; client_version: string | null; clientVersionSource: string; clientBuild: { version: string; commit: string; tree: string; dirty: boolean } | null; modelStatus: string; policyStatus: string; modelTargetVersion: number | null; policyTargetVersion: number; model_issued_version: number | null; model_applied_version: number | null; model_reported_at: number | null; model_expires_at: number | null; policy_issued_version: number | null; policy_applied_version: number | null; policy_applied_at: number | null; modelSettings: { model: string; thinking: string; maxTokens: number } | null }
const versionSources: Record<string, string> = { DEVICE_BUILD_MANIFEST: '客户端构建清单（设备自报）', LEGACY_MODEL_RECEIPT: '旧模型配置回执', UNKNOWN: '未知' };
const states: Record<string, string> = { UNREPORTED: '尚未上报', NOT_APPLIED: '运行设置未匹配', PENDING: '等待客户端确认', APPLIED: '客户端已确认下发版本', UPDATE_REQUIRED: '等待更新', EXPIRED: '下发配置已过期' };
interface Page { items: Device[]; after: string; hasMore: boolean; observedAt: number }
const filters = { ALL: '全部', ONLINE: '在线', OFFLINE: '离线', REVOKED: '已撤销' };const platforms: Record<string, string> = { darwin: 'macOS', win32: 'Windows', linux: 'Linux' };
const items = ref<Device[]>([]);const selected = ref<Device>();const query = ref('');const filter = ref('ALL');const hasMore = ref(false);const after = ref('');const observedAt = ref(0);const busy = ref(false);const loading = ref(false);const error = ref('');const notice = ref('');let disposed = false;const appliedQuery = ref('');const appliedFilter = ref('ALL');
const user = useUserStore();const canWrite = computed(() => user.permissions.includes('*:*:*') || user.permissions.includes('funnyworker:client:write'));
const time = (value: number | null) => value != null && value > 0 ? new Date(value).toLocaleString() : '尚未上报';
const status = (row: Device) => row.revoked ? '已撤销' : !row.owner_enabled ? '账号已停用' : row.online ? '在线' : '离线';
async function read(reset: boolean) { const q = reset ? query.value : appliedQuery.value;const state = reset ? filter.value : appliedFilter.value;const page: Page = await businessRequest('/desktop-devices?' + new URLSearchParams({ query: q, state, after: reset ? '' : after.value }));if (disposed) return;items.value = reset ? page.items : [...items.value, ...page.items];after.value = page.after;hasMore.value = page.hasMore;observedAt.value = page.observedAt;appliedQuery.value = q;appliedFilter.value = state;if (selected.value) selected.value = items.value.find(row => row.id === selected.value?.id); }
function clearResults(){items.value=[];selected.value=undefined;after.value='';hasMore.value=false;observedAt.value=0;}
async function load(reset: boolean) { if (loading.value || busy.value || disposed) return;loading.value = true;error.value = '';try { await read(reset); } catch (e) { if (!disposed) {clearResults();error.value = businessErrorMessage(e);} } finally { loading.value = false; } }
async function change(row: Device) {
  if (busy.value || !canWrite.value || disposed) return;busy.value = true;error.value = '';notice.value = '';
  try {
    try { await ElMessageBox.confirm(row.revoked ? `恢复“${row.name}”的同步与远程控制访问？设备重新上报后才会显示在线。` : `撤销“${row.name}”的同步与远程控制访问？排队命令将过期；已开始的操作可能仍在执行。`, row.revoked ? '恢复设备访问' : '撤销设备访问'); } catch { return; }
    if (disposed) return;
    const result = await businessRequest(`/desktop-devices/${encodeURIComponent(row.id)}/${row.revoked ? 'restore' : 'revoke'}`, 'POST', { revision: row.access_revision });
    if (disposed) return;row.revoked = result.revoked;row.access_revision = result.revision;row.online = false;notice.value = '设备访问状态已更新。';
    try { await read(true); } catch { clearResults();notice.value += ' 最新列表读取失败，请刷新核对，无需重复提交。'; }
  } catch (e) { if (!disposed) {if(e instanceof BusinessError&&(e.status===401||e.status===403||e.code==='SESSION_CHANGED'))clearResults();error.value = businessErrorMessage(e);} } finally { busy.value = false; }
}
onMounted(() => load(true));onBeforeUnmount(() => { disposed = true; });
</script>
<style scoped>
.devices { margin: 20px 20px 0; }.heading, .footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }.hint { color: var(--el-text-color-secondary); line-height: 1.7; font-size: 13px; }.el-alert { margin-bottom: 12px; }.footer { margin-top: 16px; }
</style>
