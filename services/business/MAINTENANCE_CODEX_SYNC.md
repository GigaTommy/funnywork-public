# Codex 三端只读同步检查（首切片）

本实现是显式选择 Codex 的只读版本／能力观察，不替换默认 DeepSeek/native 执行器，不进入维护修复、PR、合并或发布流程。桌面只允许固定的 Codex CLI 版本与 app-server initialize 探测；不接收任意 prompt、argv，不调用 turn/start，不产生模型计费。

## 权限与部署前提

- 新增 Flyway V114/V115 只定义任务、目标及短租约与停止证据字段。目标环境按部署流程执行迁移并核对版本，历史迁移保持不变。
- 创建／查看任务与设备能力均要求当前 ADMIN、同租户与完整设备 owner zone 范围；创建和执行要求设备已注册、未撤销并绑定维护节点；授权范围内的历史记录仍可展示已停用设备的阻塞状态。
- 只读 sync-check 统一使用 VERIFY 项目／资源池／设备授权；仅有 DEVELOP 不授予检查权限。创建、领取、续租及结果接收均复核同一 VERIFY 路由与当前授权，不会回退到 DEVELOP。维护节点 profile、平台、启用状态、池成员及有效 ALLOW 必须匹配，DENY 优先；execution-enforcement=false 时仍拒绝。撤权或过期后拒绝继续执行及成功回执，但原设备可提交 stopped 证据解除隔离。历史回执仍可只读查看，不因新增授权自动改写为成功。
- 创建、投递和回执均重新检查授权；投递／回执还校验创建者原 authVersion 及当前 ADMIN/zone。撤销、暂停、租户隔离及 runtimeEpoch 不可绕过。
- 未附带 Codex 广告的原客户端继续使用现有 native 协议。年报、triage、修复与交付没有改成 Codex。

## 能力广告

服务端 `/api/account/sync/maintenance/features` 返回可选 `syncCheck={executor:"funnywork-codex-v1",protocolVersion:1}`。客户端必须先核对该协商字段；旧服务端未声明时不发送新广告。

原 state `choices.maintenance` 保持不变；新增可选 `maintenanceExecutors` 数组（最多一个）：

```json
{"executor":"funnywork-codex-v1","protocolVersion":1,"executorVersion":"1","gitVersion":"git 2.0","capabilities":["sync-check"]}
```

已知 capability 是 `start/events/cancel/reconcile/artifacts/sync-check`，只广告实际提供者，必须含 `sync-check`。未知 executor／协议／字段或重复能力拒绝，缺席不推测为可用。能力表示固定探测进程生命周期，不表示任意模型任务能力。

## 管理 API

- `GET /api/v1/maintenance/sync-checks/devices/{deviceId}/capabilities`：白名单能力与平台、在线状态，30 秒过期；不返回凭据、完整 model choices 或本机路径。
- `POST /api/v1/maintenance/sync-checks`，要求 Idempotency-Key：

```json
{"repositoryId":"registered-repository-id","baseSha":"0123456789012345678901234567890123456789","expectedVersion":"1.2.3","executor":"funnywork-codex-v1","protocolVersion":1,"targets":[{"deviceId":"mac-device","platform":"macos"},{"deviceId":"win-device","platform":"windows"},{"deviceId":"linux-device","platform":"linux"}]}
```

恰好三个不同设备覆盖 macos/windows/linux，必须在线且能力可用。baseSha 为调用者明确选定的固定基线，不自动解析远端 main。expectedVersion 是 FunnyWork 应用版本，不是 Codex CLI 版本。相同幂等 key 与不同内容冲突。

- `GET /api/v1/maintenance/sync-checks/{taskId}`：目标状态与结构化回执。`aligned` 由服务端比较 observedSha 和 clientVersion，不从 summary 推断成功。observedSha 是已安装包内嵌构建清单的实际 commit，另有 buildTree/buildDirty；不通过 fetch 目标SHA伪造观察结果。CHECKED 必须有干净构建证据。
- `GET /api/v1/maintenance/sync-checks`：当前ADMIN范围内最近可见检查，最多25项。

## 原账号 state 通道

新增 command `kind=maintenance-sync-check`、`id=targetId`、`nativeId=""`。payload 是以下冻结规格加 canonical SHA-256 `specHash`：taskId、executor、protocolVersion、runtimeEpoch、repository、baseSha、expectedVersion、requiredCapabilities=[sync-check]、platform、timeoutMs=60000、maxOutputBytes=16384、leaseDurationMs=15000。无 prompt、shell、写权限或账号字段。

目标入队五分钟有效，投递后只发送一次，回执窗口九十秒。相同设备存在 CLAIMED 或未确认停止的 UNKNOWN 检查时持续隔离，不因90秒到期解除。runtimeEpoch 改变时运行中检查转 UNKNOWN、尚未投递的检查转 BLOCKED；已投递但过期显示 UNKNOWN。不会自动重新执行不确定任务。

