<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header><strong>会话历史</strong></template>
      <p class="hint">自动归集桌面、手机和员工 Agent 会话。目录按来源及编号分页，不显示会话标题或正文；正文需要独立授权，查看行为记录在关联审计中。</p>
      <el-form inline @submit.prevent="load(false)">
        <el-form-item label="来源"><el-select v-model="filters.source" style="width:160px"><el-option label="全部" value="ALL"/><el-option v-for="(label, value) in sources" :key="value" :label="label" :value="value"/></el-select></el-form-item>
        <el-form-item label="成员标识"><el-input v-model="filters.owner" maxlength="64" clearable placeholder="可选，精确匹配"/></el-form-item>
        <el-form-item><el-button native-type="submit" :loading="busy" :disabled="!canRead">查询 / 刷新</el-button></el-form-item>
      </el-form>
      <el-alert v-if="!canRead" title="当前账号没有会话目录权限。" type="info" :closable="false"/>
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <el-table :data="rows" v-loading="busy" :empty-text="error ? '查询未完成，请重试' : '当前页没有符合条件且在授权范围内的会话'">
        <el-table-column label="来源" width="140"><template #default="{row}">{{ sources[row.source] }}</template></el-table-column>
        <el-table-column label="成员" min-width="160"><template #default="{row}">{{ row.owner_name }}<div class="hint">{{ row.owner_id }}</div></template></el-table-column>
        <el-table-column prop="id" label="会话编号" min-width="220"/>
        <el-table-column label="归集信息" min-width="180"><template #default="{row}">{{ progress(row) }}<div v-if="row.archived" class="hint">已归档，历史仍保留</div></template></el-table-column>
        <el-table-column label="最近更新" min-width="180"><template #default="{row}">{{ time(row.updated_at) }}<div v-if="row.created_at" class="hint">建立 {{ time(row.created_at) }}</div></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{row}"><el-button link :disabled="!canContent || busy" @click="open(row)">查看正文</el-button></template></el-table-column>
      </el-table>
      <p v-if="loaded" class="hint">当前页 {{ rows.length }} 个会话。查询截至 {{ time(observedAt) }}。已接收记录不代表设备全部历史已上传；未记录的历史时间显示未知。</p>
      <p v-if="!canContent && canRead" class="hint">当前仅可查看目录；正文查看权限由管理员单独分配。</p>
      <el-button v-if="listPrevious.length" :disabled="busy || changed" @click="load(false, true)">上一页</el-button><el-button v-if="hasMore" :disabled="busy || changed" @click="load(true)">下一页</el-button><p v-if="changed" class="hint">条件已修改，请重新查询。</p>
    </el-card>
    <el-drawer :model-value="!!selected" title="会话记录" size="min(760px, 100%)" @close="close">
      <template v-if="selected">
        <p>{{ sources[selected.source] }} · {{ selected.owner_name }}</p><p class="hint">{{ selected.id }}</p>
        <p class="hint">按原始事件位置或工作轮次分批显示。员工任务只展开可核验的原始输出；附件、较大产物及无法核验的产物保留引用。长文本显示前 4096 个字符。</p>
        <el-alert v-if="contentError" :title="contentError" type="error" :closable="false"/>
        <div v-loading="contentBusy">
          <article v-for="(item, index) in messages" :key="index" class="message">
            <strong>{{ roles[item.role] || item.role }}</strong><span class="hint"> · 位置 {{ item.position }}<template v-if="item.state"> · {{ item.state }}</template></span>
            <p v-if="item.text" class="text">{{ item.text }}</p>
            <p v-if="item.artifactId" class="hint">产物 {{ item.artifactId }} · 版本 {{ item.version }} · {{ item.availability === 'ORIGINAL_OUTPUT' ? '原始输出，未经业务核验' : '本页未展开' }}</p>
            <p v-if="item.truncated" class="hint">长文本已截断。</p>
          </article>
          <el-empty v-if="contentLoaded && !messages.length && !contentError" :description="contentMore ? '此批没有可见消息，可继续读取' : '没有可显示的消息'"/>
        </div>
        <el-button v-if="contentPrevious.length" :disabled="contentBusy || !canContent" @click="readContent(false, true)">上一批记录</el-button><el-button v-if="contentMore" :loading="contentBusy" :disabled="!canContent" @click="readContent(true)">下一批记录</el-button>
      </template>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue';
