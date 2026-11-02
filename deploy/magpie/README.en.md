# Magpie shared model gateway

[简体中文](README.md) | **English**

The management backend calls Magpie through a separate Docker Compose project. FunnyWork desktop retains its existing model catalog, short-lived gateway credentials, authorization, budgets, and tickets. Clients do not store ChatGPT OAuth credentials or the Magpie Gateway Key.

## Deployment

This Compose file pins the image digest for Magpie v0.1.1290, runs a non-root process, and exposes gateway port 3425 and management port 3430 only on host loopback. Runtime data uses a separate named volume. Container removal retains authorization data; use updates that preserve the volume rather than routine `down -v`.

Put `compose.yml` and a `magpie.env` created from `magpie.env.example` in a separate server directory. Generate a random management key, set configuration permissions to 0600, then run:

```sh
docker compose -p funnyworker-magpie up -d
docker compose -p funnyworker-magpie exec magpie magpie gateway-key add "FunnyWork backend"
docker compose -p funnyworker-magpie exec magpie magpie accounts add codex
```

Store the gateway key only in a restricted backend environment file. This version rejects the example fixed key `magpie` for callers outside the container. Access management through an SSH tunnel, keeping its port private.

Authorize the same ChatGPT account separately on the VPS. Importing a daily desktop session refreshes its token upstream and requires the original tool to sign in again; keep the desktop session separate. A browser callback's localhost is the browser's computer. For remote login, securely transfer the complete callback or configure restricted callback forwarding. The callback contains short-lived credentials; exclude it from logs, tickets, and Git.

## FunnyWork integration

When the business backend and gateway share the host network namespace, configure:

```dotenv
# Merge with existing origins to retain current providers
FUNNYWORK_MODEL_INFERENCE_ORIGINS="https://api.deepseek.com,http://127.0.0.1:3425"
FUNNYWORK_MODEL_MAGPIE_KEY=<DEDICATED_GATEWAY_KEY>
```

Back up before environment changes and confirm no RUNNING/STOPPING model calls remain. Recreate only the business service and retain UNKNOWN tickets and budgets. If the backend uses bridge networking, configure a reachable private address instead of host loopback.

Use management APIs to create a shared pool and a disabled CHAT_COMPLETIONS deployment with baseUrl `http://127.0.0.1:3425/v1` and credential reference `FUNNYWORK_MODEL_MAGPIE_KEY`. Select model identifiers from the actual authorized catalog and verify context/output limits. Verify usage, streaming, tool calls, and tool-result replay before declaring capabilities and enabling the deployment. Operator pricing review and member allowances remain required. Reconcile subscription usage against upstream account policies, maintain actual provider prices, and continue enforcing FunnyWork budgets.

Mac/Windows clients read the authorized catalog through the existing HTTPS FunnyWork entry point and share upstream access within account allowances. Keys stay server-side. Phone catalogs have a separate implementation; configure phone support separately when adding providers.

## Verification and rollback

Check container health, 401 without a key, authorized catalogs, authorization persistence after restart, and real text, SSE usage, tool replay, and queueing through FunnyWork tickets. Use target-platform installers to check login, catalogs, complete tool loops, and result display. After an HTTP disconnect, reconcile the original ticket and upstream execution; confirm stopping before releasing capacity.

For rollback, disable new deployments, inspect in-flight requests, restore backend environment, and recreate only business. Stop the separate Magpie project while retaining its named volume and restricted backups. Preserve UNKNOWN records, budgets, and existing maintenance policies during recovery.

References: [upstream deployment and gateway guide](https://github.com/yetone/magpie/blob/main/docs/reference.md), [account authentication](https://github.com/yetone/magpie/blob/main/docs/subsystems/providers-accounts.md), and [FunnyWork model middleware](../../services/business/MODEL_MIDDLEWARE.md).

## Source and licensing

Upstream: [yetone/magpie](https://github.com/yetone/magpie). The current Compose template pins the image for v0.1.1290. Copyright (c) 2026 yetone; the complete [MIT license text](MAGPIE-LICENSE.txt) accompanies these deployment materials. This directory contains deployment configuration; Magpie runs as a separate service.

When delivering source, modifications, binaries, or image copies to customers, retain upstream copyright, the full MIT text, and applicable third-party notices. FunnyWork's commercial license does not override or restrict rights granted under Magpie's original license. See [third-party notices](../../THIRD_PARTY_NOTICES.md).

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.
