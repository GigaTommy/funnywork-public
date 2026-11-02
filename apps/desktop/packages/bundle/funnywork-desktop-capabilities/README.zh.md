---
description: "使用现有 FunnyWork 文本模型操作网页和桌面辅助功能控件。"
kind: "package-bundle"
---

# FunnyWork 电脑操作

[English](README.md) | 中文

本 FunnyWork 定制包采用[源码可用非商业许可](LICENSE)，商业使用须取得书面授权；所依赖的上游提供方继续适用各自许可证。

## 概述

FunnyWork Desktop 内置此组合包，让支持工具调用的文本模型操作网页和桌面辅助功能控件。浏览器操作使用独立的可见窗口和文本快照。电脑操作读取辅助功能信息，不返回截图。普通 Web 和 headless profile 不选择此组合包。

## 目录

- [使用此包](#use-this-package)
- [理解实现](#understand-the-implementation)
- [延伸阅读](#further-exploration)
- [模型体验](#model-experience)
- [已知限制与延后工作](#known-limitations-and-deferred-work)
- [开发备注](#dev-note)

-----

<a id="use-this-package"></a>
## 使用此包

Desktop profile 从打包的生产依赖中选择此组合包。可在 `funnywork-desktop-capabilities` 条目中通过 `browser.enabled` 和 `computer.enabled` 关闭对应能力。已有自定义 profile 组合包列表会保留。桌面恢复操作会关闭此组合包，后续启动保留该选择。

浏览器操作需要已安装的 Chrome、Edge、Chromium 或 Brave 可执行文件。用 `browser.executablePath` 指定准确位置，或在 `browser.searchPaths` 中添加完整候选路径。显式指定的文件不可用时，不会选择其他浏览器。`browser.headless` 隐藏受控窗口，`browser.toolCallTimeoutMs` 限制单次调用时长。启动时不运行 npm 命令，也不下载浏览器。缺少 Chromium 时，`browser_use_status` 说明所需条件，普通聊天仍可使用。

用户可以在受控浏览器中手动登录。每个活动 Session 使用独立上下文，与侧边栏标签及个人浏览器资料分开。Session 卸载和 Host 关闭会结束所属进程并丢弃浏览器状态。取消信号会到达待处理的调用，但不会撤回已经交付的操作。

-----

<a id="understand-the-implementation"></a>
## 理解实现

<details>
<summary>实现细节 — 点击展开</summary>

此包提供 profile [补丁](cordis.patch.yml)和元数据。补丁先安装浏览器和电脑注册服务，再从声明的依赖中加载[运行插件](../../extensions/funnywork-desktop-controls/README.zh.md)，保留 `funnywork-desktop-capabilities` 条目 ID。组合包不将自身作为插件导入；profile 解析会从插件作用域中排除已选择的组合包。浏览器操作使用锁定版本的 [Playwright MCP 提供方](../../experimental/browser-use-playwright-mcp/README.zh.md)，返回完整辅助功能快照并省略图像响应。macOS 使用[原生 CUA 辅助程序](../../experimental/cua-macos/README.zh.md)，Windows 使用 [Cua Driver](../../experimental/computer-use-cua-driver-native/README.zh.md)。两套电脑提供方不会同时挂载。生产依赖将提供方纳入打包运行时。

</details>

-----

<a id="further-exploration"></a>
## 延伸阅读

[Profile 文档](../../boot/app-boot/README.zh.md)说明组合包顺序，[浏览器所有权](../../experimental/browser-use-runtime/README.zh.md)说明 Session 清理。

-----

<a id="model-experience"></a>
## 模型体验

### 浏览器状态与说明

#### 模型看到什么

选中的运行插件提供 `browser_use_status` 和相应平台的电脑状态工具，操作工具由选中的提供方负责。浏览器说明涵盖独立受控窗口、文本快照、手动登录及清理。缺少浏览器时，会加入下列文本。

##### 浏览器不可用说明

```markdown
FunnyWork browser controls are unavailable because no configured Chromium executable was found. Use browser_use_status to report setup requirements; do not download a browser runtime automatically.
```

#### Token 影响

状态工具和浏览器说明增加工具定义与提示词文本。`browser_use_status` 添加简短 JSON 结果，提供方操作通过普通 Session 日志追加文本快照及结果。图像响应会省略。

#### KV Cache 影响

稳定的工具定义和浏览器说明保留请求前缀。每次调用追加参数与文本结果，profile 重新加载可能改变可用提供方和说明。

## 已知限制与延后工作

<a id="known-limitations-and-deferred-work"></a>

- 模型部署必须支持工具调用。启用组合包不会修改模型能力声明或组织策略。
- 浏览器状态不会从对话历史恢复。
- 电脑操作支持 macOS 和 Windows，其他平台返回不支持状态。浏览器和电脑提供方分别报告缺失的本机条件。

<a id="dev-note"></a>
### 开发备注

<details>
<summary>维护者工作上下文 — 点击展开</summary>

无。

</details>
