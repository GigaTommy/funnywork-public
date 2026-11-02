<template>
  <div class="p-2">
    <el-row :gutter="20">
      <!-- 部门树 -->
      <el-col :lg="4" :xs="24" style="">
        <el-card shadow="hover">
          <el-input v-model="deptName" placeholder="请输入部门名称" prefix-icon="Search" clearable />
          <el-tree
            ref="deptTreeRef"
            class="mt-2"
            node-key="id"
            :data="deptOptions"
            :props="{ label: 'label', children: 'children' } as any"
            :expand-on-click-node="false"
            :filter-node-method="filterNode"
            highlight-current
            default-expand-all
            @node-click="handleNodeClick"
          />
        </el-card>
      </el-col>
      <el-col :lg="20" :xs="24">
        <transition :enter-active-class="proxy?.animate.searchAnimate.enter" :leave-active-class="proxy?.animate.searchAnimate.leave">
          <div v-show="showSearch" class="mb-[10px]">
            <el-card shadow="hover">
              <el-form ref="queryFormRef" :model="queryParams" :inline="true">
                <el-form-item label="账号" prop="userName">
                  <el-input v-model="queryParams.userName" placeholder="请输入账号" clearable @keyup.enter="handleQuery" />
                </el-form-item>
                <el-form-item label="昵称" prop="nickName">
                  <el-input v-model="queryParams.nickName" placeholder="请输入昵称" clearable @keyup.enter="handleQuery" />
                </el-form-item>
                <el-form-item label="手机号码" prop="phonenumber">
                  <el-input v-model="queryParams.phonenumber" placeholder="请输入手机号码" clearable @keyup.enter="handleQuery" />
                </el-form-item>

                <el-form-item label="账号来源" prop="accountSource">
                  <el-select v-model="queryParams.accountSource" placeholder="全部来源" clearable style="width: 150px">
                    <el-option label="本地账号" value="local" /><el-option label="统一身份账号" value="external" />
                  </el-select>
                </el-form-item>
                <el-form-item label="状态" prop="status">
                  <el-select v-model="queryParams.status" placeholder="用户状态" clearable>
                    <el-option v-for="dict in sys_normal_disable" :key="dict.value" :label="dict.label" :value="dict.value" />
                  </el-select>
                </el-form-item>
                <el-form-item label="创建时间" style="width: 308px">
                  <el-date-picker
                    v-model="dateRange"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    type="daterange"
                    range-separator="-"
                    start-placeholder="开始日期"
                    end-placeholder="结束日期"
                    :default-time="[new Date(2000, 1, 1, 0, 0, 0), new Date(2000, 1, 1, 23, 59, 59)]"
                  ></el-date-picker>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
                  <el-button icon="Refresh" @click="resetQuery">重置</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </div>
        </transition>

        <el-card shadow="hover">
          <template #header>
            <el-row :gutter="10">
              <el-col :span="1.5">
                <el-button v-has-permi="['system:user:add']" type="primary" plain icon="Plus" @click="handleAdd()">新增本地用户</el-button>
              </el-col>
              <el-col :span="1.5">
                <el-button v-has-permi="['system:user:edit']" type="success" plain :disabled="single" icon="Edit" @click="handleUpdate()">
                  修改
                </el-button>
              </el-col>
              <el-col :span="1.5">
                <el-button v-has-permi="['system:user:remove']" type="danger" plain :disabled="multiple" icon="Delete" @click="handleDelete()">
                  删除
                </el-button>
              </el-col>
              <el-col :span="1.5">
                <el-dropdown class="mt-[1px]">
                  <el-button plain type="info">
                    更多
                    <el-icon class="el-icon--right"><arrow-down /></el-icon
                  ></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item icon="Download" @click="importTemplate">下载模板</el-dropdown-item>
                      <!-- 注意 由于el-dropdown-item标签是延迟加载的 所以v-has-permi自定义标签不生效 需要使用v-if调用方法执行 -->
                      <el-dropdown-item v-if="checkPermi(['system:user:import'])" icon="Top" @click="handleImport">导入数据</el-dropdown-item>
                      <el-dropdown-item v-if="checkPermi(['system:user:export'])" icon="Download" @click="handleExport">导出数据</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </el-col>
              <right-toolbar v-model:show-search="showSearch" :columns="columns" :search="true" @query-table="getList"></right-toolbar>
            </el-row>
          </template>

          <el-table v-loading="loading" border :data="userList" @selection-change="handleSelectionChange">
            <el-table-column type="selection" width="50" align="center" />
            <el-table-column v-if="columns[0].visible" key="userId" label="用户编号" align="center" prop="userId" />
            <el-table-column v-if="columns[1].visible" key="userName" label="账号" align="center" prop="userName" :show-overflow-tooltip="true" />
            <el-table-column label="账号来源" min-width="140" align="center"
              ><template #default="{ row }">{{ row.externalSource ? '统一身份 · ' + row.externalSource : '本地账号' }}</template></el-table-column
            >
            <el-table-column v-if="columns[2].visible" key="nickName" label="昵称" align="center" prop="nickName" :show-overflow-tooltip="true" />
            <el-table-column v-if="columns[3].visible" key="deptName" label="部门" align="center" prop="deptName" :show-overflow-tooltip="true" />
            <el-table-column v-if="columns[4].visible" key="phonenumber" label="手机号码" align="center" prop="phonenumber" width="120" />
            <el-table-column v-if="columns[5].visible" key="status" label="状态" align="center">
              <template #default="scope">
                <el-switch v-model="scope.row.status" active-value="0" inactive-value="1" @change="handleStatusChange(scope.row)"></el-switch>
              </template>
            </el-table-column>

            <el-table-column v-if="columns[6].visible" label="创建时间" align="center" prop="createTime" width="160">
              <template #default="scope">
                <span>{{ scope.row.createTime }}</span>
              </template>
            </el-table-column>

            <el-table-column label="操作" fixed="right" width="240" class-name="small-padding fixed-width">
              <template #default="scope">
                <el-button
                  v-hasPermi="['system:user:query']"
                  link
                  @click="
                    readinessUser = scope.row.userId;
                    readinessOpen = true;
                  "
                  >使用状态</el-button
                >
                <el-tooltip v-if="scope.row.userId !== 1" content="修改" placement="top">
                  <el-button v-hasPermi="['system:user:edit']" link type="primary" icon="Edit" @click="handleUpdate(scope.row)"></el-button>
                </el-tooltip>
                <el-tooltip v-if="scope.row.userId !== 1" content="删除" placement="top">
                  <el-button v-hasPermi="['system:user:remove']" link type="primary" icon="Delete" @click="handleDelete(scope.row)"></el-button>
                </el-tooltip>

                <el-tooltip v-if="scope.row.userId !== 1 && !scope.row.externalSource" content="重置密码" placement="top">
                  <el-button v-hasPermi="['system:user:resetPwd']" link type="primary" icon="Key" @click="handleResetPwd(scope.row)"></el-button>
                </el-tooltip>

                <el-tooltip v-if="scope.row.userId !== 1" content="分配角色" placement="top">
                  <el-button v-hasPermi="['system:user:edit']" link type="primary" icon="CircleCheck" @click="handleAuthRole(scope.row)"></el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>

          <pagination
            v-show="total > 0"
            v-model:page="queryParams.pageNum"
            v-model:limit="queryParams.pageSize"
            :total="total"
            @pagination="getList"
          />
        </el-card>
      </el-col>
    </el-row>

    <!-- 添加或修改用户配置对话框 -->
    <el-dialog
      ref="formDialogRef"
      v-model="dialog.visible"
      :title="dialog.title"
      width="min(600px, 95vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!savingUser"
      :show-close="!savingUser"
      :before-close="beforeUserClose"
      @close="closeDialog"
    >
      <el-alert v-if="userSaveNotice" :title="userSaveNotice" type="warning" :closable="false" class="mb-4" />
      <el-form ref="userFormRef" :model="form" :rules="rules" label-width="80px" :disabled="savingUser">
        <el-row>
          <el-col :span="12">
            <el-form-item label="昵称" prop="nickName">
              <el-input v-model="form.nickName" placeholder="请输入昵称" maxlength="30" />
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.userId == null || form.userId != useUserStore().userId">
            <el-form-item label="归属部门" prop="deptId">
              <el-tree-select
                v-model="form.deptId"
                :data="enabledDeptOptions"
                :props="{ value: 'id', label: 'label', children: 'children' } as any"
                value-key="id"
                placeholder="请选择归属部门"
                check-strictly
                @change="handleDeptChange"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="手机号码" prop="phonenumber">
              <el-input v-model="form.phonenumber" placeholder="请输入手机号码" maxlength="11" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="form.email" placeholder="请输入邮箱" maxlength="50" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item v-if="form.userId == undefined" label="账号" prop="userName">
              <el-input v-model="form.userName" placeholder="请输入账号" maxlength="30" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item v-if="form.userId == undefined" label="用户密码" prop="password">
              <el-input v-model="form.password" placeholder="请输入用户密码" type="password" maxlength="20" show-password />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="用户性别">
              <el-select v-model="form.sex" placeholder="请选择">
                <el-option v-for="dict in sys_user_sex" :key="dict.value" :label="dict.label" :value="dict.value"></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12" v-if="form.userId == null || form.userId != useUserStore().userId">
            <el-form-item label="岗位">
              <el-select v-model="form.postIds" multiple placeholder="请选择">
                <el-option
                  v-for="item in postOptions"
                  :key="item.postId"
                  :label="item.postName"
                  :value="item.postId"
                  :disabled="item.status == '1'"
                ></el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.userId == null || form.userId != useUserStore().userId">
            <el-form-item label="角色" prop="roleIds">
              <el-select v-model="form.roleIds" filterable multiple placeholder="请选择">
                <el-option
                  v-for="item in roleOptions"
                  :key="item.roleId"
                  :label="item.roleName"
                  :value="item.roleId"
                  :disabled="item.status == '1'"
                ></el-option>
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" placeholder="请输入内容"></el-input>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="savingUser" @click="submitForm">保存</el-button>
          <el-button :disabled="savingUser" @click="cancel()">取 消</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 用户导入对话框 -->
    <el-dialog
      v-model="upload.open"
      :title="upload.title"
      width="min(400px, 95vw)"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!upload.isUploading"
      :show-close="!upload.isUploading"
    >
      <el-alert v-if="uploadNotice" :title="uploadNotice" type="warning" :closable="false" />
      <el-upload
        ref="uploadRef"
        :limit="1"
        accept=".xlsx, .xls"
        :headers="upload.headers"
        :action="upload.url + '?updateSupport=' + upload.updateSupport"
        :disabled="upload.isUploading"
        :before-upload="beforeFileUpload"
        :on-error="handleFileError"
        :on-success="handleFileSuccess"
        :auto-upload="false"
        drag
      >
        <el-icon class="el-icon--upload">
          <i-ep-upload-filled />
        </el-icon>
        <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="text-center el-upload__tip">
            <div class="el-upload__tip">
              <el-checkbox v-model="upload.updateSupport" :disabled="upload.isUploading">更新已有本地用户资料</el-checkbox>
            </div>
            <div class="el-upload__tip">统一身份账号资料请通过身份源同步，不会被此文件覆盖。</div>
            <span>仅允许导入xls、xlsx格式文件。</span>
            <el-link type="primary" :underline="false" style="font-size: 12px; vertical-align: baseline" @click="importTemplate">下载模板</el-link>
          </div>
        </template>
      </el-upload>
      <template #footer>
        <div class="dialog-footer">
          <el-button type="primary" :loading="upload.isUploading" @click="submitFileForm">开始导入</el-button>
          <el-button :disabled="upload.isUploading" @click="upload.open = false">取 消</el-button>
        </div>
      </template>
    </el-dialog>
    <MemberReadiness v-model="readinessOpen" :user-id="readinessUser" />
  </div>
