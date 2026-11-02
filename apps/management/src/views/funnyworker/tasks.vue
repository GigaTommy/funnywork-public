<template>
  <div class="app-container task-center">
    <h2>任务中心</h2>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-tabs v-if="ready" v-model="tab" :before-leave="confirmTabLeave">
      <el-tab-pane v-if="canManage" label="组织任务" name="organization" />
      <el-tab-pane v-if="canReadMine" label="我的任务" name="mine" />
      <el-tab-pane v-if="canManage && checkPermi(['funnyworker:task-center:scope'])" label="管理范围" name="scope" />
      <el-tab-pane v-if="canMaintain" label="需求池" name="requirements" />
      <el-tab-pane v-if="canMaintain" label="代码维护" name="maintenance" />
    </el-tabs>
    <el-button v-if="canMaintain && route.query.rq_requirement && tab !== 'requirements'" @click="returnRequirement">返回需求详情</el-button>
    <ManagementTasks
      :can-maintain="canMaintain"
      :can-annual="access.annual"
      @workbench="openWorkflow"
      :can-input="checkPermi(['funnyworker:task-center:content'])"
      :can-artifacts="checkPermi(['funnyworker:task-center:artifacts'])"
      :route-context="route.query"
      @context="saveContext"
      :actor-key="actorKey"
      :can-operate="checkPermi(['funnyworker:task-center:write'])"
      :can-configure="checkPermi(['funnyworker:task-center:scope'])"
      ref="organization"
      v-if="ready && tab === 'organization' && canManage"
    />
    <TaskScopeSettings v-else-if="ready && tab === 'scope' && canManage" />
    <PersonalTasks v-else-if="ready && tab === 'mine' && canReadMine" ref="personal" />
    <RequirementPool
      v-else-if="ready && tab === 'requirements' && canMaintain"
      ref="requirements"
      :can-configure="roles.includes('ADMIN')"
      @view-problem="openRequirementProblem"
      @view-intake="openIntake"
    />
    <template v-else-if="ready && tab === 'maintenance' && canMaintain">
      <el-button v-if="canManage && route.query.tc_task" @click="returnOrganization">返回组织任务详情</el-button>
      <MaintenanceIntakePanel ref="intake" @view-problem="maintenance?.inspect($event)" />
      <MaintenancePanel ref="maintenance" @view-task="openTask" @problem-updated="intake?.updateRepairState($event)" />
    </template>
    <el-empty v-else-if="ready" description="当前账号没有可用的任务工作台，请联系管理员核对业务授权。" />
  </div>
</template>
<script setup lang="ts">
import { computed, ref, nextTick, onMounted, onBeforeUnmount, watch } from 'vue';
import { checkPermi } from '@/utils/permission';
import { businessRequest } from '@/utils/funnyworker-api';
import { businessErrorMessage } from '@/utils/business-feedback';
import { writeTaskCenterContext, type TaskCenterContext } from '@/utils/task-center-context';
import { taskWorkbenchAccess } from '@/utils/task-workbench-access';
import { useUserStore } from '@/store/modules/user';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import ManagementTasks from './ManagementTasks.vue';
import TaskScopeSettings from './TaskScopeSettings.vue';
import PersonalTasks from './PersonalTasks.vue';
import MaintenancePanel from './MaintenancePanel.vue';
import MaintenanceIntakePanel from './MaintenanceIntakePanel.vue';
import RequirementPool from './RequirementPool.vue';
const requirements = ref<InstanceType<typeof RequirementPool>>();
const route = useRoute(),
  router = useRouter();
function saveContext(context: TaskCenterContext) {
  void router.push({ query: writeTaskCenterContext({ ...route.query, tc_view: undefined, tc_problem: undefined }, context) });
}
const actorKey = ref('');
const roles = ref<string[]>([]),
  ready = ref(false),
  error = ref('');
let alive = true;
const user = useUserStore();
const access = computed(() => taskWorkbenchAccess(roles.value, user.permissions));
const canReadMine = computed(() => access.value.personal),
  canManage = computed(() => access.value.organization),
  canMaintain = computed(() => access.value.maintenance);
const tab = ref(route.query.taskId || route.query.state ? 'mine' : canManage.value ? 'organization' : 'mine');
const organization = ref<InstanceType<typeof ManagementTasks>>();
const personal = ref<InstanceType<typeof PersonalTasks>>();
async function confirmTabLeave() {
  const dirty =
    tab.value === 'mine'
      ? personal.value?.hasUnsavedDraft()
      : tab.value === 'maintenance'
        ? maintenance.value?.hasUnsavedDraft()
        : tab.value === 'requirements'
          ? requirements.value?.hasUnsavedDraft()
          : false;
  if (!dirty) return true;
  try {
    await ElMessageBox.confirm('切换后未提交的内容将被放弃，是否继续？', '放弃草稿', {
      confirmButtonText: '放弃并切换',
      cancelButtonText: '继续编辑'
    });
    return true;
  } catch {
    return false;
  }
}
const maintenance = ref<InstanceType<typeof MaintenancePanel>>(),
  intake = ref<InstanceType<typeof MaintenanceIntakePanel>>();
