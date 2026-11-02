---
kind: upgrade-guide
description: FunnyWorker Desktop obtains model configuration after account login instead of loading the local prototype publication.
---
# Authenticated FunnyWorker model configuration

English | [中文](guide.zh.md)

## Change

FunnyWorker Desktop obtains device-encrypted, signed model configuration from the business service after account login. It no longer reads `.local/prototype/client` when launched from source. The installation defaults to `https://funnywork.funnypixelsapp.com`; an existing `funnyworker-backend.json` still selects another service. Per-account data paths are unchanged. Each account pins its server signer on first authenticated HTTPS acquisition; a changed signer fails verification.

## Migration

1. Configure the business service's `funnywork.desktop-model-file` with a private JSON file containing the tenant, Ed25519 signing keys and provider configuration. See the parent repository's `deploy/desktop-model.example.json`. Keep private keys and API credentials outside source and installer resources.
2. Grant users `funnyworker:desktop:read` and a business identity binding for that tenant. Sign in to the updated Desktop; its main process creates a private device key and fetches a 24-hour publication.
3. Confirm authenticated `/api/account/model/publication` succeeds. Desktop renews hourly, retries transient failures every minute and stops its Host at expiry or session revocation. Changed provider settings stop the Host and relaunch the app. Signer rotation requires trusted reprovisioning of the account's public-key pin.
