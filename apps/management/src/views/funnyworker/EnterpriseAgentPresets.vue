<template>
  <div class="app-container">
    <el-card>
      <template #header
        ><strong>{{ c.title }}</strong></template
      >
      <p class="hint">{{ c.notice }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button :loading="busy" @click="refresh">{{ c.refresh }}</el-button>
      <el-table :data="items" :empty-text="c.empty">
        <el-table-column prop="name" :label="c.name" /><el-table-column prop="zone" :label="c.zone" />
        <el-table-column prop="version" :label="c.version" />
        <el-table-column :label="c.enabled"
          ><template #default="{ row }">{{ row.enabled ? c.yes : c.no }}</template></el-table-column
        >
        <el-table-column :label="c.actions"
          ><template #default="{ row }"
            ><el-button :disabled="busy||!canWrite" @click="edit(row)">{{ c.edit }}</el-button></template
          ></el-table-column
        >
      </el-table>
      <el-form v-if="canWrite" label-position="top" class="config-form" @submit.prevent="publish">
        <el-form-item :label="c.zone"
          ><el-select v-model="form.zone" :disabled="!!editing || busy" :placeholder="c.choose"
            ><el-option v-for="zone in zones" :key="zone" :label="zone" :value="zone" /></el-select
        ></el-form-item>
        <el-form-item :label="c.name"><el-input v-model="form.name" maxlength="120" :disabled="busy" /></el-form-item>
        <el-form-item :label="c.description"><el-input v-model="form.description" maxlength="1000" :disabled="busy" /></el-form-item>
        <el-form-item :label="c.enabled"><el-switch v-model="form.enabled" :disabled="busy" /></el-form-item>
        <el-form-item :label="c.persona" class="wide"
          ><el-input v-model="form.persona" type="textarea" :rows="6" maxlength="20000" show-word-limit :disabled="busy"
        /></el-form-item>
        <el-form-item class="wide"
          ><el-button native-type="submit" :loading="busy" :disabled="!valid">{{ editing ? c.publishRevision : c.publish }}</el-button
          ><el-button :disabled="busy" @click="reset">{{ c.reset }}</el-button
          ><span class="hint">{{ c.base }}</span></el-form-item
        >
      </el-form>
      <h3>{{ c.policy }}</h3>
      <p class="hint">{{ c.policyHint }}</p>
      <el-form v-if="canWrite" label-position="top" class="config-form" @submit.prevent="savePolicy">
        <el-form-item :label="c.zone"
          ><el-select v-model="policyZone" :placeholder="c.choose" :disabled="busy"
            ><el-option v-for="zone in zones" :key="zone" :value="zone" :label="zone" /></el-select
        ></el-form-item>
        <el-form-item :label="c.default"
          ><el-select v-model="policy.defaultPresetId" :disabled="busy" :placeholder="c.choose"
            ><el-option :label="c.standard" value="" /><el-option
              v-for="row in items.filter((row) => row.zone === policyZone && row.enabled)"
              :key="row.id"
              :label="row.name"
              :value="row.id" /></el-select
        ></el-form-item>
        <el-form-item :label="c.personal"><el-switch v-model="policy.allowPersonalPresets" :disabled="busy" /></el-form-item>
        <el-form-item
          ><el-button native-type="submit" :loading="busy" :disabled="!policyZone">{{ c.savePolicy }}</el-button></el-form-item
        >
      </el-form>
      <p v-if="success" role="status">{{ success }}</p>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import {checkPermi} from '@/utils/permission';
const canWrite=computed(()=>checkPermi(['funnyworker:models:write']));
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { businessRequest } from '@/utils/funnyworker-api';
import { businessAccessLost } from '@/utils/business-feedback';
import { modelOperationError } from './model-operations-copy';
import { enterpriseAgentCopy } from './enterprise-agent-copy';
interface Preset {
  id: string;
  zone: string;
  version: number;
  name: string;
  description: string;
  persona: string;
  enabled: boolean;
}
interface Policy {
  zone: string;
  version: number;
  allow_personal: boolean;
  default_preset: string;
}
const { locale } = useI18n();
const c = computed(() => enterpriseAgentCopy(locale.value));
const busy = ref(false),
  error = ref(''),
  success = ref(''),
  items = ref<Preset[]>([]),
  zones = ref<string[]>([]),
  policies = ref<Policy[]>([]),
  editing = ref('');
const form = reactive({ zone: '', name: '', description: '', persona: '', enabled: true, expectedVersion: 0 });
const policyZone = ref(''),
  policy = reactive({ allowPersonalPresets: false, defaultPresetId: '', expectedVersion: 0 });
let originalPreset=JSON.stringify(form),originalPolicy=JSON.stringify(policy);
defineExpose({hasUnsavedDraft:()=>canWrite.value&&(busy.value||JSON.stringify(form)!==originalPreset||JSON.stringify(policy)!==originalPolicy)});
let alive = true;
const valid = computed(
  () => !!form.zone && !!form.name.trim() && !!form.persona.trim() && !form.persona.includes('{{') && !form.persona.includes('}}')
);
function reset() {
  editing.value = '';
  Object.assign(form, { zone: zones.value[0] || '', name: '', description: '', persona: '', enabled: true, expectedVersion: 0 });
  originalPreset=JSON.stringify(form);
}
function selectPolicy() {
  const row = policies.value.find((row) => row.zone === policyZone.value);
  Object.assign(policy, {
    allowPersonalPresets: row?.allow_personal || false,
    defaultPresetId: row?.default_preset || '',
    expectedVersion: row?.version || 0
  });
  originalPolicy=JSON.stringify(policy);
}
async function data() {
  const result = await businessRequest('/model-middleware/agent-presets');
  if (!alive) return;
  items.value = result.items;
  zones.value = result.zones;
  policies.value = result.policies;
  if (!zones.value.includes(policyZone.value)) policyZone.value = zones.value[0] || '';
  selectPolicy();
  if (!form.zone) {form.zone = zones.value[0] || '';originalPreset=JSON.stringify(form);}
}
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await action();
  } catch (e) {
    if (alive) {
      error.value = modelOperationError(e, locale.value.startsWith('en'));
      if (businessAccessLost(e)) {
        items.value = [];
        policies.value = [];
        zones.value = [];
        policyZone.value = '';
        selectPolicy();
        reset();
      }
    }
  } finally {
    if (alive) busy.value = false;
  }
}
function refresh() {
  return run(data);
}
function edit(row: Preset) {
  editing.value = row.id;
  Object.assign(form, {
    zone: row.zone,
    name: row.name,
    description: row.description,
    persona: row.persona,
    enabled: row.enabled,
    expectedVersion: row.version
  });
  originalPreset=JSON.stringify(form);
}
function publish() {
  if (!valid.value) return;
  return run(async () => {
    await businessRequest(
      '/model-middleware/agent-presets' + (editing.value ? '/' + encodeURIComponent(editing.value) : ''),
      editing.value ? 'PUT' : 'POST',
      { ...form }
    );
    await data();
    if (alive) {
      reset();
      success.value = c.value.published;
    }
  });
}
function savePolicy() {
  return run(async () => {
    await businessRequest('/model-middleware/agent-preset-policy', 'PUT', { zone: policyZone.value, ...policy });
    await data();
    if (alive) success.value = c.value.published;
  });
}
watch(policyZone, selectPolicy);
onMounted(() => {
  void refresh();
});
onBeforeUnmount(() => {
  alive = false;
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
.wide {
  grid-column: 1 / -1;
}
.el-select {
  width: 100%;
}
.el-button {
  margin: 8px 8px 8px 0;
}
</style>
