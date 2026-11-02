<template>
 <el-card shadow="never" class="member-attribution">
  <template #header><strong>成员任务归属</strong></template>
  <p class="hint">为本地成员设置各区域的新任务所属部门，无需先接入统一身份平台。保存只影响之后创建的任务，历史任务与执行资源授权保持独立。</p>
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-form inline @submit.prevent="search(false)"><el-form-item label="成员名称"><el-input v-model="query" maxlength="120" /></el-form-item><el-button native-type="submit" :disabled="busy">搜索成员</el-button><el-button v-if="cursor" :disabled="busy" @click="search(true)">更多成员</el-button></el-form>
  <el-form label-position="top" :disabled="busy">
   <el-form-item label="成员"><el-select aria-label="任务归属成员" v-model="owner" filterable @change="selectMember" placeholder="选择成员"><el-option v-for="row in members" :key="row.id" :value="row.id" :label="row.display_name" /></el-select></el-form-item>
   <el-form-item v-if="owner" label="任务区域"><el-select aria-label="任务归属区域" v-model="zone" @change="loadAssignment"><el-option v-for="value in selectedMember?.zones||[]" :key="value" :value="value" :label="value" /></el-select></el-form-item>
   <template v-if="assignment">
    <el-form-item label="新任务所属部门"><el-select aria-label="新任务所属部门" v-model="assignment.departmentId" clearable placeholder="归属未确认"><el-option v-for="row in departments.filter(row=>row.zone===zone)" :key="row.id" :value="row.id" :label="row.name" /></el-select></el-form-item>
    <el-button v-if="nextDepartment" :disabled="busy" @click="moreDepartments">更多部门候选</el-button>
    <el-button :disabled="busy||uncertain" @click="save">预览并保存成员归属</el-button>
    <el-button :disabled="busy" @click="loadAssignment">重新读取已保存归属</el-button>
   </template>
  </el-form>
 </el-card>
</template>
<script setup lang="ts">
import {ref,computed,onBeforeUnmount} from 'vue';
import {ElMessageBox} from 'element-plus';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,BusinessError} from '@/utils/business-feedback';
type Member={id:string;display_name:string;zones:string[]};type Department={id:string;name:string;zone:string};
const members=ref<Member[]>([]),departments=ref<Department[]>([]),owner=ref(''),zone=ref(''),query=ref(''),cursor=ref(''),nextDepartment=ref(''),assignment=ref<{departmentId:string;version:number}>(),busy=ref(false),uncertain=ref(false),error=ref(''),notice=ref('');let alive=true;
const selectedMember=computed(()=>members.value.find(row=>row.id===owner.value));
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';try{await work();}catch(e){if(alive){error.value=businessErrorMessage(e);if(e instanceof BusinessError&&[401,403].includes(e.status||0)){assignment.value=undefined;members.value=[];departments.value=[];owner.value='';zone.value='';}}}finally{if(alive)busy.value=false;}}
async function search(more=false){await run(async()=>{const value=await request('/management-tasks/attribution-members?query='+encodeURIComponent(query.value)+'&after='+encodeURIComponent(more?cursor.value:''));if(alive){members.value=more?[...members.value,...value.items]:value.items;cursor.value=value.nextCursor;if(!members.value.some(row=>row.id===owner.value)){owner.value='';zone.value='';assignment.value=undefined;}}});}
async function selectMember(){zone.value=selectedMember.value?.zones[0]||'';assignment.value=undefined;await loadAssignment();}
async function loadAssignment(){if(!owner.value||!zone.value)return;assignment.value=undefined;await run(async()=>{const [value,options]=await Promise.all([request('/management-tasks/member-contexts/'+encodeURIComponent(owner.value)+'?zone='+encodeURIComponent(zone.value)),request('/management-tasks/scope-options')]);if(alive){assignment.value=value;departments.value=options.departments;if(value.departmentId&&!departments.value.some(row=>row.id===value.departmentId))departments.value.push({id:value.departmentId,name:value.departmentName||'已保存部门',zone:zone.value});nextDepartment.value=options.nextDepartment;uncertain.value=false;notice.value='已重新读取当前归属；之后创建的任务将记录该部门。';}});}
async function moreDepartments(){await run(async()=>{const value=await request('/management-tasks/scope-options?departmentAfter='+encodeURIComponent(nextDepartment.value)+'&projectAfter=__END__');if(alive){departments.value.push(...value.departments);nextDepartment.value=value.nextDepartment;}});}
async function save(){if(!assignment.value||busy.value||uncertain.value)return;const target=owner.value,body={zone:zone.value,departmentId:assignment.value.departmentId||'',expectedVersion:assignment.value.version};const name=departments.value.find(row=>row.id===body.departmentId)?.name||body.departmentId||'归属未确认';try{await ElMessageBox.confirm((selectedMember.value?.display_name||target)+' · '+body.zone+'：之后创建的任务归属为“'+name+'”。历史任务不会自动更改。','确认成员任务归属',{confirmButtonText:'保存归属',cancelButtonText:'继续编辑'});}catch{return;}await run(async()=>{try{await request('/management-tasks/member-contexts/'+encodeURIComponent(target),'PUT',body);if(alive){assignment.value!.version=body.expectedVersion+1;notice.value='成员归属已保存；历史任务与既有创建快照不变。';}}catch(e){if(alive){uncertain.value=true;notice.value='保存结果尚需核对，请重新读取已保存归属后再提交。';}throw e;}});}
onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>.member-attribution{margin-top:20px;--el-color-primary:var(--fw-accent)}.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.el-select{min-width:240px}.el-alert{margin-bottom:12px}</style>
