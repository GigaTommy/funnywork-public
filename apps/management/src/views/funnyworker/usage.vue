<template>
 <ModelMiddlewareUsage />
 <div class="app-container">
  <el-card>
   <template #header><strong>{{modelCopy.activity}}</strong></template>
   <p class="hint">按任务创建时间统计当前授权区域内的服务端任务记录，不包含未进入任务服务的桌面对话。完成与失败数量反映查询时的任务状态。</p>
   <el-form inline @submit.prevent="load()"><el-form-item label="创建时间"><el-date-picker :disabled="fullLoading" v-model="dates" type="datetimerange" value-format="x" start-placeholder="开始（含）" end-placeholder="结束（不含）" /></el-form-item><el-form-item><el-button native-type="submit" :disabled="fullLoading" :loading="busy">查询 / 刷新</el-button></el-form-item></el-form>
   <p class="hint">单次查询最多 93 天。</p>
   <el-alert v-if="error" :title="error" type="error" :closable="false" />
   <template v-if="data">
    <p class="hint">统计区间：{{time(data.from)}} — {{time(data.until)}} · 查询时间：{{time(data.observedAt)}} · 区域：{{data.zones.join('、')||'无授权区域'}}</p>
    <div class="metrics"><div><span>创建任务</span><strong>{{data.summary.tasks}}</strong></div><div><span>有任务的成员</span><strong>{{data.summary.members}}</strong></div><div><span>当前已完成</span><strong>{{data.summary.succeeded}}</strong></div><div><span>当前失败</span><strong>{{data.summary.failed}}</strong></div></div>
    <h3>成员活动</h3><el-table :data="rows" v-loading="busy" :empty-text="error?'本次查询未完成，请根据提示重试。':'暂无记录'"><el-table-column prop="display_name" label="成员" min-width="160" /><el-table-column label="当前部门" min-width="160"><template #default="{row}">{{departmentLabel(row)}}</template></el-table-column><el-table-column prop="tasks" label="任务数" /><el-table-column prop="succeeded" label="已完成" /><el-table-column prop="failed" label="失败" /></el-table>
    <el-empty v-if="!rows.length&&!busy&&!error" description="此区间没有可见的任务记录" /><el-button v-if="cursor" :disabled="busy||changed||fullLoading" @click="load(true)">加载更多成员</el-button><p v-if="changed" class="hint">时间条件已修改，请重新查询。</p>
    <h3>按当前部门汇总</h3>
    <p class="hint">基于已加载的 {{rows.length}} 位成员{{cursor?'，尚未覆盖完整区间':'；成员列表已加载完毕'}}。归属来自身份服务当前部门，不代表任务创建时的历史部门；不可确认与未分配分别统计。各页按查询时数据读取。</p>
    <el-button v-if="cursor&&!fullLoading" :disabled="busy||changed" @click="loadAll">加载全部成员并汇总</el-button><el-button v-if="fullLoading" @click="stopLoading=true">停止后续加载</el-button>
    <el-table :data="departments" :empty-text="error?'本次查询未完成，请根据提示重试。':'暂无记录'"><el-table-column prop="name" label="当前部门" min-width="180"/><el-table-column prop="members" label="成员数"/><el-table-column prop="tasks" label="任务数"/><el-table-column prop="succeeded" label="已完成"/><el-table-column prop="failed" label="失败"/></el-table>
    <p v-if="rows.some(row=>row.departmentState==='UNAVAILABLE')" class="hint">部分部门不可确认：可能尚未关联身份账号、超出身份数据范围或身份服务暂不可用。请在恢复后重新查询，不将这些记录计入已知部门。</p>
    <el-alert title="此区域仅统计业务任务活动，不是模型计费报表。模型调用、计量完整性见上方；预算及结算依据在模型管理中查看。未经过企业入口的调用不在计量覆盖范围内。" type="info" :closable="false" />
   </template>
  </el-card>
 </div>
</template>
<script setup lang="ts">
import ModelMiddlewareUsage from "./ModelMiddlewareUsage.vue";
import {useI18n} from 'vue-i18n';
import {modelOperationsCopy} from './model-operations-copy';
const {locale}=useI18n();const modelCopy=computed(()=>locale.value.startsWith('en')?modelOperationsCopy.en:modelOperationsCopy.zh);
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
import {sameUsageScope} from '@/utils/usage-scope';
const now=Date.now();
const dates=ref<number[]>([now-30*86400000,now]),applied=ref<number[]>([]);
const data=ref<any>(),rows=ref<any[]>([]),cursor=ref(''),busy=ref(false),error=ref('');const fullLoading=ref(false),stopLoading=ref(false);let alive=true;
const changed=computed(()=>JSON.stringify(dates.value)!==JSON.stringify(applied.value));
const time=(value:number)=>new Date(value).toLocaleString();
const departmentLabel=(row:any)=>row.departmentState==='MAPPED'?row.departmentName||('部门 '+row.departmentId):row.departmentState==='UNASSIGNED'?'未分配部门':'部门不可确认';
const departments=computed(()=>{
 const grouped=new Map<string,{name:string;members:number;tasks:number;succeeded:number;failed:number}>();
 for(const row of rows.value){const key=row.departmentState==='MAPPED'?'id:'+row.departmentId:row.departmentState||'UNAVAILABLE';const item=grouped.get(key)||{name:departmentLabel(row),members:0,tasks:0,succeeded:0,failed:0};item.members++;item.tasks+=Number(row.tasks);item.succeeded+=Number(row.succeeded);item.failed+=Number(row.failed);grouped.set(key,item);}
 return [...grouped.values()];
});
async function loadAll(){
 if(busy.value||fullLoading.value||changed.value)return;fullLoading.value=true;stopLoading.value=false;
 try{while(alive&&cursor.value&&!stopLoading.value&&!changed.value){const before=cursor.value;await load(true);if(error.value||cursor.value===before)break;}}
 finally{if(alive)fullLoading.value=false;}
}
async function load(more=false){
 if(busy.value||more&&changed.value)return;
 if(!dates.value||dates.value.length!==2){error.value='请选择完整的开始与结束时间。';return;}
 busy.value=true;error.value='';const range=[...(more?applied.value:dates.value)];
 try{const params=new URLSearchParams({from:String(range[0]),until:String(range[1]),after:more?cursor.value:'',limit:'50'});const result=await businessRequest('/management-usage?'+params);if(alive){if(more&&data.value&&!sameUsageScope(data.value,result)){data.value=undefined;rows.value=[];cursor.value='';applied.value=[];error.value='统计区间或授权范围已变化，旧统计已清空，请重新查询。';return;}data.value=result;rows.value=more?[...rows.value,...result.items]:result.items;cursor.value=result.nextCursor||'';applied.value=range;}}
 catch(e){if(alive)error.value=businessErrorMessage(e);}
 finally{if(alive)busy.value=false;}
}
onMounted(()=>load());onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>
.hint{color:var(--el-text-color-secondary);font-size:13px;line-height:1.6}.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:16px;margin:20px 0}.metrics>div{padding:16px;border:1px solid var(--el-border-color);border-radius:8px;display:flex;flex-direction:column;gap:8px}.metrics strong{font-size:26px}.el-alert{margin:12px 0}
</style>
