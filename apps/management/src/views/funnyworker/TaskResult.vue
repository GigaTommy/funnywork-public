<template>
 <section>
  <h4>{{description.title}}</h4><p v-if="description.note" class="hint">{{description.note}}</p>
  <el-descriptions v-if="description.fields.length" :column="1" border><el-descriptions-item v-for="field in description.fields" :key="field[0]" :label="field[0]">{{field[1]}}</el-descriptions-item></el-descriptions>
  <template v-if="description.kind==='model'">
   <el-button :loading="busy" @click="load">{{loaded?'重新读取原始产出':'读取原始产出'}}</el-button>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <pre v-if="loaded" class="result-body">{{text||'（内容为空）'}}</pre>
  </template>
  <pre v-else-if="description.kind==='text'" class="result-body">{{value}}</pre>
  <details class="technical"><summary>技术明细</summary><pre class="result-body">{{JSON.stringify(value,null,2)}}</pre></details>
 </section>
</template>
<script setup lang="ts">
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
import {describeTaskResult,decodeTaskText} from '@/utils/task-result';
const props=defineProps<{taskId:string;unitId:string;value:unknown}>();
const description=computed(()=>describeTaskResult(props.value));
const busy=ref(false),loaded=ref(false),text=ref(''),error=ref('');let generation=0;
watch(()=>[props.taskId,props.unitId,props.value],()=>{generation++;loaded.value=false;text.value='';error.value='';busy.value=false;});
onBeforeUnmount(()=>{generation++;});
async function load(){
 if(busy.value)return;busy.value=true;error.value='';const requestGeneration=++generation;
 // Clear old content before a new authority check, including on access revocation.
 loaded.value=false;text.value='';
 try{const data=await businessRequest('/tasks/'+encodeURIComponent(props.taskId)+'/units/'+encodeURIComponent(props.unitId)+'/result-content');const content=decodeTaskText(data.contentBase64);if(requestGeneration===generation){text.value=content;loaded.value=true;}}
 catch(e){if(requestGeneration===generation)error.value=businessErrorMessage(e);}
 finally{if(requestGeneration===generation)busy.value=false;}
}
</script>
<style scoped>.hint{color:var(--el-text-color-secondary);font-size:13px}.result-body{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:12px;border-radius:6px;max-height:60vh;overflow:auto}.technical{margin-top:16px}.technical summary{cursor:pointer}.technical summary:focus-visible{outline:2px solid var(--el-text-color-primary);outline-offset:4px}.el-alert{margin-top:12px}</style>
