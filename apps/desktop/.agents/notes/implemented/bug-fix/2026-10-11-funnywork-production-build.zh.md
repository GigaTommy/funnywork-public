# Agent Note：FunnyWork 分发品牌

Status: implemented

[English](2026-10-11-funnywork-production-build.md) | 中文

## 问题

桌面打包选择了上游官方客户端配置，覆盖调用方名称并内联 DeepSeek Harness 品牌。生成的 FunnyWork 应用因此显示预览标记和上游欢迎文案。

## 决策

桌面打包选择命名的 `funnywork` 分发配置，固定 `FunnyWork` 名称及其配置标识，包含源码 commit 和包版本，排除调用方附加值及 dirty 元数据。Release 打包使用同一配置校验已构建产物记录。上游官方配置保留原有约定。

## 验证

归属测试覆盖配置隔离、FunnyWork 分发拒绝上游产物，以及打包阶段的环境传递。构建应用的冒烟检查验证 FunnyWork 身份及预览标记缺失。

## 考虑过的替代方案

**只设置调用方名称。** 上游官方配置会覆盖该值，无法修正打包产物。

**使用本地构建。** 这会失去命名分发配置及其严格产物校验。

## 影响

签名与未签名桌面包使用同一 FunnyWork 客户端身份。品牌选择发生在编译及分发校验之前，而不是安装之后。
