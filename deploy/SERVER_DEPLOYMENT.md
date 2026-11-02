# 自建服务部署

本页供交付与运维人员部署业务服务、身份服务和管理页面。企业内部部署、收费服务及 SaaS 使用应先取得[商业授权](../COMMERCIAL-LICENSE.txt)；许可范围见[项目许可证](../LICENSE)。官网入口：[官网](https://funnywork.funnypixelsapp.com)。

## 部署组成与网络

| 组件 | 配置与产物 | 接入要求 |
| --- | --- | --- |
| 业务服务 | `services/business/target/funnyworker-server-0.1.0-SNAPSHOT.jar`；[配置定义](../services/business/src/main/resources/application.yml) | 默认监听 `127.0.0.1:8765`，由 HTTPS 入口提供客户端接入 |
| 身份服务 | `services/identity/ruoyi-admin/target/ruoyi-admin.jar` | 业务服务通过固定 `http://127.0.0.1:8767` 调用身份校验及若依代理；两个进程须共享可访问该回环地址的网络命名空间 |
| 管理页面 | `apps/management/dist/`，由构建脚本复制到业务服务的 `static/admin/` | 通过 `/admin/index.html` 访问；管理接口按运维网络和管理员身份控制访问 |
| 身份数据 | PostgreSQL 与 Redis | 使用受限账号和私有连接，配置数据库、缓存密码及 JWT 签名密钥 |
| 业务数据 | 独立业务数据源、迁移及持久化目录 | 多副本服务共用 PostgreSQL；文件、签名材料和恢复配置纳入备份 |

身份服务调用地址目前由源码固定。使用独立容器网络或拆分主机时，需先调整并验证服务间连接；仅修改业务服务监听地址不会改变身份服务目标。相关实现见[身份校验](../services/business/src/main/java/com/funnywork/ManagementIdentity.java)与[若依代理](../services/business/src/main/java/com/funnywork/ManagementProxyController.java)。

## 构建与配置

1. 按[开发环境](../README.md#开发环境)准备 JDK 17、Maven、Node.js 和对应工作区的固定 pnpm 版本。在构建环境执行 `sh scripts/build-server.command`；Windows 使用 `scripts/build-server.ps1`。记录源码提交及两个 JAR 的摘要。
2. 准备身份数据库和 Redis，并维护该环境的身份服务配置。已有数据库先备份并检查升级脚本。[本地准备脚本](../scripts/setup/prepare-ruoyi.py)用于本地管理环境，会写入开发配置；正式部署需独立维护账号、密码、JWT、访问控制及启用功能。
3. 使用受限环境文件设置业务服务配置。下表中的变量由[业务配置](../services/business/src/main/resources/application.yml)与[账号接入](../services/business/src/main/java/com/funnywork/ManagementDesktopAccounts.java)读取。敏感值由部署环境注入，环境文件不提交 Git。

| 配置 | 用法 |
| --- | --- |
| `FUNNYWORK_BIND_ADDRESS`、`FUNNYWORK_PORT` | 默认 `127.0.0.1`、`8765`；与入口代理及网络策略一致 |
| `FUNNYWORK_DB_URL`、`FUNNYWORK_DB_USER`、`FUNNYWORK_DB_PASSWORD` | 配置业务数据库；缺省使用相对工作目录 `.local/funnyworker-java` 的 H2 文件库，PostgreSQL URL 使用 `jdbc:postgresql://<数据库主机>:<端口>/<业务库>` |
| `FUNNYWORK_RUOYI_ACCOUNT_ENABLED` | 使用若依成员登录时设为 `true`；成员仍须具备桌面权限与业务授权 |
| `FUNNYWORK_RUOYI_DESKTOP_TENANT` | 账号接入租户，默认 `000000`；与身份系统配置一致 |
| `FUNNYWORK_MODEL_INFERENCE_ORIGINS` | 上游模型允许访问的完整 origin，逗号分隔；管理员注册的模型部署需在名单内 |
| `FUNNYWORK_RECOVERY_GENERATION`、`FUNNYWORK_RECOVERY_ISOLATION` | 数据恢复的代际与隔离配置，恢复校验机制见[模型中间层](../services/business/MODEL_MIDDLEWARE.md) |

启动前固定进程工作目录和运行账号。先启动 PostgreSQL、Redis 和身份服务，再启动业务 JAR；进程管理器加载该环境的受限配置，并保存日志。业务服务启动时执行随 JAR 提供的 Flyway 迁移，启动日志中的迁移失败应先处理再开放流量。部署脚本不得设置 `FUNNYWORK_LOCAL_MANAGEMENT_BOOTSTRAP`。

在已加载业务环境变量的受限会话中，以下命令展示两个服务的启动参数。替换 JAR 和身份配置路径；身份 YAML 应配置监听 `127.0.0.1:8767` 及所需数据源、缓存和安全设置。正式运行时由进程管理器分别托管两个进程。

```sh
# 身份服务
java -jar /部署目录/ruoyi-admin.jar \
  --spring.config.additional-location=file:/受限配置目录/identity.yml

# 业务服务，在另一个已加载该服务环境变量的会话执行
java -jar /部署目录/funnyworker-server-0.1.0-SNAPSHOT.jar
```

启动后在服务端检查 `http://127.0.0.1:8765/actuator/health`，再通过受限管理入口验证登录。健康检查与账号、模型及同步流程分别核对。

## HTTPS 与接口

[Nginx 模板](nginx/funnyworker.conf.example)放在 Nginx 的 `http` 上下文内，替换示例域名和证书路径后运行 `nginx -t`。模板要求后端在同一主机回环地址，客户端通过管理员提供的 HTTPS 根地址连接。

- 模板的根路径默认仅允许回环访问；管理页面、`/ruoyi/` 和管理 API 应按实际 VPN 或受限来源配置。保留账号认证和业务权限校验。
- `/api/account/chats/` 与 `/api/account/inference/` 关闭响应缓冲、读取超时为 660 秒。负载均衡和其他入口层应匹配流式响应及执行时长。
- 模板默认请求体上限为 3 MiB，推理路径为 16 MiB；同步附件采用分块传输。调整额度时同时检查代理请求体、分块编码开销和[同步接口限制](../services/business/DESKTOP_SYNC.md)，并执行大文件上传与 Range 下载验证。
- 按所用功能逐项核对接口方法与路径，包括头像、附件、成果预览和提醒注册。模板采用明确开放的客户端路由，新增接口应同步评审代理规则。

部分区域通过当地云服务跳转接入。具体节点与连接方式由部署环境维护。

## 业务开通与验收

按[管理员指南](../manuals/ADMIN_GUIDE.md)配置成员、业务区域、模型容量池、部署、计费审核、成员与全站额度。桌面和手机聊天分别核对各自的模型目录，见[模型中间层](../services/business/MODEL_MIDDLEWARE.md)及[模型发布](../services/business/MODEL_PUBLICATION.md)。

提醒配置见[APNs 模板](mobile-push.env.example)，成果存储配置见[成果模板](result-files.env.example)。部署后在目标平台检查登录与退出、模型流式回复、同账号设备及工作区、远程审批和停止、附件上传、成果下载与预览。iOS 提醒需同时完成服务端 APNs、应用签名与系统通知授权；Android 在前台刷新任务状态。

## 升级、备份与回退

升级前记录服务、管理页面及客户端版本，备份两个数据库、持久化文件、受限配置和签名材料。先在隔离环境验证恢复及迁移，再按业务服务、配套管理页面与客户端的兼容关系发布。发布中保留在途票据与账本，未知执行按原票据核对。

回退时恢复兼容的应用版本及配置；数据库恢复应与文件和恢复代际一致。新增迁移不通过删除历史迁移文件回退；涉及新增业务数据时先归档并确认恢复范围。安装包和客户端更新通道按[客户端发布与升级](CLIENT_RELEASES.md)独立维护。

身份服务默认仅供同源代理访问。需要跨域管理页面时，将服务端 `RUOYI_CORS_ALLOWED_ORIGINS` 配置为逗号分隔的精确来源（协议、域名及端口），例如 `https://admin.example.com`；开发环境可使用 `http://localhost:5173`。请求通过 `Authorization` 和 `clientid` 头传递身份，跨域请求不携带浏览器凭证；通配来源、路径和非本机 HTTP 来源在启动时拒绝。

身份服务默认仅暴露 Actuator `health`，健康信息不返回组件详情；未配置监控用户名或口令时拒绝访问。启用监控时，在受限服务端配置中设置 `spring.boot.admin.client.username` 与 `spring.boot.admin.client.password`（环境模板的口令由 `RUOYI_PASSWORD` 提供），并将监控端点限制在运维网络；按需逐项开放端点。

升级已有环境时，在该环境配置中同步设置 `management.endpoints.web.exposure.include=health` 和 `management.endpoint.health.show-details=never`；环境准备脚本保留已有配置，修改模板不会覆盖既有文件。
