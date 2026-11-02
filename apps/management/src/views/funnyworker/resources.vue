<template>
  <div class="app-container resources">
    <div class="toolbar">
      <div>
        <h2>执行资源</h2>
        <p class="hint">配置终端用途与项目范围，后端自动分派任务。</p>
      </div>
      <el-button :loading="busy" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
    <el-alert v-if="catalog && !catalog.enforced" title="项目门禁未启用，预览不可用于确认执行资格。" type="warning" :closable="false" />
    <el-card v-loading="busy">
      <el-tabs v-model="tab">
        <el-tab-pane v-for="kind in ['projects', 'pools', 'profiles']" :key="kind" :name="kind" :label="labels[kind]">
          <div class="toolbar">
            <p class="hint">{{ hints[kind] }}</p>
            <el-button v-if="canWrite" type="primary" :disabled="busy" @click="open(kind)">新增{{ singular[kind] }}</el-button>
          </div>
          <el-table :data="catalog?.[kind] || []" :empty-text="loaded ? '暂无配置' : '尚未读取配置'">
            <el-table-column prop="name" label="名称" min-width="180" /><el-table-column prop="zone" label="区域" width="120" />
            <el-table-column v-if="kind === 'profiles'" label="动作／平台" min-width="160"
              ><template #default="{ row }">{{ executionAction(row.action) }} · {{ platforms(row.platforms_json) }}</template></el-table-column
            >
            <el-table-column label="状态" width="95"
              ><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column
            >
            <el-table-column prop="revision" label="版本" width="75" />
            <el-table-column label="操作" min-width="170"
              ><template #default="{ row }"
                ><el-button v-if="kind === 'pools'" text :disabled="busy" @click="showMembers(row)">成员</el-button
                ><el-button v-if="canWrite" text :disabled="busy" @click="toggle(kind, row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="项目授权" name="grants">
          <div class="toolbar">
            <p class="hint">加入资源池不等于获得项目授权；明确拒绝优先于允许。</p>
            <el-button v-if="canWrite" type="primary" :disabled="busy" @click="open('grants')">新增授权</el-button>
          </div>
          <el-table :data="catalog?.grants || []" empty-text="暂无项目授权">
            <el-table-column label="项目／资料" min-width="185"
              ><template #default="{ row }"
                >{{ name('projects', row.project_id) }}
                <div class="hint">{{ repoName(row.repository_id) }}</div></template
              ></el-table-column
            >
            <el-table-column label="资源池／用途" min-width="170"
              ><template #default="{ row }"
                >{{ name('pools', row.pool_id) }}
                <div class="hint">{{ name('profiles', row.profile_id) }}</div></template
              ></el-table-column
            >
            <el-table-column label="终端范围" min-width="140"
              ><template #default="{ row }">{{ row.device_id ? deviceName(row.device_id) : '池内获准成员' }}</template></el-table-column
            >
            <el-table-column label="判定" width="90"
              ><template #default="{ row }">{{ row.effect === 'DENY' ? '拒绝' : '允许' }}</template></el-table-column
            >
            <el-table-column label="状态／到期" min-width="175"
              ><template #default="{ row }"
                >{{ !row.enabled ? '停用' : row.expires_at <= Date.now() ? '已到期' : '有效' }}
                <div class="hint">{{ time(row.expires_at) }}</div></template
              ></el-table-column
            >
            <el-table-column label="操作" width="90"
              ><template #default="{ row }"
                ><el-button v-if="canWrite" text :disabled="busy" @click="open('grants', row)">配置</el-button></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="路由" name="routes">
          <div class="toolbar">
            <p class="hint">仓库开发、验证和年报编制分别指定资源池与用途。</p>
            <el-button v-if="canWrite" type="primary" :disabled="busy" @click="open('routes')">配置路由</el-button>
          </div>
          <el-table :data="catalog?.routes || []" empty-text="尚未配置路由，无路由的任务将等待">
            <el-table-column label="资源" min-width="180"
              ><template #default="{ row }">{{ repoName(row.repository_id) }}</template></el-table-column
            >
            <el-table-column label="动作" width="80"
              ><template #default="{ row }">{{ executionAction(row.action) }}</template></el-table-column
            >
            <el-table-column label="项目" min-width="130"
              ><template #default="{ row }">{{ name('projects', row.project_id) }}</template></el-table-column
            >
            <el-table-column label="资源池／用途" min-width="170"
              ><template #default="{ row }"
                >{{ name('pools', row.pool_id) }}
                <div class="hint">{{ name('profiles', row.profile_id) }}</div></template
              ></el-table-column
            >
            <el-table-column label="状态／版本" width="120"
              ><template #default="{ row }">{{ row.enabled ? '启用' : '停用' }} · {{ row.revision }}</template></el-table-column
            >
            <el-table-column label="操作" width="90"
              ><template #default="{ row }"
                ><el-button v-if="canWrite" text :disabled="busy" @click="open('routes', row)">配置</el-button></template
              ></el-table-column
            >
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="终端预览" name="preview">
          <p class="hint">预览当前已保存的路由与授权，不派发任务、不预留名额。实际领取时会再次校验。</p>
          <el-form inline @submit.prevent="preview(false)">
            <el-form-item label="资源"
              ><el-select filterable v-model="selection.repositoryId" aria-label="预览资源" style="width: 260px"
                ><el-option v-for="r in catalog?.repositories || []" :key="r.id" :label="r.slug" :value="r.id" /></el-select
            ></el-form-item>
            <el-form-item label="动作"
              ><el-select v-model="selection.action" aria-label="预览动作" style="width: 110px"
                ><el-option label="开发" value="DEVELOP" /><el-option label="验证" value="VERIFY" /><el-option
                  label="年报编制"
                  value="COMPILE" /></el-select
            ></el-form-item>
            <el-form-item label="平台"
              ><el-select v-model="selection.platform" aria-label="预览平台" style="width: 120px"
                ><el-option v-for="p in platformOptions" :key="p" :label="executionPlatform(p)" :value="p" /></el-select
            ></el-form-item>
            <el-form-item label="终端"><el-input v-model="selection.query" maxlength="120" clearable placeholder="名称或完整编号" /></el-form-item>
            <el-form-item
              ><el-button native-type="submit" :loading="previewBusy" :disabled="!selection.repositoryId || busy">预览</el-button></el-form-item
            >
          </el-form>
          <template v-if="previewData">
            <p role="status">
              已加载 {{ previewRows.length }} 台 · 获准 {{ previewRows.filter((x) => x.authorized).length }} 台 · 当前可用
              {{ previewRows.filter((x) => x.available).length }} 台<span class="hint"> · {{ time(previewData.observedAt) }}</span>
            </p>
            <el-alert
              v-if="!previewRows.some((x) => x.available)"
              title="当前已加载终端无可用候选，任务将继续等待；可加载后续终端检查。"
              type="info"
              :closable="false"
            />
            <el-table :data="previewRows" empty-text="当前查询未找到可见终端">
              <el-table-column label="终端" min-width="190"
                ><template #default="{ row }"
                  >{{ row.name }}
                  <div class="hint identifier">{{ row.id }}</div></template
                ></el-table-column
              >
              <el-table-column label="平台" width="100"
                ><template #default="{ row }">{{ executionPlatform(row.platform) }}</template></el-table-column
              >
              <el-table-column label="资格／空闲" width="120"
                ><template #default="{ row }">{{ row.authorized ? '获准' : '未获准' }} · {{ row.busy }}/{{ row.capacity }}</template></el-table-column
              >
              <el-table-column label="预览结果" width="110"
                ><template #default="{ row }">{{ row.available ? '可参与分派' : '暂不可选' }}</template></el-table-column
              >
              <el-table-column label="排除原因" min-width="270"
                ><template #default="{ row }">{{
                  row.reasons.length ? row.reasons.map((r: string) => executionReasons[r] || '当前条件未满足').join('；') : '符合当前用途要求'
                }}</template></el-table-column
              > </el-table
            ><el-button v-if="previewData.hasMore" :loading="previewBusy" @click="preview(true)">加载更多终端</el-button> </template
          ><el-empty v-else description="选择资源与用途后预览终端" />
        </el-tab-pane>
        <el-tab-pane v-if="canWrite" label="三端版本检查" name="sync-checks">
          <SyncChecks
            :key="me?.tenant + ':' + me?.principalId"
            :repositories="(catalog?.repositories || []).filter((repository: any) => !repository.report_year)"
            :enabled="catalog?.enforced === true"
            :session-scope="me?.tenant + ':' + me?.principalId"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-dialog
      v-model="editing"
      :title="editorTitle"
      width="min(620px,95vw)"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
    >
      <el-form label-width="100px" :disabled="busy">
        <template v-if="['projects', 'pools', 'profiles'].includes(kind)">
          <el-form-item label="名称"><el-input v-model="form.name" maxlength="120" /></el-form-item>
          <el-form-item label="区域"
            ><el-select v-model="form.zone" aria-label="配置区域"
              ><el-option v-for="zone in me?.zones || []" :key="zone" :label="zone" :value="zone" /></el-select
          ></el-form-item>
          <template v-if="kind === 'profiles'"
            ><el-form-item label="动作"
              ><el-radio-group v-model="form.action"
                ><el-radio value="DEVELOP">开发</el-radio><el-radio value="VERIFY">验证</el-radio
                ><el-radio value="COMPILE">年报编制</el-radio></el-radio-group
              ></el-form-item
            ><el-form-item label="平台"
              ><el-checkbox-group v-model="form.platforms"
                ><el-checkbox v-for="p in platformOptions" :key="p" :value="p">{{ executionPlatform(p) }}</el-checkbox></el-checkbox-group
              ></el-form-item
            >
            <p class="hint">用途创建后保留动作和平台；需要调整时创建新用途。办公终端仍按开发环境证据处理。</p></template
          >
        </template>
        <template v-else>
          <el-form-item label="项目"
            ><el-select filterable v-model="form.projectId" aria-label="配置项目"
              ><el-option v-for="p in catalog?.projects || []" :key="p.id" :label="p.name + (p.enabled ? '' : '（停用）')" :value="p.id" /></el-select
          ></el-form-item>
          <el-form-item label="资源"
            ><el-select filterable v-model="form.repositoryId" aria-label="配置仓库"
              ><el-option v-for="r in catalog?.repositories || []" :key="r.id" :label="r.slug" :value="r.id" /></el-select
          ></el-form-item>
          <el-form-item label="资源池"
            ><el-select filterable v-model="form.poolId" aria-label="配置资源池"
              ><el-option v-for="p in catalog?.pools || []" :key="p.id" :label="p.name + (p.enabled ? '' : '（停用）')" :value="p.id" /></el-select
          ></el-form-item>
          <el-form-item label="用途"
            ><el-select filterable v-model="form.profileId" aria-label="配置用途"
              ><el-option
                v-for="p in catalog?.profiles || []"
                :key="p.id"
                :label="p.name + ' · ' + executionAction(p.action)"
                :value="p.id" /></el-select
          ></el-form-item>
          <template v-if="kind === 'grants'">
            <el-form-item label="终端范围"
              ><el-select v-model="form.deviceId" aria-label="授权终端" clearable placeholder="池内全部获准成员"
                ><el-option
                  v-for="m in poolMembers"
                  :key="m.device_id"
                  :label="deviceName(m.device_id) + (m.enabled ? '' : '（已移出）')"
                  :value="m.device_id" /></el-select
            ></el-form-item>
            <el-form-item label="判定"
              ><el-radio-group v-model="form.effect"
                ><el-radio value="ALLOW">允许</el-radio><el-radio value="DENY">拒绝</el-radio></el-radio-group
              ></el-form-item
            >
            <el-form-item label="到期时间"
              ><el-date-picker v-model="form.expiresAt" aria-label="授权到期时间" type="datetime" placeholder="选择到期时间"
            /></el-form-item>
          </template>
          <el-form-item label="启用"><el-switch v-model="form.enabled" aria-label="启用配置" /></el-form-item>
          <p class="hint">保存后的政策自动生效。修改正在使用的授权或路由会使旧执行许可失效。</p>
        </template> </el-form
      ><el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template #footer
        ><el-button :disabled="busy" @click="editing = false">取消</el-button
        ><el-button type="primary" :loading="busy" :disabled="!valid || !canWrite" @click="save">保存</el-button></template
      >
    </el-dialog>
    <el-drawer v-model="membersOpen" :title="'资源池成员 · ' + (memberPool?.name || '')" size="min(700px,95vw)">
      <p class="hint">成员资格与项目授权分别配置；加入此池不会自动获得仓库权限。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table :data="memberRows" empty-text="暂无成员"
        ><el-table-column label="终端" min-width="200"
          ><template #default="{ row }"
            >{{ deviceName(row.device_id) }}
            <div class="hint identifier">{{ row.device_id }}</div></template
          ></el-table-column
        ><el-table-column label="状态" width="100"
          ><template #default="{ row }">{{ row.enabled ? '池内成员' : '已移出' }}</template></el-table-column
        ><el-table-column label="操作" width="100"
          ><template #default="{ row }"
            ><el-button v-if="canWrite" text :disabled="busy" @click="setMember(row.device_id, !row.enabled)">{{
              row.enabled ? '移出' : '加入'
            }}</el-button></template
          ></el-table-column
        ></el-table
      >
      <template v-if="canWrite"
        ><h3>添加终端</h3>
        <el-input v-model="deviceQuery" maxlength="120" placeholder="按名称或编号搜索" @change="searchDevices" /><el-select
          v-model="memberDevice"
          aria-label="添加池成员"
          filterable
          style="width: 100%; margin: 12px 0"
          ><el-option
            v-for="d in devices"
            :key="d.id"
            :value="d.id"
            :label="d.name + ' · ' + executionPlatform(d.platform) + ' · ' + d.id.slice(0, 8) + (d.bound ? '' : ' · 未接入任务通道')"
            :disabled="d.revoked" /></el-select
        ><el-button :disabled="!memberDevice || busy" @click="setMember(memberDevice, true)">加入资源池</el-button
        ><el-button v-if="devicesMore" :loading="devicesBusy" @click="loadDevices(true)">加载更多终端</el-button></template
      >
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import SyncChecks from './sync-checks.vue';
import { businessRequest as request } from '@/utils/funnyworker-api';
import { businessErrorMessage, businessAccessLost, saveAndRefresh } from '@/utils/business-feedback';
import { checkPermi } from '@/utils/permission';
import { executionAction, executionPlatform, executionReasons } from './execution-copy';
const labels: Record<string, string> = { projects: '项目', pools: '资源池', profiles: '用途' },
  singular = labels;
