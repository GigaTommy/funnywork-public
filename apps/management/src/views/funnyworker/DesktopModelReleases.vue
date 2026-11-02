<template>
  <el-card class="desktop-model" shadow="never">
    <template #header><div class="release-heading"><strong>桌面模型发布</strong><el-button :loading="loading" :disabled="busy" @click="load">刷新状态</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" show-icon />
    <template v-if="current">
      <p>当前版本 {{ current.settings.expectedVersion }} · {{ current.source === 'DEPLOYMENT_FILE' ? '部署配置' : '已发布' }} · 整个组织生效范围</p>
      <p class="release-hint">共享密钥仅保留在服务端，旧版密钥配置下发已关闭。桌面推理请在模型中间层配置部署、计费和成员额度；此处发布的官方模型继续供手机聊天的服务端入口使用。</p>
      <el-alert v-if="!current.credentialReady" title="当前凭证不可用，请由部署管理员检查凭证引用。" type="warning" :closable="false" />
      <el-alert v-if="draft.expectedVersion !== current.settings.expectedVersion" title="发布版本已变化。草稿已保留，请核对当前配置后重新选择编辑基线。" type="warning" :closable="false" />
      <el-form label-position="top" :disabled="busy || !canWrite" @submit.prevent="publish">
        <el-row :gutter="16">
          <el-col :md="12"><el-form-item label="模型名称"><el-input v-model="draft.model" maxlength="100" /></el-form-item></el-col>
          <el-col :md="12"><el-form-item label="服务地址"><el-select v-model="draft.endpoint"><el-option label="DeepSeek 官方 API" value="https://api.deepseek.com/v1" /><el-option label="DeepSeek 官方 API（根地址）" value="https://api.deepseek.com" /></el-select></el-form-item></el-col>
          <el-col :md="12"><el-form-item label="思考模式"><el-select v-model="draft.thinking"><el-option label="开启" value="enabled" /><el-option label="关闭" value="disabled" /></el-select></el-form-item></el-col>
          <el-col :md="12"><el-form-item label="最大输出 token"><el-input-number v-model="draft.maxTokens" :min="128" :max="262144" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="凭证引用"><el-input v-model="draft.credentialRef" maxlength="100" placeholder="DEPLOYMENT_DEFAULT 或 FUNNYWORK_MODEL_ 开头的环境变量名" /><p class="release-hint">填写环境变量名称，不填写密钥。DEPLOYMENT_DEFAULT 使用部署文件中的凭证。历史版本只保留引用，回滚不会恢复历史密钥。</p></el-form-item>
        <div class="check-actions"><el-button :disabled="loading || busy || !canWrite" @click="check(false)">检查连接</el-button><span class="release-hint">连接检查不调用推理；推理测试请从已配置额度的客户端发起。</span></div>
        <p v-if="dirty" class="release-hint">有未发布的修改，刷新状态会保留草稿。</p>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="loading || !canWrite || draft.expectedVersion !== current.settings.expectedVersion">发布新版本</el-button>
        <el-button :disabled="!dirty || busy" @click="discard">放弃草稿</el-button>
        <el-button v-if="draft.expectedVersion !== current.settings.expectedVersion" @click="rebase">保留草稿并使用当前版本</el-button>
      </el-form>
      <h3>最近检查</h3>
      <p class="release-hint">以下为检查当时的结果，不代表草稿或部署凭证之后的变化已通过检查。</p>
      <el-table :data="current.checks" empty-text="尚无检查记录"><el-table-column label="检查" width="100"><template #default="{ row }">{{ row.check_type === 'INFERENCE' ? '推理' : '连接' }}</template></el-table-column><el-table-column label="模型" min-width="140"><template #default="{ row }">{{ checkedModel(row.settings_json) }}</template></el-table-column><el-table-column label="结果" min-width="180"><template #default="{ row }">{{ outcomes[row.outcome] || '未确认' }}</template></el-table-column><el-table-column label="时间" min-width="170"><template #default="{ row }">{{ new Date(row.observed_at).toLocaleString() }}</template></el-table-column></el-table>
      <h3>发布历史</h3>
      <el-table :data="current.history" empty-text="尚无管理端发布记录">
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column prop="model" label="模型" min-width="150" />
        <el-table-column label="参数" min-width="180"><template #default="{ row }">思考{{ row.thinking === 'enabled' ? '开启' : '关闭' }} · 输出上限 {{ row.max_tokens }}<br />{{ row.endpoint }}</template></el-table-column>
        <el-table-column prop="credential_ref" label="凭证引用" min-width="180" />
        <el-table-column label="时间" min-width="170"><template #default="{ row }">{{ new Date(row.published_at).toLocaleString() }}</template></el-table-column>
        <el-table-column label="来源" width="130"><template #default="{ row }">{{ row.origin === 'BASELINE' ? '部署基线' : row.restored_from ? `恢复自版本 ${row.restored_from}` : '发布记录' }}</template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button v-if="row.version !== current.settings.expectedVersion" link :disabled="busy || loading || !canWrite" @click="rollback(row.version)">以新版本恢复</el-button><span v-else>当前发布</span></template></el-table-column>
      </el-table>
    </template>
  </el-card>
