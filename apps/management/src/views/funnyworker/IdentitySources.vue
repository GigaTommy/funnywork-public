<template>
 <el-card v-if="canRead" class="identity-sources">
  <template #header><div class="toolbar"><strong>身份源与用户同步</strong><el-button :loading="loading" @click="load">刷新</el-button></div></template>
  <el-alert v-if="accessChecked&&!access.canRead" title="当前账号没有组织范围的身份源查看权限。" type="warning" :closable="false" />
  <p>账号资料与部门归属由身份源同步，本地角色与业务授权保持不变。身份源维护权限须由组织范围的角色授予。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-alert v-if="historyError" :title="historyError" type="warning" :closable="false" />
  <el-empty v-if="access.canRead&&!loading&&!sources.length&&!error" description="尚未配置身份源，本地账号可独立使用。接入地址与凭证由部署管理员配置。" />
  <el-table :data="sources" v-loading="loading">
   <el-table-column prop="id" label="身份源"/><el-table-column prop="departmentMappings" label="部门映射数"/>
   <el-table-column label="操作" min-width="300"><template #default="{row}">
    <el-button :disabled="busy||!canWrite" @click="synchronize(row.id,false)">预览更新</el-button>
    <el-button :disabled="busy||!canWrite" @click="synchronize(row.id,true)">预览全量</el-button>
    <el-button :disabled="busy" @click="select(row.id)">记录与计划</el-button>
   </template></el-table-column>
  </el-table>
  <template v-if="selected">
   <h3>{{selected}} · 自动增量同步</h3>
   <el-form inline @submit.prevent="saveSchedule">
    <el-form-item label="启用"><el-switch v-model="schedule.enabled" :disabled="busy||!canWrite||scheduleNeedsReload"/></el-form-item>
    <el-form-item label="间隔秒数"><el-input-number v-model="schedule.interval_seconds" :min="60" :max="86400" :disabled="busy||!canWrite||scheduleNeedsReload"/></el-form-item>
    <el-button :disabled="busy||!canWrite||scheduleNeedsReload" native-type="submit">保存计划</el-button>
   </el-form>
   <el-button v-if="scheduleNeedsReload" :disabled="busy" @click="select(selected)">重新读取计划</el-button>
   <p>下一次计划：{{scheduleNeedsReload ? '尚未读取，请重新读取计划' : schedule.enabled ? time(schedule.next_at) : '未启用'}}。同步停用账号前，本系统无法获知上游变化。</p>
   <h3>任务记录</h3>
   <p>排队与执行状态每 5 秒更新。暂时失败最多尝试 3 次；资料冲突需修正后重新提交。</p>
   <el-table :data="jobs">
    <el-table-column label="提交时间"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column>
    <el-table-column label="模式"><template #default="{row}">{{row.full_sync?'全量':'增量'}}</template></el-table-column>
    <el-table-column label="状态"><template #default="{row}">{{states[row.state]||row.state}}</template></el-table-column>
    <el-table-column prop="attempts" label="尝试次数"/><el-table-column label="预览"><template #default="{row}"><el-button v-if="row.preview_only&&row.run_id&&['PREVIEW','SUCCEEDED'].includes(row.state)" :disabled="previewBusy" @click="showPreview(row.run_id)">查看变更</el-button></template></el-table-column><el-table-column prop="error_code" label="错误代码"/>
   </el-table>
   <h3>同步批次</h3>
   <el-table :data="runs">
    <el-table-column label="时间"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column>
    <el-table-column label="状态"><template #default="{row}">{{states[row.state]||row.state}}</template></el-table-column>
    <el-table-column prop="added" label="新增"/><el-table-column prop="updated" label="更新"/><el-table-column prop="skipped" label="跳过"/>
    <el-table-column prop="error_code" label="错误代码"/>
   </el-table>
  </template>
  <el-drawer v-model="previewOpen" title="同步变更预览" size="min(1050px,95vw)">
   <el-alert v-if="previewError" :title="previewError" type="error" :closable="false"/>
   <el-button v-if="previewError&&previewId" :disabled="previewBusy" @click="showPreview(previewId,previewPage)">重新读取预览</el-button>
   <el-alert v-if="previewNotice" :title="previewNotice" type="info" :closable="false"/>
   <template v-if="preview">
    <p>新增 {{preview.added}} · 更新 {{preview.updated}}（其中停用 {{preview.disabled}}） · 跳过 {{preview.skipped}} · 冲突 {{preview.conflicts}}</p>
    <p>预览保留至 {{time(preview.expiresAt)}}。确认仅应用这份快照，不重新拉取上游；本地状态变化后需重新预览。角色和业务权限不会自动授予。</p>
    <el-alert v-if="preview.conflicts" title="存在冲突，不能应用。请修正原因后重新预览。" type="warning" :closable="false"/>
    <el-alert v-if="preview.expired" title="预览已过期，账号明细会自动清理。需要同步时请重新预览。" type="info" :closable="false"/>
    <el-table :data="preview.items" v-loading="previewBusy">
     <el-table-column prop="username" label="账号" min-width="180"/><el-table-column prop="displayName" label="姓名" min-width="150"/>
     <el-table-column label="变更" min-width="90"><template #default="{row}">{{actions[row.action]||row.action}}</template></el-table-column>
     <el-table-column label="原资料" min-width="220"><template #default="{row}">{{row.before.user_name||'—'}} · {{row.before.nick_name||'—'}}</template></el-table-column>
     <el-table-column prop="departmentId" label="目标部门标识" min-width="150"/><el-table-column label="上游状态" min-width="170"><template #default="{row}">{{row.enabled?'启用（保留本地停用）':'停用'}}</template></el-table-column>
     <el-table-column label="原因" min-width="200"><template #default="{row}">{{reasons[row.reason]||row.reason||'—'}}</template></el-table-column>
    </el-table>
    <el-pagination v-if="!preview.detailsPurged" :current-page="previewPage" :page-size="50" :total="preview.total" layout="prev, pager, next" @current-change="page=>showPreview(preview.id,page)"/>
    <el-button type="primary" :loading="previewBusy" :disabled="!!previewError||!canWrite||preview.state!=='READY'||preview.expired||preview.conflicts>0" @click="approvePreview">{{preview.state==='APPLIED'?'已应用':'确认应用这份变更'}}</el-button>
   </template>
  </el-drawer>
 </el-card>
