<template><div class="app-container"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="notice" :title="notice" type="info" :closable="false"/><el-card><template #header><div class="toolbar"><strong>员工业务授权</strong><div><el-button @click="load">刷新</el-button><el-button v-hasPermi="['funnyworker:authorization:write']" type="primary" :disabled="!canManage" @click="openEditor">配置授权</el-button></div></div></template><el-select v-model="statusFilter" aria-label="授权状态" class="status-filter"><el-option label="全部授权状态" value="all"/><el-option label="已授权" value="enabled"/><el-option label="已撤销" value="revoked"/></el-select><el-table :data="visibleRows" v-loading="busy" :empty-text="me?'暂无授权记录':'本次查询未完成，请刷新重试。'"><el-table-column prop="ruoyi_user" label="用户编号"/><el-table-column prop="display_name" label="账号"/><el-table-column label="业务角色"><template #default="{row}">{{displayRoles(row.roles)}}</template></el-table-column><el-table-column prop="zones" label="授权区域"/><el-table-column label="状态"><template #default="{row}">{{row.enabled?'已授权':'已撤销'}}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button v-hasPermi="['funnyworker:authorization:write']" text :disabled="row.id===me?.principalId" @click="editBinding(row)">{{row.enabled?'编辑':'重新授权'}}</el-button><el-button v-hasPermi="['funnyworker:authorization:write']" text type="danger" :disabled="row.id===me?.principalId||!row.enabled" @click="revoke(row)">撤销</el-button></template></el-table-column></el-table></el-card><el-dialog v-model="editing" title="配置业务授权" width="min(560px, 95vw)" :before-close="beforeEditorClose" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-form label-width="90px" :disabled="busy||targetLoading||!canManage"><el-form-item label="员工账号"><el-select v-model="form.userId" filterable remote :remote-method="searchUsers" :loading="usersLoading" placeholder="输入账号搜索" style="width:100%"><el-option v-for="user in users" :key="user.userId" :value="String(user.userId)" :label="user.nickName+' · '+user.userName"/></el-select><el-button v-if="users.length<userTotal" :loading="usersLoading" @click="loadUsers(true)">加载更多账号</el-button></el-form-item><el-form-item label="权限模板"><el-select v-model="templateId" placeholder="选择模板后可调整" @change="applyTemplate"><el-option v-for="item in templates" :key="item.id" :value="item.id" :label="item.label"/></el-select></el-form-item><el-form-item label="业务角色"><el-checkbox-group v-model="form.roles"><el-checkbox v-for="role in me?.roles||[]" :key="role" :value="role">{{names[role]||role}}</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="授权区域"><el-checkbox-group v-model="form.zones"><el-checkbox v-for="zone in me?.zones||[]" :key="zone" :value="zone">{{zone}}</el-checkbox></el-checkbox-group></el-form-item></el-form><template #footer><el-button :disabled="busy" @click="closeEditor">取消</el-button><el-button type="primary" :loading="busy" :disabled="!canManage||targetLoading||!form.userId||!form.roles.length||!form.zones.length" @click="save">保存</el-button></template></el-dialog><IdentitySources /></div></template>
<script setup lang="ts">
import {getUser,listUser} from '@/api/system/user';
import IdentitySources from './IdentitySources.vue';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,businessAccessLost,saveAndRefresh} from '@/utils/business-feedback';
const notice=ref(''),templateId=ref(''),targetLoading=ref(false);
let targetRun=0,targetKey='';
const route=useRoute();
const users=ref<any[]>([]),usersLoading=ref(false),userTotal=ref(0),statusFilter=ref('all');
let userPage=1,userQuery='',userRequest=0;
const visibleRows=computed(()=>rows.value.filter(row=>statusFilter.value==='all'||row.enabled===(statusFilter.value==='enabled')));
const rows=ref<any[]>([]),me=ref<any>(),busy=ref(false),editing=ref(false),error=ref('');const form=reactive({userId:'',roles:['VIEWER'],zones:[] as string[]});
const canManage=computed(()=>Boolean(me.value?.roles?.includes('ADMIN')));
let editorBaseline='',confirmingClose=false;
function editorSnapshot(){return JSON.stringify({userId:form.userId,roles:[...form.roles].sort(),zones:[...form.zones].sort()});}
async function beforeEditorClose(done:()=>void){
 if(busy.value||confirmingClose)return;
 if(editorSnapshot()!==editorBaseline){
  confirmingClose=true;
  try{await ElMessageBox.confirm('关闭将放弃本页草稿，不会撤销服务器已保存的授权。是否关闭？','关闭授权表单',{confirmButtonText:'关闭表单',cancelButtonText:'继续编辑',type:'warning'});}
  catch{return;}
  finally{confirmingClose=false;}
 }
 done();
}
function closeEditor(){void beforeEditorClose(()=>{editing.value=false;});}
const names:Record<string,string>={ADMIN:'业务管理员',OPERATOR:'任务操作员',VIEWER:'业务查看员',RECONCILER:'恢复审核员'};
const templates=computed(()=>[{id:'viewer',label:'只读查看',roles:['VIEWER']},{id:'operator',label:'任务操作与查看',roles:['OPERATOR','VIEWER']},{id:'reviewer',label:'恢复审核与查看',roles:['RECONCILER','VIEWER']}].filter(item=>item.roles.every(role=>me.value?.roles?.includes(role))));
watch(()=>[...form.roles],roles=>{const chosen=templates.value.find(item=>item.id===templateId.value);if(chosen&&(chosen.roles.length!==roles.length||!chosen.roles.every(role=>roles.includes(role))))templateId.value='';});
function applyTemplate(id:string){const template=templates.value.find(item=>item.id===id);if(template)form.roles=[...template.roles];}
function displayRoles(raw:string){try{return JSON.parse(raw).map((x:string)=>names[x]||x).join('、');}catch{return '';}}
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await work();}catch(e){clearLostAccess(e);error.value=businessErrorMessage(e);}finally{busy.value=false;}}
function clearLostAccess(error:unknown){
 if(!businessAccessLost(error))return;
 rows.value=[];me.value=undefined;editing.value=false;users.value=[];userTotal.value=0;
 form.userId='';form.roles=[];form.zones=[];templateId.value='';
 userRequest++;targetRun++;targetKey='';usersLoading.value=false;targetLoading.value=false;
}
async function readRows(){
 try{const [data,identity]=await Promise.all([request('/management-bindings'),request('/me')]);rows.value=data;me.value=identity;}
 catch(e){rows.value=[];me.value=undefined;clearLostAccess(e);throw e;}
}
async function load(){await run(readRows);}
async function mutate(write:()=>Promise<void>){await run(async()=>{notice.value=await saveAndRefresh(write,readRows);});}
async function loadUsers(more=false){
 const requestId=++userRequest;usersLoading.value=true;
 const page=more?userPage+1:1;
 try{const response=await listUser({pageNum:page,pageSize:30,status:'0',userName:userQuery});if(requestId!==userRequest)return;users.value=more?[...users.value,...response.rows]:response.rows;userTotal.value=response.total;userPage=page;}
 catch{if(requestId===userRequest)error.value='账号读取失败，请重试。';}
 finally{if(requestId===userRequest)usersLoading.value=false;}
}
function searchUsers(query:string){userQuery=query;users.value=[];userTotal.value=0;void loadUsers();}
async function openEditor(){if(busy.value||!canManage.value)return;error.value='';templateId.value='';form.userId='';form.roles=['VIEWER'];form.zones=[];users.value=[];userQuery='';editorBaseline=editorSnapshot();editing.value=true;await loadUsers();}
function editBinding(row:any){if(busy.value||!canManage.value)return;error.value='';templateId.value='';userRequest++;usersLoading.value=false;userPage=1;userQuery='';form.userId=String(row.ruoyi_user);form.roles=JSON.parse(row.roles);form.zones=JSON.parse(row.zones);users.value=[{userId:row.ruoyi_user,nickName:row.display_name,userName:row.display_name}];userTotal.value=1;editorBaseline=editorSnapshot();editing.value=true;}