async function openTask(id: string) {
  if (canManage.value) {
    await router.push({
      query: {
        ...route.query,
        tc_task: id,
        tc_view: undefined,
        tc_problem: undefined,
        tc_intake: undefined,
        tc_requirement: undefined,
        rq_requirement: route.query.rq_requirement
      }
    });
    return;
  }
  if (canReadMine.value) {
    tab.value = 'mine';
    void router.replace({ query: { ...route.query, tc_view: 'mine', taskId: id } });
  } else error.value = '当前账号没有此任务详情的查看权限。';
}
async function returnOrganization() {
  tab.value = 'organization';
  await router.replace({
    query: { ...route.query, tc_view: undefined, tc_problem: undefined, tc_intake: undefined, tc_requirement: undefined, rq_requirement: undefined }
  });
}
async function openWorkflow(value: { kind: string; id: string }) {
  if (value.kind === 'MAINTENANCE' && canMaintain.value) {
    await router.push({
      query: {
        ...route.query,
        tc_view: 'maintenance',
        tc_problem: value.id,
        tc_intake: undefined,
        tc_requirement: undefined,
        rq_requirement: undefined
      }
    });
  } else if (value.kind === 'ANNUAL_REPORT' && access.value.annual)
    void router.push({ path: '/funnyworker/annual', query: { datasetId: value.id, returnTaskCenter: route.fullPath } });
}
async function openIntake(id: string) {
  await router.push({
    query: { ...route.query, tc_view: 'maintenance', tc_problem: undefined, tc_intake: id, rq_requirement: requirements.value?.currentId() }
  });
}
async function openRequirementProblem(id: string) {
  await router.push({
    query: { ...route.query, tc_view: 'maintenance', tc_problem: id, tc_intake: undefined, rq_requirement: requirements.value?.currentId() }
  });
}
async function returnRequirement() {
  await router.replace({
    query: {
      ...route.query,
      tc_view: 'requirements',
      tc_requirement: route.query.rq_requirement,
      tc_problem: undefined,
      tc_intake: undefined,
      rq_requirement: undefined
    }
  });
}
async function restoreView() {
  if (!ready.value) return;
  const q = route.query;
  if (q.tc_view === 'requirements' && canMaintain.value) {
    tab.value = 'requirements';
    await nextTick();
    if (typeof q.tc_requirement === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(q.tc_requirement)) void requirements.value?.inspect(q.tc_requirement);
  } else if (q.tc_view === 'maintenance' && canMaintain.value) {
    tab.value = 'maintenance';
    await nextTick();
    if (typeof q.tc_problem === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(q.tc_problem)) void maintenance.value?.inspect(q.tc_problem);
    if (typeof q.tc_intake === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(q.tc_intake)) void intake.value?.inspect(q.tc_intake);
  } else if (q.tc_view === 'mine' && canReadMine.value) tab.value = 'mine';
  else if (canManage.value && Object.keys(q).some((key) => key.startsWith('tc_'))) tab.value = 'organization';
  else
    tab.value =
      canReadMine.value && (q.taskId || q.state)
        ? 'mine'
        : canManage.value
          ? 'organization'
          : canReadMine.value
            ? 'mine'
            : canMaintain.value
              ? 'maintenance'
              : '';
}
onMounted(async () => {
  try {
    const me = await businessRequest('/me');
    if (!alive) return;
    roles.value = me.roles;
    actorKey.value = JSON.stringify([me.tenant, me.principalId]);
    ready.value = true;
    await restoreView();
  } catch (e) {
    if (alive) error.value = businessErrorMessage(e);
  }
});
onBeforeUnmount(() => {
  alive = false;
});
watch(
  () => route.query,
  () => void restoreView(),
  { deep: true }
);
onBeforeRouteLeave(confirmTabLeave);
onBeforeRouteUpdate((to, from) =>
  JSON.stringify([to.query.tc_view, to.query.tc_requirement, to.query.tc_problem, to.query.tc_intake]) ===
  JSON.stringify([from.query.tc_view, from.query.tc_requirement, from.query.tc_problem, from.query.tc_intake])
    ? true
    : confirmTabLeave()
);
watch(access, () => {
  if (!ready.value) return;
  const allowed =
    tab.value === 'organization'
      ? canManage.value
      : tab.value === 'scope'
        ? canManage.value && checkPermi(['funnyworker:task-center:scope'])
        : tab.value === 'mine'
          ? canReadMine.value
          : canMaintain.value;
  if (!allowed) void restoreView();
});
</script>
<style scoped>
.task-center > h2 {
  margin-top: 0;
}
</style>
