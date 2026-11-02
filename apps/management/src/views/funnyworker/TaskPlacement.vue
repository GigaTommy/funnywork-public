<template>
  <section class="placement">
    <div class="toolbar">
      <h3>执行终端</h3>
      <el-button :loading="busy" @click="load">刷新终端</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-else-if="data">
      <p>
        {{ executionState(data.state) }}<span v-if="data.reason"> · {{ executionReasons[data.reason] || '等待调度条件满足' }}</span>
      </p>
      <el-alert v-if="data.writerState === 'UNKNOWN'" title="旧开发进程是否停止尚未确认，后端保留仓库写入锁。" type="warning" :closable="false" />
      <el-table :data="data.placements" empty-text="尚未分派到终端；历史任务可能没有终端放置记录。">
        <el-table-column label="终端" min-width="165"
          ><template #default="{ row }"
            >{{ row.deviceName || row.device_id }}
            <div class="hint identifier">{{ row.device_id }}</div></template
          ></el-table-column
        >
        <el-table-column label="职责" width="65"
          ><template #default="{ row }">{{ executionAction(row.action) }}</template></el-table-column
        >
        <el-table-column label="状态" width="95"
          ><template #default="{ row }">{{ executionState(row.attempt_state) }}</template></el-table-column
        >
        <el-table-column label="项目／资源池" min-width="145"
          ><template #default="{ row }"
            >{{ row.projectName }}
            <div class="hint">{{ row.poolName }}</div></template
          ></el-table-column
        >
        <el-table-column label="用途" min-width="130"
          ><template #default="{ row }"
            >{{ row.profileName }}
            <div class="hint">版本 {{ row.profileRevision }} · 路由 {{ row.routeRevision }}</div></template
          ></el-table-column
        >
        <el-table-column label="分派时间" min-width="150"
          ><template #default="{ row }"
            >{{ new Date(row.created_at).toLocaleString() }}
            <div class="hint">执行代际 {{ row.epoch }}</div></template
          ></el-table-column
        >
      </el-table>
    </template>
  </section>
</template>
<script setup lang="ts">
import { businessRequest as request } from '@/utils/funnyworker-api';
import { businessErrorMessage } from '@/utils/business-feedback';
import { executionAction, executionState, executionReasons } from './execution-copy';
const props = defineProps<{ taskId: string }>();
const data = ref<any>(),
  error = ref(''),
  busy = ref(false);
let generation = 0;
async function load() {
  const current = ++generation;
  busy.value = true;
  error.value = '';
  data.value = undefined;
  try {
    const result = await request('/tasks/' + encodeURIComponent(props.taskId) + '/placement');
    if (current === generation) data.value = result;
  } catch (e) {
    if (current === generation) error.value = businessErrorMessage(e);
  } finally {
    if (current === generation) busy.value = false;
  }
}
watch(() => props.taskId, load, { immediate: true });
onBeforeUnmount(() => {
  generation++;
});
</script>
<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.identifier {
  overflow-wrap: anywhere;
}
.placement {
  margin: 18px 0;
}
</style>
