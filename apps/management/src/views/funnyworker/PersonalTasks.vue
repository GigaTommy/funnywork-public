<template>
 <div class="app-container">
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-card>
   <template #header><div class="toolbar"><div><strong>我的任务</strong><p class="hint">仅显示当前账号在授权区域内发起的任务。</p></div><el-button v-hasPermi="['funnyworker:tasks:write']" type="primary" :disabled="busy" @click="openCreate">创建任务</el-button></div></template>
   <el-form inline class="filters" label-width="90px" @submit.prevent="load(false)">
    <el-form-item label="名称或编号"><el-input v-model="filters.query" clearable maxlength="160" placeholder="任务名称、能力名称或完整编号" /></el-form-item>
    <el-form-item label="状态"><el-select v-model="filters.state" clearable style="width:160px"><el-option v-for="(label,key) in states" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="区域"><el-select v-model="filters.zone" clearable style="width:150px"><el-option v-for="zone in zones" :key="zone" :label="zone" :value="zone" /></el-select></el-form-item>
    <el-form-item label="创建时间"><el-date-picker v-model="dates" type="datetimerange" start-placeholder="开始（含）" end-placeholder="结束（不含）" value-format="x" /></el-form-item>
    <el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button><el-button :disabled="busy" @click="resetFilters">重置</el-button></el-form-item>
   </el-form>
   <el-table v-loading="busy" :data="rows">
    <el-table-column label="任务" min-width="230"><template #default="{row}"><el-button text @click="detail(row.id)">{{row.title}}</el-button><div class="hint identifier">{{row.id}}</div></template></el-table-column>
    <el-table-column prop="owner_name" label="发起人" min-width="100" /><el-table-column prop="agent_name" label="执行能力" min-width="140" /><el-table-column prop="zone" label="区域" min-width="100" />
    <el-table-column label="状态" min-width="130"><template #default="{row}">{{stateLabel(row.state)}}<div v-if="row.dispatch_paused&&row.state==='RUNNING'" class="hint">已请求暂停调度</div></template></el-table-column>
    <el-table-column label="创建时间" min-width="180"><template #default="{row}">{{formatTime(row.created_at)}}</template></el-table-column>
    <el-table-column label="操作" width="180" fixed="right"><template #default="{row}"><el-button v-hasPermi="['funnyworker:tasks:write']" text :disabled="busy||row.state!=='RUNNING'" @click="admission(row)">{{row.dispatch_paused?'恢复调度':'暂停调度'}}</el-button><el-button v-hasPermi="['funnyworker:tasks:write']" text type="danger" :disabled="busy||row.state!=='RUNNING'" @click="cancel(row.id)">取消</el-button></template></el-table-column>
   </el-table>
   <el-empty v-if="!busy&&!rows.length" description="当前条件下没有任务" /><el-button v-if="cursor" :disabled="busy||filtersChanged" @click="load(true)">加载更多</el-button><span v-if="filtersChanged" class="hint">条件已修改，请先查询。</span>
  </el-card>
  <el-dialog v-model="creating" title="创建任务" width="min(600px, 95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
   <el-form label-width="90px" :disabled="busy">
    <el-form-item label="任务名称"><el-input v-model="form.title" maxlength="160" show-word-limit placeholder="选填，未填写时使用执行能力名称" /></el-form-item>
    <el-form-item label="执行能力"><el-select v-model="form.agentId" style="width:100%"><el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.name+' · '+agent.version" /></el-select><el-button v-if="agentCursor" :disabled="busy" @click="moreAgents">加载更多执行能力</el-button></el-form-item>
    <el-form-item label="授权区域"><el-select v-model="form.zone"><el-option v-for="zone in zones" :key="zone" :value="zone" :label="zone" /></el-select></el-form-item>
    <el-form-item label="任务内容"><el-input v-model="form.input" type="textarea" :rows="6" maxlength="10000" show-word-limit /></el-form-item>
    <el-form-item label="调度"><el-checkbox v-model="form.dispatchPaused">创建后暂停调度</el-checkbox></el-form-item>
   </el-form><p class="hint">关闭窗口会保留本页草稿。连接中断后可重试；重新打开页面后，请先核对任务列表。</p>
   <el-alert v-if="error" :title="error" type="error" :closable="false" />
   <template #footer><el-button :disabled="busy" @click="creating=false">暂存并关闭</el-button><el-button type="primary" :loading="busy" :disabled="!form.agentId||!form.zone||!form.input.trim()" @click="create">创建</el-button></template>
  </el-dialog>
  <el-drawer v-model="showDetail" title="任务详情" size="min(850px, 95vw)">
   <el-alert v-if="error" :title="error" type="error" :closable="false" />
   <template v-if="selected">
    <div class="toolbar"><h3>{{selected.title}}</h3><el-button :loading="busy" @click="detail(selected.taskId)">刷新详情</el-button></div>
    <el-descriptions :column="1" border><el-descriptions-item label="任务编号">{{selected.taskId}}</el-descriptions-item><el-descriptions-item label="发起人">{{selected.ownerName}}</el-descriptions-item><el-descriptions-item label="创建时间">{{formatTime(selected.createdAt)}}</el-descriptions-item><el-descriptions-item label="区域">{{selected.zone}}</el-descriptions-item><el-descriptions-item label="状态">{{stateLabel(selected.state)}}</el-descriptions-item><el-descriptions-item label="调度">{{pauseLabels[selected.pauseStatus]||selected.pauseStatus}}</el-descriptions-item></el-descriptions>
    <el-button v-if="canReadRecovery" @click="router.push({path:'/funnyworker/recovery',query:{taskId:selected.taskId,zone:selected.zone}})">查看关联恢复记录</el-button>
    <el-button v-if="canReadDocuments" @click="router.push({path:'/funnyworker/documents',query:{taskId:selected.taskId}})">查看知识来源绑定</el-button>
    <TaskPlacement v-if="['repository.maintain.v1','report.annual.v1'].includes(selected.capability)" :key="selected.taskId" :task-id="selected.taskId" />
    <el-tabs>
     <el-tab-pane label="执行与结果"><el-collapse><el-collapse-item v-for="unit in selected.units" :key="unit.id" :name="unit.id" :title="'步骤 '+(unit.ordinal+1)+' · '+stateLabel(unit.state)"><p class="hint">执行次数：{{unit.attempts}} · 计划版本：{{unit.plan_version}}</p><TaskResult v-if="unit.result!=null" :key="selected.taskId+unit.id" :task-id="selected.taskId" :unit-id="unit.id" :value="unit.result"/><p v-else class="hint">尚无已确认结果。</p></el-collapse-item></el-collapse><el-empty v-if="!selected.units.length" description="暂无执行步骤" /></el-tab-pane>
     <el-tab-pane label="事件时间线"><el-timeline><el-timeline-item v-for="event in events" :key="event.id" :timestamp="formatTime(event.created_at)">{{eventLabels[event.event_type]||event.event_type}}</el-timeline-item></el-timeline><el-empty v-if="!events.length" description="暂无事件" /><el-button v-if="moreEvents" :loading="busy" @click="loadEvents">加载后续事件</el-button></el-tab-pane>
    </el-tabs>
   </template>
  </el-drawer>
 </div>
