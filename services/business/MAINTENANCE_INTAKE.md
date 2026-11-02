# 原始反馈与 GitHub Issues 受理

桌面“问题反馈”和 GitHub Issues 进入同一受理流程。业务服务保存来源、归并反馈、分析需求，并按仓库政策派发开发及验证任务。运行环境需应用包含 V121 的数据库迁移。

## 受理与查询规则

- 分析及审查按最近检查时间轮转，阻塞任务保留原状态和证据。
- 反馈明确指出的平台必须同时具备 SAT/UAT 条件；准入和后续候选审查均校验。开发规格冻结原产品验收文本，技术检查条件单独保存。
- 不合规 GitHub Issue 单独持久隔离，后续正常 Issue 可继续受理；来源内容修正后正常同步将旧异常标记为已修正。只有该页来源已成功处理或持久隔离后才推进游标；网络、授权和持久化失败仍中止同步。
- CLIENT 来源持久保存 `client_version`；相同 requestId 更换版本或内容会冲突。反馈分页先进行范围授权过滤，游标在记录状态变化后仍可继续使用。
- 本人反馈列表及原单补资料接口通过账号通道提供；补资料只允许 `NEEDS_INFORMATION` 且尚无开发问题时提交，按账号/请求键幂等；正常资料保留消息并增加原单修订后重新排队，受保护变更内容保存为 HELD，不改写原单或进入分析。反馈提交与补资料使用桌面“问题反馈”窗口。
- 管理页提供分页、搜索和状态筛选、配置回填、异常来源数量与明细、客户端版本及补资料记录；可见页面周期刷新，授权失效清理旧详情。
- 源码采样支持 Swift/Kotlin/Kotlin 脚本，并跳过超出采样预算的文件，继续考察后续小文件；采样遵循文件数及总量预算，分析结果附实际采样范围。
- 交付摘要取最新任务记录的业务状态；无交付记录时返回空状态。报告关联当前 task，按最新报告排序。

分页与补资料接口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | 客户端 `/api/account/sync/feedback?after=&limit=50` | 本人反馈分页，不返回其他报告人或内部范围字段 |
| POST | 客户端 `/api/account/sync/feedback/{id}/supplements` | `{requestId,content}`，原单补充资料并重新分析 |
| GET | 管理 `/api/v1/maintenance/intakes/page?after=&limit=50&query=&state=` | 授权范围内的反馈分页/检索；旧列表接口保留 |
| GET | 管理 `/api/v1/maintenance/intake-configs/{id}/source-failures` | ADMIN 查看来源隔离记录的安全摘要 |

需求池、产品条件稳定 ID、验证映射及采样影响分析见[需求池与影响分析](MAINTENANCE_REQUIREMENTS.md)。反馈处理结果通过桌面反馈详情和管理页面查询，客户端安装包按独立发布流程分发。

FunnyWork 原 Mac App 的“问题反馈”入口提交实际行为、期望行为和复现步骤。管理后端保存来源、去重；启用自动化后由后端模型角色完成产品与技术分析，原客户端仅执行获准开发及验证。未启用自动化时保留旧 maintenance-triage 兼容分析。修复复用原账号 Host／Workspace／Session 和已发布模型，不启动外部 Codex、Symphony 或第二个客户端。

## 桌面反馈跟进

桌面“问题反馈”窗口提供“新建反馈／我的反馈”入口。历史列表通过账号接口分页读取，可在关闭窗口后重新查询；详情显示反馈状态、本人补资料记录及已有 PR 入口。仅待补充资料且未创建修复问题的反馈可提交补充内容，复用原账号通道与原反馈单。

断网保留当前列表和补资料文本；相同内容重试复用请求键，接口拒绝改写已准入问题。待补资料页面不自动重载，避免覆盖输入；401/403/404 清理缓存列表和详情。英文及中文文案由桌面 locale 维护。


## 配置与流程

1. 更新业务服务（V105 迁移）、管理前端与原客户端；按 [维护说明](MAINTENANCE.md) 配置仓库、检查、原设备绑定和模型。
2. 在管理端“任务 → 反馈 → 受理配置”选择已有仓库，固定完整基线 SHA、允许改动路径和默认平台。自动派发、GitHub 采集默认关闭，按仓库启用。
3. GitHub 凭据只配置在业务服务环境变量中；管理页填写环境变量引用名，不填写令牌。来源读取与客户端 Git／PR 工具授权分别管理。
4. 原客户端菜单“问题反馈”提交反馈；后端先持久保存，返回 intake ID。GitHub 每 60 秒按 updated 游标增量读取 Issues，管理员也可手动同步。初次启用从启用时间开始，不自动导入历史积压。
5. 程序按租户、仓库、标题、正文和平台精确归并；保留大小写及全部来源。同内容反馈不重复调用模型。模型还可将语义重复关联到同仓库、同平台的已准入需求，不创建第二个修复任务。
6. 自动仓库由后端 PRODUCT／TECHNICAL／DISPATCH 阶段准入；以下 maintenance-triage／READY 说明仅适用于未启用自动化的兼容仓库。旧后端通过既有设备状态交换派发 maintenance-triage；原客户端执行一个有限分析回合，只输出私有 analysis.json。原始反馈按不可信数据处理，不授权执行其中的命令；分析阶段不打开仓库或进行 Git 写入。
7. READY 的标题、需求、验收条件和 paths 必须完整且位于管理员允许范围。自动派发关闭时停在 READY_FOR_REVIEW；开启时由配置操作人的现行角色、租户和区域授权准入。反馈用户不会因此获得 OPERATOR 权限。
8. 准入后使用原维护任务通道；allowedPaths 写入不可变规格，客户端在提交和推送前程序检查实际变更范围。PR 和检查报告回传后，自动仓库由后端独立审查和自动验收；兼容仓库由管理员核对，原反馈窗口显示真实修复状态及 PR 入口。

