<template>
  <el-card class="desktop-policy" shadow="never">
    <template #header><div class="heading"><strong>桌面组织策略</strong><el-button :disabled="busy" :loading="loading" @click="load">刷新状态</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" show-icon />
    <template v-if="state">
      <p>当前版本 {{ state.current.version }} · 作用于整个组织</p>
      <el-alert title="强制项由服务端检查，旧客户端也不能绕过。默认设置仅在成员没有个人设置时使用。" type="info" :closable="false" />
      <el-form label-position="top" :disabled="busy || !canWrite" @submit.prevent="publish">
        <h3>强制限制</h3>
        <el-form-item label="允许跨设备访问"><el-switch v-model="draft.constraints.crossDeviceAccess" /><span class="hint">关闭后禁止读取云端对话、连接码及跨设备操作，桌面仍可上传归档。</span></el-form-item>
        <el-form-item label="允许远程控制"><el-switch v-model="draft.constraints.remoteControl" :disabled="!draft.constraints.crossDeviceAccess" /><span class="hint">关闭后新命令被拒绝，排队命令过期；已经开始的操作不会被撤回。</span></el-form-item>
        <h3>默认设置</h3>
        <el-row :gutter="16"><el-col :md="12"><el-form-item label="默认主题"><el-select v-model="draft.defaults.theme"><el-option v-for="(label, value) in themes" :key="value" :label="label" :value="value" /></el-select></el-form-item></el-col><el-col :md="12"><el-form-item label="默认语言"><el-select v-model="draft.defaults.locale"><el-option v-for="(label, value) in locales" :key="value" :label="label" :value="value" /></el-select></el-form-item></el-col></el-row>
        <p v-if="dirty" class="hint">草稿尚未发布，刷新状态不会覆盖草稿。</p>
        <el-alert v-if="baseVersion !== state.current.version" title="发布版本已变化，请核对历史后放弃草稿并重新编辑。" type="warning" :closable="false" />
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="loading || baseVersion !== state.current.version || !canWrite">发布策略</el-button><el-button :disabled="!dirty || busy" @click="discard">放弃草稿</el-button>
      </el-form>
      <h3>生效预览</h3>
      <p class="hint">使用当前草稿模拟客户端设置，不会发布策略或写入设备回执。</p>
      <el-row :gutter="16"><el-col :md="8"><el-select v-model="preferences.theme" :disabled="busy" aria-label="个人主题（预览）" clearable placeholder="未设置个人主题"><el-option v-for="(label, value) in themes" :key="value" :label="label" :value="value" /></el-select></el-col><el-col :md="8"><el-select v-model="preferences.locale" :disabled="busy" aria-label="个人语言（预览）" clearable placeholder="未设置个人语言"><el-option v-for="(label, value) in locales" :key="value" :label="label" :value="value" /></el-select></el-col><el-col :md="8"><el-select v-model="supportedSchema" :disabled="busy" aria-label="客户端支持能力（预览）"><el-option :value="1" label="支持组织策略的客户端" /><el-option :value="0" label="未支持的旧客户端" /></el-select></el-col></el-row>
      <el-button class="preview-button" :disabled="busy || !canWrite" @click="preview">查看有效设置</el-button>
      <el-descriptions v-if="effective" :column="2" border><el-descriptions-item label="跨设备访问">{{ effective.crossDeviceAccess ? '允许' : '禁止' }}</el-descriptions-item><el-descriptions-item label="远程控制">{{ effective.remoteControl ? '允许' : '禁止' }}</el-descriptions-item><el-descriptions-item label="主题">{{ themes[effective.theme] }} · {{ effective.themeSource === 'USER' ? '个人设置' : '组织默认' }}</el-descriptions-item><el-descriptions-item label="语言">{{ locales[effective.locale] }} · {{ effective.localeSource === 'USER' ? '个人设置' : '组织默认' }}</el-descriptions-item></el-descriptions>
      <el-alert v-if="compatible === false" title="旧客户端无法确认默认设置已应用；服务端仍执行强制限制。" type="warning" :closable="false" />
      <h3>发布历史</h3>
      <el-table :data="state.history" empty-text="尚未发布自定义策略"><el-table-column prop="version" label="版本" width="90" /><el-table-column label="记录时间" min-width="170"><template #default="{ row }">{{ new Date(row.created_at).toLocaleString() }}</template></el-table-column><el-table-column label="设置" min-width="260"><template #default="{ row }">{{ policySummary(row.policy_json) }}</template></el-table-column><el-table-column label="来源"><template #default="{ row }">{{ row.version === 0 ? '系统默认基线' : row.restored_from != null ? `恢复自版本 ${row.restored_from}` : '发布' }}</template></el-table-column><el-table-column label="操作" width="150"><template #default="{ row }"><el-button v-if="row.version !== state.current.version" link :disabled="busy || !canWrite" @click="restore(row.version)">恢复为新版本</el-button><span v-else>当前版本</span></template></el-table-column></el-table>
      <h3>客户端回执</h3>
      <p class="hint">显示最近 100 台获取过策略的客户端。没有回执的设备状态为未知，不能将发布成功视为所有设备已应用。</p>
      <el-table :data="state.receipts" empty-text="尚无客户端获取或应用回执"><el-table-column label="设备" min-width="130"><template #default="{ row }"><span :title="row.device_id">{{ row.device_id.slice(0, 12) }}</span></template></el-table-column><el-table-column prop="client_version" label="客户端版本" /><el-table-column label="获取 / 应用版本"><template #default="{ row }">{{ row.issued_version }} / {{ row.applied_version ?? '未知' }}</template></el-table-column><el-table-column label="状态"><template #default="{ row }">{{ row.applied_version == null ? '未收到应用回执' : row.applied_version === state.current.version ? '客户端已报告应用' : '等待更新' }}</template></el-table-column></el-table>
    </template>
  </el-card>