</template>

<script setup name="User" lang="ts">
import { importFeedback, importFeedbackContent } from '@/utils/import-feedback';
import MemberReadiness from '@/views/funnyworker/MemberReadiness.vue';
const readinessOpen = ref(false),
  readinessUser = ref<string | number>(),
  savingUser = ref(false);
const userSaveNotice = ref('');
const userSaveUncertain = ref(false);
import api from '@/api/system/user';
import { UserForm, UserQuery, UserVO } from '@/api/system/user/types';
import { DeptTreeVO, DeptVO } from '@/api/system/dept/types';
import { RoleVO } from '@/api/system/role/types';
import { PostVO } from '@/api/system/post/types';
import { globalHeaders } from '@/utils/request';
import { to } from 'await-to-js';
import { optionselect } from '@/api/system/post';
import { checkPermi } from '@/utils/permission';
import { useUserStore } from '@/store/modules/user';

const router = useRouter();
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const { sys_normal_disable, sys_user_sex } = toRefs<any>(proxy?.useDict('sys_normal_disable', 'sys_user_sex'));
const userList = ref<UserVO[]>();
const loading = ref(true);
const showSearch = ref(true);
const ids = ref<Array<number | string>>([]);
const single = ref(true);
const multiple = ref(true);
const total = ref(0);
const dateRange = ref<[string, string]>(['', '']);
const deptName = ref('');
const deptOptions = ref<DeptTreeVO[]>([]);
const enabledDeptOptions = ref<DeptTreeVO[]>([]);
const initPassword = ref<string>('');
const postOptions = ref<PostVO[]>([]);
const roleOptions = ref<RoleVO[]>([]);
/*** 用户导入参数 */
const upload = reactive<ImportOption>({
  // 是否显示弹出层（用户导入）
  open: false,
  // 弹出层标题（用户导入）
  title: '',
  // 是否禁用上传
  isUploading: false,
  // 是否更新已经存在的用户数据
  updateSupport: 0,
  // 设置上传的请求头部
  headers: globalHeaders(),
  // 上传的地址
  url: import.meta.env.VITE_APP_BASE_API + '/system/user/importData'
});
// 列显隐信息
const columns = ref<FieldOption[]>([
  { key: 0, label: `用户编号`, visible: false, children: [] },
  { key: 1, label: `账号`, visible: true, children: [] },
  { key: 2, label: `昵称`, visible: true, children: [] },
  { key: 3, label: `部门`, visible: true, children: [] },
  { key: 4, label: `手机号码`, visible: true, children: [] },
  { key: 5, label: `状态`, visible: true, children: [] },
  { key: 6, label: `创建时间`, visible: true, children: [] }
]);

