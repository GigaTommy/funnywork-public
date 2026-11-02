# 企业内网模型接入探测

**Windows 无 Python 用户**：使用原生 [PowerShell 版本和 Windows 操作说明](ENTERPRISE_MODEL_POC_WINDOWS.md)。它不需要 Python、额外模块或管理员权限；下面的命令为 Python 版。

`probe-enterprise-model.py` 是 Python 3.10+ 标准库脚本，无需安装依赖，可复制到能访问企业内网的电脑或服务器运行。它检查指定账号、接口和模型的请求行为；模型版本、上下文配置及授权信息由部署方提供。

## 统一认证和系统接口的关系

网页登录可能通过 OIDC、SAML、验证码或多因素认证完成；系统接口可能使用另一套 API Token、签名或服务账号。模型 API 使用其要求的独立凭证和访问权限。

此工具支持：

- API Key、Bearer Token，以及已有授权会话的 Cookie：由环境变量填入指定请求头，不读取浏览器数据库，不保存凭证。
- 已有文档明确的 JSON 登录接口：发送配置的请求体，从指定字段提取 Token，用于模型接口。只执行一次登录，不尝试绕过 SSO、验证码或 MFA。
- JSON 请求／JSON 响应的系统封装接口：配置字段映射。复杂签名、加密、浏览器交互式认证及专用流协议需要按实际文档增加适配，不能自动猜测。

若仅有网页，先在授权账号下通过浏览器 Network 找到聊天请求，向部署方确认正式 API、凭证范围和协议。不要把完整 HAR、Token、Cookie 或用户密码上传到聊天或 Git。

## 快速运行

所有示例均为占位地址，不能直接用于真实连接。先在有内网权限的主机上，向部署方索取 API 根地址和模型 ID，选择并复制一份配置到项目 `.local/agent/`。本机产物使用已挂载的 `/Volumes/SSD`；复制到其他企业主机时按企业规定选择私有目录。

```sh
mkdir -p .local/agent/model-poc
chmod 700 .local/agent/model-poc
cp deploy/enterprise-model-probe.example.json .local/agent/model-poc/config.json
chmod 600 .local/agent/model-poc/config.json
```

修改私有配置的 `base_url`、`model`、协议和认证头，凭证仍保留 `${FW_POC_API_TOKEN}` 形式。命令通过 `--prompt-env` 无回显输入凭证；也可由企业密钥管理工具设置相同环境变量。不要在命令参数或配置中填真实密钥。

先只检查目录接口，不发送推理请求：

```sh
python3 scripts/checks/probe-enterprise-model.py \
  --config .local/agent/model-poc/config.json \
  --prompt-env FW_POC_API_TOKEN \
  --output .local/agent/model-poc/catalog.json
```

然后运行完整小样本探测，会消耗实际模型额度：

```sh
python3 scripts/checks/probe-enterprise-model.py \
  --config .local/agent/model-poc/config.json \
  --prompt-env FW_POC_API_TOKEN --active \
  --output .local/agent/model-poc/capabilities.json
```

报告拒绝覆盖；每次使用新的输出文件名。只验证基本对话和多轮上下文时添加 `--checks multi_turn`，只做基本对话时添加 `--checks ''`。已知协议正常完整探测为 7 个请求：1 次模型列表、基本对话、多轮、流式、JSON、工具调用及工具结果回传。配置 JSON 登录会额外登录一次；`auto` 尝试第二种协议时至多增加一次请求；没有自动重试或并发。

## 配置方式

### 标准模型服务

模板：[API Token 配置](../../deploy/enterprise-model-probe.example.json)。

- `base_url` 是 API 前缀，例如 `https://host/v1`。`chat_path` 默认 `/chat/completions`，拼接为 `/v1/chat/completions`；`messages_path` 默认 `/messages`；路径参数均相对于此前缀，不能包含查询参数。不要填写 Chatbot 首页地址。
- `protocol` 可为 `chat_completions`、`messages`、`auto` 或 `custom`。自动模式只对这两种已知协议发合成基本对话，不扫描内网或猜测其他路径；只记录首个成功的协议。
- `models_path` 默认 `/models`；没有模型列表接口时设置为 `null`，避免目录缺失影响最终退出码。目录关闭时仍可检查配置明确指定的模型。模型 ID 必须由配置明确选择，工具不会自动选择目录中的模型发请求。
- 目录默认读取 `data` 数组中的 `id`；可通过 `models_items_path` 和 `model_id_path` 调整，例如 `data.items`、`modelCode`。只保存最多 200 项格式受限的 ID，不保存目录响应全文。目录名称用于接口选型标识；实际权重、量化精度和上下文长度从部署资料核对。
- Messages 服务按其文档设置请求头，例如 `x-api-key: ${FW_POC_API_TOKEN}`、`anthropic-version: 2023-06-01`。不同平台支持的头和版本需向部署方确认。
- 已授权网页 Cookie 可以使用 `Cookie: ${FW_POC_COOKIE}` 请求头，但只在部署方确认该 API 支持它时使用。不要把统一认证平台的 Cookie 直接转发给其他域名。
- `request_defaults` 可添加部署方要求的普通参数。标准请求固定 `model`、合成 `messages`、`max_tokens: 128` 和 `stream`，不能通过 defaults 替换。若服务要求其他输出预算参数，需使用自定义适配或按文档扩展。
- TLS 默认校验证书，可用 `ca_file` 指向企业 CA PEM 文件；不提供跳过证书校验选项。非本机 HTTP 需显式设置 `allow_http: true`，仅用于已确认的测试网络。
- 不继承系统代理，也不跟随 HTTP 跳转。运行主机必须能够直连目标；有额外代理、VPN或客户端证书要求时需另外配置或扩展。

