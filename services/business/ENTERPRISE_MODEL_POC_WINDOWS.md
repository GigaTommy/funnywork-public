# Windows 企业模型探测：无需 Python

交付脚本 `scripts/checks/probe-enterprise-model.ps1` 使用 PowerShell 和系统 .NET API，无需 Python、额外模块或管理员权限。按 Windows 自带的 Windows PowerShell 5.1 语法及 API 编写，也支持 PowerShell 7。实际已在 macOS PowerShell 7.4.20 上运行本地模拟接口测试；尚未在 Windows PowerShell 5.1 实机、Windows 文件 ACL 分支或企业内网完成验收。

## 准备文件

可使用本次交付的 Windows ZIP 包，或从仓库复制以下文件到内网电脑的一个私有文件夹，例如 `D:\FunnyWorkModelPOC`：

- [PowerShell 脚本](../../scripts/checks/probe-enterprise-model.ps1)
- [API Token 模板](../../deploy/enterprise-model-probe.example.json)
- [JSON 登录模板](../../deploy/enterprise-model-probe-login.example.json)
- [系统封装接口模板](../../deploy/enterprise-model-probe-custom.example.json)

ZIP 包内分别为 `probe-enterprise-model.ps1`、`api-token.example.json`、`login.example.json`、`custom.example.json`，并附这份说明。运行时只需要脚本和你选定的配置。

## 最快开始

打开 **Windows PowerShell**，进入解压目录：

```powershell
Set-Location D:\FunnyWorkModelPOC
Copy-Item .\api-token.example.json .\config.json
notepad .\config.json
```

仓库中的模板文件名为 `enterprise-model-probe.example.json`；如果直接复制源码，请把上面的源文件名改成它。

请部署方协助填写实际信息：

```json
{
  "base_url": "https://模型接口域名/v1",
  "protocol": "auto",
  "model": "实际模型ID",
  "headers": {"Authorization": "Bearer ${FW_POC_API_TOKEN}"},
  "models_path": "/models",
  "timeout_seconds": 30
}
```

地址必须是模型 API 的前缀，不是 Chatbot 网页首页。保留 `${FW_POC_API_TOKEN}` 原样；脚本运行时会无回显询问凭证，不要写进配置。没有模型列表接口时将 `models_path` 改为 `null`。保存配置时使用 UTF-8。

先检查目录和接口认证，不调用推理：

```powershell
.\probe-enterprise-model.ps1 -Config .\config.json -Output .\catalog.json -PromptEnv FW_POC_API_TOKEN
```

再运行能力探测，会消耗真实模型额度：

```powershell
.\probe-enterprise-model.ps1 -Config .\config.json -Output .\capabilities.json -PromptEnv FW_POC_API_TOKEN -Active
```

它验证基本对话、多轮上下文、SSE 流式、JSON 输出和工具调用往返，标准协议全部运行通常为 7 个请求，串行执行、没有重试。`auto` 探测第二种协议时可能额外增加 1 次请求。登录换 Token 额外增加 1 次登录请求。

报告不会覆盖同名文件。再次运行请使用新名字，例如 `capabilities-02.json`。在 PowerShell 中查看报告：

```powershell
Get-Content .\capabilities.json -Raw -Encoding UTF8
```

只测试对话和多轮：

```powershell
.\probe-enterprise-model.ps1 -Config .\config.json -Output .\chat-only.json -PromptEnv FW_POC_API_TOKEN -Active -Checks multi_turn
```

在 PowerShell 内调用时，`-Checks ''` 只执行基本对话，不做其他能力检查。

如果本机执行策略阻止脚本，请按企业规定使用签名脚本或由管理员批准执行；脚本不会修改机器或用户的执行策略。下载文件存在来源标记时，也需按企业规定核验和处理。无需以管理员身份打开 PowerShell。

## 你们的统一认证场景

**网页登录和系统接口可以使用不同凭证。** 优先向部署方索取系统接入文档和对应测试凭证，不能假设网页的 SSO Cookie 就是 API Token。

### 用户名密码换 Token

复制 `login.example.json` 为 `config.json`，按正式文档修改登录 URL、请求字段和 `token_path`，然后在当前 PowerShell 窗口运行：