const deptTreeRef = ref<ElTreeInstance>();
const queryFormRef = ref<ElFormInstance>();
const userFormRef = ref<ElFormInstance>();
const uploadRef = ref<ElUploadInstance>();
const formDialogRef = ref<ElDialogInstance>();

const dialog = reactive<DialogOption>({
  visible: false,
  title: ''
});

const initFormData: UserForm = {
  userId: undefined,
  deptId: undefined,
  userName: '',
  nickName: undefined,
  password: '',
  phonenumber: undefined,
  email: undefined,
  sex: undefined,
  status: '0',
  remark: '',
  postIds: [],
  roleIds: []
};

const initData: PageData<UserForm, UserQuery> = {
  form: { ...initFormData },
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    userName: '',
    accountSource: '',
    phonenumber: '',
    status: '',
    deptId: '',
    roleId: ''
  },
  rules: {
    userName: [
      { required: true, message: '账号不能为空', trigger: 'blur' },
      {
        min: 2,
        max: 20,
        message: '账号长度必须介于 2 和 20 之间',
        trigger: 'blur'
      }
    ],
    nickName: [{ required: true, message: '昵称不能为空', trigger: 'blur' }],
    password: [
      { required: true, message: '用户密码不能为空', trigger: 'blur' },
      {
        min: 5,
        max: 20,
        message: '用户密码长度必须介于 5 和 20 之间',
        trigger: 'blur'
      },
      { pattern: /^[^<>"'|\\]+$/, message: '不能包含非法字符：< > " \' \\ |', trigger: 'blur' }
    ],
    email: [
      {
        type: 'email',
        message: '请输入正确的邮箱地址',
        trigger: ['blur', 'change']
      }
    ],
    phonenumber: [
      {
        pattern: /^1[3456789][0-9]\d{8}$/,
        message: '请输入正确的手机号码',
        trigger: 'blur'
      }
    ],
    roleIds: [{ required: true, message: '用户角色不能为空', trigger: 'blur' }]
  }
};
const data = reactive<PageData<UserForm, UserQuery>>(initData);

