<template>
 <div class="app-container"><p v-if="loading" role="status">正在读取业务权限与页面…</p><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" :disabled="loading" @click="mount">重新读取</el-button><div ref="container" /></div>
</template>
<script setup lang="ts">
import React from 'react';
import {WriteAccess} from '../../../../../packages/management-react/src/write-access';
import { useUserStore } from '@/store/modules/user';
import {createRoot, type Root} from 'react-dom/client';
import {businessApi as api} from '@/utils/funnyworker-api';
import styles from '../../../../../packages/management-react/src/style.css?inline';
import {Nodes} from '../../../../../packages/management-react/src/nodes';
import {AgentQuotas} from '../../../../../packages/management-react/src/agent-quotas';
import {ModelProfiles} from '../../../../../packages/management-react/src/model-profiles';
import {OfficeAdmin} from '../../../../../packages/management-react/src/office-admin';
import {ManagedGateways} from '../../../../../packages/management-react/src/managed-gateways';
import {CapabilityPackages} from '../../../../../packages/management-react/src/capability-packages';
import {ClientPolicyAdmin} from '../../../../../packages/management-react/src/client-policy-admin';
import {AgentTools} from '../../../../../packages/management-react/src/agent-tools';
import {DocumentBindings} from '../../../../../packages/management-react/src/document-bindings';
import {ComputerControls} from '../../../../../packages/management-react/src/computer-controls';
import {Recovery} from '../../../../../packages/management-react/src/recovery';
import {Overview,CapabilityInventory} from './shared';
const props=defineProps<{module:string}>();const route=useRoute();const container=ref<HTMLDivElement>();const error=ref('');const loading=ref(false);
let root:Root|undefined;let generation=0;
const user=useUserStore();
const components:Record<string,React.ComponentType<any>>={overview:Overview,nodes:Nodes,scheduling:AgentQuotas,models:ModelProfiles,office:OfficeAdmin,gateways:ManagedGateways,packages:CapabilityPackages,client:ClientPolicyAdmin,tools:AgentTools,documents:DocumentBindings,computer:ComputerControls,recovery:Recovery,agents:CapabilityInventory};
async function mount(){
 const run=++generation;root?.unmount();root=undefined;error.value='';loading.value=true;
 try {
  const response=await fetch('/api/v1/me',{headers:{Authorization:'Bearer '+(await import('@/utils/auth')).getToken(),'X-FunnyWorker-Management':'1'},cache:'no-store'});
  if(!response.ok)throw new Error(response.status===403?'当前账号尚未获得业务授权。':'无法验证业务会话，请重新登录。');
  const me=await response.json();if(run!==generation||!container.value)return;
  const module=props.module;
  const component=components[module];if(!component)throw new Error('此功能暂不可用。');
  const shadow=container.value.shadowRoot||container.value.attachShadow({mode:'open'});shadow.replaceChildren();
  const style=document.createElement('style');style.textContent=styles+'\n:host{display:block;color:var(--fw-text,#292724);font-family:inherit;font-size:14px}.panel{margin-bottom:18px}';shadow.append(style);
  const target=document.createElement('div');shadow.append(target);root=createRoot(target);
  const domain=({overview:'scheduling',scheduling:'scheduling',gateways:'integrations',packages:'integrations'} as Record<string,string>)[module]||module;
  const canWrite=me.roles.includes('ADMIN')&&(user.permissions.includes('*:*:*')||user.permissions.includes('funnyworker:'+domain+':write'));
  root.render(React.createElement(WriteAccess.Provider,{value:canWrite},React.createElement(component,{api,zones:me.zones,principal:me.principalId,model:false,taskId:typeof route.query.taskId==='string'?route.query.taskId:'',initialZone:typeof route.query.zone==='string'?route.query.zone:''})));

 }catch(e){if(run===generation)error.value=e instanceof Error?e.message:'页面读取失败。';}
 finally{if(run===generation)loading.value=false;}
}
function dispose(){generation++;root?.unmount();root=undefined;loading.value=false;}
// Nested cached views may first activate only when revisited; use actual mount state.
onMounted(mount);onActivated(()=>{if(!root&&!loading.value)void mount();});onDeactivated(dispose);onBeforeUnmount(dispose);
</script>
