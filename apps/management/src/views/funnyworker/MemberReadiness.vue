<template>
  <el-drawer v-model="open" title="成员使用状态" size="480px">
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="account">
        <h2>{{ account.nickname || account.username }}</h2>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="账号">{{ account.username }}</el-descriptions-item>
          <el-descriptions-item label="部门">{{ account.department?.state === 'MAPPED' ? account.department.departmentName : account.department?.state === 'UNASSIGNED' ? '尚未分配部门' : '部门信息未能确认' }}</el-descriptions-item>
          <el-descriptions-item label="已启用身份角色">{{ !Array.isArray(account.roles) ? '未能确认' : account.roles.length ? account.roles.join('、') : '暂无可用角色' }}</el-descriptions-item>
          <el-descriptions-item label="角色功能授权">{{ !Array.isArray(account.permissions) ? '未能确认' : account.permissions.includes('*:*:*') ? '全部功能' : account.permissions.length ? account.permissions.length+' 项，以当前角色授权为准' : '尚未授予功能权限' }}</el-descriptions-item>
          <el-descriptions-item label="账号来源">{{ account.source ? '统一身份 · '+account.source : '本地账号' }}</el-descriptions-item>
          <el-descriptions-item label="账号状态">{{ account.enabled ? '启用' : '停用' }}</el-descriptions-item>
          <el-descriptions-item label="桌面权限">{{ account.desktopAllowed ? '已授予' : '待开通' }}</el-descriptions-item>
          <el-descriptions-item label="业务授权">{{ binding ? (binding.configured ? (binding.enabled ? '已授权' : '已撤销') : '待配置') : '未能确认' }}</el-descriptions-item>
          <el-descriptions-item v-if="binding?.configured" label="业务范围">{{ binding.zones.join('、') }}</el-descriptions-item>
          <el-descriptions-item v-if="binding?.configured" label="业务角色">{{ binding.roles.map((role:string)=>names[role]||role).join('、') }}</el-descriptions-item>
        </el-descriptions>
        <p v-if="!account.enabled">账号已停用，请先核对停用原因。</p>
        <p v-if="!account.desktopAllowed">请在用户角色中授予桌面客户端使用权限。</p>
        <p v-if="binding&&!binding.enabled">业务授权尚未生效，请配置业务角色和授权区域。</p>
        <p v-if="account.enabled&&account.desktopAllowed&&binding?.enabled">账号与授权条件已具备。实际登录还需身份服务和模型配置可用。</p>
        <el-button v-if="binding&&canConfigure" :disabled="!account.enabled" @click="configure">配置业务授权</el-button>
      </template>
      <el-button :disabled="loading" @click="load">刷新状态</el-button>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import request from '@/utils/request';
import {useUserStore} from '@/store/modules/user';
const user=useUserStore();
const canConfigure=computed(()=>user.permissions.includes('*:*:*')||user.permissions.includes('funnyworker:authorization:write'));
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
const props=defineProps<{userId?:string|number}>();
const open=defineModel<boolean>({default:false});
const router=useRouter();
const account=ref<any>(),binding=ref<any>(),loading=ref(false),error=ref('');
const names:Record<string,string>={ADMIN:'业务管理员',OPERATOR:'任务操作员',VIEWER:'业务查看员',RECONCILER:'恢复审核员'};
let generation=0;
async function load(){
 const run=++generation;account.value=undefined;binding.value=undefined;error.value='';
 if(!open.value||!props.userId)return;
 loading.value=true;
 try{
  const response=await request({url:'/funnyworker/accounts/'+encodeURIComponent(props.userId)+'/status'});
  if(run!==generation)return;account.value=response.data;
  try{const value=await businessRequest('/management-bindings/'+encodeURIComponent(props.userId));if(run===generation)binding.value=value;}
  catch(e){if(run===generation)error.value='业务授权状态未能确认。'+businessErrorMessage(e);}
 }catch{if(run===generation)error.value='账号状态读取失败，请检查查看权限后重试。';}
 finally{if(run===generation)loading.value=false;}
}
function configure(){open.value=false;router.push({path:'/funnyworker/authorization',query:{userId:String(props.userId)}});}
watch(()=>[open.value,props.userId],load);onBeforeUnmount(()=>{generation++;});
</script>
<style scoped>h2{font-size:20px}p{line-height:1.7;color:var(--el-text-color-secondary)}.el-alert,.el-descriptions{margin-bottom:16px}.el-button{margin-top:12px}</style>