const { queryParams, form, rules } = toRefs<PageData<UserForm, UserQuery>>(data);

/** 通过条件过滤节点  */
const filterNode = (value: string, data: any) => {
  if (!value) return true;
  return data.label.indexOf(value) !== -1;
};
/** 根据名称筛选部门树 */
watchEffect(
  () => {
    deptTreeRef.value?.filter(deptName.value);
  },
  {
    flush: 'post' // watchEffect会在DOM挂载或者更新之前就会触发，此属性控制在DOM元素更新后运行
  }
);

/** 查询用户列表 */
const getList = async () => {
  loading.value = true;
  try {
    const res = await api.listUser(proxy?.addDateRange(queryParams.value, dateRange.value));
    userList.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};

/** 查询部门下拉树结构 */
const getDeptTree = async () => {
  const res = await api.deptTreeSelect();
  deptOptions.value = res.data;
  enabledDeptOptions.value = filterDisabledDept(res.data);
};

/** 过滤禁用的部门 */
const filterDisabledDept = (deptList: DeptTreeVO[]) => {
  return deptList.filter((dept) => {
    if (dept.disabled) {
      return false;
    }
    if (dept.children && dept.children.length) {
      dept.children = filterDisabledDept(dept.children);
    }
    return true;
  });
};

/** 节点单击事件 */
const handleNodeClick = (data: DeptVO) => {
  queryParams.value.deptId = data.id;
  handleQuery();
};

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.value.pageNum = 1;
  getList();
};
/** 重置按钮操作 */
const resetQuery = () => {
  dateRange.value = ['', ''];
  queryFormRef.value?.resetFields();
  queryParams.value.pageNum = 1;
  queryParams.value.deptId = undefined;
  deptTreeRef.value?.setCurrentKey(undefined);
  handleQuery();
};

