# FunnyWork 业务服务开发参考

**简体中文** | [English](README.en.md)

`services/business/` 提供账号业务入口、模型中间层、电脑同步、任务调度及管理接口，使用独立的业务数据库并接入身份服务。环境与启动见 [根目录开发指南](../../README.md#本地开发)；客户管理员操作见 [管理指南](../../manuals/ADMIN_GUIDE.md)。

## 按问题查阅

| 主题 | 参考 |
| --- | --- |
| 账号与统一身份 | [身份目录接入](../identity/IDENTITY_DIRECTORY.md) |
| 模型协议、容量池与准入 | [模型中间层](MODEL_MIDDLEWARE.md) |
| 消费、预留、计费审核与异常处理 | [模型额度与预算](MODEL_BUDGETS.md) |
| 模型目录发布与客户端加载 | [模型发布](MODEL_PUBLICATION.md) |
| 桌面与手机记录、远程命令和恢复 | [桌面同步](DESKTOP_SYNC.md) |
| 固定版本成果、下载和预览 | [结果文件](RESULT_FILES.md) |
| 设备授权、连接与回执 | [设备管理](MANAGED_DEVICES.md) |
| 组织限制、默认设置和发布 | [桌面策略](DESKTOP_POLICY.md) |

## 业务模块

| 模块 | 实现与协议入口 |
| --- | --- |
| 反馈与维护任务 | [维护配置](MAINTENANCE.md)、[反馈受理](MAINTENANCE_INTAKE.md)、[原客户端协议](MAINTENANCE_PROTOCOL.md)、[受控交付](MAINTENANCE_DELIVERY.md) |
| 执行资源 | [资源与授权](EXECUTION_RESOURCES.md)、[管理模块](EXECUTION_RESOURCES_R4.md) |
| 年报编制 | [年度经营摘要流程](ANNUAL_REPORTS_R5.md) |

## 检查与交付

服务配置、身份连接、反向代理与数据库升级见[自建服务部署](../../deploy/SERVER_DEPLOYMENT.md)。

模型集成检查见[自动回归方案](MODEL_UAT_PLAN.md)和[人工验收脚本](MODEL_UAT_MANUAL.md)。维护自动化的运行、配置和恢复见[自动维护参考](MAINTENANCE_AUTOMATION.md)。开发时按修改范围选择检查，入口见[根目录测试与验证](../../README.md#测试与验证)。

发布时分别记录业务服务、身份服务、管理前端和客户端版本，按[客户端发布与升级](../../deploy/CLIENT_RELEASES.md)验证登录、模型、同步与成果流程。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。