const hints: Record<string, string> = {
  projects: '项目确定业务归属；仓库的开发与验证属于同一项目。',
  pools: '资源池指定可参与一类工作的原客户端，成员仍需项目授权。',
  profiles: '用途区分开发与验证动作，并限定平台。'
};
const platformOptions = ['macos', 'windows', 'linux'];
const tab = ref('projects'),
  catalog = ref<any>(),
  me = ref<any>(),
  loaded = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('');
let alive = true;
const canWrite = computed(() => me.value?.roles?.includes('ADMIN') && checkPermi(['funnyworker:resources:write']));
const name = (kind: string, id: string) => catalog.value?.[kind]?.find((x: any) => x.id === id)?.name || id;
const repoName = (id: string) => catalog.value?.repositories?.find((x: any) => x.id === id)?.slug || id;
const time = (ms: number | string) => (ms ? new Date(Number(ms)).toLocaleString() : '未设置');
const platforms = (raw: string) => {
  try {
    return JSON.parse(raw).map(executionPlatform).join('、');
  } catch {
    return '未设置';
  }
};
const devices = ref<any[]>([]),
  devicesMore = ref(false),
  devicesAfter = ref(''),
  devicesBusy = ref(false),
  deviceQuery = ref('');
let deviceGeneration = 0;
const deviceName = (id: string) =>
  devices.value.find((d) => d.id === id)?.name || catalog.value?.members?.find((m: any) => m.device_id === id)?.device_name || id;
