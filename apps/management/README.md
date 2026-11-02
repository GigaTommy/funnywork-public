# FunnyWork 管理前端开发

**简体中文** | [English](README.en.md)

这是 FunnyWork 默认的 Vue 3 管理前端，基于 plus-ui 定制，部分业务组件引用 `packages/management-react/`。业务入口由 `services/business/` 提供，身份后端为仓库内定制的 `services/identity/`，不需要另行克隆上游项目。

客户操作见 [管理指南](../../manuals/ADMIN_GUIDE.md)。本页供开发人员准备环境、启动和构建。

## 业务入口

| 页面 | 维护内容 |
| --- | --- |
| 员工业务授权 | 账号绑定、业务角色与区域 |
| 模型配置 | 模型与容量、预算与计价、Agent 预设、客户端发布 |
| 客户端策略 | 成员设备、组织策略及回执 |
| 任务管理、会话历史 | 任务及执行终端、问题维护、目录与授权正文 |
| 执行资源、年报 | 项目、资源池、用途、路由及结构化经营摘要 |

菜单及功能权限由[初始化 SQL](../../scripts/setup/ruoyi-business-menus.sql)登记。Vue 页面在 `src/views/funnyworker/`，共享业务组件在 `packages/management-react/`；菜单权限之外，业务服务继续校验角色、租户与区域。

富文本编辑器使用 Quill 2，加载与保存 HTML 时清理脚本、事件属性、可执行链接、表单控件和页面定位样式。保留编辑器支持的排版标签、格式类和文字样式、图片，以及 YouTube 和 Vimeo 的 HTTPS 嵌入视频。

## 准备与启动

从 [根目录开发指南](../../README.md#本地开发) 准备本地后端。使用 `package.json` 指定的 pnpm 和锁文件；仓库准备脚本生成本地 `.env.development`／`.env.production`，不要覆盖已有配置或提交凭证。

从仓库根目录执行：

```sh
pnpm --dir apps/management install --frozen-lockfile
pnpm --dir apps/management dev
```

开发默认只监听 `127.0.0.1:5173`；跨设备联调时显式使用 `pnpm dev --host <局域网地址>`，并限制网络访问范围。身份和业务代理分别由 `VITE_IDENTITY_PROXY`、`VITE_BUSINESS_PROXY` 配置。开发联调连接本地服务和专用测试账号。

## 构建与检查

```sh
pnpm --dir apps/management build:prod
pnpm --dir apps/management typecheck
```

首次构建生成自动导入与组件声明，再运行类型检查。`build:prod` 本身不包含类型检查；仓库服务构建入口会运行配套检查。生产资源需要正确的 `/admin/` 路径和环境模板，由 [产物检查](../../scripts/checks/check-management-artifact.py) 核对。

模型与任务的组件验收入口见 [模型验收](uat/model-middleware/README.md)、[任务中心验收](uat/task-center/README.md)。组件检查使用隔离数据；发布验收另验证已登录账号的真实业务流程。

`VITE_*` 值会进入公开浏览器资源，只填写客户端参数。默认关闭旧 RSA 封装且模板密钥留空；供应商密钥、服务端私钥和账号密码由后端受限配置管理。启用兼容封装时，浏览器侧材料按公开客户端配置管理。

## 发布资料

管理前端、业务 JAR 和身份服务分别记录版本。使用独立静态管理入口时，替换业务 JAR 不会更新该入口；发布时核对前端指向与配套接口。

## 上游与许可

上游说明原文见 [plus-ui README](UPSTREAM_README.md)，版权及许可见 [LICENSE](LICENSE) 和根目录 [第三方声明](../../THIRD_PARTY_NOTICES.md)。FunnyWork 功能入口见本页业务入口和客户管理指南。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。
