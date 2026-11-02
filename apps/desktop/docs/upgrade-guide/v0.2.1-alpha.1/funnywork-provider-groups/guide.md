---
kind: upgrade-guide
description: FunnyWork enterprise model selections use maker routes and deployment model IDs instead of one provider route per deployment.
---

# FunnyWork provider groups

English | [中文](guide.zh.md)

## Change

When the management catalog supplies `provider_id` and `provider_name`, enterprise models share a maker route such as `enterprise-openai`. The selected model ID is its deployment ID; the adapter sends the original upstream model ID and binds admission to that deployment. Existing selections using `enterprise-<deploymentId>` must be selected again. Historical messages are preserved. Older catalogs without provider metadata retain their original routes.

## Migration

1. Deploy the management service that publishes maker metadata, then update the desktop client.
2. In existing conversations and saved model defaults, select the model again under its maker group. Do not rewrite Session logs.
3. Confirm the model picker shows one OpenAI group containing its authorized GPT models and a separate DeepSeek group. Send a request and confirm the usage record references the selected deployment.