function failure(e: unknown) {
  error.value = businessErrorMessage(e);
  if (businessAccessLost(e)) {
    invalidatePreview();
    catalog.value = undefined;
    me.value = undefined;
    loaded.value = false;
    editing.value = false;
    membersOpen.value = false;
    devices.value = [];
    previewData.value = undefined;
    previewRows.value = [];
  }
}
async function read() {
  invalidatePreview();
  try {
    const [data, identity] = await Promise.all([request('/execution-resources'), request('/me')]);
    if (alive) {
      data.repositories = [
        ...(data.repositories || []),
        ...(data.datasets || []).map((d: any) => ({ ...d, slug: '年报资料 · ' + d.name + ' · ' + d.report_year }))
      ];
      data.grants = [...data.grants, ...(data.datasetGrants || [])];
      data.routes = [...data.routes, ...(data.datasetRoutes || [])];
      catalog.value = data;
      me.value = identity;
      loaded.value = true;
    }
  } catch (e) {
    catalog.value = undefined;
    loaded.value = false;
    throw e;
  }
}
async function run(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    await work();
  } catch (e) {
    if (alive) failure(e);
  } finally {
    if (alive) busy.value = false;
  }
}
async function load() {
  await run(read);
}
async function mutate(write: () => Promise<void>) {
  await run(async () => {
    invalidatePreview();
    notice.value = await saveAndRefresh(write, read);
    previewData.value = undefined;
    previewRows.value = [];
  });
}
const editing = ref(false),
  kind = ref(''),
  editorTitle = computed(() =>
    kind.value === 'grants' ? '配置项目授权' : kind.value === 'routes' ? '配置路由' : '新增' + (labels[kind.value] || '')
  );
