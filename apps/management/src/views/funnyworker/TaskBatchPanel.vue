<template>
 <section class="batch-panel">
  <p class="hint">已选 {{selected.length}} 个任务。每批最多 50 个；只处理预览中明确确认的任务，逐项核对状态。取消请求不代表终端已停止。</p>
  <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template v-if="pending">
   <el-button :disabled="busy" @click="check">核对批量回执</el-button>
   <el-button :disabled="busy||blocked" @click="submit">继续处理原确认集合</el-button>
  </template>
  <template v-else>
   <el-button v-for="action in ['PAUSE','RESUME','CANCEL']" :key="action" :disabled="busy||blocked||storageBlocked||!selected.length||selected.length>50" @click="prepare(action as Action)">{{actionLabel(action)}}</el-button>
  </template>
  <el-table v-if="receipt" :data="receipt.items"><el-table-column label="任务" min-width="160"><template #default="{row}">{{row.title||'当前范围不可查看'}}</template></el-table-column><el-table-column label="逐项结果" min-width="180"><template #default="{row}">{{stateLabel(row.state)}}</template></el-table-column></el-table>
 </section>
</template>
<script setup lang="ts">
import {ref,watch,computed,h,onMounted,onBeforeUnmount} from 'vue';
import {ElMessageBox} from 'element-plus';
import {businessRequest as request} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
import {readPendingTaskBatch,savePendingTaskBatch,clearPendingTaskBatch,type PendingTaskBatch} from '@/utils/pending-task-batch';
type Action=PendingTaskBatch['body']['action'];type Receipt={state:string;settled?:boolean;items:Record<string,any>[]};
const props=defineProps<{selected:string[];actorKey:string;blocked:boolean;scopeEpoch:number}>(),emit=defineEmits<{blocked:[value:boolean];updated:[]}>();
const pending=ref<PendingTaskBatch>(),receipt=ref<Receipt>(),busy=ref(false),storageBlocked=ref(false),notice=ref(''),error=ref('');let alive=true;
watch(()=>props.scopeEpoch,()=>{receipt.value=undefined;});
const locked=computed(()=>busy.value||storageBlocked.value||!!pending.value);watch(locked,value=>emit('blocked',value),{immediate:true});
const actionLabel=(value:string)=>({PAUSE:'批量暂停后续调度',RESUME:'批量恢复后续调度',CANCEL:'批量请求取消'} as Record<string,string>)[value];
const stateLabel=(value:string)=>({DONE:'请求已记录，实际执行状态另行核对',REJECTED:'未执行：状态或权限已变化，需重新预览',PENDING:'尚未处理',UNCERTAIN:'结果尚待核对',SCOPE_UNAVAILABLE:'当前范围不可查看，请恢复授权后核对'} as Record<string,string>)[value]||value;
async function accept(value:Receipt){receipt.value=value;if(value.settled||['COMPLETED','COMPLETED_WITH_REJECTIONS'].includes(value.state)){if(pending.value)clearPendingTaskBatch(props.actorKey,pending.value.key);pending.value=undefined;notice.value=value.state==='SCOPE_UNAVAILABLE'?'原确认集合已处理结束；部分任务当前不可查看，未展示其明细。':value.state==='COMPLETED'?'本批请求均已记录；实际执行及停止状态仍需逐项核对。':'本批处理结束，部分任务被拒绝；未将整批标为全部成功。';emit('updated');}else notice.value=value.state==='SCOPE_UNAVAILABLE'?'管理范围已变化，原批量请求已保留，请恢复授权后核对。':'本批仍有待处理或待核对项，请保留原请求。';}
async function submit(){if(!pending.value||busy.value||props.blocked)return;busy.value=true;error.value='';try{const value=await request('/management-tasks/batches','POST',pending.value.body,pending.value.key);if(alive)await accept(value);}catch(e){if(alive){receipt.value=undefined;error.value=businessErrorMessage(e);notice.value='批量结果尚未确认，请核对回执或继续原确认集合，不能替换请求。';}}finally{if(alive)busy.value=false;}}
async function check(){if(!pending.value||busy.value)return;busy.value=true;error.value='';try{const value=await request('/management-tasks/batches/'+encodeURIComponent(pending.value.key));if(alive)await accept(value);}catch(e){if(alive){receipt.value=undefined;error.value=businessErrorMessage(e);notice.value='尚未核对到完整回执，原批量请求继续保留。';}}finally{if(alive)busy.value=false;}}
async function prepare(action:Action){if(busy.value||props.blocked||pending.value||storageBlocked.value||!props.selected.length||props.selected.length>50)return;busy.value=true;error.value='';notice.value='';try{const value=await request('/management-tasks/batch-preview','POST',{action,tasks:[...props.selected]});if(!alive)return;const allowed=value.items.filter((item:any)=>item.allowed);if(!allowed.length){notice.value='所选任务当前均不可操作，请刷新后重新选择。';return;}const message=allowed.map((item:any,index:number)=>(index+1)+'. '+item.title).join('\n')+'\n\n'+allowed[0].effect+'\n仅提交以上 '+allowed.length+' 个任务；另有 '+(value.items.length-allowed.length)+' 个不满足当前状态或范围的任务不提交。';try{await ElMessageBox.confirm(h('div',{style:'white-space:pre-wrap;max-height:50vh;overflow:auto'},message),actionLabel(action),{confirmButtonText:'确认以上任务',cancelButtonText:'返回核对'});}catch{return;}if(!alive)return;const command:PendingTaskBatch={key:crypto.randomUUID(),body:{action,items:allowed.map((item:any)=>({taskId:item.taskId,expectedToken:item.expectedToken}))}};savePendingTaskBatch(props.actorKey,command);pending.value=command;receipt.value=undefined;}catch(e){if(alive)error.value=businessErrorMessage(e);}finally{if(alive)busy.value=false;}if(pending.value)await submit();}
onMounted(()=>{try{pending.value=readPendingTaskBatch(props.actorKey);if(pending.value)notice.value='发现先前批量请求，请先核对回执。';}catch{storageBlocked.value=true;error.value='无法读取待核对记录，请先恢复浏览器存储后再操作。';}});onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>.batch-panel{margin:16px 0}.hint{font-size:13px;line-height:1.7;color:var(--el-text-color-secondary)}.el-alert{margin-bottom:10px}</style>
