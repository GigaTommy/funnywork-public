# FunnyWork 身份服务开发

**简体中文** | [English](README.en.md)

本目录包含基于 RuoYi-Vue-Plus 定制的身份与管理后端，维护账号、部门、角色、菜单权限和登录会话，并为 FunnyWork 业务服务提供身份校验。管理前端位于 `apps/management/`，业务入口位于 `services/business/`。

客户操作见[管理员指南](../../manuals/ADMIN_GUIDE.md)；环境与完整启动流程见[根目录开发指南](../../README.md#本地开发)。

## 功能与实现入口

| 功能 | 实现与参考 |
| --- | --- |
| 本地账号、部门和角色 | 系统管理中的用户与角色管理，复用若依账号及权限体系 |
| 桌面与手机身份桥 | [FunnyWorkerIdentityController](ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/FunnyWorkerIdentityController.java)；认证结果包含租户、账号、部门、昵称及权限 |
| 成员使用状态 | [FunnyWorkerUsageController](ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/FunnyWorkerUsageController.java)，供管理前端核对开通条件 |
| 企业身份认证与同步 | [身份目录接入](IDENTITY_DIRECTORY.md)；本地用户与外部身份源共享权限规则，分别维护密码与账号来源 |

定制 `/funnyworker/identity` 返回 `nickname`，缺失时为空字符串；业务服务通过 `/api/account/session` 将其传递给桌面问候与账号入口，`username` 保留为登录标识。

## 准备与启动

需要 JDK 17、仓库准备的 Maven 3.9.9、PostgreSQL 和 Redis。从仓库根目录准备本地管理环境：

```sh
.venv/bin/python scripts/prepare-ruoyi.py
sh scripts/start-ruoyi.command
```

先按根 README 创建 Python 环境并准备数据库工具。准备脚本保留已有账号和数据，将本地配置写入 `.local/ruoyi/`；随机管理员密码保存在 `.local/ruoyi/admin-login.json`。身份服务默认监听 `127.0.0.1:8767`，通过业务入口的 `/ruoyi/` 代理接入。Windows 使用根目录的 `scripts/start.ps1 -Build` 启动配套服务。

正式部署使用独立受限配置，数据库与 Redis 位于私有网络。服务网络关系、HTTPS 入口和备份回退见[自建服务部署](../../deploy/SERVER_DEPLOYMENT.md)。

## 身份目录与升级

未配置企业身份源时，在管理端创建本地用户并分配角色与业务授权。外部身份源通过 `FUNNYWORK_IDENTITY_DIRECTORY_FILE` 指定配置文件，凭证使用环境变量引用；首次同步前准备租户、部门映射、迁移及身份源权限。详细协议、迁移顺序、同步规则和审计见[身份目录接入](IDENTITY_DIRECTORY.md)。

## 构建与检查

根目录的 `scripts/build-server.command` 或 `scripts/build-server.ps1` 构建配套服务。只运行身份目录定向测试时，从仓库根目录执行：

```sh
.local/tools/apache-maven-3.9.9/bin/mvn -f services/identity/pom.xml -pl ruoyi-admin -am \
  -Dtest=DirectorySyncTest,HttpDirectoryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

使用独立测试账号和数据库验证本地登录、身份源同步、账号停用、权限及业务授权。数据库升级前先备份，按身份目录指南执行迁移，再部署配套服务与管理前端。

独立 Docker 样例的口令准备与校验见[Docker 样例指南](script/docker/README.md)。

## 上游与许可

原有若依介绍完整保留在[上游参考](UPSTREAM_README.md)。组件的版权和独立 MIT 许可见[LICENSE](LICENSE)；FunnyWork 整体许可范围及商业使用条件见[根目录许可说明](../../README.md#许可)和[第三方声明](../../THIRD_PARTY_NOTICES.md)。

启用 SnailJob 时，通过服务端环境变量 `FUNNYWORK_SNAIL_JOB_TOKEN` 配置与调度服务一致的专用令牌。

身份服务默认仅供同源代理访问。需要跨域管理页面时，将服务端 `RUOYI_CORS_ALLOWED_ORIGINS` 配置为逗号分隔的精确来源（协议、域名及端口），例如 `https://admin.example.com`；开发环境可使用 `http://localhost:5173`。请求通过 `Authorization` 和 `clientid` 头传递身份，跨域请求不携带浏览器凭证；通配来源、路径和非本机 HTTP 来源在启动时拒绝。

身份服务默认仅暴露 Actuator `health`，健康信息不返回组件详情；未配置监控用户名或口令时拒绝访问。启用监控时，在受限服务端配置中设置 `spring.boot.admin.client.username` 与 `spring.boot.admin.client.password`（环境模板的口令由 `RUOYI_PASSWORD` 提供），并将监控端点限制在运维网络；按需逐项开放端点。

升级已有环境时，在该环境配置中同步设置 `management.endpoints.web.exposure.include=health` 和 `management.endpoint.health.show-details=never`；环境准备脚本保留已有配置，修改模板不会覆盖既有文件。