const form = reactive<any>({});
const poolMembers = computed(() => catalog.value?.members?.filter((m: any) => m.pool_id === form.poolId) || []);
watch(
  () => form.poolId,
  () => {
    if (form.deviceId && !poolMembers.value.some((m: any) => m.device_id === form.deviceId)) form.deviceId = '';
  }
);
function open(type: string, row?: any) {
  if (!canWrite.value || busy.value) return;
  error.value = '';
  kind.value = type;
  Object.assign(form, {
    name: '',
    zone: me.value?.zones?.[0] || '',
    action: 'DEVELOP',
    platforms: ['macos'],
    projectId: row?.project_id || '',
    repositoryId: row?.repository_id || '',
    poolId: row?.pool_id || '',
    profileId: row?.profile_id || '',
    deviceId: row?.device_id || '',
    effect: row?.effect || 'ALLOW',
    enabled: row?.enabled ?? false,
    expiresAt: new Date(Number(row?.expires_at || Date.now() + 86400000))
  });
  editing.value = true;
  void loadDevices(false);
}
const valid = computed(() =>
  ['projects', 'pools', 'profiles'].includes(kind.value)
    ? Boolean(form.name?.trim() && form.zone && (kind.value !== 'profiles' || form.platforms?.length))
    : Boolean(
        form.projectId &&
        form.repositoryId &&
        form.poolId &&
        form.profileId &&
        (kind.value !== 'grants' || (Number(form.expiresAt) > 0 && (!form.enabled || Number(form.expiresAt) > Date.now())))
      )
);
async function save() {
  if (!valid.value || !canWrite.value) return;
  await mutate(async () => {
    let body: any,
      path = '';
    if (['projects', 'pools', 'profiles'].includes(kind.value)) {
      path = '/execution-' + kind.value;
      body = { name: form.name.trim(), zone: form.zone, ...(kind.value === 'profiles' ? { action: form.action, platforms: form.platforms } : {}) };
    } else {
      body = { projectId: form.projectId, repositoryId: form.repositoryId, poolId: form.poolId, profileId: form.profileId, enabled: form.enabled };
      path = '/execution-' + kind.value;
      const action = catalog.value.profiles.find((x: any) => x.id === form.profileId)?.action;
      const old =
        kind.value === 'routes'
          ? catalog.value.routes.find((r: any) => r.repository_id === form.repositoryId && r.action === action)
          : catalog.value.grants.find(
              (r: any) =>
                r.project_id === form.projectId &&
                r.repository_id === form.repositoryId &&
                r.pool_id === form.poolId &&
                r.profile_id === form.profileId &&
                r.device_id === (form.deviceId || '')
            );
      body.expectedVersion = old?.revision || 0;
      if (kind.value === 'grants') Object.assign(body, { deviceId: form.deviceId || '', effect: form.effect, expiresAt: Number(form.expiresAt) });
    }
    await request(path, 'POST', body);
    editing.value = false;
  });
}
async function toggle(type: string, row: any) {
  if (!canWrite.value) return;
  try {
    await ElMessageBox.confirm((row.enabled ? '停用' : '启用') + '“' + row.name + '”？政策变化会重新校验执行资格。', '更新配置', { type: 'warning' });
  } catch {
    return;
  }
  await mutate(() =>
    request('/execution-' + type + '/' + encodeURIComponent(row.id) + '/enabled', 'PUT', { enabled: !row.enabled, expectedVersion: row.revision })
  );
}
const membersOpen = ref(false),
  memberPool = ref<any>(),
  memberDevice = ref('');
