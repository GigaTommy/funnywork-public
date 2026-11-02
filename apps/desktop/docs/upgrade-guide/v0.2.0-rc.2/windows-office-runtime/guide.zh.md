---
kind: upgrade-guide
description: Windows 桌面将账号独立的文件编写运行时安装到更短的目录。
---

# Windows 桌面文件编写运行时目录

[English](guide.md) | 中文

## 变更

Windows 桌面现在将文件编写运行时安装到 `DSH_HOME/rt`，替代 `DSH_HOME/dsh-runtimes/dsh-primary-runtime`。这避免账号目录使库文件路径超过 Windows 传统路径长度限制时出现 Python 导入失败。macOS 和 Linux 保留原目录。账号主目录、会话和沙箱权限保持不变。

## 迁移

1. 重启桌面并调用 `load_workspace_dependencies`，将内置运行时安装到新目录。
2. 脚本使用该工具返回的路径；更新任何写死的运行时路径。旧运行时额外安装的包不会自动复制。
3. 确认 Python 可以导入 `openpyxl` 并保存工作簿。旧运行时目录保留，可手动恢复其中的数据。
