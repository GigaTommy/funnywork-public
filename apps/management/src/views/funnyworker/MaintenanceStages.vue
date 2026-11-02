<template>
  <el-table :data="stages" row-key="id">
    <el-table-column type="expand">
      <template #default="{ row }">
        <div class="stage-evidence">
          <p v-if="reason(row.result_json)">{{ reason(row.result_json) }}</p>
          <details>
            <summary>{{ zh ? '判定详情' : 'Decision details' }}</summary>
            <pre>{{ maintenancePretty(row.result_json) }}</pre>
          </details>
          <el-button text :loading="loading === row.id" @click="loadInput(row.id)">{{ zh ? '查看分析输入' : 'View analysis input' }}</el-button>
          <el-alert v-if="errors[row.id]" :title="zh ? '读取失败，请重试。' : 'Unable to load. Retry.'" type="error" :closable="false" />
          <details v-if="inputs[row.id]" open>
            <summary>{{ zh ? '分析输入' : 'Analysis input' }}</summary>
            <pre>{{ inputs[row.id] }}</pre>
          </details>
        </div>
      </template>
    </el-table-column>
    <el-table-column :label="zh ? '职责' : 'Role'" min-width="125"
      ><template #default="{ row }">{{ role(row.stage) }}</template></el-table-column
    >
    <el-table-column :label="zh ? '阶段' : 'Stage'" min-width="125"
      ><template #default="{ row }">{{ maintenanceStageName(row.stage, language) }}</template></el-table-column
    >
    <el-table-column :label="zh ? '状态' : 'Status'"
      ><template #default="{ row }">{{ maintenanceStageState(row.state, language) }}</template></el-table-column
    >
    <el-table-column :label="zh ? '判定' : 'Decision'"
      ><template #default="{ row }">{{ maintenanceStageDecision(row.result_json, language) }}</template></el-table-column
    >
    <el-table-column :label="zh ? '阻塞原因' : 'Blocker'"
      ><template #default="{ row }">{{ maintenanceStageBlocker(row.error_code, language) }}</template></el-table-column
    >
  </el-table>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { businessRequest } from '@/utils/funnyworker-api';
import {
  maintenanceJson,
  maintenancePretty,
  maintenanceStageName,
  maintenanceStageDecision,
  maintenanceStageState,
  maintenanceStageBlocker
} from './maintenance-copy';
const props = defineProps<{
  stages: { id: string; stage: string; state: string; result_json: string | null; error_code: string | null }[];
  language: string;
}>();
const zh = computed(() => props.language.startsWith('zh'));
const inputs = ref<Record<string, string>>({}),
  errors = ref<Record<string, boolean>>({}),
  loading = ref('');
watch(
  () => props.stages,
  () => {
    inputs.value = {};
    errors.value = {};
  }
);
function reason(value: unknown) {
  const parsed = maintenanceJson(value);
  return parsed && typeof parsed === 'object' && 'reason' in parsed && typeof parsed.reason === 'string' ? parsed.reason : '';
}
function role(stage: string) {
  if (['PRODUCT', 'PRODUCT_CONSTRAINT', 'SECURITY', 'PRODUCT_ACCEPTANCE', 'DUPLICATE_REVIEW'].includes(stage))
    return zh.value ? '需求／产品' : 'Requirements / product';
  if (['BASELINE', 'TECHNICAL', 'DISPATCH', 'CODE_REVIEW', 'VERIFICATION_PLAN', 'REPAIR_EVIDENCE', 'REPAIR_REVIEW'].includes(stage))
    return zh.value ? '技术管理' : 'Technical management';
  return zh.value ? '后端协调' : 'Backend coordination';
}
async function loadInput(id: string) {
  if (loading.value || inputs.value[id]) return;
  loading.value = id;
  errors.value[id] = false;
  try {
    const stage = await businessRequest('/maintenance/automation/stages/' + encodeURIComponent(id));
    if (props.stages.some((item) => item.id === id)) inputs.value[id] = maintenancePretty(stage.input_json);
  } catch {
    errors.value[id] = true;
  } finally {
    loading.value = '';
  }
}
</script>
<style scoped>
.stage-evidence {
  padding: 12px 20px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 320px;
  overflow: auto;
}
p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
summary {
  cursor: pointer;
  padding: 8px 0;
}
</style>
