<template>
 <div class="app-container"><el-card><template #header><strong>关联审计</strong></template>
  <p class="hint">查询业务服务记录的任务、授权、设备及配置操作。仅显示操作元数据，不返回任务正文、凭证或原始请求。身份同步批次可在下方独立来源中查询。</p>
  <el-form inline @submit.prevent="load()"><el-form-item label="发生时间"><el-date-picker v-model="dates" type="datetimerange" value-format="x" start-placeholder="开始（含）" end-placeholder="结束（不含）"/></el-form-item><el-form-item label="事件类型"><el-input v-model="filters.type" maxlength="80" clearable placeholder="例如 DESKTOP_POLICY_PUBLISHED"/></el-form-item><el-form-item label="关联编号"><el-input v-model="filters.reference" maxlength="120" clearable placeholder="任务、成员、设备或其他对象编号"/></el-form-item><el-form-item><el-button native-type="submit" :loading="busy">查询 / 刷新</el-button></el-form-item></el-form>
  <el-alert v-if="error" type="error" :title="error" :closable="false"/>
  <p v-if="loaded" class="hint">查询时间：{{time(observedAt)}}。{{legacyVisible?'当前范围允许查看未记录历史操作范围的旧管理事件。':'旧管理事件缺少操作范围，仅覆盖整个租户范围的管理员可查看。'}}新管理事件按记录时的操作范围校验；任务事件按任务区域校验。</p>
  <el-table @focusin="revealTableFocus" v-loading="busy" :data="rows" :empty-text="error?'本次查询未完成，请根据提示重试。':'暂无记录'"><el-table-column label="时间" min-width="180"><template #default="{row}">{{time(row.created_at)}}</template></el-table-column><el-table-column label="事件" min-width="240"><template #default="{row}">{{labels[row.event_type]||row.event_type}}<div class="hint">{{row.event_type}}</div></template></el-table-column><el-table-column label="操作者" min-width="140"><template #default="{row}">{{row.actor_name||row.actor_id||'系统'}}</template></el-table-column><el-table-column label="关联信息" min-width="240"><template #default="{row}"><div v-for="(value,key) in row.details" :key="key"><span>{{key}}：</span><el-button v-if="typeof value==='string'&&String(key).endsWith('Id')" text :disabled="busy" @click="correlate(value)">{{value}}</el-button><span v-else>{{value}}</span></div><span v-if="!Object.keys(row.details).length" class="hint">无可公开的附加信息</span></template></el-table-column></el-table>
  <el-empty v-if="loaded&&!busy&&!error&&!rows.length" :description="cursor?'此批扫描没有符合权限及条件的事件，可继续扫描。':'未找到符合条件的事件'"/>
  <el-button v-if="cursor" :disabled="busy||changed" @click="load(true)">继续扫描后续事件</el-button><p v-if="changed" class="hint">条件已修改，请重新查询。</p>
 </el-card><IdentityAudit v-if="canReadDirectory" class="identity-audit"/></div>
</template>
<script setup lang="ts">
import {revealTableFocus} from '@/utils/table-focus';
import IdentityAudit from './IdentityAudit.vue';
import {useUserStore} from '@/store/modules/user';
const user=useUserStore();
const canReadDirectory=computed(()=>String(user.userId)==='1'||user.permissions.includes('*:*:*')||user.permissions.includes('funnyworker:directory:read'));
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
const now=Date.now(),dates=ref<number[]>([now-7*86400000,now]),filters=reactive({type:'',reference:''});
const rows=ref<any[]>([]),cursor=ref(''),busy=ref(false),error=ref(''),loaded=ref(false),legacyVisible=ref(false),observedAt=ref(0),applied=ref('');let alive=true;
const labels:Record<string,string>={HISTORY_DIRECTORY_READ:'会话目录查询',HISTORY_CONTENT_READ:'会话正文查看',MANAGEMENT_SCOPE_CHANGED:'业务授权已调整',PRINCIPAL_CREATED:'业务账号已创建',PRINCIPAL_DISABLED:'业务账号已停用',SESSION_LOGGED_OUT:'账号已注销',DESKTOP_POLICY_PUBLISHED:'组织策略已发布',DESKTOP_MODEL_PUBLISHED:'桌面模型配置已发布',DESKTOP_MODEL_CHECKED:'桌面模型检查',DESKTOP_DEVICE_REVOKED:'设备已撤销',DESKTOP_DEVICE_RESTORED:'设备已恢复',TASK_CREATED:'任务已创建',TASK_CANCELLED:'任务已取消',TASK_DISPATCH_PAUSED:'任务调度已暂停',TASK_DISPATCH_RESUMED:'任务调度已恢复',MCP_BINDING_CHECKED:'MCP 连接契约检查',GATEWAY_CONFIGURATION_CHECKED:'网关配置检查'};
const snapshot=()=>JSON.stringify({from:dates.value?.[0]||'',until:dates.value?.[1]||'',type:filters.type.trim(),reference:filters.reference.trim()});
const changed=computed(()=>loaded.value&&snapshot()!==applied.value);
const time=(value:number)=>new Date(value).toLocaleString();
async function correlate(value:string){filters.reference=value;await load();}
async function load(more=false){
 if(busy.value||more&&changed.value)return;if(!dates.value||dates.value.length!==2){error.value='请选择完整的时间范围。';return;}
 busy.value=true;error.value='';const query=more?applied.value:snapshot();
 try{const params=new URLSearchParams({...JSON.parse(query),after:more?cursor.value:'0',limit:'50'});const data=await businessRequest('/management-audit?'+params);if(alive){rows.value=more?[...rows.value,...data.items]:data.items;cursor.value=data.nextCursor?String(data.nextCursor):'';observedAt.value=data.observedAt;legacyVisible.value=data.legacyTenantAuditVisible;applied.value=query;loaded.value=true;}}
 catch(e){if(alive){rows.value=[];cursor.value='';loaded.value=false;observedAt.value=0;legacyVisible.value=false;applied.value='';error.value=businessErrorMessage(e);}}finally{if(alive)busy.value=false;}
}
onMounted(()=>load());onBeforeUnmount(()=>{alive=false;});
</script>
<style scoped>.identity-audit{margin-top:20px}.hint{font-size:13px;color:var(--el-text-color-secondary);line-height:1.6}.el-alert{margin:12px 0}.el-button{max-width:100%;overflow-wrap:anywhere;white-space:normal}</style>
