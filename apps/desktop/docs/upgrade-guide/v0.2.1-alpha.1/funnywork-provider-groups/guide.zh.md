---
kind: upgrade-guide
description: FunnyWork 企业模型选择使用厂商路由与部署模型标识，替代每个部署独占供应商路由。
---

# FunnyWork 供应商分组

[English](guide.md) | 中文

## 变更

管理目录提供 `provider_id` 和 `provider_name` 时，企业模型共用厂商路由，例如 `enterprise-openai`。选择器中的模型标识是部署 ID；适配器向上游发送原模型标识，并将许可绑定到该部署。原有 `enterprise-<deploymentId>` 选择需要重新选择模型。历史消息保留。不提供厂商信息的旧目录继续沿用原路由。

## 迁移

1. 部署提供厂商元数据的管理服务，再更新桌面客户端。
2. 在已有对话和已保存的默认模型中，从厂商分组重新选择模型。不要改写会话日志。
3. 确认选择器以一个 OpenAI 分组展示其授权 GPT 模型，并另设 DeepSeek 分组。发送请求，确认用量记录关联所选部署。
