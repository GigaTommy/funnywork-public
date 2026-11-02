<template>
 <section>
  <h3>输入与文件</h3>
  <p class="hint">需独立内容授权，每次读取记录审计。文件仅作为原始文件下载；模型产出不代表已通过业务验收。专用维护／年报交付证据仍以原工作流为准。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template v-if="canInput&&capabilities.readInput">
   <el-select aria-label="查看输入的任务步骤" v-model="unit" placeholder="选择步骤" :disabled="busy"><el-option v-for="row in units" :key="row.id" :value="row.id" :label="'步骤 '+(row.ordinal+1)" /></el-select>
   <el-button :disabled="busy||!unit" @click="readInput">读取所选步骤输入</el-button>
   <pre v-if="input!==undefined">{{input}}</pre>
  </template>
  <p v-else class="hint">未开放任务输入读取。</p>
  <template v-if="canArtifacts&&capabilities.readArtifacts">
   <el-button :disabled="busy" @click="loadArtifacts(false)">读取附件与模型产出目录</el-button>
   <el-table v-if="artifacts" :data="artifacts.items" empty-text="此范围没有可读取的附件或已关联模型产出"><el-table-column prop="name" label="文件" min-width="180" /><el-table-column prop="version" label="版本" width="70" /><el-table-column prop="byte_size" label="字节数" width="100" /><el-table-column label="操作" width="160"><template #default="{row}"><el-button :disabled="busy" @click="download(row)">下载原始文件</el-button></template></el-table-column></el-table>
   <el-button v-if="artifacts?.hasMore" :disabled="busy" @click="loadArtifacts(true)">更多文件</el-button>
  </template>
  <p v-else class="hint">未开放文件读取。</p>
 </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage,BusinessError} from '@/utils/business-feedback';
const props=defineProps<{task:string;units:Record<string,any>[];canInput:boolean;canArtifacts:boolean}>();
const emit=defineEmits<{restricted:[]}>();
const capabilities=ref({readInput:false,readArtifacts:false}),unit=ref(''),input=ref<string>(),artifacts=ref<{items:Record<string,any>[];hasMore:boolean;nextArtifact:string;nextVersion:number}>(),busy=ref(false),error=ref('');let generation=0,alive=true;
function clear(){input.value=undefined;artifacts.value=undefined;capabilities.value={readInput:false,readArtifacts:false};}
async function run(work:(run:number)=>Promise<void>){if(busy.value)return;const run=generation;busy.value=true;error.value='';try{await work(run);}catch(e){if(alive&&run===generation){input.value=undefined;artifacts.value=undefined;error.value=businessErrorMessage(e);if(e instanceof BusinessError&&[401,403,404].includes(e.status)){clear();emit('restricted');}}}finally{if(alive&&run===generation)busy.value=false;}}
watch(()=>[props.task,props.canInput,props.canArtifacts],()=>{generation++;clear();unit.value='';busy.value=false;if(props.canInput||props.canArtifacts)void run(async(run)=>{const value=await request('/management-tasks/'+encodeURIComponent(props.task)+'/content-capabilities');if(alive&&run===generation)capabilities.value=value;});},{immediate:true});
watch(unit,()=>{input.value=undefined;});
async function readInput(){input.value=undefined;await run(async(run)=>{const value=await request('/management-tasks/'+encodeURIComponent(props.task)+'/units/'+encodeURIComponent(unit.value)+'/input');if(alive&&run===generation)input.value=value.input_text;});}
async function loadArtifacts(more=false){const previous=artifacts.value;await run(async(run)=>{const value=await request('/management-tasks/'+encodeURIComponent(props.task)+'/artifacts?after='+encodeURIComponent(more?previous?.nextArtifact||'':'')+'&afterVersion='+(more?previous?.nextVersion||0:0));if(alive&&run===generation)artifacts.value={...value,items:more?[...previous!.items,...value.items]:value.items};});}
async function download(row:Record<string,any>){await run(async(run)=>{const value=await request('/management-tasks/'+encodeURIComponent(props.task)+'/artifacts/'+encodeURIComponent(row.artifact_id)+'/'+row.version);if(!alive||run!==generation)return;const bytes=Uint8Array.from(atob(value.content_base64),character=>character.charCodeAt(0));const url=URL.createObjectURL(new Blob([bytes],{type:'application/octet-stream'})),link=document.createElement('a');link.href=url;link.download=('task-'+props.task+'-'+row.artifact_id+'-v'+row.version).replace(/[^A-Za-z0-9_.-]/g,'_')+'.bin';link.click();setTimeout(()=>URL.revokeObjectURL(url),30000);});}
onBeforeUnmount(()=>{alive=false;generation++;clear();});
</script>
<style scoped>.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow:auto;padding:12px;border:1px solid var(--el-border-color);border-radius:6px}.el-select{min-width:160px;margin-right:8px}</style>
