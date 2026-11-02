# 定向执行资源

执行资源复用 FunnyWork 桌面客户端、sync_devices、维护节点、Task／Unit／Attempt、账号状态交换和租约。管理员配置项目、资源池与用途授权，后端按政策自动选择终端并记录放置结果。

## 资源与调度

| 功能 | 机制 |
| --- | --- |
| 资源政策 | 项目、资源池、用途档案、指定成员、项目／仓库／用途／设备或池授权；版本冲突保护、有效期、显式拒绝优先和授权变更审计；仓库的开发／验证路由必须属于同一项目 |
| 调度与容量 | 既有调度器在平台、能力、区域、在线和容量约束之外过滤项目资格；一个单元只预留一个原节点；跨资源池复用节点容量；同一仓库最多一个开发写入者；没有合格资源保持等待 |
| 执行资格校验 | 预留时冻结来源、授权和运行实例；领取、规格读取、续租、原结果回传、上传授权和字节接收复核当前资格；后端候选审查及验收交付前重验来源资格 |

管理页面提供资源配置、只读候选预览和终端展示，见[资源管理模块](EXECUTION_RESOURCES_R4.md)。年报使用独立资料集与 COMPILE 用途，由支持报表协议的桌面生成候选，后端重算核验并交付文件，见[年报编制](ANNUAL_REPORTS_R5.md)。仓库任务与年报共享节点容量，分别配置用途及授权。

## 政策来源与迁移

授权默认强制启用；空政策不回退到同区域全部节点。绑定维护设备只建立通道，不授予任何项目资格。项目、池和用途均有租户／区域边界，配置 API 要求 ADMIN；读取要求 VIEWER／OPERATOR 及区域权限。管理身份桥还要求对应页面权限。

为现有维护仓库显式配置项目和开发／验证用途，分别创建路由，再将获准设备加入池，创建有期限的 ALLOW。设备名称、客户端标签、反馈正文和模型推理不能创建授权。池成员关系本身不授予仓库权限。池级 ALLOW 允许当前有效成员；设备级 ALLOW 可仅允许一台设备；任一匹配 DENY 覆盖 ALLOW。

原有已完成记录保留原貌，不补造放置证据。缺少新资格的排队任务等待，历史无放置记录的候选不能因导入而自动进入新交付。遗留协议测试在隔离测试配置中显式关闭强制门禁，以验证旧接口行为；新增安全测试及开发环境保持开启。不得通过关闭门禁让实际任务通过。

## 生命周期

1. 后端需求与技术角色冻结规格；仓库注册 ID 与开发／验证动作决定项目路由，不解析反馈中的自由项目标签。
2. 原调度器检查当前路由、项目、池、用途、成员、授权、设备撤销、账号权限、节点暂停、平台、执行器、运行实例及有效期，再在事务中分配节点并保存放置快照。
3. 开始／续租／报告检查原租约、epoch、runtimeEpoch、specHash、当前授权及快照摘要。任何相关授权版本变化后，旧尝试不能继续使用；恢复成员不复活旧尝试。
4. 原 Mac App 继续提交已有格式的候选 SHA、PR 和检查结果，无需替换客户端。后端拒绝其他设备、项目、旧租约和撤销后回执，包括对已完成报告的重放。
5. 后端独立获取实际远端候选并审查；固定候选 VERIFY 使用单独用途授权。验收和交付前复核开发与验证任务来源资格。客户端没有 DELIVER 通道；旧客户端需求分析通道在新门禁下停止派发，后端承担分析。

开发与验证可以使用同一获准原 Mac，但仍标为 DEVELOPMENT_FUNCTIONAL／CLIENT_REPORTED，不升级为受控宿主证明。项目授权用于控制 FunnyWork 任务派发；宿主安全与 GitHub 写入权限由各自的终端和仓库策略管理，持有长期写凭据的账号仍可直接 push。

## 上传接口

