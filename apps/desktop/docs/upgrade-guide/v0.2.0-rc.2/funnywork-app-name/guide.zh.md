---
kind: upgrade-guide
description: 桌面应用包名称从 FunnyWorker 改为 FunnyWork。
---
# FunnyWork 应用名称

[English](guide.md) | 中文

## 变更

桌面应用在登录页、菜单、窗口标题和安装程序品牌中显示 FunnyWork。macOS 应用包从 `FunnyWorker.app` 改为 `FunnyWork.app`，Windows 可执行文件改为 `FunnyWork.exe`。应用标识 `com.funnyworker.desktop`、URL 协议、已保存账号凭证及账号工作区路径保持不变。

## 迁移

1. 退出 FunnyWorker 并安装 FunnyWork。在 macOS 中替换旧应用，避免 Applications 中保留两个应用包。
2. 将明确引用 `FunnyWorker.app` 或 `FunnyWorker.exe` 的快捷方式和脚本更新为新名称。现有 `DSH_CLIENT_TITLE=FunnyWorker` 源码启动方式继续兼容；新启动使用 `FunnyWork`。
3. 打开 FunnyWork，确认应用菜单显示新名称，原账号工作区仍然可用。macOS 可能要求为新签名构建重新授权钥匙串访问。