</template>
<script setup lang="ts">
import { businessRequest } from '@/utils/funnyworker-api';
import { businessAccessLost, businessErrorMessage } from '@/utils/business-feedback';
import { useUserStore } from '@/store/modules/user';
import { ElMessageBox } from 'element-plus';
type Settings = { expectedVersion: number; endpoint: string; model: string; thinking: string; maxTokens: number; credentialRef: string };
type State = { settings: Settings; source: string; credentialReady: boolean; history: any[]; checks: any[] };
const current = ref<State>();
const draft = reactive<Settings>({ expectedVersion: 0, endpoint: 'https://api.deepseek.com/v1', model: '', thinking: 'disabled', maxTokens: 65536, credentialRef: 'DEPLOYMENT_DEFAULT' });
const original = ref('');const busy = ref(false);const loading = ref(false);const error = ref('');const notice = ref('');let generation = 0;
const user = useUserStore();const canWrite = computed(() => user.permissions.includes('*:*:*') || user.permissions.includes('funnyworker:models:write'));
const dirty = computed(() => original.value !== JSON.stringify(draft));
defineExpose({hasUnsavedDraft:()=>canWrite.value&&!!current.value&&dirty.value});
function reset() { if (current.value) { Object.assign(draft, current.value.settings);original.value = JSON.stringify(draft); } }
function clearLostAccess(e: unknown) { if (!businessAccessLost(e)) return;current.value=undefined;Object.assign(draft,{expectedVersion:0,endpoint:'https://api.deepseek.com/v1',model:'',thinking:'disabled',maxTokens:65536,credentialRef:''});original.value=JSON.stringify(draft); }
async function load() {
  if (loading.value) return;
  const run = ++generation;loading.value = true;error.value = '';
  try { const value = await businessRequest('/desktop-model-releases') as State;if (run !== generation) return;const preserve = !!current.value && dirty.value;current.value = value;if (!preserve) reset(); }
  catch (e) { if (run === generation) { clearLostAccess(e);error.value = businessErrorMessage(e); } }
  finally { if (run === generation) loading.value = false; }
}
async function rebase() { if (!current.value) return;try { await ElMessageBox.confirm(`当前版本 ${current.value.settings.expectedVersion} 使用模型 ${current.value.settings.model}。继续将以保留的草稿替换当前设置，请先核对发布历史。`, '更新编辑基线');draft.expectedVersion = current.value.settings.expectedVersion; } catch {} }
async function discard() { try { await ElMessageBox.confirm('放弃尚未发布的修改，重新使用当前配置？', '放弃草稿');reset(); } catch {} }
async function save(path: string, body: unknown) {
  if (busy.value || !canWrite.value || !current.value) return;
  busy.value = true;error.value = '';notice.value = '';const run = generation;
  try {
    const result = await businessRequest(path, 'POST', body);
    if (run !== generation) return;
    notice.value = `版本 ${result.version} 已发布。客户端加载状态尚待确认。`;
    // A successful write is final even when the subsequent status read fails.
    original.value = JSON.stringify(draft);await load();
    if (error.value) { error.value = '';notice.value += ' 最新状态读取失败，请刷新核实，无需重复发布。'; }
  } catch (e) { if (run === generation) { clearLostAccess(e);error.value = businessErrorMessage(e); } }
  finally { busy.value = false; }
}
const outcomes: Record<string, string> = { DISCOVERABLE: '连接和凭证有效，模型可发现', MODEL_NOT_LISTED: '接口可达，模型未列出', INFERENCE_OK: '推理返回有效结果', AUTH_REJECTED: '凭证被拒绝', REDIRECT_REJECTED: '接口重定向被拒绝', INVALID_RESPONSE: '接口返回格式无效', HTTP_ERROR: '接口返回错误', UNREACHABLE: '接口连接未完成' };
function checkedModel(settings: string) { try { return JSON.parse(settings).model; } catch { return '未知'; } }
async function check(inference: boolean) {
  if (busy.value || !canWrite.value || !current.value) return;
  if (inference) { try { await ElMessageBox.confirm('发送固定的简短测试提示，最多请求 128 个输出 token，将产生少量模型用量。', '测试推理'); } catch { return; } }
  busy.value = true;error.value = '';notice.value = '';const run = generation;
  try { const result = await businessRequest('/desktop-model-releases/check?inference=' + inference, 'POST', { ...draft });if (run !== generation) return;notice.value = `${outcomes[result.outcome] || '检查已完成'} · ${result.latencyMs} 毫秒`;await load(); }
  catch (e) { if (run === generation) { clearLostAccess(e);error.value = businessErrorMessage(e); } }
  finally { busy.value = false; }
}
async function publish() {
  if (!/^[A-Za-z0-9_.-]{1,100}$/.test(draft.model) || !/^(DEPLOYMENT_DEFAULT|FUNNYWORK_MODEL_[A-Z0-9_]{1,80})$/.test(draft.credentialRef)) { error.value = '请填写有效的模型名称和凭证引用。';return; }
  try { await ElMessageBox.confirm('将向整个组织发布此配置，客户端在续期或重新登录时获取。确认发布？', '发布桌面模型'); } catch { return; }
  await save('/desktop-model-releases', { ...draft });
}
async function rollback(version: number) {
  if (!current.value) return;
  try { await ElMessageBox.confirm(`将版本 ${version} 的设置发布为更高的新版本。凭证按当前引用解析，不恢复历史密钥。${dirty.value ? '未发布草稿将被替换。' : ''}`, '恢复模型设置'); } catch { return; }
  if (!current.value) return;
  await save('/desktop-model-releases/rollback', { expectedVersion: current.value.settings.expectedVersion, targetVersion: version });
}
onMounted(load);onBeforeUnmount(() => { generation++; });
</script>
<style scoped>
.desktop-model { margin: 20px 20px 0; }.release-heading { display: flex; align-items: center; justify-content: space-between; }.release-hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; }.el-select { width: 100%; }.el-alert { margin-bottom: 14px; }.check-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
</style>