const memberRows = computed(() => catalog.value?.members?.filter((m: any) => m.pool_id === memberPool.value?.id) || []);
async function showMembers(pool: any) {
  memberPool.value = pool;
  memberDevice.value = '';
  deviceQuery.value = '';
  membersOpen.value = true;
  await loadDevices(false);
}
async function searchDevices() {
  await loadDevices(false);
}
async function loadDevices(more = false) {
  if (more && devicesBusy.value) return;
  const current = ++deviceGeneration;
  devicesBusy.value = true;
  try {
    const page = await request(
      '/execution-resources/devices?after=' + encodeURIComponent(more ? devicesAfter.value : '') + '&query=' + encodeURIComponent(deviceQuery.value)
    );
    if (alive && current === deviceGeneration) {
      devices.value = more ? [...devices.value, ...page.items] : page.items;
      devicesAfter.value = page.after;
      devicesMore.value = page.hasMore;
    }
  } catch (e) {
    if (current === deviceGeneration) failure(e);
  } finally {
    if (current === deviceGeneration) devicesBusy.value = false;
  }
}
async function setMember(deviceId: string, enabled: boolean) {
  if (!memberPool.value || !canWrite.value) return;
  const old = memberRows.value.find((m: any) => m.device_id === deviceId);
  await mutate(() =>
    request('/execution-pools/' + encodeURIComponent(memberPool.value.id) + '/members', 'PUT', {
      deviceId,
      enabled,
      expectedVersion: old?.revision || 0
    })
  );
  memberDevice.value = '';
}
const selection = reactive({ repositoryId: '', action: 'DEVELOP', platform: 'macos', query: '' }),
  previewData = ref<any>(),
  previewRows = ref<any[]>([]),
  previewBusy = ref(false);
