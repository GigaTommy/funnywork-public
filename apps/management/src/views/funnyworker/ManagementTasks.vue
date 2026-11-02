<template>
 <el-card shadow="never" class="task-center-panel">
  <template #header><strong>组织任务</strong></template>
  <p class="hint">查看当前授权区域内各成员创建的服务端任务。桌面对话与手机聊天尚未纳入此列表。正文和产物不随查看权限开放；管理操作需独立授权。</p>
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <p v-if="pending">有一项操作结果待核对。<el-button @click="checkReceipt" :disabled="commandBusy">核对回执</el-button><el-button @click="submitPending" :disabled="commandBusy">使用原请求重试</el-button></p>
  <el-form inline @submit.prevent="load()">
   <el-form-item label="名称或编号"><el-input v-model="filter.query" clearable maxlength="160" /></el-form-item>
   <el-form-item label="负责人"><el-select v-model="filter.owner" clearable filterable remote :remote-method="searchMembers" :loading="membersBusy" placeholder="搜索成员名称" style="width:180px"><el-option v-for="member in members" :key="member.id" :label="member.display_name" :value="member.id" /></el-select></el-form-item>
   <el-form-item label="状态"><el-select v-model="filter.state" placeholder="全部状态" clearable style="width:160px"><el-option v-for="(label,key) in states" :key="key" :label="label" :value="key" /></el-select></el-form-item>
   <el-form-item label="授权区域"><el-select v-model="filter.zone" placeholder="全部授权区域" clearable style="width:150px"><el-option v-for="zone in zones" :key="zone" :label="zone" :value="zone" /></el-select></el-form-item>
   <el-form-item label="用途"><el-select v-model="filter.capability" placeholder="全部用途" clearable style="width:150px"><el-option label="模型生成" value="model.read.v1" /><el-option label="代码维护" value="repository.maintain.v1" /><el-option label="年报编制" value="report.annual.v1" /></el-select></el-form-item>
   <el-form-item label="部门"><el-select v-model="filter.department" clearable placeholder="全部已知及未知部门" style="width:180px"><el-option label="归属未确认" value="__UNCONFIRMED__" /><el-option v-for="item in contextOptions.departments" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
   <el-form-item label="项目"><el-select v-model="filter.project" clearable placeholder="全部已知及未知项目" style="width:180px"><el-option label="归属未确认" value="__UNCONFIRMED__" /><el-option v-for="item in contextOptions.projects" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
   <el-form-item label="创建时间"><el-date-picker v-model="dates" type="datetimerange" value-format="x" start-placeholder="开始（含）" end-placeholder="结束（不含）" /></el-form-item>
   <el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button><el-button :disabled="busy" @click="reset">清空筛选</el-button></el-form-item>
  </el-form>
  <el-button v-if="contextOptions.nextDepartment||contextOptions.nextProject" :disabled="busy" @click="moreFilterOptions">加载更多部门和项目筛选项</el-button>
  <p v-if="memberError&&memberError!==error" class="hint">{{memberError}}</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="changed" class="hint">条件已修改，请重新查询；当前仍展示上次查询结果。</p>
  <template v-if="observedAt">
   <p class="hint">当前授权区域：{{zones.join('、')||'无'}} · 截至 {{time(observedAt)}} · 符合筛选 {{total}} 个任务</p>
   <div class="metrics"><div v-for="item in summary" :key="item.state"><span>{{label(item.state)}}</span><strong>{{item.count}}</strong></div></div>
  </template>
  <TaskBatchPanel v-if="canOperate" :scope-epoch="scopeEpoch" :selected="selection" :actor-key="actorKey" :blocked="!!pending||storageBlocked||commandBusy" @blocked="batchBlocked=$event" @updated="refreshAfterBatch" />
  <el-table ref="table" @selection-change="selectionChanged" :data="rows" v-loading="busy" :empty-text="error?'查询未完成，请重试':'此范围暂无任务'">
   <el-table-column v-if="canOperate" type="selection" width="45" />
   <el-table-column prop="title" label="任务" min-width="200" />
   <el-table-column prop="owner_name" label="负责人" min-width="140" />
   <el-table-column label="归属" min-width="160"><template #default="{row}">{{row.department_name||'部门未确认'}}<br>{{row.project_name||'项目未确认'}}</template></el-table-column>
   <el-table-column prop="zone" label="区域" min-width="100" />
   <el-table-column label="状态" min-width="140"><template #default="{row}">{{label(row.state)}}</template></el-table-column>
   <el-table-column label="用途" min-width="150"><template #default="{row}">{{purpose(row.capability)}}</template></el-table-column>
   <el-table-column label="创建时间" min-width="175"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column>
   <el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button :disabled="busy" @click="inspect(row.id)">详情</el-button></template></el-table-column>
  </el-table>
  <el-button v-if="nextId" :disabled="busy||changed" @click="load(true)">加载更多</el-button>
  <el-drawer v-model="open" title="任务运行详情" size="min(780px, 95vw)">
   <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
   <p v-if="pending">此操作结果待核对。<el-button :disabled="commandBusy" @click="checkReceipt">核对回执</el-button><el-button :disabled="commandBusy" @click="submitPending">使用原请求重试</el-button></p>
   <div v-loading="detailBusy">
    <template v-if="selected">
     <el-descriptions :column="1" border><el-descriptions-item label="任务">{{selected.title}}</el-descriptions-item><el-descriptions-item label="编号">{{selected.id}}</el-descriptions-item><el-descriptions-item label="负责人">{{selected.owner_name}}</el-descriptions-item><el-descriptions-item label="状态">{{label(selected.state)}}</el-descriptions-item><el-descriptions-item label="调度">{{selected.dispatch_paused?'后续调度已暂停':'允许后续调度'}}</el-descriptions-item></el-descriptions>
     <p class="hint">产出就绪不代表已通过业务验收。查看元数据不授予输入、执行结果或产物读取权限。</p>
     <p v-if="canOperate&&selected.state==='RUNNING'"><el-button v-for="action in ['PAUSE','RESUME','CANCEL']" :key="action" :disabled="commandBusy||!!pending||storageBlocked||batchBlocked" @click="prepare(action)">{{action==='PAUSE'?'暂停后续调度':action==='RESUME'?'恢复后续调度':'请求取消'}}</el-button></p>
     <p class="hint">部门和项目为有审计记录的业务归属，不代表创建时的身份部门或执行资源授权；历史未知归属不自动推测。</p>
     <el-button v-if="canConfigure" :disabled="commandBusy" @click="editContext">维护业务归属</el-button>
     <el-form v-if="editingContext" inline @submit.prevent="saveContext">
      <el-form-item label="部门"><el-select v-model="contextDraft.departmentId" clearable placeholder="归属未确认"><el-option v-for="item in assignmentOptions.departments" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
      <el-form-item label="项目"><el-select v-model="contextDraft.projectId" clearable placeholder="归属未确认"><el-option v-for="item in assignmentOptions.projects" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item><el-button native-type="submit" :disabled="commandBusy">保存归属</el-button>
      <el-button v-if="assignmentOptions.nextDepartment||assignmentOptions.nextProject" :disabled="commandBusy" @click="moreContextOptions">加载更多归属候选</el-button>
     </el-form>
     <h3>创建时归属</h3>
     <p v-if="!selected.creationAttribution?.length" class="hint">此历史任务没有创建时归属记录，不从当前部门反推。</p>
     <p v-for="item in selected.creationAttribution||[]" :key="item.captured_at" class="hint">部门：{{item.department_name||'创建时未确认'}} · 项目：{{item.project_name||'创建时未确认'}} · 记录于 {{time(item.captured_at)}}。{{item.department_basis==='LOCAL_MEMBER_ASSIGNMENT'?'部门来自创建时的本地成员配置。':''}}{{item.project_basis==='SPECIALIZED_TASK_RESOURCE'?'项目来自专用任务的业务资源。':''}}此记录不随后续归属维护更改。</p>
     <h3>执行终端</h3>
     <el-table :data="selected.placements?.items||[]" empty-text="尚无执行终端记录"><el-table-column label="终端" min-width="150"><template #default="{row}">{{row.device_name||row.node_name||'名称未记录'}}<small>{{row.device_id||row.node_id}}</small></template></el-table-column><el-table-column label="执行关系"><template #default="{row}">{{row.current_attempt?'当前执行':'历史尝试'}}</template></el-table-column><el-table-column label="尝试状态"><template #default="{row}">{{label(row.state)}}</template></el-table-column><el-table-column prop="epoch" label="执行代际" /><el-table-column label="写入锁"><template #default="{row}">{{row.writer_state==='UNKNOWN'?'停止未知，保留写入锁':row.writer_state==='HELD'?'已占用':row.writer_state==='RELEASED'?'已释放':row.writer_state==='NONE'?'无写入锁':'未记录'}}</template></el-table-column><el-table-column label="执行项目"><template #default="{row}">{{row.project_name||row.project_id||'未记录'}}</template></el-table-column><el-table-column label="资源池"><template #default="{row}">{{row.pool_name||row.pool_id||'未记录'}}</template></el-table-column></el-table>
     <el-button v-if="selected.placements?.hasMore" :disabled="detailBusy" @click="morePlacements">加载更多执行尝试</el-button>
     <h3>执行步骤</h3><el-table :data="selected.units"><el-table-column label="步骤序号"><template #default="{row}">{{row.ordinal+1}}</template></el-table-column><el-table-column label="状态"><template #default="{row}">{{label(row.state)}}</template></el-table-column><el-table-column prop="attempts" label="尝试次数" /></el-table>
     <TaskWorkflowPanel :key="selected.id" :task="selected.id" :can-maintain="canMaintain" :can-annual="canAnnual" @workbench="emit('workbench',$event)" @restricted="contentRestricted" />
     <TaskContentPanel :key="selected.id" :task="selected.id" :units="selected.units" :can-input="canInput" :can-artifacts="canArtifacts" @restricted="contentRestricted" />
     <h3>事件</h3><el-table :data="events"><el-table-column label="事件"><template #default="{row}">{{eventLabel(row.event_type)}}</template></el-table-column><el-table-column label="时间"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column></el-table><el-button v-if="eventCursor" :disabled="detailBusy" @click="moreEvents">加载后续事件</el-button>
    </template>
   </div>
  </el-drawer>
 </el-card>
