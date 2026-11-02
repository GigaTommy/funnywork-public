<template>
  <div class="app-container">
    <h2>模型中心</h2>
    <p class="hint">按模型服务、预算、Agent 预设和客户端发布分别管理。保存配置与客户端生效状态分别核对。</p>
    <el-tabs v-model="tab">
      <el-tab-pane label="模型与容量" name="services" lazy><ModelMiddleware ref="services" /></el-tab-pane>
      <el-tab-pane label="预算与计价" name="budgets" lazy><ModelBudgets ref="budgets" /></el-tab-pane>
      <el-tab-pane label="Agent 预设" name="presets" lazy><EnterpriseAgentPresets ref="presets" /></el-tab-pane>
      <el-tab-pane label="客户端发布" name="releases" lazy><DesktopModelReleases ref="releases" /></el-tab-pane>
      <el-tab-pane label="节点模型配置" name="nodes" lazy><BusinessModule module="models" /></el-tab-pane>
    </el-tabs>
  </div>
</template>
<script setup lang="ts">
import EnterpriseAgentPresets from './EnterpriseAgentPresets.vue';
import ModelMiddleware from './ModelMiddleware.vue';
import ModelBudgets from './ModelBudgets.vue';
import BusinessModule from './BusinessModule.vue';
import DesktopModelReleases from './DesktopModelReleases.vue';
import {ref} from 'vue';
import {onBeforeRouteLeave} from 'vue-router';
const tab=ref('services');
type DraftPanel={hasUnsavedDraft:()=>boolean};
const services=ref<DraftPanel>(),budgets=ref<DraftPanel>(),presets=ref<DraftPanel>(),releases=ref<DraftPanel>();
onBeforeRouteLeave(async()=>{if(![services,budgets,presets,releases].some(panel=>panel.value?.hasUnsavedDraft()))return true;try{await ElMessageBox.confirm('离开后未保存的模型配置将被放弃，是否继续？','放弃草稿',{confirmButtonText:'放弃并离开',cancelButtonText:'继续编辑'});return true;}catch{return false;}});
</script>
<style scoped>.hint{color:var(--el-text-color-secondary);line-height:1.7}</style>