`POST .../{targetId}/renew` 接收 runtimeEpoch、specHash，返回 leaseDurationMs 与 expiresAt；每次重新校验授权。客户端约每5秒续租，以最后成功确认的15秒短租约为上限；授权拒绝或断线到期必须停止真实进程。`POST .../{targetId}/stopped` 接收 runtimeEpoch、specHash、evidenceDigest，只记录原设备/原运行期确认停止的证据，不依赖已撤销的执行授权、不接受成功结果。明确 processStopped=true 的有效终态回执也解除隔离。旧epoch UNKNOWN不能由新epoch的停止声明解除，必须先人工确认旧进程停止，再另行实施受控恢复；当前没有自动解除接口。

`POST /api/account/sync/devices/{deviceId}/maintenance-sync-checks/{targetId}/result`：

```json
{"runtimeEpoch":"runtime","specHash":"64-lowercase-hex","outcome":"CHECKED","clientVersion":"1.2.3","executorVersion":"1","pluginVersion":"0.153.4","observedSha":"40-or-64-lowercase-hex","capabilities":["sync-check"],"summary":"Bounded observation","evidenceDigest":"64-lowercase-hex","eventCount":2,"terminalState":"completed","processStopped":true,"buildTree":"40-or-64-lowercase-hex","buildDirty":false}
```

outcome 为 CHECKED/UNAVAILABLE/FAILED/UNKNOWN；terminalState 为 completed/failed/unknown。无法确认进程停止必须 UNKNOWN，不能称成功或自动重派。CHECKED 要求进程已停、completed、版本和SHA证据存在、包含sync-check能力；版本差异不是自动修改授权。clientVersion/observedSha/pluginVersion 在不可用时可为 null。summary 最长2000字符，不存原始日志、token或路径。evidenceDigest 是本地结构化探测证据摘要，不是服务器独立复验或可信执行证明。回执重复仅接受完全一致的内容，旧runtime、错spec、超时、其他设备、权限变化均拒绝。

## 管理界面

既有「执行资源」页增加「三端版本检查」子页，仅当前业务 ADMIN 且具有 `funnyworker:resources:write` 的管理账号可见。管理会话 API 按精确路径映射已有 resources:read/write；不新增生产菜单或角色授权。选择仓库后逐平台读取既有资源预览，只可选已授权可用设备；选择设备再核对 Codex 能力。缺平台/离线/能力缺失会阻止创建，不自动注册节点或放宽授权。

界面只接受固定SHA及与客户端一致的三段版本格式；确认弹窗展示仓库、基线、版本及三设备。最近记录显示真实安装构建与进程证据。网络结果未知后沿相同幂等key重试，429不会改key；按当前tenant/principal隔离的sessionStorage保存待确认请求，切页/刷新后只恢复显示，不自动重发。授权丢失清除旧历史、候选与表单字段；响应式登录身份变化还会锁住旧页面实例，迟到异步结果不得回填。已经打开的确认弹窗摘要会保留到关闭，但旧确认动作被阻止。确认前后及提交前复核同一登录凭据，提交前读取 /me 验证当前tenant/principal与页面一致；持久草稿不含登录凭据。界面不提供UNKNOWN强制解锁、任意prompt、修复或合并按钮。

## 验证边界

H2 集成测试验证三端目标、幂等性、授权隔离、native 回归、runtime变化、UNKNOWN、超时及撤销授权。测试迁移只作用内存测试数据库。真实三台机器安装、各OS app-server探测、生产admin UI、网络断线恢复、代码优化与GitHub合并仍需分别验收，不能以单测或服务端记录替代。

## VERIFY 路由与候选预览

三端页面通过 POST /api/v1/execution-placement/preview 请求 action=VERIFY、purpose=SYNC_CHECK。此标识仅选择固定 Codex sync-check 能力的只读预览，不新增授权动作，也不能用于 DEVELOP/COMPILE。未带 purpose 的普通 VERIFY 预览仍要求原生 funnywork-native-v2 执行器。服务端创建与后续生命周期复核仍独立执行，预览不预留、不授权、不创建节点。

仓库每个 action 只有一条路由。Mac、Windows、Linux 可共享包含三平台的 VERIFY 用途和资源池，并分别设置设备精确授权；不能为同仓库创建三条 VERIFY 路由。修改路由会影响该仓库普通 VERIFY 任务，切换前应核对现有平台成员和授权。无需创建 DEVELOP 路由或开放普通 Codex 模型调用。

本修改只修正授权动作和预览契约；固定 commit、安装包 tree/dirty 证据、三平台不同设备、Codex 能力、租约、取消与 UNKNOWN 隔离要求保持不变。已有排队检查按新的 VERIFY 规则复核，缺少 VERIFY 时阻止领取；历史归档记录不迁移。
