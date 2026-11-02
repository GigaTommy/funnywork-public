<template>
 <section class="content-access">
  <h3>任务内容读取授权</h3>
  <p class="hint">查看任务元数据不会开放成员输入或文件。以下授权还需对应菜单权限，并始终受任务管理范围限制；每次读取留有审计。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-button :disabled="busy" @click="load">重新读取内容授权</el-button>
  <el-form v-if="policy" :disabled="busy" label-position="top">
   <el-form-item label="成员输入"><el-switch v-model="policy.readInput" active-text="允许读取任务输入" inactive-text="不允许" /></el-form-item>
   <el-form-item label="附件与模型产出"><el-switch v-model="policy.readArtifacts" active-text="允许读取文件" inactive-text="不允许" /></el-form-item>
   <el-button :disabled="busy||uncertain" @click="save">预览并保存内容授权</el-button>
  </el-form>
 </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue';
import {ElMessageBox} from 'element-plus';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
const props=defineProps<{principal:string;name:string}>();
const policy=ref<{readInput:boolean;readArtifacts:boolean;version:number}>(),busy=ref(false),uncertain=ref(false),error=ref(''),notice=ref('');let generation=0,alive=true;
async function load(){const run=++generation;policy.value=undefined;busy.value=true;error.value='';notice.value='';try{const value=await request('/management-tasks/content-access/'+encodeURIComponent(props.principal));if(alive&&run===generation){policy.value=value;uncertain.value=false;}}catch(e){if(alive&&run===generation)error.value=businessErrorMessage(e);}finally{if(alive&&run===generation)busy.value=false;}}
async function save(){if(!policy.value||busy.value||uncertain.value)return;const target=props.principal,run=generation,body={readInput:policy.value.readInput,readArtifacts:policy.value.readArtifacts,expectedVersion:policy.value.version};try{await ElMessageBox.confirm(props.name+'：任务输入'+(body.readInput?'允许读取':'不允许读取')+'；附件及模型产出'+(body.readArtifacts?'允许读取':'不允许读取')+'。授权仅适用于当前可管理任务，实际读取将记入审计。','确认内容读取授权',{confirmButtonText:'保存授权',cancelButtonText:'继续编辑'});}catch{return;}if(run!==generation)return;busy.value=true;error.value='';try{await request('/management-tasks/content-access/'+encodeURIComponent(target),'PUT',body);if(alive&&run===generation){policy.value!.version=body.expectedVersion+1;notice.value='内容授权已保存；不改变任务范围或执行授权。';}}catch(e){if(alive&&run===generation){uncertain.value=true;error.value=businessErrorMessage(e);notice.value='请重新读取当前授权后再提交。';}}finally{if(alive&&run===generation)busy.value=false;}}
watch(()=>props.principal,()=>void load(),{immediate:true});onBeforeUnmount(()=>{alive=false;generation++;});
</script>
<style scoped>.content-access{margin-top:24px}.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.el-alert{margin-bottom:12px}.el-switch{--el-switch-on-color:#736e66}</style>