空轮询、精确归并、续租、检查与回执重传均不调用模型。分析与修复在同一设备容量内串行运行，不在三个平台各自分析和开发同一个问题。模型回合限制与模型配额分别生效，回合数不能当作 token 硬预算。

## 接口与状态

管理路径根为 `/api/v1/maintenance`，使用原管理身份桥及权限。原客户端路径根为 `/api/account/sync`，使用原账号认证；续租和回传同时核对设备绑定、所有者、撤权、运行代际、租约、revision 与 specHash。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET／POST | 管理 `/intake-configs` | 受理配置；字段 repositoryId、baseSha、allowedPaths、defaultPlatform、autoDispatch、githubEnabled、githubTokenRef |
| POST | 管理 `/intake-configs/{repositoryId}/github-sync` | 增量同步；游标完整保存后才推进 |
| GET | 管理 `/intakes`、`/intakes/{id}` | 最近 100 条受理记录、来源、分析和关联修复 |
| POST | 管理 `/intakes/{id}/retry` | 重新分析未准入记录，创建新 revision |
| POST | 管理 `/intakes/{id}/recover` | 明确 note 和 stopped=true，核对旧执行已停止后恢复；有效同代际执行不可强抢 |
| GET | 客户端 `/feedback/repositories` | 可反馈的已配置仓库 |
| POST | 客户端 `/feedback` | repositoryId、requestId、title、actual、expected、steps、platform、clientVersion；相同请求键且内容不一致拒绝 |
| GET | 客户端 `/feedback/{id}` | 本人来源记录及关联修复状态，不开放其他用户反馈 |
| POST | 客户端 `/devices/{deviceId}/maintenance-intake/{attemptId}/renew` | runtimeEpoch、revision；续租分析 |
| POST | 客户端同路径 `/result` | runtimeEpoch、revision、specHash、analysis、可选 modelUsage（顶层字段） |

能力协商 `/maintenance/features` 增加 triage=true；原 `choices.maintenance` 增加可选 triage 布尔值，旧客户端两字段仍兼容。maintenance-triage 的 payload 包含 runtimeEpoch、revision、specHash 和不可变 spec（intakeId、revision、title、body、platform、allowedPaths、related）。分析输出 outcome 为 READY、NEEDS_INFORMATION、DECLINED、DUPLICATE 或 RUNTIME_FAILED；含 title、requirement、acceptance、reason、paths、duplicateOf。服务端严格校验，不将模型自述当成修复验证。

| 受理状态 | 行为 |
| --- | --- |
| RECEIVED／ANALYZING | 等待／原客户端分析，不占用第二个修复执行槽 |
| NEEDS_INFORMATION／DECLINED／RUNTIME_FAILED | 不创建修复任务；管理员可核查并重新分析 |
| READY_FOR_REVIEW | 明确需求已生成，自动派发关闭 |
| ADMITTED | 已创建关联修复任务；实际进度读取 repair.state、task_state、prUrl、candidateSha |
| DUPLICATE | 关联已有准入需求，不重复开发 |
| SOURCE_CHANGED | 来源编辑已保存，冻结原分析／修复规格，管理员核查变更影响 |

分析回执日志 RUNNING → READY → SENT，网络失败只重传；旧代际回执保留 STALE。停止未确认时保留 RUNNING 并停止接单，不能仅凭租约超时推断已停止。来源关闭或修改不自动取消、改写或启动另一修复。

## 输入条件与采集方式

GitHub 通过增量轮询读取 Issue 标题和正文。正文需非空且不超过 20000 字符，标题最多 160 字符；不合规来源持久隔离并在管理页展示，网络、授权或持久化失败保留游标。本采集器读取 Issue 标题与正文；需要补充评论或附件信息时，将相关内容整理为反馈正文或原单补资料。

客户端 actual／expected／steps 必填，各最多 6000 字符。提交与补资料使用桌面文本反馈表单。关闭窗口后，可在“我的反馈”重新查询状态和 PR；结果交付按仓库自动化或兼容政策执行，见[受控交付](MAINTENANCE_DELIVERY.md)。