</template>
<script setup lang="ts">
import request from '@/utils/request';
import {directoryPreviewOffset} from '@/utils/directory-preview';
import {useUserStore} from '@/store/modules/user';
const user=useUserStore();
const permits=(action:string)=>String(user.userId)==='1'||user.permissions.includes('*:*:*')||user.permissions.includes('funnyworker:directory:'+action);
const access=reactive({canRead:false,canWrite:false});const accessChecked=ref(false);
const canRead=computed(()=>permits('read')),canWrite=computed(()=>permits('write')&&access.canRead&&access.canWrite);
const sources=ref<any[]>([]),runs=ref<any[]>([]),jobs=ref<any[]>([]),loading=ref(false),busy=ref(false),error=ref(''),notice=ref(''),selected=ref('');
const historyError=ref(''),scheduleNeedsReload=ref(false);
const schedule=reactive({interval_seconds:3600,enabled:false,next_at:0});
const states:Record<string,string>={QUEUED:'等待执行',RUNNING:'同步中',FETCHING:'同步中',SUCCEEDED:'已完成',PREVIEW:'预览已生成',FAILED:'失败'};
const base='/funnyworker/identity-sources';let alive=true,visible=true,generation=0,polling=false;
const time=(value:number)=>value?new Date(value).toLocaleString():'—';
async function load(){
 if(!canRead.value||loading.value)return;loading.value=true;error.value='';access.canRead=false;access.canWrite=false;accessChecked.value=false;
 try{
  const capability=await request({url:base+'/access'});if(!alive)return;
  access.canRead=capability.data?.canRead===true;access.canWrite=capability.data?.canWrite===true;accessChecked.value=true;
  if(!access.canRead){sources.value=[];selected.value='';runs.value=[];jobs.value=[];return;}
  const result=await request({url:base});if(alive)sources.value=result.data;
 }catch{if(alive)error.value='身份源读取失败，请检查服务连接与组织范围权限。';}
 finally{if(alive)loading.value=false;}
}
async function history(id:string){
 const run=generation;
 const [batch,queue]=await Promise.all([request({url:`${base}/${encodeURIComponent(id)}/runs`}),request({url:`${base}/${encodeURIComponent(id)}/jobs`})]);
 if(alive&&run===generation&&id===selected.value){runs.value=batch.data;jobs.value=queue.data;historyError.value='';}
}
async function select(id:string,clearError=true){
 const run=++generation;selected.value=id;runs.value=[];jobs.value=[];busy.value=true;scheduleNeedsReload.value=true;historyError.value='';if(clearError)error.value='';
 try{const config=await request({url:`${base}/${encodeURIComponent(id)}/schedule`});if(alive&&run===generation){Object.assign(schedule,config.data);scheduleNeedsReload.value=false;}await history(id);}
 catch{if(alive&&run===generation){const message='记录或计划读取失败，请重新选择身份源。';if(clearError)error.value=message;else historyError.value=message;}}
 finally{if(alive&&run===generation)busy.value=false;}
}
async function synchronize(id:string,full:boolean){
 if(busy.value||!canWrite.value)return;busy.value=true;error.value='';notice.value='';
 try{const result=await request({url:`${base}/${encodeURIComponent(id)}/previews`,method:'post',params:{full}});if(alive)notice.value='已受理预览任务 '+result.data.id+'，完成后请查看变更并确认应用。';}
 catch{if(alive)error.value='提交结果未确认，请查看任务记录后再操作。';}
 finally{if(alive){busy.value=false;await select(id,false);}}
}
async function saveSchedule(){
 if(busy.value||!canWrite.value||scheduleNeedsReload.value)return;busy.value=true;error.value='';notice.value='';
 const id=selected.value;
 try{
  await request({url:`${base}/${encodeURIComponent(id)}/schedule`,method:'put',data:{intervalSeconds:schedule.interval_seconds,enabled:schedule.enabled}});
  if(alive){scheduleNeedsReload.value=true;notice.value='同步计划已保存。';}
  try{const latest=await request({url:`${base}/${encodeURIComponent(id)}/schedule`});if(alive&&id===selected.value){Object.assign(schedule,latest.data);scheduleNeedsReload.value=false;}}
  catch{if(alive)notice.value='同步计划已保存，但最新计划读取失败。请重新读取核对，无需重复保存。';}
 }
 catch{if(alive)error.value='保存未确认，请重新读取计划核对。';}
 finally{if(alive)busy.value=false;}
}
const previewId=ref('');
const previewOpen=ref(false),previewBusy=ref(false),preview=ref<any>(),previewPage=ref(1),previewError=ref(''),previewNotice=ref('');
const actions:Record<string,string>={ADD:'新增',UPDATE:'更新',DISABLE:'停用',SKIP:'跳过',CONFLICT:'冲突'};
const reasons:Record<string,string>={SYNC_DEPARTMENT_UNMAPPED:'部门映射缺失或部门已停用',SYNC_USERNAME_CONFLICT:'账号名被本地或其他身份源占用',SYNC_USER_VERSION_CONFLICT:'相同版本包含不同资料',SYNC_ACCOUNT_CONFLICT:'原账号已删除或来源变化'};
let previewSource='',previewGeneration=0;
async function showPreview(id:string,page=1){
 const run=++previewGeneration;previewId.value=id;previewPage.value=page;previewSource=selected.value;previewOpen.value=true;previewBusy.value=true;previewError.value='';previewNotice.value='';
 try{const response=await request({url:`${base}/${encodeURIComponent(previewSource)}/previews/${encodeURIComponent(id)}`,params:{offset:directoryPreviewOffset(page),limit:50}});if(alive&&run===previewGeneration){preview.value=response.data;previewPage.value=page;}}
 catch{if(alive&&run===previewGeneration){preview.value=undefined;previewError.value='预览读取失败，请检查权限或重新生成预览。';}}
 finally{if(alive&&run===previewGeneration)previewBusy.value=false;}
}
async function approvePreview(){
 if(previewBusy.value||previewError.value||!canWrite.value||!preview.value||preview.value.state!=='READY'||preview.value.expired||preview.value.conflicts>0)return;
 const id=preview.value.id,source=previewSource,run=previewGeneration;previewBusy.value=true;previewError.value='';previewNotice.value='';
 try{
  const response=await request({url:`${base}/${encodeURIComponent(source)}/previews/${encodeURIComponent(id)}/confirm`,method:'post',timeout:180000});
  if(alive&&run===previewGeneration){preview.value.state='APPLIED';previewNotice.value=response.data.sessionsInvalidated?'变更已应用。':'变更已应用；部分会话退出未确认，请核对会话状态。旧外部会话仍由请求校验阻止访问。';}
 }catch{if(alive&&run===previewGeneration)previewError.value='应用未确认或预览已失效。请重新读取预览核对结果；状态变化时重新生成预览。';}
 finally{if(alive&&run===previewGeneration)previewBusy.value=false;}
}
watch(selected,()=>{previewOpen.value=false;previewGeneration++;previewId.value='';preview.value=undefined;previewBusy.value=false;});
const timer=setInterval(async()=>{if(!alive||!visible||!selected.value||document.hidden||polling||busy.value)return;polling=true;try{await history(selected.value);}catch{if(alive)historyError.value='同步状态暂时无法更新，显示的是上次读取结果。';}finally{polling=false;}},5000);
onActivated(()=>{visible=true;});onDeactivated(()=>{visible=false;});
onMounted(load);onBeforeUnmount(()=>{alive=false;generation++;previewGeneration++;clearInterval(timer);});
</script>
<style scoped>
.identity-sources{margin-top:20px}.toolbar{display:flex;align-items:center;justify-content:space-between}p{line-height:1.6;color:var(--el-text-color-secondary)}.el-alert{margin-bottom:16px}h3{margin-top:24px}
</style>
