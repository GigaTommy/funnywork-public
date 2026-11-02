# 企业模型中间层

本模块提供私有化模型入口、容量管理与调用计量。若依负责身份及管理权限，业务服务负责容量池、准入和调用账本。支持企业入口的新版桌面默认接入中间层。V90 起共享模型密钥不再下发，新账号与全站预算默认拒绝付费调用；上线配置见[模型限额](MODEL_BUDGETS.md)。

## 配置和上线范围

在部署环境设置 `FUNNYWORK_MODEL_INFERENCE_ORIGINS`，值为允许访问的完整 origin，逗号分隔，例如 `https://model.internal:8443,http://10.0.0.8:8000`。默认空值拒绝创建或执行管理员注册的上游部署。该名单用于管理员注册的部署；手机聊天中由服务端固定的官方模型来源仍使用其固定官方地址。HTTP 仅应用于可信内网；平台到客户端使用 HTTPS。配置允许访问内网是有意行为，不接受客户端提供的上游地址；重定向被拒绝。部署管理员应控制 DNS 和允许的服务地址。

`FUNNYWORK_MODEL_INFERENCE_WORKERS` 默认为 16，范围 1–256，限制本进程流式 IO 工作线程；不创建待执行 IO 工作队列。所有副本共用数据库容量许可。容量不足时客户端只持有票据、查询状态，不占长连接。推理请求最多 16 MiB；非流式结果最多 4 MiB；连接超时 5 秒、读取空闲超时 60 秒、执行期限 10 分钟，目前为固定值。

管理端“模型”页面增加容量池、部署和活动队列；“活动与用量”页面增加入口调用用量，原有任务活动统计保留。首次创建部署默认停用，先完成具体模型/框架/工具解析器的兼容验证再启用。能力字段由管理员按实际兼容验证结果填写。上下文上限用于目录展示，预算准入按输入上界预留；实际 Token 用量取供应方回传计量。

启用 `images` 能力并审核该部署的 Token 计费上界后，入口接受协议对应的内联图片：Chat Completions 的 `image_url` data URL，或 Messages 的 base64 `image`（含工具结果中的嵌套图片）。只接受 JPEG、PNG、GIF、WebP 与有效 Base64；仍拒绝外部图片 URL、Files API 引用和其他媒体块。关闭图片能力时，嵌套图片同样被拒绝。图片与文字一起绑定请求摘要和单次许可，不下发提供者密钥。

