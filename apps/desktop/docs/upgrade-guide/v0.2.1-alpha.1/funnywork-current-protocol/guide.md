---
kind: upgrade-guide
description: Funnywork desktop requires principal-based accounts, current policy and metered model APIs.
---
# Funnywork current service protocol

English | [中文](guide.zh.md)

## Change

Funnywork desktop requires a nonempty `principalId` in account sessions, current organization policy and enabled metered inference. Missing endpoints or documents fail explicitly instead of selecting an older protocol. Client-side shared model keys, signed key publication and direct-provider renewal are removed; the server owns model credentials, budgets and admission. New account directories use the principal identity; existing recorded directory mappings remain data locators and are not deleted.

## Migration

1. Update `services/business/` and the desktop application together from the current funnyworker repository revision.
2. Keep `.local/` and account data intact. Do not deploy older service APIs alongside the new client. If an unmapped local workspace exists for the account name, sign in and confirm ownership in the restore dialog. The client rechecks the authenticated identity before recording the directory mapping; cancelling leaves the files and mapping untouched. Files stay in their existing directory, and subsequent sign-ins reuse the recorded mapping.
3. Verify login, organization policy receipts, model allowances and a metered model response. Unsupported responses must fail rather than silently disable a feature.
