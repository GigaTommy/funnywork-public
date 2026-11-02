# FunnyWork 桌面定制参考

本页说明 FunnyWork 在 DeepSeek Harness 工作区中的接入位置。上游运行时、插件和开发架构见 [Harness README](README.md)；FunnyWork 的账号、模型、手机协作和分发入口见[项目 README](../../README.md)。

## 功能与源码

| 功能 | 主要实现与参考 |
| --- | --- |
| 登录与账号运行配置 | [账号接入](apps/desktop/src/funnyworker-account.ts)、[Python 启动器](../../funnyworker/desktop.py) |
| 模型目录、额度与组织配置 | [模型中间层](../../services/business/MODEL_MIDDLEWARE.md)、[组织策略](../../services/business/DESKTOP_POLICY.md) |
| 手机连接、对话与文件同步 | [同步实现](apps/desktop/src/funnyworker-history-sync.ts)、[同步协议](../../services/business/DESKTOP_SYNC.md) |
| 浏览器与电脑操作 | [Desktop Controls](packages/bundle/funnywork-desktop-capabilities/README.zh.md) |
| 仓库维护执行 | [客户端执行器](apps/desktop/src/funnyworker-maintenance.ts)、[维护协议](../../services/business/MAINTENANCE_PROTOCOL.md) |
| 固定版本成果和预览 | [结果文件协议](../../services/business/RESULT_FILES.md) |

客户端沿用 Harness 的 Host、Workspace、Session 和插件体系。账号配置与数据按服务及身份隔离；远程指令通过业务服务校验授权，再交由原电脑执行。模型供应商凭证由服务端管理。

## 开发与构建入口

从项目根目录按[启动桌面客户端](../../README.md#启动桌面客户端)准备配置并运行。连接已有服务只需要客户端工具链和有效账号；开发业务接口时另启动本地服务。

源码运行、安装包构建和客户端发布各自使用对应入口：[项目打包指南](../../README.md#桌面打包与分发)、[桌面构建参考](apps/desktop/README.md)、[发布与升级](../../deploy/CLIENT_RELEASES.md)。各平台的构建工具、签名和产物目录由这些指南维护。

## 验证与协作

修改账号、同步或维护执行器时，按实现目录选择对应测试；开发检查入口见[Harness 开发指南](docs/development.md)。安装后的登录、模型及同步检查见[项目安装包检查](../../README.md#windows-安装包检查)。

保留上游包名、会话格式、版权和许可。定制功能通过插件及应用接入层维护；修改前遵循[工作区规范](AGENTS.md)及相关子目录约定。
