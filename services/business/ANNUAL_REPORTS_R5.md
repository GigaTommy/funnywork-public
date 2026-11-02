# 年度经营摘要编制

年报模块将结构化收入、成本和上年收入资料编制为固定模板经营摘要。管理端注册资料快照，业务服务按项目授权分派到 FunnyWork 客户端内置 Agent，核验候选后生成正式 Markdown 文件。执行能力为 `report.annual.v1`，命令为 `annual-report`，动作为 `COMPILE`。

## 资料与授权

`POST /api/v1/annual-reports/datasets` 注册企业、年度、项目及最多 100 条结构化资料，每条包含稳定编号、名称、指标和最多两位小数的非负金额。必须同时具备 REVENUE、COST、PRIOR_REVENUE。所属项目、资料、资源池、用途必须同租户同区域；资料由创建人查看和发起编制。资料不可修改，修订数据创建新快照；停用／恢复会递增版本，旧任务不会因恢复重新获得资格。

复用 `execution_projects`、`execution_pools`、`execution_profiles`、成员及放置记录。V111 以 `execution_dataset_routes`、`execution_dataset_grants` 存储资料集关系，与旧仓库关系通过只读联合视图进入同一个授权判定。旧配置接口的 `repositoryId` 字段暂作为兼容性资源编号，年报实际引用 `annual_datasets.id`，不创建虚假仓库。管理界面展示“资源”和“年报资料”。COMPILE 只能绑定资料集；DEVELOP／VERIFY 只能绑定仓库。明确拒绝优先，授权到期、成员变化、设备撤销、runtime 变化、用途和路由版本改变均使旧放置失效。

空授权不分派资料。一个资料集至多发起一个编制任务，创建请求幂等；不同幂等键重复发起不会产生孤儿任务。新快照可重新编制，不覆盖已有正式文件。当前单一模板、单一输出区域不拆章节并行。

## 原客户端执行

先确认 `/maintenance/features` 返回 `annualReport = annual-report.v1`，再由 `choices.annualReport = annual-report.v1` 明确声明原 App 已支持此协议；旧 App 不接报表。附加能力登记在同一原节点的 `node_capabilities`，所有用途共用 attempts 占用与 capacity，不为每类用途建立独立客户端或容量。报表就绪仅依赖原 App 模型，不要求 Git 就绪；仓库执行仍需其维护就绪检查。

后端选择唯一获准终端，经既有 `/api/account/sync/devices/{id}/state` 返回冻结 spec、specHash、runtimeEpoch、attempt epoch 和租期。原 App 以原 Host 创建可见会话，将全部授权快照写入原会话 prompt 事件。工作区在当前账号 home 的私有年报目录中，不克隆仓库，不发放 GitHub 凭据。执行期间通过既有租约接口续租；目录或候选为符号链接、异常大小／格式时拒绝使用。

原内置 Agent 写入 `candidate.json`。客户端候选先写独占本地回执，再从 `/api/account/sync/devices/{id}/annual-report/{attempt}/candidate` 回传；同 runtime 的断网回执可重试，重传优先提交候选以恢复服务端已提交但响应丢失的确认；断网、登录失效、限流和服务错误保留原候选。同一 attempt 重发命令只恢复已保存回执，不再次执行 Agent；同进程并发命令共用原执行 Promise。执行前写 `annual-report-executions` 的 RUNNING 记录，内置 Agent 返回或确认停止后记录 STOPPED；无法确认停止时保持原安全阻断，不把旧执行当作完成。已确认停止但候选尚未落盘的恢复只回告失败，不再次运行。接收确认后的原候选移入 `annual-report-accepted`，即使成功回传与在途命令交错也不会重复执行。每条回执独立处理，400／403／404／409／410／422、损坏文件和旧 runtime 回执移入账号私有 `annual-report-quarantine`，保留原文件与不含凭据的隔离原因；隔离前写 `annual-report-retired` 持久标记，防止迟到命令在隔离后重新执行同一 attempt。旧 runtime 回执不占当前上传窗口，也不冒充当前执行；原 App 重启后不能将旧 runtime 回执重新绑定到新 runtime。超过租期时隔离旧候选，按原后端调度与重试预算执行新 attempt，不接纳过期候选。已确认停止的本地失败先持久化到 `annual-report-failure-receipts`，再走 `/failure`；失败回告断网可单独重传，不阻断后续任务，不发布文件。候选或失败回告都必须收到 `accepted=true` 才记录接收确认；无法确认停止时保留原通道恢复阻断，不把未知执行当作已停止。设备隔离与凭据撤销沿用原账号与执行资源机制。

## 独立核验与交付

候选仅允许 schema、snapshotHash、facts、sourceIds 四个字段。facts 必须包含 revenue、cost、profit、priorRevenue、growthPercent 五个两位小数字符串；上年收入为零时同比为 N/A。后端从冻结授权资料独立使用 BigDecimal 重算总额、利润、同比与 HALF_UP 舍入，并逐项比较来源编号、快照、当前资料版本及执行租约。不接受终端的自评通过标志，也不接受额外正文或链接。

候选不一致时 REJECTED，任务失败且不创建正式文件。全部通过时后端使用固定模板生成 UTF-8 Markdown 经营报告，写入现有 ArtifactService，绑定原任务并自动置 DELIVERED／SUCCEEDED。回传是候选接收和后端核验的一个事务，重复回执幂等，冲突回执拒绝。正式产出可在原管理端查看和下载，无逐单人工验收。`annual-evidence.v2` 证据记录来源与 spec 指纹、attempt／epoch、候选和重算 facts 指纹、整体结论与时间；授权、候选格式、快照、总额、利润、同比、来源覆盖、正式文件生成分别记录 PASSED／FAILED／NOT_RUN 和原因。格式错误的候选也结算为 REJECTED，避免无限回传。管理端逐项显示结果和原因；旧 v1 证据只展示已有整体结论，单项标明未记录，不补造通过记录。审计沿用原服务审计。正式交付附件受后端保护，通用附件修改和删除接口均返回 FORMAL_DELIVERY_PROTECTED，保留正文、任务绑定和交付状态；没有普通接口强制删除入口。

## 输入与输出条件

本模板以收入减成本计算经营利润，按结构化金额计算同比，输出固定格式的 UTF-8 Markdown 经营摘要。输入需提供本页要求的指标、稳定来源编号和项目授权；修订资料时创建新快照。法定披露、审计意见或其他模板需要使用对应业务流程。

后端核验候选格式、资料快照、计算和来源覆盖，资料真实性由资料提供方审核，终端访问按设备与执行资源授权管理。正式文件受交付保护，查看与下载遵循原任务的权限。
