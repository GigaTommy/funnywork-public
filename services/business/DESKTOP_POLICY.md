# 桌面组织策略

组织策略与旧客户端应用目录分别维护。V76 新增策略版本和客户端回执，不改变既有账号、业务授权或应用目录。首次发布前保留原有行为：允许跨设备访问与远程控制，主题和语言跟随系统。

## 强制限制与默认值

`constraints.crossDeviceAccess` 控制跨设备读取云端对话、连接码和远程操作。服务端校验所有相关请求，不依赖客户端声明自己属于桌面或手机。关闭后，桌面仍可上传归档和状态；设备撤销、已执行命令的结果确认等清理操作保留。

`constraints.remoteControl` 控制远程命令提交和领取。跨设备访问关闭时，有效远程控制始终关闭。策略发布与命令提交、领取使用相同事务锁；关闭控制会使尚未领取的排队命令过期。已经领取的命令可能已开始执行，不承诺撤回或自动重放。重新启用不会恢复已过期命令。

`defaults.theme` 接受 system、light、dark；`defaults.locale` 接受 system、zh-CN、en-US。默认值只在没有个人偏好时使用，个人设置优先。服务端预览返回每个有效值及 USER / ORGANIZATION_DEFAULT 来源。未知策略字段拒绝接收，不静默忽略新的强制项。

## 管理接口

管理身份需要 `funnyworker:client:read` / `write`，并具备业务 ADMIN 身份及覆盖租户全部现有成员的区域范围。

- GET `/api/v1/desktop-policy`：当前策略、最近 100 个版本和获取过策略的最近 100 台客户端回执。
- POST 同路径：`expectedVersion` 与 `policy`；策略包含 `constraints` 和 `defaults`。首次发布保存版本 0 系统基线，再生成版本 1。
- POST `/api/v1/desktop-policy/restore`：`expectedVersion` 与 `targetVersion`；恢复历史内容为更高的新版本。
- POST `/api/v1/desktop-policy/preview`：`policy`、`preferences`、`supportedSchema`；只计算有效值，不发布、不写回执。偏好字段为 null 表示未设置个人偏好。

## 客户端协议与回执

GET `/api/account/policy?device=<32位小写十六进制设备标识>` 使用桌面读取权限，返回 schema=1、version、digest 和 policy，并记录下发版本。POST `/api/account/policy/receipt` 接收 device、version、digest、schema、clientVersion 和 preferences。

回执必须匹配同一账号、同一设备已获取且仍为当前的版本与摘要；schema 必须等于 1。未获取、旧版本、错误摘要和其他账号的回执均被拒绝。返回的 APPLIED 表示客户端报告已应用，不是硬件证明。发布新版本后，旧回执显示等待更新；没有回执的旧客户端显示未知。强制限制在服务端执行，不以客户端回执为生效条件。

桌面登录后获取策略，把默认值写入账号隔离的 `cordis.defaults.yml`，由运行时热更新加载；个人 profile 字段优先。客户端读取运行中设置的实际值和个人覆盖，匹配后才发送应用回执，后台仅接收当前已下发的版本。个人语言偏好支持最长 80 字符的语言标签，组织默认语言仍限定为系统、中英文。每分钟最多刷新一次；运行中暂时断网保留会话和最后策略，服务端强制限制继续生效。首次访问返回 404 的旧服务兼容，已知策略消失、版本倒退、相同版本摘要变化和未知 schema 均拒绝。设备清单通过模型加载回执关联同账号的同步设备，再显示对应组织策略回执；没有关联或回执时仍显示未知。

## 升级接入

升级前备份业务库并部署包含 V76 及后续迁移的业务服务。身份目录升级另按统一身份接入说明执行管理库迁移，保留已有本地账号与授权。先确认服务端可提供策略，再安装新版桌面应用；客户端签名、公证和目标服务端兼容性分别检查。

桌面模型分发还需 `GET /api/account/model/signer`、`POST /api/account/model/publication` 与 `POST /api/account/model/receipt`，按账号和设备鉴权并下发设备加密包。

反向代理需允许已认证客户端访问 `GET /api/account/policy` 与 `POST /api/account/policy/receipt`。仓库 Nginx 模板包含两个精确路径、方法限制和限流；管理策略发布接口继续使用管理访问限制。

## 验证

自动测试覆盖默认值与个人设置优先级、过期发布、基线恢复、组织范围、同账号下发关联、旧回执拒绝、未知字段拒绝、旧客户端强制限制、关闭控制后排队命令过期，以及策略事务提交前命令领取等待。对话同步和连接码既有流程一起回归。生产升级前仍需备份数据库并验证目标环境。