管理员启用图片前，应确认上游将图片用量合并到输入 Token，并完成计费审核与实际多轮验证。[DeepSeek 图像理解文档](https://api-docs.deepseek.com/zh-cn/guides/vision/)说明 Flash 图片与文本按 Token 合并计费。其他模型按各自协议与计量规则完成兼容验证后启用。

上游密钥通过 `FUNNYWORK_MODEL_*` 环境变量引用，空引用表示无需凭证。Chat Completions 使用 `Authorization: Bearer`；Messages 使用 `x-api-key`。员工目录不返回地址、凭证引用或密钥。部署配置不可修改，更换版本/地址时创建新部署，旧部署停用会取消尚未执行的票据；已经运行的请求按停止与核对流程处理。

## API 和权限

所有接口沿用现有认证。若依管理路由使用 `funnyworker:models:read/write`，用量路由使用 `funnyworker:usage:read`；后台同时要求业务 ADMIN 和授权 zone。桌面接口使用 `funnyworker:desktop:read`，业务身份必须为 USER 且对模型池 zone 有权限。NODE 无法使用员工推理入口。

| 接口 | 用途 |
| --- | --- |
| GET/POST `/api/v1/model-middleware/pools` | 查询/创建容量池 |
| PUT `/api/v1/model-middleware/pools/{id}` | 带 expectedVersion 更新池策略 |
| GET/POST `/api/v1/model-middleware/deployments` | 查询/创建固定部署 |
| PUT `/api/v1/model-middleware/deployments/{id}/enabled` | 启用或停用 |
| GET `/api/v1/model-middleware/queue?poolId=...&limit=100` | 最早最多 100 条活动票据，非全部队列总数 |
| POST `/api/v1/model-middleware/tickets/{id}/cancel` | 辖内管理员取消 |
| POST `/api/v1/model-middleware/tickets/{id}/reconcile` | 核对 UNKNOWN 上游已停止后释放；需 evidence |
| GET `/api/v1/model-middleware/usage` | 按提交时间聚合，最长 93 天，分页 1–100 |
| GET `/api/account/inference/catalog` | 员工授权目录 |
| POST `/api/account/inference/tickets` | 申请许可，不提交提示词 |
| GET `/api/account/inference/tickets/{id}` | 读取状态并推进该池调度 |
| POST `/api/account/inference/tickets/{id}/cancel` | 员工取消本人请求 |
| POST `/api/account/inference/tickets/{id}/execute` | 获准后提交原始协议请求 |

票据申请字段：deploymentId、callId、logicalCallId、deviceId、sessionId、taskId、parentTaskId、purpose、requestDigest、maxOutputTokens。requestDigest 为最终请求 JSON 的原始 UTF-8 字节 SHA-256；必须保持执行时字节完全一致。callId 每个员工租户内唯一，重复相同申请返回原票据，改变申请内容返回冲突。实际重试必须用新的 callId，但可保持同一 logicalCallId；票据 id 为执行尝试标识。服务端不自动重试上游。

目录 id 是 deploymentId；执行体 model 是该部署的 upstream model。请求必须含 messages、max_tokens（Chat 也支持 max_completion_tokens），输出限制必须与申请一致；多候选 n 只允许 1。Chat 流式调用应由适配器声明 `stream_options.include_usage=true`，前提是实际推理服务支持；入口不擅自重写已绑定摘要的请求，不支持 usage 的服务记为未知。不做 Messages 与 Chat 的语义转换。工具增量、签名推理等内容字节透传；DeepSeek Files 上传和其他端点尚不在本模块范围，不能将旧客户端 baseURL 直接替换后宣称完整兼容。

## 容量和持久化语义

使用数据库容量池行锁协调所有副本，每次事务只处理短操作，无网络 IO。执行与等待上限均以容量池为范围；同一员工跨不同独立资源池的限制分别计算。多个共享昇腾资源的模型必须归入同一池，不能为共享硬件各建一池。

调度为员工之间按上次获准顺序轮转，员工内部按提交时间排序。GRANTED、RUNNING、STOPPING、UNKNOWN 全部占用容量。许可有效期 30 秒，未提交的许可可安全过期。排队总上限和员工上限只统计 WAITING。

WAITING/GRANTED 可以直接取消；RUNNING 取消转为 STOPPING，本进程看门狗约每秒断开上游连接。断开 HTTP 连接不是上游 GPU 已停止的证明，因此进入 UNKNOWN，并继续占用容量。管理员核对实际上游停止后提交依据释放；该依据为人工声明而非自动认证。进程故障后原执行不会重放，超过执行期限在下次池调度时转 UNKNOWN。首期没有服务化引擎取消/查询接口，因此不能自动安全释放所有故障执行。

客户端 GET 票据使用退避和随机抖动；建议初始 1–2 秒，上限 10 秒。当前调度由申请、状态读取和后台队列读取驱动，无 SSE 推送或后台全池扫描。状态已经持久化，进程重启不会丢失票据。

所有写事务首先验证恢复世代，PostgreSQL 使用共享恢复锁；旧进程不能在恢复隔离后继续派发或写回。最终派发在同一事务内锁定 principal 并重新校验授权，避免撤权与派发竞态；运行中的本地授权撤销由看门狗停止连接。入口仍沿用现有身份认证，不新增上游共享凭证下发。

## 用量口径

管理控制台的“模型”入口提供容量池配置、不可变部署注册与复制、模型启停、实时队列筛选与分页、暂停/恢复准入、取消请求及未知执行核对。队列首页每五秒刷新；加载后续页时暂停自动刷新，避免列表跳动。列表按提交时间及票据 id 游标分页，容量统计覆盖整池，筛选后的 total 仅覆盖匹配请求。分页不是固定快照，已结束请求会退出队列。

“活动与用量”的入口用量区域提供成员汇总、小时热点、调用明细和任务/会话关联；支持时间、授权区域、成员、部署、状态、用途、会话和任务筛选。概览与热点在服务端聚合，不需要浏览器加载全部成员。成员按 owner 游标、明细按 created_at/id 倒序游标、关联任务按 owner/session/task 游标分页。时间范围最多 93 天，区间 `[from,until)`。

新增运营 API：GET `/api/v1/model-middleware/queue/page`；GET `/api/v1/model-middleware/usage/overview`、`/members`、`/calls`、`/tasks`。queue/page 参数 poolId、state、owner、deployment、afterCreated、afterId、limit；usage 路径统一参数 from、until、zone、owner、deployment、state、purpose、session、task、limit。members 接受 afterOwner；calls 接受 before/beforeId；tasks 接受 afterOwner/afterSession/afterTask。响应均限制分页大小为 1–100，返回 next 游标（如有）。旧 queue/usage API 保留兼容。

名字来自当前 principal 的 display_name，授权区域来自调用记录；部门归属按身份系统记录另行查询。任务行只关联查询窗口内的请求，task_id 为空时只是会话汇总；未携带 task/session 的请求仍计入成员和总量，不伪造长任务。重试执行数按每个成员、或每个成员/session/task 组中，已开始执行的票据数减去不同 logical_call_id 数计算；未开始请求不计重试。排队时间、首字延迟和推理时间分开展示；推理时间只表示入口观察到的连接执行时段，GPU 实际计算耗时由推理服务监控获取。小时热点按提交时间分桶，页面显示本地时间。

聚合 token 为已知部分之和；若某组没有任何已知 token，则字段为 null。已知合计与 incomplete_usage_calls 必须一起解读。成员可跳转到调用明细，关联任务跳转会同时限定成员、会话与任务。明细不包含请求正文、上游地址、凭证、请求摘要或原始提供者响应。

每张票据最多一次实际执行，无模型正文入库。保存上游 usage 中限定的计量字段，累计样本覆盖、Messages 部分字段合并。Messages 输入总量为普通输入+缓存读取+缓存创建；Chat prompt_tokens 已包含缓存，缓存仅单独展示，不重复相加。reasoning 不额外加到输出总量。

未收到计量标记 UNKNOWN 或 PARTIAL，不作为零消耗。报表 attempts 与申请 calls 分开。GROUP BY 员工、zone、部署；nextOwner/nextDeployment 用于下一页。分页按查询时数据读取，非固定报表快照。zone 是请求发生时授权资源范围，不等同于历史若依部门。device/session/task/purpose 是已认证客户端声明的关联元数据，不是独立设备所有权证明。

## 客户端准入扩展点

后端提供 GET `/api/account/inference/bootstrap` 查询显式启用状态（`FUNNYWORK_MODEL_INFERENCE_ENABLED` 默认 true），POST `/api/account/inference/credential` 签发十分钟短期入口凭证。签发必须使用原账号凭证；入口凭证只可访问 inference 路径，不能续签或访问管理 API。数据库只保存令牌摘要，身份每次按当前授权版本重新核验，停用用户立即失效。该开关目前控制短期凭证签发和认证，不关闭使用原账号凭证的既有票据 API。

Harness 的 DeepSeek Messages 和 pi-ai OpenAI Chat Completions 适配器新增可选 `prepareHttp`，收到最终序列化请求后申请准入，再返回绑定票据的转发函数。等待时暂停流式空闲计时，用户取消仍有效；启用准入扩展点后提供者自动重试次数为零。pi-ai 的其他协议使用该扩展点时明确拒绝，避免协议能力不匹配后绕过准入。

`llm-pi-ai` 导出 `admissionTransport`，负责摘要、幂等申请、轮询、取消、单次执行、短期凭证认证及恢复状态检查。它要求调用方实现原子、持久化的 acquire/save/remove，申请前保留 callId；恢复运行中或 UNKNOWN 的调用不会再次执行。请求及响应字节保持原样，不转发客户端上游凭证，不接受跳转。不完整调用保留票据，完整读完响应后查询服务端状态，确认终态再移除恢复记录。taskId 使用当前上下文最后一条真实用户消息 id，parentTaskId 留空；缺少用户消息时仅按会话关联。该分组取自客户端声明，展示用户请求关联；任务拓扑和工具耗时由对应执行记录获取。

新版桌面启动已挂载完整企业路径：主进程续期短期凭证，Host 使用私有恢复文件和授权模型目录，复用现有多模型选择并显示排队数。企业模式不挂载直连适配器，并移除继承的 DeepSeek 密钥/地址；进入企业模式后不会因目录故障退回公网。标题、压缩、主请求等经相同 LLM 入口准入。退出账号先停止 Host 等待取消处理，再撤销当前设备全部入口凭证。目录十五秒刷新、票据轮询退避至十秒；已移除的模型在下一次请求不可选择，服务端始终重新验权。

Harness 公共调用链应在网络适配器计时前完成申请/等待，覆盖主任务、子任务、压缩和标题等所有模型调用。排队不触发模型失败重试，不持有会话写锁；取消恢复查询原票据。请求改变必须重新申请，不能在已获许可后替换请求体。

私有 DeepSeek、千问优先验证 llm-pi-ai 的 OpenAI-compatible 路由；公网 DeepSeek 使用 Messages 透传。启用模型前，按固定硬件、引擎与模型版本验证容量和完整多轮兼容。V90 为每次实际执行增加用户与全站预算预占；V91 在票据接收和执行许可发放前校验有效额度与运营审核计费，独立限制新票据申请频率。并发许可与实际执行限流跨池共享；模型目录按个人输出策略收缩上限。未审核计费或成员额度时拒绝准入。预设置用于填写初始配置，启用前按[验收方案](MODEL_UAT_PLAN.md)验证实际服务。

## 昇腾私有部署预设置

管理页面提供 MindIE/DeepSeek、MindIE/Qwen、vLLM-Ascend/DeepSeek、vLLM-Ascend/Qwen 四个表单预设，均走 CHAT_COMPLETIONS。预设清空地址、实际模型标识和凭证引用，填写请求上下文 8192、输出 2048；这些是准备值，不是实测容量或模型最大能力。创建默认停用，工具、推理、图片和流式 usage 默认关闭。工具关闭的模型不能承载 Harness 工具请求，验证后创建相应能力部署并启用。

先创建共享硬件容量池：执行 1、每人执行 1、等待 100、每人等待 5、等待五分钟（表单初始值）。这些初始值需按模型装载和请求长度进行显存容量测试。活跃任务、排队数量和推理并发分别统计，超出队列上限明确拒绝。模型上下文限制须与实际引擎配置相符，长上下文应另做容量验证。

[MindIE 3.0 的兼容接口说明](https://www.hiascend.com/doc_center/source/zh/mindie/300/mindiellm/llmdev/mindie_service0078.html)列出 v1/chat 接口。模型标识必须填写实际服务配置值，根地址通常填写 `https://<internal-host>:<port>/v1`；入口追加 `/chat/completions`。这不是对任意 MindIE 版本的兼容承诺。

[vLLM-Ascend 安装说明](https://github.com/vllm-project/vllm-ascend/blob/main/docs/source/getting_started/installation.md)要求从同一发布选择经过共同验证的驱动/CANN/PyTorch/TorchNPU/vLLM/Ascend 组合，不能拼装“各自最新”版本。没有硬件型号时不预装引擎或固定版本。[vLLM 工具调用说明](https://docs.vllm.ai/en/stable/features/tool_calling/)和[Qwen 部署说明](https://github.com/QwenLM/Qwen3/blob/main/docs/source/deployment/vllm.md)显示工具解析器、推理解析器及模板随模型而异；不把某个 Qwen3 或 DeepSeek 版本的参数复制到所有新模型。当前客户端不自动添加 `enable_thinking` 或任意 engine extra_body；在推理服务固定经过验证的默认策略。

上线顺序：填写并允许上游 origin → 注册停用部署 → 以实际框架、模型/量化版本验证普通流式、工具调用及工具结果回放、推理历史、取消/断连、最终 usage → 按验证结果创建能力部署 → 小范围启用企业模式 → 用真实负载逐步调整共享池容量。SSE 代理需禁用响应缓冲；客户端票据等待走普通短请求，勿把排队做成长推理连接。未收到 usage 时账本保留未知；断连无法证明 GPU 停止时保留 UNKNOWN，管理员核对后释放。

企业模式开关示例（不含真实地址/密钥）：

```dotenv
FUNNYWORK_MODEL_INFERENCE_ENABLED=true
FUNNYWORK_MODEL_INFERENCE_ORIGINS=https://model.internal:8443
FUNNYWORK_MODEL_INFERENCE_WORKERS=16
# 上游需要认证时，在部署环境另行设置 FUNNYWORK_MODEL_*，管理端只填写变量名。
```

POST `/api/account/inference/credential` 支持 `{deviceId}`；POST `/api/account/inference/credentials/revoke` 使用原账号凭证撤销当前账号该设备所有轮换令牌。入口令牌不能调用签发/撤销接口。设备记录与入口凭证文件只保存在账号私有目录，票据恢复文件不保存提示词或上游密钥。

## 故障与小窗口适配修正

TCP/TLS 建连成功前不会发送推理请求，建连失败记为 FAILED 并释放名额；建连成功后请求可能已发送，后续失败仍按终止证据判断，证据不足记 UNKNOWN。Messages 的 message_start 输出零值不作为最终计量；没有 message_delta 或完整非流式输出计量时 output_tokens 为未知。中断时已观察的增量用量保留为 PARTIAL，只有终止证据和完整字段共同满足时标记 COMPLETE。

Harness 对未显式配置的压缩预算适配模型容量：默认压力余量无法容纳时收缩到可用消息预算四分之一；默认摘要输出受实际摘要模型默认输出及上下文四分之一约束，记录生效值。显式策略不被静默改写，越界配置仍需调整。

## 验收工具

详细场景和分层验收边界见 [测试方案](MODEL_UAT_PLAN.md)，完整安装包与实际昇腾环境见 [人工UAT脚本](MODEL_UAT_MANUAL.md)。自动回归运行 `python3 scripts/checks/run-model-uat.py`；页面UAT运行方法见 [真实组件测试工具](../../apps/management/uat/model-middleware/README.md)。

## 企业 Agent 预设与客户端设置

模型设置在企业模式下只显示后台供应商，隐藏本地新增、凭证编辑和删除入口；现有对话模型选择保持可用。macOS 和 Windows 共用 Host 同步与 UI 实现。

管理端“模型”页面提供企业 Agent 预设和辖区策略。`POST/PUT /api/v1/model-middleware/agent-presets[/{id}]` 创建或替换当前预设，使用 `expectedVersion` 防止并发覆盖。仅保存当前名称、说明和指令；不保留历史、草稿、回滚或旧版本兼容记录。版本数字用于识别配置更新，发布时覆盖旧内容。禁用默认预设前需先修改该辖区默认项。指令仅为纯文本（最多 20000 字符），拒绝 `{{...}}` 模板表达式；服务端不接收可执行插件组合。

`PUT /api/v1/model-middleware/agent-preset-policy` 设置辖区个人预设许可及新任务默认预设。未配置辖区默认禁止个人预设。多辖区用户只有全部辖区允许时才能使用个人预设；默认按辖区名称排序选择首个有效配置，无配置时采用本地标准预设。管理权限复用若依 `models` 模块权限，并校验租户、当前身份及辖区。

`GET /api/account/inference/agent-presets` 使用设备入口凭证读取当前授权预设与策略。客户端按模型目录刷新间隔同步（默认 15 秒），基于安装包自带标准组合替换 persona 文本，保留 macOS Bash 与 Windows PowerShell 条件及原有工具权限。预设自身不指定模型，继续使用现有模型选择与统一准入层。企业预设在设置中单独分组；策略禁止个人预设时隐藏 Creator 入口并在 Host 拒绝新任务选择个人预设。个人文件不自动删除。

旧预设定义在客户端接受新目录后移除，后台旧指令内容直接被替换。运行中的 Agent 与子任务保留已加载作用域直到结束，然后释放，不在磁盘缓存旧版本。重启后打开使用已删除版本的会话会明确失败，需要用户创建新任务；不会静默换用新指令。断网或刷新失败时保持最近一次有效策略，策略更新在恢复连接后获取。产品调用与操作系统终端权限分别管理；发布状态记录服务端版本，设备应用情况在实际客户端核对。

企业预设发布在租户事务锁内校验全租户目录：最多 512 项、最坏响应不超过 4 MiB UTF-8 JSON（包含禁用项、字段和默认预设）。覆盖所有辖区的并集，保证多辖区员工的响应可被客户端接收。新增与替换都校验容量，超过上限返回 `PRESET_CATALOG_LIMIT`，不会写入新配置或递增版本。客户端新挂载在实际绑定前重新验证策略，已运行任务的子任务继续继承原作用域。


## 企业客户端供应商分组

`GET /api/account/inference/catalog` 的每个部署额外返回 `provider_id` 和 `provider_name`。当前按上游模型族识别 OpenAI、DeepSeek、Anthropic、Google、Alibaba、Moonshot 与 xAI；无法识别的自定义模型归入 Custom，不依据管理员填写的模型显示名称推断厂商。字段不暴露上游地址或凭证。

新版 Harness 客户端按厂商注册供应商路由，以部署 ID 作为选择器的模型标识，再在发送时恢复上游模型 ID。相同厂商、甚至相同上游模型的多个部署继续使用独立的能力、输出上限和预算许可。旧客户端忽略新增元数据；新版客户端读取旧目录时继续沿用原路由。更新客户端后，旧对话需要从厂商分组重新选择模型，历史会话不改写。

桌面启动使用认证后的 `POST /api/account/startup`，请求包含 `modelDevice` 与 `policyDevice`，响应以 `schema: 1` 返回 `inference`（`bootstrap`、`credential`、`catalog`）及 `policy`。接口复用现有模型和策略服务，保持租户、区域、角色和输出额度限制，完成策略与目录读取后才签发作用域凭证；不增加成员或全站预算，后续推理仍须通过原预算门禁。若依账号沿用 `funnyworker:desktop:read` 权限及业务身份绑定，不要求新增写权限。独立接口继续供旧客户端和凭证续期使用。