</template>
<script setup lang="ts">
import TaskResult from './TaskResult.vue';
import TaskPlacement from './TaskPlacement.vue';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,saveAndRefresh} from '@/utils/business-feedback';
import {checkPermi} from '@/utils/permission';
import {createTaskSubmission} from '@/utils/task-submission';
const submitTask=createTaskSubmission();
const route=useRoute(),router=useRouter();
const businessAdmin=ref(false);
const canReadDocuments=computed(()=>businessAdmin.value&&checkPermi(['funnyworker:documents:read']));
const canReadRecovery=computed(()=>businessAdmin.value&&checkPermi(['funnyworker:recovery:read']));
const states:Record<string,string>={RUNNING:'进行中',SUCCEEDED:'已完成',FAILED:'失败',CANCELLED:'已取消',BLOCKED_AUTH:'授权已撤销',RECONCILING:'待核对',RECONCILED:'已核对',OUTPUT_READY:'产出待确认',PARTIAL_OUTPUT:'部分产出',PARTIALLY_SUCCEEDED:'部分完成'};
const unitStates:Record<string,string>={GENERATED:'已生成，待确认',READY:'等待调度',RESERVED:'已分配',WAITING_DEPENDENCY:'等待前置步骤',BLOCKED_DEPENDENCY:'前置步骤未完成'};
const pauseLabels:Record<string,string>={TERMINAL:'任务已结束',NOT_REQUESTED:'允许调度',DRAINING:'等待正在执行的步骤结束',PAUSED:'已暂停'};
const eventLabels:Record<string,string>={TASK_CREATED:'任务已创建',TASK_DISPATCH_PAUSED:'已暂停后续调度',TASK_DISPATCH_RESUMED:'已恢复调度',TASK_PLAN_APPENDED:'已追加执行计划',TASK_CANCELLED:'已取消任务',TASK_AUTH_REVOKED:'任务授权已撤销',UNIT_RESERVED:'执行步骤已分配',UNIT_STARTED:'执行步骤已开始',RESULT_ACCEPTED:'执行结果已确认',MODEL_OUTPUT_STORED:'模型产出已保存，等待确认',MODEL_FAILED:'模型执行失败'};
const stateLabel=(value:string)=>states[value]||unitStates[value]||value;
const formatTime=(value:number)=>value?new Date(value).toLocaleString():'未记录';
const notice=ref(''),agentCursor=ref('');
const rows=ref<any[]>([]),agents=ref<any[]>([]),zones=ref<string[]>([]),cursor=ref(''),busy=ref(false),error=ref(''),creating=ref(false),showDetail=ref(false),selected=ref<any>(),events=ref<any[]>([]),moreEvents=ref(false);
const filters=reactive({query:'',state:'',zone:''}),dates=ref<number[]>([]);
const filterParams=()=>({query:filters.query.trim(),state:filters.state,zone:filters.zone,from:dates.value?.[0]||'0',until:dates.value?.[1]||'0'});
let applied=JSON.stringify(filterParams());
const appliedFilters=ref(applied);
const filtersChanged=computed(()=>JSON.stringify(filterParams())!==appliedFilters.value);
const form=reactive({title:'',agentId:'',zone:'',input:'',dispatchPaused:true});let alive=true,routeReload=false,pendingDetail='';
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await work();}catch(e){if(alive)error.value=businessErrorMessage(e);}finally{if(alive){busy.value=false;if(pendingDetail){const id=pendingDetail;pendingDetail='';void detail(id);}else if(routeReload){routeReload=false;void load();}}}}
async function readRows(more=false){if(more&&filtersChanged.value)return;const snapshot=more?applied:JSON.stringify(filterParams());const params=new URLSearchParams({...JSON.parse(snapshot),limit:'50',after:more?cursor.value:''});const data=await request('/tasks?'+params);if(alive){rows.value=more?[...rows.value,...data.items]:data.items;cursor.value=data.nextCursor||'';applied=snapshot;appliedFilters.value=snapshot;}}
async function load(more=false){await run(()=>readRows(more));}
async function resetFilters(){filters.query='';filters.state='';filters.zone='';dates.value=[];await load();}
async function mutate(write:()=>Promise<void>){await run(async()=>{const result=await saveAndRefresh(write,()=>readRows());if(alive)notice.value=result;});}
async function openCreate(){await run(async()=>{const [me,data]=await Promise.all([request('/me'),request('/agent-definitions/page?limit=50')]);if(alive){data.items=data.items.filter((agent:any)=>!['repository.maintain.v1','report.annual.v1'].includes(agent.capability));zones.value=me.zones;if(!form.zone)form.zone=zones.value[0]||'';const selectedAgent=agents.value.find(agent=>agent.id===form.agentId);agents.value=selectedAgent&&!data.items.some((agent:any)=>agent.id===selectedAgent.id)?[selectedAgent,...data.items]:data.items;agentCursor.value=data.nextCursor||'';creating.value=true;}});}
async function moreAgents(){await run(async()=>{const page=await request('/agent-definitions/page?limit=50&after='+encodeURIComponent(agentCursor.value));if(alive){const ids=new Set(agents.value.map(agent=>agent.id));agents.value.push(...page.items.filter((agent:any)=>!['repository.maintain.v1','report.annual.v1'].includes(agent.capability)&&!ids.has(agent.id)));agentCursor.value=page.nextCursor||'';}});}
async function create(){await mutate(async()=>{await submitTask({title:form.title.trim()||undefined,agentId:form.agentId,zone:form.zone,inputs:[form.input],dispatchPaused:form.dispatchPaused});if(alive){creating.value=false;form.input='';form.title='';}});}
async function admission(row:any){await mutate(async()=>{await request('/tasks/'+encodeURIComponent(row.id)+'/admission','PUT',{paused:!row.dispatch_paused});});}
async function cancel(id:string){try{await ElMessageBox.confirm('取消后任务将停止继续调度，是否确认？','取消任务',{type:'warning'});}catch{return;}await mutate(async()=>{await request('/tasks/'+encodeURIComponent(id)+'/cancel','POST',{});});}
async function detail(id:string){await run(async()=>{selected.value=undefined;events.value=[];showDetail.value=true;const [task,history]=await Promise.all([request('/tasks/'+encodeURIComponent(id)),request('/tasks/'+encodeURIComponent(id)+'/events')]);if(alive){selected.value=task;events.value=history;moreEvents.value=history.length===200;showDetail.value=true;}});}
async function loadEvents(){if(!selected.value)return;const id=selected.value.taskId;const after=events.value.at(-1)?.id||0;await run(async()=>{const history=await request('/tasks/'+encodeURIComponent(id)+'/events?after='+after);if(alive&&selected.value?.taskId===id){events.value.push(...history);moreEvents.value=history.length===200;}});}
function applyRouteState(){const state=route.query.state;filters.state=typeof state==='string'&&Object.hasOwn(states,state)?state:'';}
applyRouteState();
watch(()=>route.query.taskId,(id)=>{if(typeof id!=='string'||!id||id===selected.value?.taskId)return;if(busy.value)pendingDetail=id;else void detail(id);});
watch(()=>route.query.state,()=>{applyRouteState();if(busy.value)routeReload=true;else void load();});
onMounted(()=>run(async()=>{const me=await request('/me');if(alive){zones.value=me.zones;businessAdmin.value=me.roles.includes('ADMIN');}await readRows();}).then(()=>{if(typeof route.query.taskId==='string')void detail(route.query.taskId);}));onBeforeUnmount(()=>{alive=false;});
defineExpose({hasUnsavedDraft:()=>creating.value||!!form.title.trim()||!!form.input.trim()});
</script>
<style scoped>
.toolbar{display:flex;justify-content:space-between;align-items:center;gap:12px}.hint{color:var(--el-text-color-secondary);font-size:13px}.identifier{overflow-wrap:anywhere}.el-alert{margin-bottom:16px}.filters{margin-bottom:12px}.result{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:12px;border-radius:6px}.el-tabs{margin-top:20px}
</style>