</template>
<script setup lang="ts">
import { businessRequest } from '@/utils/funnyworker-api';
import { businessAccessLost, businessErrorMessage } from '@/utils/business-feedback';
import { useUserStore } from '@/store/modules/user';
import { ElMessageBox } from 'element-plus';
type Policy = { constraints: { crossDeviceAccess: boolean; remoteControl: boolean }; defaults: { theme: string; locale: string } };
const themes: Record<string, string> = { system: '跟随系统', light: '浅色', dark: '深色' };const locales: Record<string, string> = { system: '跟随系统', 'zh-CN': '简体中文', 'en-US': 'English' };
const state = ref<any>();const draft = reactive<Policy>({ constraints: { crossDeviceAccess: true, remoteControl: true }, defaults: { theme: 'system', locale: 'system' } });const baseVersion = ref(0);const original = ref('');
const preferences = reactive({ theme: '', locale: '' });const supportedSchema = ref(1);const effective = ref<any>();const compatible = ref<boolean>();
const busy = ref(false);const loading = ref(false);const error = ref('');const notice = ref('');let generation = 0;
const user = useUserStore();const canWrite = computed(() => user.permissions.includes('*:*:*') || user.permissions.includes('funnyworker:client:write'));const dirty = computed(() => JSON.stringify(draft) !== original.value);
function reset() { Object.assign(draft, JSON.parse(JSON.stringify(state.value.current.policy)));baseVersion.value = state.value.current.version;original.value = JSON.stringify(draft);effective.value = undefined;compatible.value = undefined; }
function clearLostAccess(e: unknown) { if (!businessAccessLost(e)) return;state.value=undefined;Object.assign(draft,{constraints:{crossDeviceAccess:false,remoteControl:false},defaults:{theme:'system',locale:'system'}});baseVersion.value=0;original.value=JSON.stringify(draft);preferences.theme='';preferences.locale='';effective.value=undefined;compatible.value=undefined; }
async function load() { if (loading.value) return;const run = ++generation;loading.value = true;error.value = '';try { const value = await businessRequest('/desktop-policy');if (run !== generation) return;const preserve = !!state.value && dirty.value;state.value = value;if (!preserve) reset(); } catch (e) { if (run === generation) { clearLostAccess(e);error.value = businessErrorMessage(e); } } finally { if (run === generation) loading.value = false; } }
async function discard() { try { await ElMessageBox.confirm('放弃未发布的策略草稿，重新读取当前设置？', '放弃草稿');reset(); } catch {} }
async function preview() { if (busy.value || !state.value) return;busy.value = true;error.value = '';try { const result = await businessRequest('/desktop-policy/preview', 'POST', { policy: draft, preferences: { theme: preferences.theme || null, locale: preferences.locale || null }, supportedSchema: supportedSchema.value });effective.value = result.effective;compatible.value = result.compatible; } catch (e) { clearLostAccess(e);error.value = businessErrorMessage(e); } finally { busy.value = false; } }
async function save(path: string, body: unknown) { if (busy.value || !canWrite.value || !state.value) return;busy.value = true;error.value = '';notice.value = '';try { const result = await businessRequest(path, 'POST', body);notice.value = `策略版本 ${result.version} 已发布。强制项用于后续请求，默认设置等待客户端应用。`;original.value = JSON.stringify(draft);await load();if (error.value) { error.value = '';notice.value += ' 状态读取失败，请刷新核对，无需重复发布。'; } } catch (e) { clearLostAccess(e);error.value = businessErrorMessage(e); } finally { busy.value = false; } }
async function publish() { try { await ElMessageBox.confirm('将向整个组织发布策略。关闭远程控制会使排队命令过期，不能撤回已经开始的操作。', '发布组织策略'); } catch { return; }await save('/desktop-policy', { expectedVersion: baseVersion.value, policy: draft }); }
function policySummary(value: string) { try { const policy = JSON.parse(value) as Policy;return `跨设备${policy.constraints.crossDeviceAccess ? '允许' : '禁止'}，远程控制${policy.constraints.crossDeviceAccess && policy.constraints.remoteControl ? '允许' : '禁止'}；${themes[policy.defaults.theme]}，${locales[policy.defaults.locale]}`; } catch { return '设置不可读'; } }
async function restore(version: number) { if(!state.value)return; const target = state.value.history.find((row: any) => row.version === version);try { await ElMessageBox.confirm(`将版本 ${version} 恢复为更高的新版本：${policySummary(target.policy_json)}。未发布草稿将被替换。`, '恢复策略'); } catch { return; }if(!state.value)return;await save('/desktop-policy/restore', { expectedVersion: state.value.current.version, targetVersion: version }); }
watch([draft, preferences, supportedSchema], () => { effective.value = undefined;compatible.value = undefined; }, { deep: true });onMounted(load);onBeforeUnmount(() => { generation++; });
</script>
<style scoped>
.desktop-policy { margin: 20px 20px 0; }.heading { display: flex; justify-content: space-between; align-items: center; }.hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; margin-left: 8px; }.el-alert { margin-bottom: 14px; }.el-select { width: 100%; }.preview-button { margin: 14px 0; }
</style>
