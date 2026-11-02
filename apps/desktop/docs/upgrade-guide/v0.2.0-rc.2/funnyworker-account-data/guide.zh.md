---
kind: upgrade-guide
description: FunnyWorker 桌面账号使用独立的本地 Harness 目录，替代原共享桌面数据目录。
---
# FunnyWorker 账号数据

[English](guide.md) | 中文

## 变更

FunnyWorker 将各账号的 Harness 数据存入 `DSH_HOME/accounts/<hash>`，哈希由服务地址、租户及用户名确定。账号登录信息与加密模型发布仍存放在安装主目录。用户名更名后打开新的账号目录。这一机制隔离应用内切换账号的数据，不隔离同一操作系统用户运行的其他进程对文件的访问。

## 迁移

1. 退出 FunnyWorker，通过父项目的 `scripts/start-desktop.command` 启动。
2. 登录工作账号，确认工作区没有显示其他账号的会话。
3. 保留原共享主目录及 `profiles/desktop` 数据。共享会话不自动归属任何账号；明确数据归属后再迁移。
4. 保存的会话收到 HTTP 401 或 403 时清除，并要求重新登录。运行期间撤销授权会在下一次会话检查时停止 Host 并退出应用。