let previewGeneration = 0;
function invalidatePreview() {
  previewGeneration++;
  previewData.value = undefined;
  previewRows.value = [];
  previewBusy.value = false;
}
watch(
  () => JSON.stringify(selection),
  () => {
    previewGeneration++;
    previewData.value = undefined;
    previewRows.value = [];
    previewBusy.value = false;
  }
);
async function preview(more = false) {
  if (previewBusy.value || !selection.repositoryId) return;
  const current = ++previewGeneration;
  previewBusy.value = true;
  error.value = '';
  if (!more) {
    previewData.value = undefined;
    previewRows.value = [];
  }
  try {
    const result = await request('/execution-placement/preview', 'POST', { ...selection, after: more ? previewData.value?.after || '' : '' });
    if (alive && current === previewGeneration) {
      previewData.value = result;
      previewRows.value = more ? [...previewRows.value, ...result.items] : result.items;
    }
  } catch (e) {
    if (current === previewGeneration) failure(e);
  } finally {
    if (current === previewGeneration) previewBusy.value = false;
  }
}
onMounted(load);
onBeforeUnmount(() => {
  alive = false;
  deviceGeneration++;
  previewGeneration++;
});
</script>
<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.identifier {
  overflow-wrap: anywhere;
}
.resources h2 {
  margin: 0;
}
.resources > .toolbar {
  margin-bottom: 16px;
}
.el-alert {
  margin: 12px 0;
}
.el-select {
  min-width: 180px;
}
.el-form .el-select {
  width: 100%;
}
.el-table {
  margin: 12px 0;
}
.el-dialog .hint {
  line-height: 1.6;
}
</style>