/** 删除按钮操作 */
const handleDelete = async (row?: UserVO) => {
  const userIds = row?.userId || ids.value;
  const [err] = await to(proxy?.$modal.confirm('是否确认删除用户编号为"' + userIds + '"的数据项？') as any);
  if (!err) {
    await api.delUser(userIds);
    await getList();
    proxy?.$modal.msgSuccess('删除成功');
  }
};

/** 用户状态修改  */
const handleStatusChange = async (row: UserVO) => {
  const text = row.status === '0' ? '启用' : '停用';
  try {
    await proxy?.$modal.confirm('确认要"' + text + '""' + row.userName + '"用户吗?');
    await api.changeUserStatus(row.userId, row.status);
    proxy?.$modal.msgSuccess(text + '成功');
  } catch (err) {
    row.status = row.status === '0' ? '1' : '0';
  }
};
/** 跳转角色分配 */
const handleAuthRole = (row: UserVO) => {
  const userId = row.userId;
  router.push('/system/user-auth/role/' + userId);
};

/** 重置密码按钮操作 */
const handleResetPwd = async (row: UserVO) => {
  const [err, res] = await to(
    ElMessageBox.prompt('请输入"' + row.userName + '"的新密码', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      closeOnClickModal: false,
      inputPattern: /^.{5,20}$/,
      inputErrorMessage: '用户密码长度必须介于 5 和 20 之间',
      inputValidator: (value) => {
        if (/<|>|"|'|\||\\/.test(value)) {
          return '不能包含非法字符：< > " \' \\ |';
        }
      }
    })
  );
  if (!err && res) {
    await api.resetUserPwd(row.userId, res.value);
    proxy?.$modal.msgSuccess('修改成功，新密码是：' + res.value);
  }
};

/** 选择条数  */
const handleSelectionChange = (selection: UserVO[]) => {
  ids.value = selection.map((item) => item.userId);
  single.value = selection.length != 1;
  multiple.value = !selection.length;
};

/** 导入按钮操作 */
const handleImport = () => {
  upload.title = '用户导入';
  upload.open = true;
};
/** 导出按钮操作 */
const handleExport = () => {
  proxy?.download(
    'system/user/export',
    {
      ...queryParams.value
    },
    `user_${new Date().getTime()}.xlsx`
  );
};
/** 下载模板操作 */
const importTemplate = () => {
  proxy?.download('system/user/importTemplate', {}, `user_template_${new Date().getTime()}.xlsx`);
};

const uploadNotice = ref('');
const beforeFileUpload = () => {
  if (upload.isUploading) return false;
  uploadNotice.value = '';
  upload.isUploading = true;
  return true;
};
const handleFileError = () => {
  upload.isUploading = false;
  uploadRef.value?.clearFiles();
  uploadNotice.value = '连接中断，导入结果尚未确认。部分记录可能已经保存，请先刷新列表核对，再重新选择需要处理的文件。';
  void getList().catch(() => {
    uploadNotice.value += ' 列表刷新失败，请恢复连接后刷新。';
  });
};
/** Display text nodes, never execute markup from an imported account or server message. */
const handleFileSuccess = (response: unknown, file: UploadFile) => {
  upload.isUploading = false;
  uploadRef.value?.handleRemove(file);
  const result = importFeedback(response);
  upload.open = !result.succeeded;
  uploadNotice.value = result.succeeded ? '' : result.message;
  void ElMessageBox.alert(importFeedbackContent(result.message), result.title).catch(() => {});
  void getList().catch(() => {
    proxy?.$modal.msgWarning('导入返回已收到，但列表刷新失败，请刷新核对。');
  });
};

/** 提交上传文件 */
function submitFileForm() {
  if (upload.isUploading) return;
  uploadRef.value?.submit();
}

