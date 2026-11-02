<template><div><div v-if="route.query.taskId" class="app-container"><el-button @click="router.replace({path:route.path})">返回全部恢复记录</el-button></div><BusinessModule :key="route.fullPath" module="recovery" /></div></template>
<script setup lang="ts">
import BusinessModule from './BusinessModule.vue';
const route=useRoute(),router=useRouter();
</script>
