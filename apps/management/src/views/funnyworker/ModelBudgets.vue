<template>
  <div class="app-container"><el-card shadow="never">
    <template #header><strong>{{ c.title }}</strong></template>
    <p class="hint">{{ c.hint }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <el-tabs v-model="tab">
      <el-tab-pane :label="c.users" name="users">
        <el-form inline @submit.prevent="loadMember"><el-form-item :label="c.member"><el-select v-model="owner" filterable :placeholder="c.choose" :disabled="busy" @change="loadMember"><el-option v-for="member in members" :key="member.id" :label="member.display_name" :value="member.id" /></el-select></el-form-item><el-button :disabled="busy || !owner" @click="loadMember">{{ c.refresh }}</el-button><el-button v-if="cursor" :disabled="busy" @click="moreMembers">{{ c.more }}</el-button></el-form>
        <template v-if="memberState">
          <p v-if="!memberState.configured" class="hint">{{ c.unconfigured }}</p>
          <div class="metrics"><div>{{ c.spent }}<strong>{{ money(memberState.spent) }}</strong></div><div>{{ c.held }}<strong>{{ money(memberState.held) }}</strong></div><div>{{ c.today }}<strong>{{ money(memberState.daily_spent) }}</strong></div></div>
          <el-form label-position="top" class="budget-form" :key="memberState.version || 0" :disabled="busy||!canWrite" @submit.prevent="saveMember">
            <el-form-item :label="c.total"><el-input v-model="memberForm.total" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.daily"><el-input v-model="memberForm.daily" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.concurrency"><el-input-number v-model="memberForm.concurrency" :min="1" :max="32" /></el-form-item>
            <el-form-item :label="c.rpm"><el-input-number v-model="memberForm.callsPerMinute" :min="1" :max="600" /></el-form-item>
            <el-form-item :label="c.output"><el-input-number v-model="memberForm.maxOutputTokens" :min="1" :max="262144" /></el-form-item>
            <el-form-item :label="c.expires"><el-date-picker v-model="memberForm.expiresAt" type="datetime" :placeholder="c.choose" /></el-form-item>
            <el-form-item :label="c.enabled"><el-switch v-model="memberForm.enabled" /></el-form-item>
            <el-form-item><el-button native-type="submit" :loading="busy">{{ c.save }}</el-button></el-form-item>
          </el-form>
          <p class="hint">{{ c.lifetime }}</p>
          <p class="hint">{{ c.topupHint }}</p>
          <el-form inline :disabled="busy||!canWrite" @submit.prevent="topupMember"><el-form-item :label="c.topupAmount"><el-input v-model="topupAmount" inputmode="decimal" maxlength="14" :disabled="!!pendingTopup" /></el-form-item><el-button native-type="submit" :disabled="busy || !memberState.configured">{{ pendingTopup ? c.retryTopup : c.topup }}</el-button><el-button v-if="pendingTopup" :disabled="busy" @click="loadMember">{{ c.checkTopup }}</el-button></el-form>
        </template>
      </el-tab-pane>
      <el-tab-pane v-if="canManageGlobal" :label="c.global" name="global">
        <p class="hint">{{ c.operator }}</p>
        <el-button :loading="busy" @click="loadGlobal">{{ c.refresh }}</el-button>
        <template v-if="globalState">
          <div class="metrics"><div>{{ c.spent }}<strong>{{ money(globalState.spent) }}</strong></div><div>{{ c.held }}<strong>{{ money(globalState.held) }}</strong></div><div>{{ c.today }}<strong>{{ money(globalState.daily_spent) }}</strong></div></div>
          <el-form label-position="top" class="budget-form" :key="globalState.version" :disabled="busy||!canWrite" @submit.prevent="saveGlobal">
            <el-form-item :label="c.total"><el-input v-model="globalForm.total" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.daily"><el-input v-model="globalForm.daily" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.paused"><el-switch v-model="globalForm.paused" /></el-form-item><el-form-item><el-button native-type="submit" :loading="busy">{{ c.save }}</el-button></el-form-item>
          </el-form><p class="hint">{{ c.lifetime }}</p>
        </template>
      </el-tab-pane>
      <el-tab-pane v-if="canManageGlobal" :label="c.promotion" name="promotion">
        <p class="hint">{{ c.promoHint }}</p><el-button :disabled="busy" @click="loadPromotion">{{ c.refresh }}</el-button>
        <template v-if="promotionState">
          <div class="metrics"><div>{{ c.issued }}<strong>{{ money(promotionState.issued) }}</strong></div><div>{{ c.issuedToday }}<strong>{{ money(promotionState.issued_today) }}</strong></div></div>
          <el-form label-position="top" class="budget-form" :key="promotionState.version" :disabled="busy||!canWrite" @submit.prevent="savePromotion">
            <el-form-item :label="c.tenant"><el-input v-model="promotionForm.tenantId" maxlength="64" /></el-form-item>
            <el-form-item :label="c.mode"><el-select v-model="promotionForm.mode"><el-option value="DISABLED" :label="c.disabled" /><el-option value="WELCOME" :label="c.welcome" /><el-option value="DAILY" :label="c.dailyClaim" /></el-select></el-form-item>
            <el-form-item v-for="field in promoMoneyFields" :key="field.key" :label="c[field.label]"><el-input v-model="promotionForm[field.key]" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.validity"><el-input-number v-model="promotionForm.validityDays" :min="1" :max="3650" /></el-form-item>
            <el-form-item :label="c.concurrency"><el-input-number v-model="promotionForm.concurrency" :min="1" :max="32" /></el-form-item>
            <el-form-item :label="c.rpm"><el-input-number v-model="promotionForm.callsPerMinute" :min="1" :max="600" /></el-form-item>
            <el-form-item :label="c.output"><el-input-number v-model="promotionForm.maxOutputTokens" :min="1" :max="262144" /></el-form-item>
            <el-form-item><el-button native-type="submit" :loading="busy">{{ c.save }}</el-button></el-form-item>
          </el-form><p class="hint">{{ c.priceHint }}</p>
        </template>
      </el-tab-pane>
      <el-tab-pane :label="c.prices" name="prices">
        <p class="hint">{{ c.priceHint }}</p>
        <p v-if="!canManagePricing" class="hint">{{ c.pricingOperator }}</p>
        <el-form inline @submit.prevent="loadPrice"><el-form-item :label="c.model"><el-select v-model="deployment" filterable :placeholder="c.choose" :disabled="busy" @change="loadPrice"><el-option v-for="item in deployments" :key="item.id" :label="(item.tenant_id ? item.tenant_id + ' · ' : '') + item.name + ' · ' + item.model" :value="item.id" /></el-select></el-form-item><el-button :disabled="busy || !deployment" @click="loadPrice">{{ c.refresh }}</el-button><el-button v-if="deploymentCursor" :disabled="busy" @click="moreDeployments">{{ c.moreDeployments }}</el-button></el-form>
        <template v-if="priceState"><p v-if="priceState.configured === false" class="hint">{{ c.noPrice }}</p>
          <el-form label-position="top" class="budget-form" :key="priceState.version || 0" :disabled="busy || !canWrite || !canManagePricing" @submit.prevent="savePrice">
            <el-form-item :label="c.tariff"><el-select v-model="priceForm.tariffProfile" @change="selectTariff"><el-option :label="c.fixedTariff" value="FIXED" /><el-option :label="c.flashTariff" value="DEEPSEEK_FLASH_CNY_20260910" /></el-select></el-form-item>
            <el-form-item :label="c.cached"><el-input v-model="priceForm.cached" :disabled="priceForm.tariffProfile !== 'FIXED'" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.input"><el-input :disabled="priceForm.tariffProfile !== 'FIXED'" v-model="priceForm.input" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.outputPrice"><el-input :disabled="priceForm.tariffProfile !== 'FIXED'" v-model="priceForm.output" inputmode="decimal" maxlength="14" /></el-form-item>
            <el-form-item :label="c.ceiling"><el-input-number v-model="priceForm.inputCeilingTokens" :min="128" :max="2000000" /></el-form-item>
            <el-form-item v-if="canManagePricing"><el-button native-type="submit" :loading="busy">{{ c.save }}</el-button></el-form-item>
          </el-form>
        </template>
      </el-tab-pane>
    </el-tabs>
  </el-card></div>
</template>
<script setup lang="ts">
import {checkPermi} from '@/utils/permission';
const canWrite=computed(()=>checkPermi(['funnyworker:models:write']));
import { computed, reactive, ref, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from 'vue-i18n';
import { businessRequest } from '@/utils/funnyworker-api';
import { BusinessError, businessErrorMessage, businessAccessLost } from '@/utils/business-feedback';
import { readPendingTopup, savePendingTopup, clearPendingTopup, type PendingTopup } from './pending-topup';
import { modelBudgetCopy } from './model-budget-copy';
type State = Record<string, any>;
const { locale } = useI18n();
const c = computed(() => modelBudgetCopy[locale.value.startsWith('en') ? 'en' : 'zh']);
const tab = ref('users'), busy = ref(false), error = ref(''), notice = ref('');
const members = ref<State[]>([]), deployments = ref<State[]>([]), cursor = ref(''), owner = ref(''), deployment = ref('');
const canManageGlobal = ref(false), canManagePricing = ref(false), deploymentCursor = ref('');
const memberState = ref<State>(), globalState = ref<State>(), priceState = ref<State>();
const memberForm = reactive({ total: '0', daily: '0', concurrency: 32, callsPerMinute: 600, maxOutputTokens: 262144, expiresAt: undefined as Date | undefined, enabled: false });
const globalForm = reactive({ total: '0', daily: '0', paused: true });
const promotionState = ref<State>();
const promotionForm = reactive({ tenantId: '', mode: 'WELCOME', grant: '1', cap: '5', total: '0', daily: '0', userDaily: '5', validityDays: 365, concurrency: 32, callsPerMinute: 600, maxOutputTokens: 262144 });
const promoMoneyFields = [{ key: 'grant', label: 'grant' }, { key: 'cap', label: 'cap' }, { key: 'total', label: 'issuanceTotal' }, { key: 'daily', label: 'issuanceDaily' }, { key: 'userDaily', label: 'daily' }] as const;
const topupAmount = ref('1'), pendingTopup = ref<PendingTopup>();
const priceForm = reactive({ input: '0', cached: '0', output: '0', tariffProfile: 'FIXED', inputCeilingTokens: 128 });
let originalMember=JSON.stringify(memberForm),originalGlobal=JSON.stringify(globalForm),originalPrice=JSON.stringify(priceForm),originalPromotion=JSON.stringify(promotionForm);
defineExpose({hasUnsavedDraft:()=>canWrite.value&&(busy.value||!!pendingTopup.value||JSON.stringify(memberForm)!==originalMember||JSON.stringify(globalForm)!==originalGlobal||JSON.stringify(priceForm)!==originalPrice||JSON.stringify(promotionForm)!==originalPromotion)});
class BudgetInputError extends Error {}
let alive = true;
const path = '/model-middleware/budgets';
function money(value: number | undefined) { return '¥' + ((value || 0) / 1000000).toFixed(6); }
function micros(value: string) {
  if (!/^\d+(?:\.\d{1,6})?$/.test(value)) throw new BudgetInputError(c.value.invalid);
  const [whole, fraction = ''] = value.split('.');
  const amount = BigInt(whole) * 1000000n + BigInt(fraction.padEnd(6, '0'));
  if (amount > 1000000000000n) throw new BudgetInputError(c.value.invalid);
  return Number(amount);
}
async function run(action: () => Promise<void>, scope: 'module' | 'global' | 'pricing-write' = 'module') {
  if (busy.value) return; busy.value = true; error.value = ''; notice.value = '';
  try { await action(); }
  catch (e) { if (alive) { error.value = e instanceof BusinessError ? e.code === 'BUDGET_VERSION_CONFLICT' ? c.value.conflict : businessErrorMessage(e) : e instanceof BudgetInputError ? c.value.invalid : c.value.failed;
    if (e instanceof BusinessError && e.status === 403 && scope === 'global') { canManageGlobal.value = false; globalState.value = undefined; promotionState.value = undefined; tab.value = 'users'; error.value = c.value.operator; }
    else if (e instanceof BusinessError && e.status === 403 && scope === 'pricing-write') { canManagePricing.value = false; error.value = c.value.pricingOperator; }
    else if (businessAccessLost(e)) { members.value = []; deployments.value = []; memberState.value = undefined; globalState.value = undefined; priceState.value = undefined; promotionState.value = undefined; canManageGlobal.value = false; canManagePricing.value = false; }
  } }
  finally { if (alive) busy.value = false; }
}
function applyMember(value: State) {
  if (!alive) return; memberState.value = value;
  Object.assign(memberForm, { total: String((value.total_limit || 0) / 1000000), daily: String((value.daily_limit || 0) / 1000000), concurrency: value.concurrency_limit || 32, callsPerMinute: value.calls_per_minute || 600, maxOutputTokens: value.max_output_tokens || 262144, expiresAt: new Date(value.expires_at || Date.now() + 365 * 86400000), enabled: value.enabled || false });originalMember=JSON.stringify(memberForm);
}
function applyGlobal(value: State) { if (!alive) return; globalState.value = value; Object.assign(globalForm, { total: String(value.total_limit / 1000000), daily: String(value.daily_limit / 1000000), paused: value.paused });originalGlobal=JSON.stringify(globalForm); }
function selectTariff() { if (priceForm.tariffProfile !== 'FIXED') Object.assign(priceForm, { input: '2', cached: '0.04', output: '8' }); }
function applyPrice(value: State) { if (!alive) return; priceState.value = value; Object.assign(priceForm, { input: String((value.input_rate || 0) / 1000000), cached: String((value.cached_rate ?? value.input_rate ?? 0) / 1000000), tariffProfile: value.tariff_profile || 'FIXED', output: String((value.output_rate || 0) / 1000000), inputCeilingTokens: value.input_ceiling || deployments.value.find(item => item.id === deployment.value)?.context_tokens || 128 });originalPrice=JSON.stringify(priceForm); }
async function loadMember() {
  if (!owner.value || busy.value) return; memberState.value = undefined; pendingTopup.value = undefined; let value: State | undefined;
  await run(async () => {
    const pending = readPendingTopup(owner.value); pendingTopup.value = pending;
    if (pending) {
      topupAmount.value = String(pending.amountMicros / 1000000);
      const receipt = await businessRequest(path + '/users/' + encodeURIComponent(owner.value) + '/topups/' + encodeURIComponent(pending.requestId));
      value = receipt.budget;
      if (receipt.state === 'POSTED') {
        if (receipt.amountMicros !== pending.amountMicros) throw new BudgetInputError(c.value.invalid);
        clearPendingTopup(pending); pendingTopup.value = undefined; notice.value = c.value.topupConfirmed;
      } else if (receipt.state !== 'ABSENT') throw new BudgetInputError(c.value.invalid);
    } else value = await businessRequest(path + '/users/' + encodeURIComponent(owner.value));
  }); if (value) applyMember(value);
}
async function loadPrice() { if (!deployment.value || busy.value) return; priceState.value = undefined; let value: State | undefined; await run(async () => { value = await businessRequest(path + '/prices/' + encodeURIComponent(deployment.value)); }); if (value) applyPrice(value); }
async function loadGlobal() { if (busy.value || !canManageGlobal.value) return; let value: State | undefined; await run(async () => { try { value = await businessRequest(path + '/global'); } catch (e) { globalState.value = undefined; throw e; } }, 'global'); if (value) applyGlobal(value); }
function moreMembers() { return run(async () => { const page = await businessRequest(path + '/users?after=' + encodeURIComponent(cursor.value)); if (alive) { members.value.push(...page.items); cursor.value = page.nextCursor || ''; } }); }
function moreDeployments() { return run(async () => { const page = await businessRequest(path + '/deployments?after=' + encodeURIComponent(deploymentCursor.value)); if (alive) { deployments.value.push(...page.items); deploymentCursor.value = page.nextCursor || ''; } }); }
async function saveMember() { if (!canWrite.value || !owner.value || !memberState.value || busy.value) return; let value: State | undefined; await run(async () => {
  if (!memberForm.expiresAt || !Number.isFinite(memberForm.expiresAt.getTime()) || memberForm.expiresAt.getTime() <= Date.now()) throw new BudgetInputError(c.value.invalid);
  value = await businessRequest(path + '/users/' + encodeURIComponent(owner.value), 'PUT', { totalMicros: micros(memberForm.total), dailyMicros: micros(memberForm.daily), concurrency: memberForm.concurrency, callsPerMinute: memberForm.callsPerMinute, maxOutputTokens: memberForm.maxOutputTokens, expiresAt: memberForm.expiresAt.getTime(), enabled: memberForm.enabled, expectedVersion: memberState.value?.version || 0 });
}); if (value) { applyMember(value); if (alive) notice.value = c.value.saved; } }
async function saveGlobal() { if (!canWrite.value || !globalState.value || busy.value || !canManageGlobal.value) return; let value: State | undefined; await run(async () => { value = await businessRequest(path + '/global', 'PUT', { totalMicros: micros(globalForm.total), dailyMicros: micros(globalForm.daily), paused: globalForm.paused, expectedVersion: globalState.value?.version }); }, 'global'); if (value) { applyGlobal(value); if (alive) notice.value = c.value.saved; } }
async function savePrice() { if (!canWrite.value || !deployment.value || !priceState.value || busy.value || !canManagePricing.value) return; let value: State | undefined; await run(async () => { if (micros(priceForm.input) <= 0 || micros(priceForm.output) <= 0) throw new BudgetInputError(c.value.invalid); value = await businessRequest(path + '/prices/' + encodeURIComponent(deployment.value), 'PUT', { inputMicrosPerMillion: micros(priceForm.input), cachedMicrosPerMillion: micros(priceForm.cached), tariffProfile: priceForm.tariffProfile, outputMicrosPerMillion: micros(priceForm.output), inputCeilingTokens: priceForm.inputCeilingTokens, expectedVersion: priceState.value?.version || 0 }); }, 'pricing-write'); if (value) { applyPrice(value); if (alive) notice.value = c.value.saved; } }
function applyPromotion(value: State) { if (!alive) return; promotionState.value = value; Object.assign(promotionForm, { tenantId: value.tenant_id, mode: value.mode, grant: String(value.grant_micros / 1000000), cap: String(value.balance_cap / 1000000), total: String(value.total_limit / 1000000), daily: String(value.daily_limit / 1000000), userDaily: String(value.user_daily_limit / 1000000), validityDays: value.validity_days, concurrency: value.concurrency_limit, callsPerMinute: value.calls_per_minute, maxOutputTokens: value.max_output_tokens });originalPromotion=JSON.stringify(promotionForm); }
async function loadPromotion() { if (busy.value || !canManageGlobal.value) return; let value: State | undefined; await run(async () => { value = await businessRequest(path + '/promotion'); }, 'global'); if (value) applyPromotion(value); }
async function savePromotion() { if (!canWrite.value || busy.value || !promotionState.value || !canManageGlobal.value) return; let value: State | undefined; await run(async () => { if (!promotionForm.tenantId.trim() || micros(promotionForm.grant) <= 0 || micros(promotionForm.grant) > micros(promotionForm.cap) || micros(promotionForm.userDaily) <= 0) throw new BudgetInputError(c.value.invalid); value = await businessRequest(path + '/promotion', 'PUT', { tenantId: promotionForm.tenantId.trim(), mode: promotionForm.mode, grantMicros: micros(promotionForm.grant), balanceCapMicros: micros(promotionForm.cap), totalMicros: micros(promotionForm.total), dailyMicros: micros(promotionForm.daily), userDailyMicros: micros(promotionForm.userDaily), validityDays: promotionForm.validityDays, concurrency: promotionForm.concurrency, callsPerMinute: promotionForm.callsPerMinute, maxOutputTokens: promotionForm.maxOutputTokens, expectedVersion: promotionState.value?.version }); }, 'global'); if (value) { applyPromotion(value); notice.value = c.value.saved; } }
async function topupMember() { if(!canWrite.value)return;
  if (busy.value || !owner.value || !memberState.value?.configured) return; let value: State | undefined;
  await run(async () => {
    let pending = readPendingTopup(owner.value);
    if (!pending) {
      const amount = micros(topupAmount.value); if (amount <= 0) throw new BudgetInputError(c.value.invalid);
      pending = { owner: owner.value, amountMicros: amount, requestId: crypto.randomUUID() };
      savePendingTopup(pending); // A storage failure must prevent transmission.
    }
    pendingTopup.value = pending; topupAmount.value = String(pending.amountMicros / 1000000);
    value = await businessRequest(path + '/users/' + encodeURIComponent(pending.owner) + '/topups', 'POST', { amountMicros: pending.amountMicros, requestId: pending.requestId, expectedVersion: memberState.value?.version });
    clearPendingTopup(pending); pendingTopup.value = undefined;
  }); if (value) { applyMember(value); notice.value = c.value.saved; }
}
onMounted(() => run(async () => {
  const [users, models] = await Promise.all([businessRequest(path + '/users'), businessRequest(path + '/deployments')]);
  if (alive) { members.value = users.items; cursor.value = users.nextCursor || ''; deployments.value = models.items; deploymentCursor.value = models.nextCursor || ''; canManageGlobal.value = users.canManageGlobal === true; canManagePricing.value = users.canManagePricing === true; }
}));
onBeforeUnmount(() => { alive = false; });
</script>
<style scoped>
.app-container{--el-color-primary:var(--fw-accent)}.hint{color:var(--el-text-color-secondary);line-height:1.6}.budget-form{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:0 20px;margin-top:20px}.el-select{min-width:240px}.metrics{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}.metrics>div{padding:12px;border:1px solid var(--el-border-color);border-radius:8px;display:flex;flex-direction:column;gap:6px}.metrics strong{font-size:20px}
</style>
