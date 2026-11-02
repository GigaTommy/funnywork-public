<template>
 <el-card>
  <template #header><strong>身份同步批次与状态</strong></template>
  <p class="hint">按提交时间查询后台任务及每次同步批次。这里展示查询时的状态，重试产生的旧批次也会保留；不是所有状态变化的历史流水。仅具有组织范围身份源读取权限的管理员可查看，不包含账号快照和认证凭证。</p>
  <el-form inline @submit.prevent="load()">
   <el-form-item label="提交时间"><el-date-picker v-model="dates" type="datetimerange" value-format="x" start-placeholder="开始（含）" end-placeholder="结束（不含）"/></el-form-item>
   <el-form-item label="身份源"><el-input v-model="filters.source" maxlength="64" clearable placeholder="身份源标识"/></el-form-item>
   <el-form-item label="批次或任务编号"><el-input v-model="filters.reference" maxlength="36" clearable/></el-form-item>
   <el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button></el-form-item>
  </el-form>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <p v-if="loaded" class="hint">查询时间：{{time(observedAt)}}。任务行显示排队与重试状态，批次行显示同步结果及预览确认情况。</p>
  <el-table @focusin="revealTableFocus" v-loading="busy" :data="rows" :empty-text="error?'本次查询未完成，请根据提示重试。':'暂无记录'">
   <el-table-column label="提交时间" min-width="180"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column>
   <el-table-column label="记录 / 身份源" min-width="160"><template #default="{row}">{{row.kind==='JOB'?'后台任务':'同步批次'}} · {{row.source_id}}<div class="hint">{{row.full_sync?'全量':'增量'}}{{row.preview_only?' · 预览':''}}</div></template></el-table-column>
   <el-table-column label="状态与结果" min-width="180"><template #default="{row}">{{states[row.state]||row.state}}<div v-if="row.kind==='JOB'" class="hint">已尝试 {{row.attempts}} 次</div><div v-else-if="row.state==='SUCCEEDED'" class="hint">新增 {{row.added}} · 更新 {{row.updated}} · 跳过 {{row.skipped}}</div><div v-else class="hint">尚无已提交的账号变更</div><div v-if="row.error_code" class="hint">{{row.error_code}}</div></template></el-table-column>
   <el-table-column label="预览确认" min-width="200"><template #default="{row}"><template v-if="row.preview_state">{{states[row.preview_state]||row.preview_state}}<div v-if="row.approved_by" class="hint">确认人：{{row.approved_by}}<br/>应用时间：{{time(row.applied_at)}}</div><div v-else class="hint">保留至 {{time(row.expires_at)}}{{row.expires_at<=observedAt?'（已过期）':''}}</div></template><span v-else>—</span></template></el-table-column>
   <el-table-column label="关联编号" min-width="240"><template #default="{row}"><div>编号：{{row.id}}</div><el-button v-if="row.run_id" text :disabled="busy" @click="correlate(row.run_id)">关联批次：{{row.run_id}}</el-button><div class="hint">提交人：{{row.actor_id}}</div></template></el-table-column>
  </el-table>
  <el-empty v-if="loaded&&!busy&&!error&&!rows.length" description="未找到符合条件的同步记录"/>
  <el-button v-if="cursor" :disabled="busy||changed" @click="load(true)">加载更多记录</el-button>
  <p v-if="changed" class="hint">条件已修改，请重新查询。</p>
 </el-card>
</template>
<script setup lang="ts">
import {revealTableFocus} from '@/utils/table-focus';
import request from '@/utils/request';
const now=Date.now(),dates=ref<number[]>([now-7*86400000,now]),filters=reactive({source:'',reference:''});
const rows=ref<any[]>([]),cursor=ref(''),busy=ref(false),error=ref(''),loaded=ref(false),observedAt=ref(0),applied=ref('');let alive=true;
const states:Record<string,string>={QUEUED:'等待执行',RUNNING:'执行中',FETCHING:'拉取中',SUCCEEDED:'已完成',FAILED:'失败',PREVIEW:'预览已就绪',READY:'待确认',APPLIED:'已应用',EXPIRED:'已过期'};
const snapshot=()=>JSON.stringify({from:dates.value?.[0]||'',until:dates.value?.[1]||'',source:filters.source.trim(),reference:filters.reference.trim()});
const changed=computed(()=>loaded.value&&snapshot()!==applied.value);
const time=(value:number)=>value?new Date(value).toLocaleString():'—';
async function correlate(reference:string){filters.reference=reference;await load();}
async function load(more=false){
 if(busy.value||more&&changed.value)return;
 if(!dates.value||dates.value.length!==2){error.value='请选择完整的时间范围。';return;}
 busy.value=true;error.value='';const query=more?applied.value:snapshot();
 try{const response=await request({url:'/funnyworker/identity-sources/audit',method:'get',params:{...JSON.parse(query),cursor:more?cursor.value:'',limit:50}});const data=response.data;if(alive){rows.value=more?[...rows.value,...data.items]:data.items;cursor.value=data.nextCursor;observedAt.value=data.observedAt;applied.value=query;loaded.value=true;}}
 catch{if(alive){rows.value=[];cursor.value='';loaded.value=false;observedAt.value=0;applied.value='';error.value='无法读取身份同步记录，请检查组织范围的身份源读取权限及查询条件，然后重试。';}}finally{if(alive)busy.value=false;}
}
onMounted(()=>load());onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>.hint{font-size:13px;color:var(--el-text-color-secondary);line-height:1.6}.el-alert{margin:12px 0}.el-button{max-width:100%;overflow-wrap:anywhere;white-space:normal}</style>