### JSON 登录换 Token

模板：[登录配置](../../deploy/enterprise-model-probe-login.example.json)。按真实文档修改 `login.url`、`body`、`token_path`。字段路径使用点分隔，数组索引可写成 `data.tokens.0.value`。默认提取后的 Token 放入 `Authorization: Bearer ...`，也可指定 `header` 和 `prefix`。

```sh
python3 scripts/checks/probe-enterprise-model.py \
  --config .local/agent/model-poc/login-config.json \
  --prompt-env FW_POC_USERNAME --prompt-env FW_POC_PASSWORD --active \
  --output .local/agent/model-poc/login-capabilities.json
```

登录请求只使用 `login.headers`，不会继承模型 API 凭证。若登录接口也需要应用凭证，可用该字段引用另一个环境变量。登录得到的 Token 仅保留在内存中，正常平台过期策略仍生效；脚本不会推测注销或撤销接口。

### 已封装的系统服务

模板：[自定义接口配置](../../deploy/enterprise-model-probe-custom.example.json)。

`custom.body` 的完整字符串值 `{{model}}`、`{{messages}}`、`{{max_tokens}}`、`{{stream}}` 会被对应类型替换；例如 `{{messages}}` 替换为 JSON 数组，不能嵌入其他字符串。`custom.text_path` 是响应中回答文字的路径。

自定义适配目前只验证基本对话和调用方传入历史的多轮对话。若服务只接受单句 `question`、依赖 `conversation_id` 或不使用此历史结构，需要按服务文档扩展适配；不能直接套用模板。流式、工具和结构化输出标为 `NOT_TESTED`，不会根据普通聊天成功推断这些能力。

## 如何解读报告

| 状态 | 含义 |
| --- | --- |
| `PASS` | 该样本的请求、响应及断言通过，报告附已测试样本范围 |
| `NOT_CONFIRMED` | 本次没有验证通过，可能是参数、协议、版本或模型行为差异 |
| `BLOCKED` | 登录、权限、跳转、网页响应或限流阻碍了该项验证 |
| `NOT_TESTED` | 本次未测试，或工具未提供该项适配；按对应接口另行验证 |

`authentication_setup` 只表示凭证配置／登录提取步骤完成。实际 API 权限需结合目录和基本推理结果判断。若推理阶段遇到权限或限流阻断，停止后续能力请求。

报告包含模型目录、已验证协议、调用次数、各项状态、SSE 首事件耗时和可用的 Token 数值；不含凭证、地址、提示词、生成文本或上游错误正文。仍应将模型 ID 等部署信息视作企业内部资料。输出文件新建权限为 `0600`（Windows 的 ACL 需由运行环境保障）。

- 多轮通过：调用方提交的消息历史在样本中有效，平台侧的历史持久化另按存储接口检查。
- 流式通过：收到 SSE 文本和完整结束事件；缓冲行为和工具流式另行检查。首事件耗时与首个文本 Token 耗时分别记录。
- JSON 通过：证明本次文本可以解析且值符合预期；未验证严格 Schema 强制约束。Chat Completions 会请求 JSON Object 模式，Messages 只用提示词约束。
- 工具通过：验证强制选择一个工具、工具名与参数、工具结果回传及最终回答。只执行本地固定回声桩，不运行模型返回的代码，不接触真实业务工具；未验证自主选工具、并行调用或长期 Agent 成功率。
- Usage：只记录响应实际提供的非负整数 Token 值，缺失则为空，不补成 0。标准流测试不额外请求平台专用的 usage 参数，需要流用量时，按平台协议单独检查对应参数与返回值。
- 上下文上限、并发、图片、服务端取消均保留为 `NOT_TESTED`。128 Token 的小请求通过只代表该预算可用，不能推断最大输出长度。

每个请求有 1–120 秒的可配置网络超时、读取过程中的总时长检查、2 MiB 响应上限；单次阻塞读取仍可能额外等待一个网络超时。没有重试，避免请求失败后重复消耗。中断客户端也不意味着服务端已取消推理。

退出码：`0` 表示实际列出的检查全部通过或明确未测，**不表示全能力可用**；`2` 表示报告已保存但存在未确认／阻断；`1` 表示配置、文件或其他本地错误。未配置 `--active` 时不会尝试推理。

用于 funnywork 接入时，`chat_completions` 对应 `CHAT_COMPLETIONS`，`messages` 对应 `MESSAGES`。自定义服务需增加协议适配。报告仅供人工审阅，不自动修改模型能力、上下文、价格、权限或生产部署。

## 开发验证

```sh
python3 -B scripts/checks/test-enterprise-model-probe.py
python3 scripts/checks/check-repository.py
```

模拟 HTTP 服务覆盖两种标准协议、封装接口、登录与凭证隔离、重定向、HTML 登录页、401/403、429、SSE 截断／错误、工具名与结果往返校验。企业实际接口按目标部署配置另行联调。

协议参考：[vLLM 兼容接口](https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/)、[Messages 流事件](https://platform.claude.com/docs/en/build-with-claude/streaming)。部署版本仍以企业实际文档为准。
