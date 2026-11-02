# 模型检测

**简体中文** | [English](README.en.md)

默认入口已改为 Windows 原生脚本工具。下载后解压，双击「启动工具.cmd」，填写地址、正常登录并发送一条测试消息，随后自动识别和检测，无需复制脚本或导入导出 JSON。

使用步骤和验证范围见 [Windows 工具说明](NATIVE_MODEL_CHECK.md)。

`index.html` 是精简后的下载入口；`index.manual.html` 和 `page-shell.manual.html` 保留上一版手动网页工具，仅作为备用方式。原生窗口和新启动页均采用操作型文案，不再展示产品介绍或实现说明。

浏览器核心由 `scripts/checks/browser-model-probe.js` 维护。旧版单文件 HTML 可使用 `node scripts/build/build-enterprise-model-poc.cjs` 重新生成；它只更新 `index.manual.html`，不会覆盖新的下载入口。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。