async function save(){if(!canManage.value||targetLoading.value)return;await mutate(async()=>{await request('/management-bindings','PUT',form);editing.value=false;});}
async function revoke(row:any){if(!canManage.value)return;try{await ElMessageBox.confirm('撤销后该账号将无法继续访问业务数据，是否确认？','撤销授权',{type:'warning'});}catch{return;}if(!canManage.value)return;await mutate(async()=>{await request('/management-bindings/'+encodeURIComponent(row.ruoyi_user),'DELETE');});}
watch(editing,(open)=>{if(!open){userRequest++;targetRun++;usersLoading.value=false;targetLoading.value=false;}});
async function openRequestedTarget(){
 if(route.path!=='/funnyworker/authorization'){targetRun++;targetKey='';targetLoading.value=false;return;}
 const id=String(route.query.userId||'');
 if(busy.value||!me.value||!/^[0-9]{1,24}$/.test(id)||targetKey===id)return;
 targetKey=id;const run=++targetRun;targetLoading.value=true;
 await openEditor();
 try{if(run!==targetRun||!editing.value)return;const response=await getUser(id);if(run!==targetRun||!editing.value||String(route.query.userId||'')!==id)return;users.value=[response.data.user];userTotal.value=1;form.userId=id;editorBaseline=editorSnapshot();}
 catch{if(run===targetRun&&editing.value)error.value='目标账号读取失败，请搜索选择账号。';}
 finally{if(run===targetRun)targetLoading.value=false;}
}
watch(()=>[route.path,route.query.userId,busy.value],()=>{void openRequestedTarget();});
onMounted(async()=>{await load();await openRequestedTarget();});
onActivated(async()=>{await load();await openRequestedTarget();});
onBeforeUnmount(()=>{userRequest++;targetRun++;});
</script><style scoped>.toolbar{display:flex;justify-content:space-between;align-items:center}.el-alert{margin-bottom:16px}.status-filter{margin-bottom:16px;width:180px}</style>
