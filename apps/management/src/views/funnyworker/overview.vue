<template>
 <div>
  <div v-if="canReadTasks" class="app-container summary">
   <el-card>
    <template #header><div class="toolbar"><div><strong>我的任务待办</strong><p class="hint">当前账号在授权区域内的全部任务，与任务列表使用相同范围。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-else-if="!eligible&&!loading" class="hint">业务授权未包含任务查看权限。</p>
    <template v-if="summary">
     <div class="cards"><button v-for="item in attention" :key="item.state" class="task-card" @click="openTasks(item.state)"><span>{{item.label}}</span><strong>{{count(item.state)}}</strong><small>{{item.description}}</small></button></div>
     <p class="hint">截至 {{new Date(summary.asOf).toLocaleString()}} · 共 {{summary.total}} 个任务；刷新失败时保留上次结果。</p>
     <el-button @click="openTasks('')">查看全部任务</el-button>
    </template>
   </el-card>
  </div>
  <BusinessModule module="overview" />
 </div>
</template>
<script setup lang="ts">
import BusinessModule from './BusinessModule.vue';
import {checkPermi} from '@/utils/permission';
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
const router=useRouter();
const canReadTasks=computed(()=>checkPermi(['funnyworker:tasks:read']));
const loading=ref(false),error=ref(''),eligible=ref(true);
const summary=ref<{items:{state:string;count:number}[];total:number;asOf:number}>();
const attention=[{state:'RECONCILING',label:'待核对',description:'执行结果尚不确定'},{state:'BLOCKED_AUTH',label:'授权中断',description:'需要核对账号授权'},{state:'FAILED',label:'失败',description:'查看详情后处理'},{state:'OUTPUT_READY',label:'产出待确认',description:'已有产出，尚未确认完成'},{state:'PARTIAL_OUTPUT',label:'部分产出',description:'检查未完成步骤'},{state:'PARTIALLY_SUCCEEDED',label:'部分完成',description:'检查失败步骤'}];
const count=(state:string)=>summary.value?.items.find(item=>item.state===state)?.count??0;
function openTasks(state:string){void router.push({path:'/funnyworker/tasks',query:state?{state}:{}});}
let alive=true;
async function load(){
 if(loading.value||!canReadTasks.value)return;
 loading.value=true;error.value='';
 try{
  const me=await businessRequest('/me');if(!alive)return;
  eligible.value=me.roles.some((role:string)=>role==='OPERATOR'||role==='VIEWER');
  if(!eligible.value){summary.value=undefined;return;}
  const data=await businessRequest('/tasks/summary');if(alive)summary.value=data;
 }catch(e){if(alive)error.value=businessErrorMessage(e);}
 finally{if(alive)loading.value=false;}
}
onMounted(load);onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>
.summary{padding-bottom:0}.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px}.hint{color:var(--el-text-color-secondary);font-size:13px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px}.task-card{display:flex;flex-direction:column;gap:8px;text-align:left;padding:16px;border:1px solid var(--el-border-color);border-radius:8px;background:var(--el-bg-color);color:var(--el-text-color-primary);font:inherit;cursor:pointer}.task-card strong{font-size:26px}.task-card small{color:var(--el-text-color-secondary)}.task-card:focus-visible{outline:2px solid var(--el-text-color-primary);outline-offset:3px}.el-alert{margin-bottom:12px}
</style>
