<template>
 <el-card shadow="never" class="scope-settings">
  <template #header><strong>任务管理范围</strong></template>
  <p class="hint">这是组织任务的业务部门与项目范围，和身份平台的当前部门、执行资源授权分别维护。受限管理员不能修改范围配置；限定为空表示没有可见任务，不代表全部。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-button :disabled="busy" @click="run(loadOptions)">刷新配置</el-button>
  <el-form inline @submit.prevent="createDepartment"><el-form-item label="业务部门"><el-input v-model="department.name" maxlength="120" /></el-form-item><el-form-item label="区域"><el-select v-model="department.zone" placeholder="选择区域"><el-option v-for="zone in options.zones" :key="zone" :value="zone" :label="zone" /></el-select></el-form-item><el-button native-type="submit" :disabled="busy">创建业务部门</el-button></el-form>
  <el-divider />
  <el-form inline @submit.prevent="searchMembers(false)"><el-form-item label="管理员"><el-input v-model="query" placeholder="按显示名称搜索" maxlength="120" /></el-form-item><el-button native-type="submit" :disabled="busy">搜索</el-button><el-button v-if="cursor" :disabled="busy" @click="searchMembers(true)">加载更多</el-button></el-form>
  <el-select aria-label="范围管理员" v-model="owner" placeholder="选择需配置范围的管理员" :disabled="busy" @change="loadPolicy"><el-option v-for="item in members" :key="item.id" :value="item.id" :label="item.display_name" /></el-select>
  <el-form v-if="policy" label-position="top" :disabled="busy" @submit.prevent="savePolicy">
   <el-form-item label="部门范围"><el-switch v-model="policy.restrictDepartments" active-text="仅限所选部门" inactive-text="全部授权区域内部门及未知归属" /></el-form-item>
   <el-form-item v-if="policy.restrictDepartments" label="选择业务部门"><el-select v-model="policy.departments" :multiple-limit="100" multiple filterable placeholder="选择业务部门"><el-option v-for="item in options.departments" :key="item.id" :value="item.id" :label="item.name+' · '+item.zone" /></el-select></el-form-item>
   <el-form-item label="项目范围"><el-switch v-model="policy.restrictProjects" active-text="仅限所选项目" inactive-text="全部授权区域内项目及未知归属" /></el-form-item>
   <el-form-item v-if="policy.restrictProjects" label="选择项目"><el-select v-model="policy.projects" :multiple-limit="100" multiple filterable placeholder="选择项目"><el-option v-for="item in options.projects" :key="item.id" :value="item.id" :label="item.name+' · '+item.zone" /></el-select></el-form-item>
   <p class="hint">部门与项目条件同时满足才可见，且始终受租户与区域限制。此操作不会授予业务角色或菜单权限。</p>
   <p class="hint">每种范围最多选择 100 项；候选目录可继续加载。</p><el-button v-if="options.nextDepartment||options.nextProject" @click="moreOptions" :disabled="busy">加载更多部门和项目候选</el-button>
   <el-button native-type="submit" >预览并保存范围</el-button>
  </el-form>
  <TaskContentAccess v-if="policy&&owner" :principal="owner" :name="members.find(row=>row.id===owner)?.display_name||owner" />
 </el-card>
 <MemberTaskAttribution />
</template>
<script setup lang="ts">
import {ref,reactive,onMounted,onBeforeUnmount} from 'vue';
import MemberTaskAttribution from './MemberTaskAttribution.vue';
import TaskContentAccess from './TaskContentAccess.vue';
import {ElMessageBox} from 'element-plus';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
type Row=Record<string,any>;
type ScopePolicy={version:number;restrictDepartments:boolean;departments:string[];restrictProjects:boolean;projects:string[]};
const options=ref<{zones:string[];departments:Row[];projects:Row[];nextDepartment?:string;nextProject?:string}>({zones:[],departments:[],projects:[]}),members=ref<Row[]>([]),owner=ref(''),policy=ref<ScopePolicy>(),query=ref(''),cursor=ref(''),busy=ref(false),error=ref(''),notice=ref(''),department=reactive({name:'',zone:''});let alive=true;
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await work();}catch(e){if(alive){policy.value=undefined;members.value=[];options.value={zones:[],departments:[],projects:[]};error.value=businessErrorMessage(e);}}finally{if(alive)busy.value=false;}}
async function loadOptions(){const value=await request('/management-tasks/scope-options');if(alive)options.value=value;}
async function moreOptions(){await run(async()=>{const previous=options.value;const value=await request('/management-tasks/scope-options?departmentAfter='+encodeURIComponent(previous.nextDepartment||'__END__')+'&projectAfter='+encodeURIComponent(previous.nextProject||'__END__'));if(alive){for(const kind of ['departments','projects'] as const){const rows=new Map(previous[kind].map(item=>[item.id,item]));for(const row of value[kind])rows.set(row.id,row);value[kind]=[...rows.values()];}options.value=value;}});}
async function searchMembers(more=false){await run(async()=>{const value=await request('/management-tasks/scope-members?query='+encodeURIComponent(query.value)+'&after='+encodeURIComponent(more?cursor.value:''));if(alive){members.value=more?[...members.value,...value.items]:value.items;cursor.value=value.nextCursor;}});}
async function loadPolicy(){await run(async()=>{policy.value=undefined;const value=await request('/management-tasks/scopes/'+encodeURIComponent(owner.value));if(alive)policy.value=value;});}
async function createDepartment(){await run(async()=>{await request('/management-tasks/departments','POST',{...department});notice.value='业务部门已创建。';department.name='';await loadOptions();});}
async function savePolicy(){if(!policy.value)return;const target=owner.value,body={restrictDepartments:policy.value.restrictDepartments,departments:[...policy.value.departments],restrictProjects:policy.value.restrictProjects,projects:[...policy.value.projects],expectedVersion:policy.value.version};const names=(ids:string[],items:Row[])=>ids.map(id=>items.find(item=>item.id===id)?.name||id).join('、')||'空范围（不可见）';const preview='管理员：'+(members.value.find(item=>item.id===target)?.display_name||target)+'；部门：'+(body.restrictDepartments?names(body.departments,options.value.departments):'全部授权区域')+'；项目：'+(body.restrictProjects?names(body.projects,options.value.projects):'全部授权区域')+'。限定为空时无法查看任何任务；修改自己的范围后可能需要另一位管理员恢复。是否继续？';try{await ElMessageBox.confirm(preview,'确认管理范围',{confirmButtonText:'保存范围',cancelButtonText:'继续编辑'});}catch{return;}await run(async()=>{await request('/management-tasks/scopes/'+encodeURIComponent(target),'PUT',body);notice.value='范围已保存；后续请求立即按新范围检查。';const value=await request('/management-tasks/scopes/'+encodeURIComponent(target));if(alive)policy.value=value;});}
onMounted(()=>void run(loadOptions));onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>.scope-settings{--el-color-primary:var(--fw-accent)}:global(html.dark) .scope-settings :deep(.el-switch){--el-switch-on-color:#736e66}.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.el-alert{margin-bottom:12px}.el-select{min-width:240px}</style>
