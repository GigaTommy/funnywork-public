<template>
  <div class="app-container home">
    <h1>FunnyWork 运营首页</h1><p class="subtitle">查看任务进展、模型服务、用量与成本，处理需要关注的工作。</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <el-row :gutter="20">
      <el-col v-for="item in availableEntries" :key="item.path" :xs="24" :sm="12" :lg="8">
        <el-card shadow="hover" class="entry" @click="router.push(item.path)">
          <h3>{{ item.title }}</h3><p>{{item.path.endsWith('/tasks')&&!access.organization?'查看本人任务及执行进展':item.description}}</p>
          <el-button type="primary" text>打开 →</el-button>
        </el-card>
      </el-col>
    </el-row>
    <el-empty v-if="loaded&&!error&&!availableEntries.length" description="当前账号尚无业务模块入口，可在系统设置中核对开通情况。" />
  </div>
</template>
<script setup lang="ts">
import { useUserStore } from '@/store/modules/user';
import {businessRequest} from '@/utils/funnyworker-api';
import {businessErrorMessage} from '@/utils/business-feedback';
import {taskWorkbenchAccess} from '@/utils/task-workbench-access';
const router = useRouter();
const user = useUserStore();
const roles=ref<string[]>([]),loaded=ref(false),error=ref('');let alive=true;
const access=computed(()=>taskWorkbenchAccess(roles.value,user.permissions));
onMounted(async()=>{try{const me=await businessRequest('/me');if(alive)roles.value=me.roles;}catch(e){if(alive)error.value=businessErrorMessage(e);}finally{if(alive)loaded.value=true;}});
onBeforeUnmount(()=>{alive=false;});
const entries = [
 {title:'任务中心', description:'查看组织任务、执行终端、验收与交付进展', path:'/funnyworker/tasks', permission:'funnyworker:tasks:read', alternative:'funnyworker:task-center:read'},
 {title:'模型中心', description:'管理模型接入、容量、授权和客户端发布', path:'/funnyworker/models', permission:'funnyworker:models:read'},
 {title:'用量与成本', description:'查看受管模型调用、预算和计量依据', path:'/funnyworker/usage', permission:'funnyworker:usage:read'},
 {title:'会话中心', description:'查询已归集会话，按独立授权查看正文', path:'/funnyworker/history', permission:'funnyworker:history:read'},
 {title:'执行资源', description:'查看执行项目、资源池与终端可用范围', path:'/funnyworker/resources', permission:'funnyworker:resources:read'},
 {title:'年报工作台', description:'管理资料、编制候选与后端核验交付', path:'/funnyworker/annual', permission:'funnyworker:annual:read'}
];
const availableEntries = computed(() => entries.filter(item => (item.path.endsWith('/tasks')?access.value.organization||access.value.personal:item.path.endsWith('/annual')?access.value.annual:roles.value.includes('ADMIN'))&&(user.permissions.includes('*:*:*') || user.permissions.includes(item.permission) || !!item.alternative&&user.permissions.includes(item.alternative))&&router.resolve(item.path).matched.some(route=>route.name!=='NotFound'&&!String(route.path).includes('pathMatch'))));
</script>
<style scoped>
h1 {font-size:26px;color:var(--el-text-color-primary);margin:12px 0} .subtitle {color:var(--el-text-color-secondary);margin-bottom:28px} .entry {margin-bottom:20px;cursor:pointer;border-radius:12px} .entry h3{font-size:18px}.entry p{color:var(--el-text-color-secondary)}
</style>
