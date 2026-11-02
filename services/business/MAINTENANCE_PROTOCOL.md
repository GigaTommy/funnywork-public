# FunnyWork 原客户端维护任务接口

本页描述 `MaintenanceDesktopChannel`、`MaintenanceService`、`DesktopInteractionsController` 和桌面 `funnyworker-history-sync.ts` 的接口。业务服务向已登录的 FunnyWork 客户端派发任务，客户端使用内置 Agent 执行开发及验证；Worker 指该客户端的执行角色。接入配置见[维护说明](MAINTENANCE.md)，自动仓库的后端分析、审查和交付见[自动维护参考](MAINTENANCE_AUTOMATION.md)。[Codex 只读同步检查](MAINTENANCE_CODEX_SYNC.md)是独立的版本与 initialize 探测入口。

## 项目执行授权（R1–R3）

维护默认强制项目／资源池／用途授权；既有设备绑定不足以领取任何仓库任务。原领取、续租及结果接口增加服务端当前资格和放置快照校验，原 App 的请求格式不变。可选的原通道上传授权／字节接口及配置接口见 [定向执行资源](EXECUTION_RESOURCES.md)。强制门禁下客户端 DELIVER 与旧客户端需求分析不再派发，最终交付和分析由后端负责。

## 职责与身份

| 组件 | 职责 |
| --- | --- |
| FunnyWork 管理后端 | 保存指定问题、仓库和不可变规格；复用 Task／Unit／Attempt、节点、区域、配额和租约；保存报告；按当前问题版本及候选 SHA 验收或返工 |
| 原客户端账号通道 | 复用已登记设备、账号认证和设备状态交换；发布维护能力、接收维护帧、续租、回传及重传报告 |
| 原客户端内置 Agent | 复用当前 Host、Workspace、Session、模型目录、权限和审批；在隔离仓库内完成有限代码修改，返回原生事件和用量 |
| 客户端维护执行器 | 用程序运行 Git／GitHub CLI 和配置检查，核对提交及 PR head；负责提交、推送、PR 和本地执行日志；等待确认取消 |
| 管理界面 | 使用现有“任务”页的问题视图，录入需求、绑定原设备、查看检查及 PR、填写验收或返工依据 |

管理员通过设备绑定授权现有同步设备的维护区域。后端内部创建容量为 1 的维护节点，并将设备、租户、节点关联；客户端不领取或保存这个节点的 bearer。绑定不改变桌面账号身份。后端在每次派发、续租、回传时检查账号、设备所有者、撤权、区域及运行代际。

`deviceId` 是原同步设备 ID；`nodeId` 是后端任务节点 ID，二者不同，不能当作兼容别名。`attemptId` 是一次执行尝试；`epoch` 是原任务租约代际；`runtimeEpoch` 是原客户端 Host／同步运行代际。`spec.version` 固定本次问题需求版本；`expectedVersion` 用于管理端决策的并发检查，验收会递增问题版本。`specHash` 由后端生成，客户端原样回传。现行协议不使用旧设计中的 `worker_id`、`job_id`、`lease_token` 或独立 `/workers/register`。

## 管理端接口

路径根为 `/api/v1`；使用现有管理身份桥、业务角色和页面权限，不向客户端传管理凭据。

| 方法与路径 | 请求／用途 |
| --- | --- |
| GET `/desktop-devices` | 原设备列表；管理界面据此选择已登记、未撤销的 FunnyWork 客户端 |
| GET／POST `/maintenance/repositories` | 查询／配置 `slug`、`baseBranch`、`zone`、`checks`；每项检查为 `id`、`argv`、`timeoutSeconds`；仓库配置不可变 |
| POST `/maintenance/device-bindings` | `{deviceId, zone}`；ADMIN 授权原设备，返回 `{deviceId, nodeId}`，不返回节点令牌 |
| GET／POST `/maintenance/problems` | 查询／创建指定问题；POST 要求 `Idempotency-Key` 和 `repositoryId`、`title`、`requirement`、`acceptance`、`platform`、完整 `baseSha` |
| GET `/maintenance/problems/{problemId}` | 当前需求、版本、问题与任务状态、当前及历史报告、验收记录 |
| POST `/maintenance/problems/{problemId}/decision` | `{expectedVersion, action, note, candidateSha}`；`action=accept` 必须带当前候选 SHA，`rework` 形成下一需求版本和新任务 |

管理界面保留现有 `tasks:read/write`、仓库配置的 `agents:write` 和设备绑定的 `nodes:write` 权限。后端仍验证租户及区域；权限按钮隐藏不是唯一授权检查。相同创建键且内容一致返回原问题，不一致拒绝；验收版本或候选不一致返回冲突。

## 原客户端通道

路径根为 `/api/account/sync`；使用原 FunnyWork 账号认证。客户端既有状态交换承担领取，不新增独立 Worker 轮询进程。

| 方法与路径 | 请求／响应 |
| --- | --- |
| GET `/maintenance/features` | 协商 `executor=funnywork-native-v1`；旧后端返回 404 时保留原同步功能，不发布维护能力 |
| POST `/devices/{deviceId}/state` | 原状态含 `epoch`、会话等字段；`choices.maintenance={executor:"funnywork-native-v1",gitVersion:"…"}`；返回原命令数组，其中可包含 `kind=maintenance` 帧 |
| POST `/devices/{deviceId}/maintenance/{attemptId}/renew` | `{runtimeEpoch, epoch}`；延长已有有效任务租约，撤权／取消／代际变化会拒绝 |
| POST `/devices/{deviceId}/maintenance/{attemptId}/result` | `{runtimeEpoch, report}`；保存维护报告并确认，重复内容一致可重传，不一致拒绝 |

