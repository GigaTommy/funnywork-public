---
kind: upgrade-guide
description: FunnyWorker 桌面端在账号登录后获取模型配置，不再读取本地原型配置包。
---
# FunnyWorker 模型配置认证分发

[English](guide.md) | 中文

## 变更

FunnyWorker 桌面端在账号登录后，从业务服务获取经过设备加密和签名的模型配置。源码启动不再读取 `.local/prototype/client`。安装版默认连接 `https://funnywork.funnypixelsapp.com`；已有的 `funnyworker-backend.json` 仍可指定其他服务。账号数据路径保持不变。每个账号首次通过已认证的 HTTPS 连接获取并固定服务端签名公钥；公钥变更会导致校验失败。

## 迁移

1. 通过业务服务的 `funnywork.desktop-model-file` 指定私有 JSON 文件，包含租户、Ed25519 签名密钥和模型调用配置。字段示例见外层仓库的 `deploy/desktop-model.example.json`。私钥及 API 凭证不得进入源码或安装包资源。
2. 为用户配置 `funnyworker:desktop:read` 权限及对应租户的业务身份绑定。登录更新后的桌面端，主进程会生成私有设备密钥并获取有效期为 24 小时的配置包。
3. 确认已认证的 `/api/account/model/publication` 请求成功。桌面端每小时续期，临时失败后每分钟重试；到期或账号权限撤销时停止运行。模型设置变更时先停止运行，再重新启动应用。签名密钥轮换需要通过可信渠道重新配置账号的公钥固定文件。
