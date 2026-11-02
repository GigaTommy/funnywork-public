# Windows 模型检测工具

当前默认交付为单文件 `FunnyWork-Model-Check.cmd`：直接双击，控制器、界面、文案和检测逻辑均已内置，无需网页、解压或旁置脚本。下文多文件 ZIP 仅保留为旧版说明。单文件由 `node scripts/build/build-model-check-single.cjs` 生成。

## 开始

1. 将 `FunnyWork-model-checker-windows.zip` 解压到可写文件夹，例如 `D:\FunnyWorkCheck`。
2. 双击 **启动工具.cmd**。
3. 填写统一认证地址（可选）和模型对话页地址，点击 **开始检测**。
4. 在打开的测试浏览器中正常登录，发送一句不含业务信息的测试消息。
5. 等待窗口显示结果。报告自动保存，可点击 **打开报告文件夹** 查看。

无需 Python、Node、浏览器扩展、驱动或管理员权限。工具使用 Windows 自带的 Windows PowerShell 5.1、系统 .NET 和已安装的 Edge／Chrome。需要先解压，不能直接在 ZIP 预览中运行。

如果对话页会自动跳转统一认证，直接完成登录即可；若没有跳转，可点击工具中的 **登录**，完成后点击 **进入对话**。密码只在原登录网页中输入，工具窗口不接收密码。

默认检测基本对话、多轮、流式、JSON、工具往返，最多发送 6 次合成推理请求，计入模型用量。可取消不需要的检测项。原测试消息回复结束后才开始后续检测。

完成后点击 **停止** 或关闭工具，独立测试浏览器随之关闭，测试登录数据清理。日常 Edge／Chrome 窗口及其用户目录不会被接管或修改。如果文件被浏览器占用而未能清理，窗口会明确提示；关闭测试窗口后再次点击停止，不要直接删除唯一报告。

## 结果

- **通过**：当前小样本验证成功。
- **受阻**：登录或限流等条件阻止了本次验证。
- **未确认**：请求、参数或响应未通过验证，不能断言模型不支持。
- **未检测**：例如网页专用封装接口需要适配。此时工具仍自动保存接口元信息，不伪造标准协议检测结果。

窗口直接显示各项结果及已观察到的模型 ID。Token 只记录实际返回的数值，缺失不补成 0。上下文上限、并发、图片、自主工具选择和生产 Agent 稳定性不在这组小样本中。

## 文件

工具包内包含：

- `启动工具.cmd`：双击入口。
- `start-model-browser-check.ps1`：原生操作窗口及命令行入口。
- `enterprise-model-browser-engine.ps1`：独立浏览器的启动、连接、采集、自动检测与清理。
- `enterprise-model-browser-text.json`：中英文界面文案，不需要填写。
- `browser-model-probe.js`：自动注入浏览器的检测逻辑，不需要复制。
- `使用说明.md`：此说明。

报告保存在解压目录的 `.funnywork-model-check/<时间和随机编号>/report.json`。在源码目录直接运行时，使用仓库根目录 `.local/agent/model-browser-check/`。Windows 会给每次检测的目录设置仅当前用户的访问规则。

入口为当前 PowerShell 进程指定执行策略，不修改机器或用户的持久执行策略；企业管理策略仍生效。不要关闭企业浏览器安全策略。

## 实现和验收记录

通过 Edge／Chrome 自带的 DevTools Protocol 控制**工具自己启动的独立用户目录**。调试端口随机分配并仅连接本机回环地址；不连接现有浏览器、不读取已保存密码或浏览器凭证库、不使用关闭同源限制或跳过证书验证的参数。

脚本只在配置的对话页来源注入检测逻辑，登录页由浏览器正常处理。通过已观察到的请求确定协议和模型，复用请求的短期认证头及原 Cookie 凭证模式；凭证只在浏览器内存中使用，不写入报告。报告只保留脱敏后的地址、字段名、模型标识和检测结果，仍属于企业内部资料。

已通过本机实际 PowerShell + 独立 Chromium 的完整链路回归，包括模拟登录跳转、HttpOnly Cookie、多轮、SSE、JSON、工具往返、限流、封装接口识别以及配置目录清理。核心浏览器检测还有 16 项回归。**尚未在 Windows PowerShell 5.1 的 WinForms 窗口和文件 ACL 分支实机验收，也未连接企业真实地址。**

源码回归入口为 `scripts/checks/check-native-model-browser.cjs`。该 Node 开发测试运行器不随用户使用流程运行。

参考：[Edge DevTools Protocol](https://learn.microsoft.com/en-us/microsoft-edge/devtools/protocol/)、[CDP 页面启动注入](https://chromedevtools.github.io/devtools-protocol/tot/Page/#method-addScriptToEvaluateOnNewDocument)。