```powershell
.\probe-enterprise-model.ps1 -Config .\config.json -Output .\login-capabilities.json -PromptEnv FW_POC_USERNAME,FW_POC_PASSWORD -Active
```

这里的逗号用于 PowerShell 数组参数，应直接在 PowerShell 中执行此命令。它会分别无回显询问用户名和密码。登录请求不会继承模型接口请求头；只有提取出的 Token 才会放到配置的模型接口认证头。

### 只有浏览器 SSO 登录

OIDC、SAML、验证码或 MFA 的浏览器交互不能用一个通用脚本自动完成。本工具不会读取浏览器凭证库或绕过认证。部署方明确支持现有会话访问 API 时，可把配置中的认证头改为：

```json
"headers": {"Cookie": "${FW_POC_COOKIE}"}
```

运行时使用 `-PromptEnv FW_POC_COOKIE` 输入已有授权 Cookie；不要把身份平台 Cookie 随意转发给其他域名。若平台没有开放此用法，应申请正式系统凭证。

### 已有系统封装接口

使用 `custom.example.json`，按实际接口调整请求结构和回答字段。这个通用适配只测试基本对话及传入历史的多轮对话，其他能力标为未测试。特定签名、加密、会话 ID 协议需根据真实接口文档继续适配。

## 结果和边界

| 状态 | 含义 |
| --- | --- |
| `PASS` | 当前样本的实际请求和断言通过 |
| `NOT_CONFIRMED` | 未确认，可能涉及参数、协议或模型行为，不能断言底层模型不支持 |
| `BLOCKED` | 登录、权限、限流、跳转或网页响应阻断了验证 |
| `NOT_TESTED` | 未测试或当前适配未实现 |

- `authentication_setup` 只表示凭证配置／Token 提取完成，API 访问权还需看目录或推理检查。
- 仅请求固定合成内容，不读取项目、用户历史或业务文件；不运行模型生成的代码，只使用无副作用的本地回声工具桩。
- 不记录密码、Token、Cookie、地址、生成文本或错误正文。目录中的模型 ID 仍属于内部部署信息，报告应内部保存。
- Windows 报告创建时设置仅当前用户的文件 ACL；不修改已有目录的 ACL，也不覆盖已有文件。请使用支持 Windows ACL 的本地磁盘目录；若文件系统不支持 ACL，工具会失败，不会降级写入报告。
- TLS 使用系统可信证书库，不跳过验证。Windows 版本不使用 Python 配置中的 `ca_file`；企业私有 CA 应按企业流程配置到系统信任库后再运行。
- 默认不使用系统代理、不跟随重定向。运行电脑必须能直连模型和认证地址。非本机 HTTP 需在配置中显式设置 `allow_http: true`，限已确认的测试网络。
- 每个请求网络超时默认 30 秒，响应体／SSE 读取以同一时钟计算剩余等待时间，响应上限 2 MiB，输出预算固定 128 Token。不探测极限上下文或高并发。
- 工具测试覆盖本页列出的固定样本；上下文长度、并发、图片、服务端取消、自主工具选择及并行调用按目标业务另行测试。
- Usage 缺失保留为空，不当成 0；JSON 样本验证返回内容的解析，严格 Schema 约束另设测试。
- 退出码 `0`：列出的检查通过或明确未测；`2`：报告已保存但有阻断／未确认；`1`：配置、文件或本地错误。

详细配置语义与协议说明见仓库中的 [通用说明](ENTERPRISE_MODEL_POC.md)。

## 开发验证记录

PowerShell 版已经在本机 PowerShell 7.4.20 运行真实脚本子进程，对本地模拟服务验证目录、标准协议、自动协议回退、登录、流式、工具往返、自定义映射、权限和限流、脱敏、配置检查等场景。测试运行器使用 Python，仅供开发回归；交付给用户的 PowerShell 脚本不依赖它。

Windows PowerShell 5.1 实机及 Windows ACL 分支待实际 Windows 环境验证；企业平台实测待提供地址和授权配置。不能把模拟测试当作企业模型能力验收。

Windows 文件安全 API 使用微软的 [FileStream 安全构造函数](https://learn.microsoft.com/en-us/dotnet/api/system.io.filestream.-ctor)及 [.NET FileSystemAclExtensions.Create](https://learn.microsoft.com/en-us/dotnet/api/system.io.filesystemaclextensions.create)。
