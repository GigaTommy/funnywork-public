<template>
 <section>
  <h3>验收与交付流程</h3>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button :disabled="busy" @click="load">刷新流程状态</el-button>
  <template v-if="value">
   <el-descriptions :column="1" border>
    <el-descriptions-item label="流程">{{kindLabel(value.kind)}}</el-descriptions-item>
    <el-descriptions-item v-if="value.executionState" label="执行记录">{{stateLabel(value.executionState)}}</el-descriptions-item>
    <el-descriptions-item v-if="value.state" label="流程记录">{{stateLabel(value.state)}}</el-descriptions-item>
    <el-descriptions-item v-if="value.validation" label="业务验收">{{stateLabel(value.validation)}}</el-descriptions-item>
    <el-descriptions-item v-if="value.delivery" label="交付">{{stateLabel(value.delivery)}}</el-descriptions-item>
   </el-descriptions>
   <p class="hint">{{value.nextStep}}</p>
   <p v-if="value.requestedTaskIsCurrent===false" class="hint">当前查看的是此流程的一次历史、验证或交付执行；流程记录对应仍在授权范围内的当前主任务。</p>
   <el-button v-if="mayOpen" :disabled="busy" @click="$emit('workbench',value.workbench)">进入{{value.workbench.kind==='MAINTENANCE'?'代码维护':'年报'}}工作台</el-button>
   <el-table v-if="value.stages?.length" :data="value.stages"><el-table-column label="后端阶段" min-width="150"><template #default="{row}">{{stageLabel(row.stage)}}</template></el-table-column><el-table-column label="候选关联"><template #default="{row}">{{row.currentCandidate?'当前候选':'历史或其他阶段'}}</template></el-table-column><el-table-column label="记录状态"><template #default="{row}">{{stateLabel(row.state)}}</template></el-table-column><el-table-column prop="attempts" label="执行次数" /><el-table-column prop="error_code" label="原因码" /></el-table>
   <el-table v-if="value.jobs?.length" :data="value.jobs"><el-table-column label="关联执行"><template #default="{row}">{{row.mode==='VERIFY'?'独立验证':'受控交付'}}</template></el-table-column><el-table-column prop="platform" label="平台" /><el-table-column label="状态"><template #default="{row}">{{stateLabel(row.state)}}</template></el-table-column></el-table>
   <p v-if="value.hasMoreHistory" class="hint">此处仅显示最近 100 条阶段或关联执行记录；完整证据仍在原工作台。</p>
   <p class="hint">此区只显示流程元数据，未开放需求正文、候选报告或远端凭证。处理入口仍按原工作流的独立权限和安全规则执行。</p>
  </template>
 </section>
</template>
<script setup lang="ts">
import {ref,watch,computed,onBeforeUnmount} from 'vue';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,BusinessError} from '@/utils/business-feedback';
const props=defineProps<{task:string;canMaintain:boolean;canAnnual:boolean}>();
const emit=defineEmits<{workbench:[value:{kind:string;id:string}];restricted:[]}>();
const value=ref<Record<string,any>>(),busy=ref(false),error=ref('');let generation=0,alive=true;
const mayOpen=computed(()=>value.value?.workbench?.canOpen&&(value.value.workbench.kind==='MAINTENANCE'?props.canMaintain:props.canAnnual));
const kindLabel=(kind:string)=>({ANNUAL_REPORT:'年报编制与后端核验',MAINTENANCE:'代码维护与受控交付',GENERAL:'通用执行'} as Record<string,string>)[kind]||kind;
const stateLabel=(state:string)=>({RUNNING:'进行中',SUCCEEDED:'执行完成',OUTPUT_READY:'产出待确认',FAILED:'执行失败',CANCELLED:'已取消',RECONCILING:'待核对',WAITING:'等待编制',COMPILING:'候选编制中',REJECTED:'未通过核验',DELIVERED:'正式文件已交付',SERVER_VERIFIED:'后端核验通过',NOT_CONFIRMED:'尚未确认',NOT_COVERED:'此用途尚未覆盖',ACCEPTED:'验收已通过',IN_PROGRESS:'开发进行中',AWAITING_ACCEPTANCE:'产出待验收',BLOCKED:'已阻止，需核对',PASSED:'本阶段通过',READY:'等待执行',RETRY:'等待按原流程重试',QUEUED:'已排队',VERIFIED:'平台验证通过',DELIVERY_FLOW_CONFIRMED:'开发交付流程已确认'} as Record<string,string>)[state]||state;
const stageLabel=(stage:string)=>({CODE_REVIEW:'独立代码审查',PRODUCT_ACCEPTANCE:'产品验收',DELIVERY:'受控交付',VERIFICATION_PLAN:'平台验证计划',COORDINATION:'任务协调',DISPATCH:'派发',REPAIR_REVIEW:'修复审查'} as Record<string,string>)[stage]||stage;
async function load(){const run=++generation;value.value=undefined;busy.value=true;error.value='';try{const response=await request('/management-tasks/'+encodeURIComponent(props.task)+'/workflow');if(alive&&run===generation)value.value=response;}catch(e){if(alive&&run===generation){error.value=businessErrorMessage(e);if(e instanceof BusinessError&&[401,403,404].includes(e.status))emit('restricted');}}finally{if(alive&&run===generation)busy.value=false;}}
watch(()=>props.task,()=>void load(),{immediate:true});onBeforeUnmount(()=>{alive=false;generation++;value.value=undefined;});
</script>

<style scoped>.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.el-descriptions{margin-top:12px;overflow-wrap:anywhere}</style>