维护帧结构如下。示例值只说明字段，不能作为真实派发直接提交。

```json
{
  "id": "assigned-attempt-id",
  "kind": "maintenance",
  "nativeId": "",
  "payload": {
    "epoch": 1,
    "runtimeEpoch": "current-desktop-runtime",
    "leaseDurationMs": 60000,
    "specHash": "server-generated-sha256",
    "spec": {
      "version": 1,
      "problemId": "assigned-problem-id",
      "taskId": "assigned-task-id",
      "repository": "owner/repository",
      "baseBranch": "main",
      "baseSha": "full-baseline-sha",
      "platform": "macos",
      "branch": "funnywork/maintenance-assigned-problem-id",
      "title": "简短问题标题",
      "requirement": "明确的修改范围",
      "acceptance": "可验证的接受条件",
      "checks": [{"id": "diff", "argv": ["git", "diff", "--check"], "timeoutSeconds": 30}]
    }
  }
}
```

维护能力发布前检查内置模型可用、Git 与 GitHub CLI 可执行，以及是否存在未确认执行的 RUNNING 日志；检查不调用模型。当前派发将 darwin／macos 映射为 macos、win32／windows 映射为 windows，其他值按 linux 处理。接入方应提交明确的平台值，并在对应系统准备 GUI、构建凭据及检查命令；在线和工具版本用于就绪检查，场景能力由对应验证任务核对。规格和帧不携带模型密钥。

客户端对重复帧按 attempt 及本地日志去重；每次实际任务创建确定的原生会话 `maintenance-{problemId}-v{spec.version}-e{epoch}`，复用当前账号的 Host，通过 Workspace／Session RPC 执行。本阶段最多两个维护回合，一个回合可以有多次模型请求；执行步骤有时间及输出限制，模型配额沿用后端已有模型政策，回合上限与累计 Token 预算分别控制。

## 报告与验收

`report` 字段为 `epoch`、`specHash`、`outcome`、`resultSha`、`prUrl`、`summary`、`checks` 及可选 `modelUsage`。检查项为 `{id, exitCode, output}`；用量为 `{calls, inputTokens, outputTokens, cachedInputTokens}`，未知 token 值为 null。结果取值为 CANDIDATE_READY、CHECKS_FAILED、NEEDS_ENVIRONMENT、RUNTIME_FAILED；P5 验证及交付另使用 VERIFIED、DELIVERED；不使用通用设计示例中的 `completed` 或 `CHECKS_PASSED` 作为当前 HTTP 结果枚举。

候选报告必须对应原仓库的 GitHub PR、不同于基线的完整提交、全部配置检查且各项退出码为零。客户端在推送／创建 PR 前确认租约，交付后核对远端 PR head 与本地已检查提交；后端保存报告和产物并验证身份、规格及一致性，`businessVerified=false` 标记报告一致性检查结果，独立代码及 GitHub 验证由对应审查阶段记录。

| 当前事件 | 问题 | 原任务／工作单元 |
| --- | --- | --- |
| 创建或返工 | IN_PROGRESS | 调度中的原任务／单元 |
| 候选报告接纳 | AWAITING_ACCEPTANCE | OUTPUT_READY／GENERATED |
| 检查、环境或执行失败 | BLOCKED | FAILED／FAILED |
| 管理端按当前版本和 SHA 验收 | ACCEPTED | SUCCEEDED／SUCCEEDED |

自动仓库由后端独立核对远端候选、检查和需求，验收记录标记 BACKEND_AGENT；兼容仓库仍为人工决定。ACCEPTED 表示候选验收完成；DELIVERED 表示后端确认合并及回告关闭。用户版本发布状态在客户端发布记录中查看。返工追加说明、保存旧报告，形成新规格和新任务；不静默修改运行中的规格。

客户端执行日志为 RUNNING → READY → SENT；网络故障只重传 READY 报告，不再次执行 Agent。运行代际不同或报告冲突时保留 STALE 记录供核对。无法确认原生会话／子进程停止时保留 RUNNING 并停止接单；取消、登出、退出、Host 恢复和更新准备等待停止确认。租约失效不自动授权另一台机器继续写同一问题；管理员先核对进程、提交和远端动作再返工。

## 原节点接口与后续扩展

`/api/v1/maintenance/nodes/profile`、`/api/v1/nodes/poll`、`/api/v1/attempts/{attemptId}/maintenance/spec` 和节点 result 等仍是后台节点机制的接口，供原任务体系和相关测试复用。它们不是原客户端需要另外注册或运行的执行路径，也不是 Codex 兼容接口。

协议扩展继续围绕 FunnyWork 原账号、设备通道和内置 Agent；新增字段先协商能力，保持旧后端 404 与原同步行为兼容。P4 原始反馈、Issue 增量归并和 maintenance-triage 分析接口见 [反馈受理协议](MAINTENANCE_INTAKE.md)。规格增加可选 allowedPaths；客户端在提交前程序校验实际改动范围。P5 配置平台验证及受控合并／回告／关闭接口见 [受控交付](MAINTENANCE_DELIVERY.md)。原设备额外协商 delivery=true，并接收 spec.mode=VERIFY／DELIVER；旧客户端不领取扩展任务。其他平台实测、可信 CI 自动验收和发布仍待后续实施。