import {useUserStore} from '@/store/modules/user';
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
interface Entry {source:string;id:string;owner_id:string;owner_name:string;received_position:number|null;updated_at:number|null;created_at:number|null;archived:boolean}
interface ContentCursor {after:number;continuation:string}
interface Message {role:string;text:string;position:number;state?:string;artifactId?:string;version?:number;availability?:string;truncated?:boolean}
const sources:Record<string,string>={DESKTOP:'桌面会话',MOBILE:'手机聊天',EMPLOYEE:'员工 Agent'};
const roles:Record<string,string>={USER:'员工',ASSISTANT:'助手',CORRECTION:'员工补充指令',NOTICE:'说明'};
const user=useUserStore();const permitted=(permission:string)=>user.permissions.includes('*:*:*')||user.permissions.includes(permission);
const canRead=computed(()=>permitted('funnyworker:history:read'));const canContent=computed(()=>canRead.value&&permitted('funnyworker:history:content'));
const filters=reactive({source:'ALL',owner:''});const rows=ref<Entry[]>([]);const after=ref('');const hasMore=ref(false);const observedAt=ref(0);const busy=ref(false);const error=ref('');const loaded=ref(false);const applied=ref('');
const selected=ref<Entry>();const messages=ref<Message[]>([]);const contentAfter=ref(-1);const contentContinuation=ref('');const contentMore=ref(false);const contentBusy=ref(false);const contentError=ref('');const contentLoaded=ref(false);
const listPrevious=ref<string[]>([]),contentPrevious=ref<ContentCursor[]>([]);let listStart='',contentStart:ContentCursor={after:-1,continuation:''};
let alive=true,listGeneration=0,contentGeneration=0;
const snapshot=()=>JSON.stringify({source:filters.source,owner:filters.owner.trim()});const changed=computed(()=>loaded.value&&snapshot()!==applied.value);
const time=(value:number|null)=>value && value>0 ? new Date(value).toLocaleString() : '未知';
const progress=(row:Entry)=>row.source==='DESKTOP' ? `已接收 ${row.received_position} 条事件` : row.source==='MOBILE' ? `已保存 ${row.received_position} 轮请求` : '工作会话已保存';
function close(){contentGeneration++;contentPrevious.value=[];contentStart={after:-1,continuation:''};contentContinuation.value='';selected.value=undefined;messages.value=[];contentAfter.value=-1;contentMore.value=false;contentError.value='';contentLoaded.value=false;contentBusy.value=false;}
async function load(more:boolean,previous=false){
  if(busy.value || !canRead.value || (more||previous)&&changed.value)return;const generation=++listGeneration;const query=more||previous?applied.value:snapshot();const start=previous ? listPrevious.value.at(-1) ?? '' : more ? after.value : '';busy.value=true;error.value='';close();
  try{const page=await businessRequest('/management-history?'+new URLSearchParams({...JSON.parse(query),after:start}));if(alive&&generation===listGeneration&&canRead.value){if(previous)listPrevious.value.pop();else if(more)listPrevious.value.push(listStart);else listPrevious.value=[];listStart=start;rows.value=page.items;after.value=page.after;hasMore.value=page.hasMore;observedAt.value=page.observedAt;applied.value=query;loaded.value=true;}}
  catch(failure){if(alive&&generation===listGeneration){rows.value=[];hasMore.value=false;after.value='';loaded.value=false;error.value=businessErrorMessage(failure);}}
  finally{if(alive&&generation===listGeneration)busy.value=false;}
}
function open(row:Entry){if(!canContent.value)return;close();selected.value=row;void readContent(false);}
async function readContent(more:boolean,previous=false){
  const row=selected.value;if(!row||!canContent.value||contentBusy.value)return;const generation=++contentGeneration;const start:ContentCursor=previous ? contentPrevious.value.at(-1) ?? {after:-1,continuation:''} : more ? {after:contentAfter.value,continuation:contentContinuation.value} : {after:-1,continuation:''};contentBusy.value=true;contentError.value='';
  try{const page=await businessRequest(`/management-history/${encodeURIComponent(row.source)}/${encodeURIComponent(row.id)}/content?${new URLSearchParams({after:String(start.after),continuation:start.continuation})}`);if(alive&&generation===contentGeneration&&canContent.value){if(previous)contentPrevious.value.pop();else if(more)contentPrevious.value.push(contentStart);else contentPrevious.value=[];contentStart=start;messages.value=page.items;contentAfter.value=page.after;contentContinuation.value=page.continuation ?? '';contentMore.value=page.hasMore;contentLoaded.value=true;}}
  catch(failure){if(alive&&generation===contentGeneration){messages.value=[];contentMore.value=false;contentLoaded.value=false;contentError.value=businessErrorMessage(failure);}}
  finally{if(alive&&generation===contentGeneration)contentBusy.value=false;}
}
watch(canContent,value=>{if(!value)close();});watch(canRead,value=>{if(!value){listGeneration++;listPrevious.value=[];rows.value=[];loaded.value=false;hasMore.value=false;busy.value=false;close();}});
onMounted(()=>void load(false));onBeforeUnmount(()=>{alive=false;listGeneration++;close();});
</script>
<style scoped>
.hint{color:var(--el-text-color-secondary);font-size:13px;line-height:1.6;overflow-wrap:anywhere}.message{padding:16px 0;border-bottom:1px solid var(--el-border-color)}.text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7}.el-alert{margin:12px 0}
</style>