在原账号维护通道增加两个可选接口：

- POST `/api/account/sync/devices/{device}/maintenance/{attempt}/upload-authorizations`
- POST `/api/account/sync/devices/{device}/maintenance/{attempt}/uploads`

申请参数为 runtimeEpoch、epoch、specHash、SHA-256 digest、maxBytes。返回不可猜测的授权 ID 和 expiresAt，不允许指定本地文件路径、对象键或回调地址。许可最多 120 秒，且不超过当前租约；每尝试最多 4 份，每份最多 240000 字节。

上传参数包含相同运行／租约／规格绑定、authorizationId 和 contentBase64。接收时再次检查资格、有效期、大小及实际字节摘要。相同内容幂等，不同内容不能覆写。上传写入隔离候选记录，返回 acceptedAsResult=false；不会触发报告完成、验收或合并。桌面客户端通过既有结果接口提交 PR；调用字节上传接口时需单独完成上述授权流程。

隔离候选存储于业务数据库，运维备份和访问控制应覆盖这些记录。保留与清理按部署的数据管理政策执行。

## 管理接口

| 接口 | 权限／作用 |
| --- | --- |
| GET `/api/v1/execution-resources` | resources:read；区域内项目、池、用途、成员、授权及路由 |
| POST `/api/v1/execution-projects`、`execution-pools` | resources:write + ADMIN；name、zone |
| POST `/api/v1/execution-profiles` | resources:write + ADMIN；name、zone、action=DEVELOP／VERIFY、platforms |
| PUT `/api/v1/execution-{projects\|pools\|profiles}/{id}/enabled` | resources:write + ADMIN；enabled、expectedVersion |
| PUT `/api/v1/execution-pools/{id}/members` | resources:write + ADMIN；deviceId、enabled、expectedVersion |
| POST `/api/v1/execution-grants` | resources:write + ADMIN；projectId、repositoryId、poolId、profileId、deviceId、effect、enabled、expiresAt、expectedVersion；deviceId 空串表示池级 |
| POST `/api/v1/execution-routes` | resources:write + ADMIN；repositoryId、projectId、poolId、profileId、enabled、expectedVersion |
| GET `/api/v1/tasks/{id}/placement` | tasks:read；任务状态、当前／历史放置、冻结授权、写入锁及等待原因 |

新增配置 expectedVersion=0；已有成员／授权／路由必须携当前 revision。项目／池／用途启停携当前 revision，从 1 起。所有更新在租户数据库锁中执行；配置与任务运行继续复用现有租户事务边界。

## 撤销与未知停止

移除成员、禁用用途、授权过期／变更、设备撤销、节点暂停或账号资格变化会使当前尝试不可继续续租及提交。调度补偿扫描取消失效预留。未开始的仓库写入预留释放后，可重新选择当前合格设备。

已经开始的写入进程停止未知时，仓库写入锁变为 UNKNOWN，阻止再次开发。取消任务或账号失权也保留该保护，不把发送取消当作进程已停止。只有当前有效尝试的确定结果回传可正常释放 HELD。UNKNOWN 状态下暂停该仓库的新开发任务，核对实际终端进程和执行回执并保存依据；保留写入锁，不以伪造成功结果释放。

## 关联修复

后端只向归并提供仍未完成的问题，并以程序校验目标状态／仓库／平台；已交付目标不能吞掉新触发条件。错误归并可通过原 retry 接口重新分析，记录原目标与依据，清除的是该反馈的归并链接，不改变已交付问题。

## 验证

使用隔离项目和专用设备，依次核对无授权等待、有效授权定向领取、显式拒绝优先、成员撤销、租约过期、上传摘要与有效期，以及同仓库单写入者约束。恢复授权后重新调度，确认旧尝试仍被拒绝。管理页面操作见[资源管理模块](EXECUTION_RESOURCES_R4.md)，运行检查见[业务服务索引](README.md#检查与交付)。
