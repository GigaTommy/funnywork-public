---
kind: upgrade-guide
description: 桌面安装包文件名由 deepseek-harness 改为 funnywork 品牌。
---
# funnywork 安装包文件名

[English](guide.md) | 中文

## 变更

新桌面安装包使用 `funnywork-<version>-<os>-<arch>.<ext>`。此前使用 `deepseek-harness-<version>-<os>-<arch>.<ext>`。未签名包仍保留 `-unsigned` 后缀。

这会影响自行拼接安装包文件名的脚本。构建编号和安装更新验收继续接受已有旧名称产物。应用标识、更新缓存目录和用户数据路径保留原值。

## 迁移

1. 新生成 DMG、ZIP 和 EXE 的外部文件名匹配规则由 `deepseek-harness-` 改为 `funnywork-`。保留已发布旧文件供下载。
2. 使用当前打包脚本生成更新源和上传计划，它们会引用新的版本化文件名。
3. 发布前核对更新源文件名、产物大小和 SHA-512 与生成包一致。已有客户端从更新源取得文件名。