/** 重置操作表单 */
const reset = () => {
  form.value = { ...initFormData };
  userFormRef.value?.resetFields();
};
/** 取消按钮 */
let userFormBaseline = '';
const userFormSnapshot = () => JSON.stringify(form.value, (_key, value) => (value == null ? '' : value));
const beforeUserClose = async (done: () => void) => {
  if (savingUser.value) return;
  if (userSaveUncertain.value || userFormSnapshot() !== userFormBaseline) {
    const message = userSaveUncertain.value
      ? '保存结果尚未确认。关闭只放弃本页草稿，不会撤销可能已保存的账号；请返回列表核对账号及使用状态。'
      : '尚有未保存的修改，是否放弃？';
    try {
      await ElMessageBox.confirm(message, '关闭用户表单', {
        confirmButtonText: userSaveUncertain.value ? '关闭并核对' : '放弃修改',
        cancelButtonText: '继续编辑',
        type: 'warning'
      });
    } catch {
      return;
    }
  }
  done();
};
const cancel = () =>
  beforeUserClose(() => {
    dialog.visible = false;
  });

/** 新增按钮操作 */
const handleAdd = async () => {
  userSaveUncertain.value = false;
  userSaveNotice.value = '';
  reset();
  const { data } = await api.getUser();
  dialog.visible = true;
  dialog.title = '新增用户';
  postOptions.value = data.posts;
  roleOptions.value = data.roles;
  form.value.password = initPassword.value.toString();
  await nextTick();
  userFormBaseline = userFormSnapshot();
};

/** 修改按钮操作 */
const handleUpdate = async (row?: UserForm) => {
  userSaveUncertain.value = false;
  userSaveNotice.value = '';
  reset();
  const userId = row?.userId || ids.value[0];
  const { data } = await api.getUser(userId);
  dialog.visible = true;
  dialog.title = '修改用户';
  Object.assign(form.value, data.user);
  postOptions.value = data.posts;
  roleOptions.value = Array.from(new Map([...data.roles, ...data.user.roles].map((role) => [role.roleId, role])).values());
  form.value.postIds = data.postIds;
  form.value.roleIds = data.roleIds;
  form.value.password = '';
  await nextTick();
  userFormBaseline = userFormSnapshot();
};

/** 提交按钮 */
const submitForm = () => {
  userFormRef.value?.validate(async (valid: boolean) => {
    if (valid && !savingUser.value) {
      savingUser.value = true;
      userSaveNotice.value = '';
      try {
        if (form.value.userId) {
          // 自己编辑自己的情况下 不允许编辑角色部门岗位
          if (form.value.userId == useUserStore().userId) {
            form.value.roleIds = null;
            form.value.deptId = null;
            form.value.postIds = null;
          }
          await api.updateUser(form.value);
        } else {
          const result = await api.addUser(form.value);
          const created = result.data?.userId;
          if (created) {
            form.value.userId = created;
            readinessUser.value = created;
            readinessOpen.value = true;
          }
        }
        proxy?.$modal.msgSuccess('操作成功');
        userSaveUncertain.value = false;
        dialog.visible = false;
        try {
          await getList();
        } catch {
          proxy?.$modal.msgWarning('账号已保存，列表刷新失败，请刷新核对，无需重复提交。');
        }
      } catch (error) {
        const transportError = error as { isAxiosError?: boolean; response?: { status: number } };
        const uncertain =
          userSaveUncertain.value || (transportError?.isAxiosError && (!transportError.response || transportError.response.status >= 500));
        userSaveUncertain.value = Boolean(uncertain);
        userSaveNotice.value = uncertain
          ? '保存结果尚未确认，草稿已保留。账号可能已保存，请先在用户列表核对账号及使用状态，再决定是否重试；不要重新创建同一成员。'
          : '保存未完成，草稿已保留。请根据错误提示调整后重试。';
      } finally {
        savingUser.value = false;
      }
    }
  });
};

/**
 * 关闭用户弹窗
 */
const closeDialog = () => {
  dialog.visible = false;
  resetForm();
};

/**
 * 重置表单
 */
const resetForm = () => {
  userFormRef.value?.resetFields();
  userFormRef.value?.clearValidate();

  form.value.id = undefined;
  form.value.status = '1';
};
onMounted(() => {
  getDeptTree(); // 初始化部门数据
  getList(); // 初始化列表数据
  proxy?.getConfigKey('sys.user.initPassword').then((response) => {
    initPassword.value = response.data;
  });
});

async function handleDeptChange(value: number | string) {
  const response = await optionselect(value);
  postOptions.value = response.data;
  form.value.postIds = [];
}
</script>