</template>
<script setup lang="ts">
import {computed,reactive,ref,watch,onMounted,onBeforeUnmount,nextTick} from 'vue';
import {readPendingTaskCommand,savePendingTaskCommand,clearPendingTaskCommand} from '@/utils/pending-task-command';
import {ElMessageBox} from 'element-plus';
import TaskContentPanel from './TaskContentPanel.vue';
import TaskBatchPanel from './TaskBatchPanel.vue';
import TaskWorkflowPanel from './TaskWorkflowPanel.vue';
import {readTaskCenterContext,taskCenterContextKey,type TaskCenterContext} from '@/utils/task-center-context';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,BusinessError} from '@/utils/business-feedback';
const readError=(error:unknown)=>error instanceof BusinessError&&[401,403].includes(error.status||0)?businessErrorMessage(error):'本次读取未完成，请重新查询。';
const props=withDefaults(defineProps<{canOperate?:boolean;canConfigure?:boolean;canInput?:boolean;canArtifacts?:boolean;canMaintain?:boolean;canAnnual?:boolean;actorKey?:string;routeContext?:Record<string,unknown>}>(),{canOperate:false,canConfigure:false,canInput:false,canArtifacts:false,canMaintain:false,canAnnual:false,actorKey:''});
const emit=defineEmits<{context:[value:TaskCenterContext];workbench:[value:{kind:string;id:string}]}>();
type Row=Record<string,any>;
const scopeEpoch=ref(0),batchBlocked=ref(false),selection=ref<string[]>([]),table=ref<{clearSelection:()=>void}>();
function selectionChanged(items:Row[]){selection.value=items.map(row=>row.id);}
async function refreshAfterBatch(){const id=selected.value?.id;await load();if(id)await inspect(id);}
const contextOptions=ref<{departments:Row[];projects:Row[];nextDepartment?:string;nextProject?:string}>({departments:[],projects:[]}),assignmentOptions=ref<{departments:Row[];projects:Row[];nextDepartment?:string;nextProject?:string}>({departments:[],projects:[]});
const confirmedNotice=ref(false);
const uncertainCommand=ref(false),storageBlocked=ref(false),notice=ref(''),commandBusy=ref(false),editingContext=ref(false),contextDraft=reactive({departmentId:'',projectId:'',expectedVersion:0});
const pending=ref<{taskId:string;key:string;body:{action:string;expectedToken:string}}>();
const states:Record<string,string>={RUNNING:'进行中',SUCCEEDED:'已完成',FAILED:'失败',CANCELLED:'已取消',BLOCKED_AUTH:'授权中断',RECONCILING:'待核对',RECONCILED:'已核对',OUTPUT_READY:'产出待确认',PARTIAL_OUTPUT:'部分产出',PARTIALLY_SUCCEEDED:'部分完成'};
const label=(state:string)=>states[state]||({READY:'等待调度',RESERVED:'已分配',GENERATED:'已生成',WAITING_DEPENDENCY:'等待前置步骤',BLOCKED_DEPENDENCY:'前置步骤未完成'} as Record<string,string>)[state]||state;
const filter=reactive({query:'',owner:'',state:'',zone:'',capability:'',department:'',project:''}),dates=ref<number[]>([]);
const params=()=>({...filter,from:String(dates.value?.[0]||0),until:String(dates.value?.[1]||0)});
const rows=ref<Row[]>([]),summary=ref<Row[]>([]),zones=ref<string[]>([]),total=ref(0),observedAt=ref(0),busy=ref(false),error=ref(''),nextId=ref(''),nextAt=ref(0),scopeVersion=ref(0),applied=ref('');
watch(rows,()=>{selection.value=[];table.value?.clearSelection();});
const members=ref<Row[]>([]),membersBusy=ref(false),memberError=ref('');let memberGeneration=0;
const purpose=(value:string)=>({'model.read.v1':'模型生成','repository.maintain.v1':'代码维护','report.annual.v1':'年报编制'} as Record<string,string>)[value]||value;
const eventLabel=(value:string)=>({TASK_CREATED:'任务已创建',TASK_CANCELLED:'任务已取消',TASK_DISPATCH_PAUSED:'后续调度已暂停',TASK_DISPATCH_RESUMED:'后续调度已恢复',RESULT_ACCEPTED:'执行结果已确认',MODEL_OUTPUT_STORED:'模型产出已保存'} as Record<string,string>)[value]||value;
const open=ref(false),selected=ref<Row>(),events=ref<Row[]>([]),detailBusy=ref(false),detailError=ref(''),eventCursor=ref(0);
const changed=computed(()=>!!applied.value&&JSON.stringify(params())!==applied.value);
let alive=true,detailGeneration=0,dataGeneration=0,listGeneration=0,lastContext='';
let requestedTask='',restoringContext=false,contextGeneration=0;
function publishContext(){if(!applied.value)return;const context={...JSON.parse(applied.value),task:requestedTask} as TaskCenterContext;for(const key of ['from','until'] as const)if(context[key]==='0')context[key]='';lastContext=taskCenterContextKey(context);emit('context',context);}
async function restoreContext(){
 if(!props.routeContext)return;let context:TaskCenterContext;
 try{context=readTaskCenterContext(props.routeContext);}catch(e){contextGeneration++;restoringContext=true;requestedTask='';open.value=false;listGeneration++;busy.value=false;clearResults();error.value=(e as Error).message;await nextTick();restoringContext=false;return;}
 const key=taskCenterContextKey(context);if(key===lastContext)return;const run=++contextGeneration;lastContext=key;restoringContext=true;requestedTask='';open.value=false;detailGeneration++;selected.value=undefined;events.value=[];eventCursor.value=0;
 const {task,from,until,...values}=context;Object.assign(filter,values);dates.value=from||until?[Number(from||0),Number(until||0)]:[];
 try{await load(false,false);if(!alive||run!==contextGeneration||key!==lastContext)return;requestedTask=task;if(task)await inspect(task,false);}finally{if(run===contextGeneration)restoringContext=false;}
}
const time=(value:number)=>new Date(value).toLocaleString();
function contentRestricted(){clearResults();error.value='查看权限或任务范围已变化，请重新查询。';detailError.value=error.value;}
function clearResults(){notice.value=confirmedNotice.value?'上次操作已收到服务端确认；当前数据需重新查询。':'';zones.value=[];scopeEpoch.value++;dataGeneration++;contextOptions.value={departments:[],projects:[]};members.value=[];rows.value=[];summary.value=[];total.value=0;observedAt.value=0;nextId.value='';nextAt.value=0;selected.value=undefined;events.value=[];eventCursor.value=0;detailGeneration++;detailBusy.value=false;}
async function searchMembers(query=''){const generation=++memberGeneration,dataRun=dataGeneration;membersBusy.value=true;memberError.value='';try{const page=await request('/management-tasks/members?query='+encodeURIComponent(query));if(alive&&generation===memberGeneration&&dataRun===dataGeneration){if(observedAt.value&&scopeVersion.value!==Number(page.scopeVersion||0)){clearResults();memberError.value='管理范围已变化，请重新查询。';return;}members.value=page.items;if(page.hasMore)memberError.value='仅显示前 50 位，请输入更具体的姓名。';}}catch(e){if(alive&&generation===memberGeneration){members.value=[];clearResults();memberError.value=readError(e);}}finally{if(alive&&generation===memberGeneration)membersBusy.value=false;}}
async function load(more=false,publish=true){
 if(more&&(busy.value||changed.value))return;if(memberError.value)void searchMembers();busy.value=true;error.value='';const generation=dataGeneration,run=++listGeneration,snapshot=JSON.stringify(params());
 try{const q=new URLSearchParams({...JSON.parse(snapshot),limit:'50',before:more?String(nextAt.value):'0',beforeId:more?nextId.value:''});const page=await request('/management-tasks?'+q);if(!alive||generation!==dataGeneration||run!==listGeneration)return;
  if(more&&(JSON.stringify(zones.value)!==JSON.stringify(page.zones)||scopeVersion.value!==Number(page.scopeVersion||0))){clearResults();error.value='授权范围已变化，请重新查询。';return;}
  if(!more&&observedAt.value&&(scopeVersion.value!==Number(page.scopeVersion||0)||JSON.stringify(zones.value)!==JSON.stringify(page.zones))){clearResults();detailError.value='管理范围已变化，请重新打开任务详情。';}
  if(scopeVersion.value!==Number(page.scopeVersion||0))contextOptions.value={departments:[],projects:[]};scopeVersion.value=Number(page.scopeVersion||0);
  rows.value=more?[...rows.value,...page.items]:page.items;summary.value=page.summary;total.value=page.total;zones.value=page.zones;observedAt.value=page.observedAt;nextId.value=page.nextId||'';nextAt.value=page.nextCreatedAt||0;applied.value=snapshot;if(publish)publishContext();
 }catch(e){if(alive&&run===listGeneration){clearResults();error.value=readError(e);}}finally{if(alive&&run===listGeneration)busy.value=false;}
}
function reset(){Object.assign(filter,{query:'',owner:'',state:'',zone:'',capability:'',department:'',project:''});dates.value=[];void load();}
async function inspect(id:string,publish=true){
 requestedTask=id;if(publish)publishContext();
 const generation=++detailGeneration;editingContext.value=false;open.value=true;selected.value=undefined;events.value=[];eventCursor.value=0;detailError.value='';detailBusy.value=true;
 try{const [task,page]=await Promise.all([request('/management-tasks/'+encodeURIComponent(id)),request('/management-tasks/'+encodeURIComponent(id)+'/events')]);if(alive&&generation===detailGeneration){selected.value=task;events.value=page.items;eventCursor.value=page.nextCursor||0;}}
 catch(e){if(alive&&generation===detailGeneration){clearResults();detailError.value=readError(e);}}
 finally{if(alive&&generation===detailGeneration)detailBusy.value=false;}
}
async function morePlacements(){if(!selected.value||detailBusy.value)return;const id=selected.value.id,run=detailGeneration,page=selected.value.placements;detailBusy.value=true;try{const value=await request('/management-tasks/'+id+'/placements?before='+page.nextCreatedAt+'&beforeId='+encodeURIComponent(page.nextId));if(!alive||run!==detailGeneration)return;if(Number(value.scopeVersion||0)!==Number(selected.value?.scopeVersion||0)){clearResults();detailError.value='管理范围已变化，请重新查询。';return;}selected.value!.placements={...value,items:[...page.items,...value.items]};}catch(e){if(alive&&run===detailGeneration){clearResults();detailError.value=readError(e);}}finally{if(alive&&run===detailGeneration)detailBusy.value=false;}}
async function moreEvents(){
 if(!selected.value||detailBusy.value)return;const id=selected.value.id,generation=detailGeneration;detailBusy.value=true;
 try{const page=await request('/management-tasks/'+encodeURIComponent(id)+'/events?after='+eventCursor.value);if(alive&&generation===detailGeneration){events.value.push(...page.items);eventCursor.value=page.nextCursor||0;}}
 catch(e){if(alive&&generation===detailGeneration){clearResults();detailError.value=readError(e);}}finally{if(alive&&generation===detailGeneration)detailBusy.value=false;}
}
watch(open,value=>{if(!value){if(!restoringContext){requestedTask='';publishContext();}detailGeneration++;selected.value=undefined;events.value=[];detailBusy.value=false;}});
onMounted(()=>{if(props.actorKey)try{pending.value=readPendingTaskCommand(props.actorKey);uncertainCommand.value=!!pending.value;}catch{storageBlocked.value=true;notice.value='待核对记录无法读取，请先恢复该记录，再提交新操作。';}if(props.routeContext)void restoreContext();else void load();void searchMembers();void loadOptions();});onBeforeUnmount(()=>{alive=false;detailGeneration++;memberGeneration++;});
watch(()=>props.routeContext,()=>{void restoreContext();},{deep:true});
async function loadOptions(){const run=dataGeneration;try{const value=await request('/management-tasks/options');if(alive&&run===dataGeneration){if(observedAt.value&&scopeVersion.value!==Number(value.scopeVersion||0)){clearResults();error.value='管理范围已变化，请重新查询。';return;}contextOptions.value=value;}}catch(e){if(alive&&run===dataGeneration){clearResults();error.value=readError(e);}}}
async function moreFilterOptions(){const run=dataGeneration,previous=contextOptions.value;try{const value=await request('/management-tasks/options?departmentAfter='+encodeURIComponent(previous.nextDepartment||'__END__')+'&projectAfter='+encodeURIComponent(previous.nextProject||'__END__'));if(!alive||run!==dataGeneration)return;if(scopeVersion.value!==Number(value.scopeVersion||0)){clearResults();error.value='管理范围已变化，请重新查询。';return;}for(const kind of ['departments','projects'] as const){const rows=new Map(previous[kind].map(item=>[item.id,item]));for(const item of value[kind])rows.set(item.id,item);value[kind]=[...rows.values()];}contextOptions.value=value;}catch(e){if(alive&&run===dataGeneration){clearResults();error.value=readError(e);}}}
async function editContext(){if(!selected.value||!props.canConfigure)return;const id=selected.value.id,run=detailGeneration;try{const value=await request('/management-tasks/'+id+'/context-options');if(!alive||run!==detailGeneration||selected.value?.id!==id)return;assignmentOptions.value=value;Object.assign(contextDraft,{departmentId:selected.value.department_id||'',projectId:selected.value.project_id||'',expectedVersion:selected.value.context_version||0});editingContext.value=true;}catch(e){detailError.value=readError(e);}}
async function moreContextOptions(){if(!selected.value)return;const id=selected.value.id,run=detailGeneration,previous=assignmentOptions.value;try{const value=await request('/management-tasks/'+id+'/context-options?departmentAfter='+encodeURIComponent(previous.nextDepartment||'__END__')+'&projectAfter='+encodeURIComponent(previous.nextProject||'__END__'));if(!alive||run!==detailGeneration)return;for(const kind of ['departments','projects'] as const){const rows=new Map(previous[kind].map(item=>[item.id,item]));for(const item of value[kind])rows.set(item.id,item);value[kind]=[...rows.values()];}assignmentOptions.value=value;}catch(e){detailError.value=readError(e);}}
async function saveContext(){if(!selected.value||commandBusy.value)return;commandBusy.value=true;confirmedNotice.value=false;notice.value='';const id=selected.value.id;try{await request('/management-tasks/'+id+'/context','PUT',{...contextDraft});confirmedNotice.value=true;notice.value='业务归属已保存。';await load();await inspect(id);await loadOptions();}catch(e){detailError.value=businessErrorMessage(e);}finally{commandBusy.value=false;}}
async function prepare(action:string){if(!selected.value||commandBusy.value||pending.value||storageBlocked.value||batchBlocked.value)return;commandBusy.value=true;confirmedNotice.value=false;notice.value='';detailError.value='';const id=selected.value.id;try{const preview=await request('/management-tasks/'+id+'/preview','POST',{action});try{await ElMessageBox.confirm(preview.task.title+'：'+preview.effect,'确认管理操作',{confirmButtonText:'确认提交',cancelButtonText:'返回核对'});}catch{return;}const value={taskId:id,key:crypto.randomUUID(),body:{action,expectedToken:preview.expectedToken}};savePendingTaskCommand(props.actorKey,value);uncertainCommand.value=false;pending.value=value;}catch(e){detailError.value=businessErrorMessage(e);}finally{commandBusy.value=false;}if(pending.value)await submitPending();}
async function completed(id:string){const action=pending.value?.body.action,refresh=!selected.value||selected.value.id===id,caption=selected.value?.id===id?selected.value.title:'此前提交的任务';if(pending.value)clearPendingTaskCommand(props.actorKey,pending.value.key);pending.value=undefined;uncertainCommand.value=false;confirmedNotice.value=true;notice.value=caption+'：'+(action==='PAUSE'?'已暂停后续调度，正在执行的步骤可继续完成。':action==='RESUME'?'已恢复后续调度，仍需通过资源和授权检查。':'取消请求已记录；实际终端停止与外部副作用仍需核对。');await load();if(refresh)await inspect(id);}
async function submitPending(){if(!pending.value||commandBusy.value)return;commandBusy.value=true;const value=pending.value;try{await request('/management-tasks/'+value.taskId+'/commands','POST',value.body,value.key);await completed(value.taskId);}catch(e){if(!uncertainCommand.value&&e instanceof BusinessError&&e.status&&e.status>=400&&e.status<500&&e.status!==408){clearPendingTaskCommand(props.actorKey,value.key);pending.value=undefined;notice.value='本次操作已被拒绝，请刷新并重新预览。';}else{uncertainCommand.value=true;notice.value='操作结果尚未确认，请核对回执或使用原请求重试。';}detailError.value=businessErrorMessage(e);}finally{commandBusy.value=false;}}
async function checkReceipt(){if(!pending.value||commandBusy.value)return;commandBusy.value=true;const value=pending.value;try{await request('/management-tasks/'+value.taskId+'/commands/'+value.key);await completed(value.taskId);}catch(e){notice.value='尚未确认回执；请保留原请求，稍后核对或原请求重试。';}finally{commandBusy.value=false;}}
defineExpose({inspect});
</script>
<style scoped>
.task-center-panel{--el-color-primary:var(--fw-accent)}.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.metrics{display:flex;flex-wrap:wrap;gap:12px;margin:16px 0}.metrics>div{display:flex;flex-direction:column;gap:6px;padding:12px 20px;border:1px solid var(--el-border-color);border-radius:8px}.metrics strong{font-size:24px}.el-alert{margin:12px 0}.el-descriptions{overflow-wrap:anywhere}
</style>
