<template>
  <section>
    <p>隔离任务列表</p>
    <button @click="$emit('workbench', { kind: 'MAINTENANCE', id: 'problem-1' })">查看维护任务</button>
  </section>
</template>
<script setup lang="ts">
defineExpose({ hasUnsavedDraft: () => false });
defineEmits(['workbench']);
</script>
