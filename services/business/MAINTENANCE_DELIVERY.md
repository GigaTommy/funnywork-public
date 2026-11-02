# 候选验证与受控交付

管理后端复用原 Task／Unit／Attempt 与账号设备通道；自动仓库的原 FunnyWork 客户端只执行开发和 VERIFY，合并及回告由后端执行；旧兼容仓库保留客户端 VERIFY／DELIVER 模式，不创建独立客户端、不启动 Symphony 或外部 Codex。自动仓库由后端 PRODUCT 阶段分类；旧兼容仓库由原内置 Agent 在 P4 分析阶段输出 BUG、IMPROVEMENT、QUESTION、OTHER，管理后端校验并保存；原始反馈不作为仓库写入授权。

## 验证政策

启用自动维护的仓库由后端派发 VERIFY、审查、验收及合并回告；该路径的条件与恢复见[自动维护参考](MAINTENANCE_AUTOMATION.md)。以下人工审核和客户端 DELIVER 接口用于未启用自动化的兼容仓库。

### 配置和审核

业务服务应用 V106，原客户端协商 delivery=true；旧客户端仍执行原修复功能，不领取验证及交付规格。管理端“任务 → 问题 → 交付配置”按仓库选择验证平台，为每个平台配置明确 argv 检查和必需 GitHub CI 名称。默认关闭，管理员按仓库启用；配置保存时核对租户和区域，有未结束交付任务时拒绝修改。验证平台须与已配置终端及对应检查命令匹配。

候选提交生成后，管理员在问题详情填写检查依据，点击“验证候选”。每个平台形成一个原任务，固定 problem version、candidate SHA、PR 和检查。客户端独立 clone、detached checkout 候选，仅运行检查；检查改写工作区或提交则拒绝通过，不调用模型。全部配置平台通过后才能在管理端验收当前候选；修改验证政策使旧政策的验证结果失效。

已验收仍不自动合并。管理员另行填写交付依据，确认“授权合并当前候选，并回告关闭关联 Issue”，点击“合并并回告”。后端核对验收 SHA、全部平台验证、配置政策及来源版本，生成原客户端 DELIVER 任务。修复模式继续禁止合并和关闭动作。

## 兼容仓库的客户端交付

1. 重新核对 PR 的仓库、目标分支和 head SHA。未合并 PR 必须开放、所有已有 CI 完成成功，且配置的必需 CI 名称全部存在。
2. 原账号租约确认后调用 GitHub merge API，附精确 sha 和 squash 合并方式。GitHub 在实际合并请求时拒绝变化的 head；不启用强制合并、不改分支保护。
3. 再读取远端 PR，确认 MERGED 及 merge commit。未确认合并时不关闭 Issue；合并超时返回阻塞记录，不能假定未合并而盲目重做。
4. 对来自同一问题、同仓库的 GitHub 来源写回说明，包含 PR、候选、合并提交及“安装包发布进度请查看版本发布记录”。每个来源使用稳定 job marker，并查询已有评论以减少重传重复。
5. 评论得到 GitHub ID 后才关闭 Issue，读取 PATCH 返回状态确认 closed。客户端保存交付证据并回传；服务端校验候选、PR、merge SHA、全部来源和评论 ID，保留原产物及审计。

正常断线只重传已保存回执，不再次执行模型或外部写入。无法确认旧执行停止时保留 RUNNING 并停接单，不能依据租约过期强行换机器。部分交付失败需核查远端合并、评论和 Issue 状态；当前不自动重派失败的写任务。

## 验证与兼容交付接口

管理路径根 `/api/v1/maintenance`，使用原管理身份桥。客户端继续使用 `/api/account/sync/devices/{deviceId}/maintenance/{attemptId}/renew` 和 `/result`，同一维护帧 kind=maintenance，spec.mode 为 VERIFY／DELIVER。

| 方法／路径 | 字段或结果 |
| --- | --- |
| GET／POST `/delivery-policies` | repositoryId、platforms、checks（按平台）、requiredCi、enabled；管理员配置 |
| POST `/problems/{id}/verify` | expectedVersion、candidateSha、note；幂等生成固定平台任务 |
| POST `/problems/{id}/deliver` | expectedVersion、candidateSha、note；要求 ACCEPTED 及全部配置平台验证通过 |
| GET `/problems/{id}` | 保留原修复报告，新增 deliveryJobs（mode、platform、candidate_sha、state、task_state、report_json） |

扩展 spec 包含 jobId、mode、candidateSha、prUrl、requiredCi、issues。结果新增 VERIFIED、DELIVERED；失败使用原 CHECKS_FAILED、NEEDS_ENVIRONMENT、RUNTIME_FAILED。验证报告包括全部已配置检查；交付报告包括固定 delivery 检查及 JSON 证据（candidateSha、prUrl、mergeSha、issues[{issue,commentId,closed}]）。报告仍保留 businessVerified=false：后端校验回执一致性，独立 GitHub API 核对发生在原客户端和验收流程，并未宣称后端自身复测代码。

问题 ACCEPTED 表示候选审核通过，交付 job DELIVERED 表示本次 PR 已合并且关联 GitHub 来源已回告关闭。原反馈详情 repair.deliveryState 显示独立交付状态。没有 GitHub 来源时可完成合并，不虚构回告或 Issue 关闭。不存在安装包自动发布或生产部署步骤。
