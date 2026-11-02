# Organization-task component acceptance

[简体中文](README.md) | **English**

Uses production `ManagementTasks.vue` with fictional API data. Identity services and production databases are not connected. Verify real authorization, the full management shell, migrations, production releases, and cross-device task aggregation in their respective integration environments.

From the repository root, using existing management dependencies:

```sh
~/.codex/bin/with-external-storage --project "$PWD/apps/management" -- corepack pnpm exec vite --config uat/task-center/vite.config.mjs
```

Open http://127.0.0.1:19194/ . Select an owner and query; compare list and summary. Check light/dark appearance, details, empty data, 503, 403, and retry after recovery. Error modes apply to the next request; slow-request mode checks busy states. Close temporary windows and the service afterward.

`ManagementTasksIntegrationTest` checks real server queries, identity-permission mapping, pagination, tenant/zone isolation, and content projection. Record fixture 403 responses separately from real access-revocation results. Archive local implementation and acceptance records according to [contribution guidance](../../../../CONTRIBUTING.md#配置与数据).

## Second acceptance batch

Covers department/project filters, business attribution editing, execution devices, management scope settings, single-task pause/resume/cancel previews, and recovery after lost responses. Entry permissions are fictional test permissions rather than production administrator or identity-provider configuration.

The lost-command-response mode injects 503 only on the first simulated POST response, then allows receipt lookup to check page reconciliation. Check actual network disconnection and physical execution stopping separately. Reloading resets simulated backend state. Pending-command records are isolated by site, tenant, and account; pending-task-command tests cover storage recovery and original-request preservation, while ManagementTasksIntegrationTest covers database idempotence.

Backend isolated tests check large pagination, cross-scope refusal, and reconciliation of cancelled UNKNOWN external actions. Verify the complete management shell, production PostgreSQL, and real devices separately from fixture permission modes.

## Subsequent acceptance batches

Covers local member-department configuration, immutable creation attribution, independent input/file authorization, fixed-set batch confirmation and original receipts, and acceptance/delivery metadata. URL `tc_*` parameters restore queries and details independently of personal-task taskId/state parameters.

Batch previews exclude inapplicable states. Lost-response mode also covers batch 503 simulations and clears pending records after reconciliation. Browser refresh resets simulated server records; use ManagementTasksIntegrationTest and separate PostgreSQL tests for persistence, transaction rollback, and concurrent idempotence. Inputs use ordinary text interpolation; the directory executes or embeds no file previews. Real HTTP tests verify raw bytes and association boundaries.

Acceptance/delivery panels show output, acceptance, and delivery separately and retain permission prompts for unauthorized content. They follow the original purpose protocol without a separate success override. Verify complete-shell permissions, cross-workbench round trips, and actual revocation in the corresponding isolated services.

Run complete management-shell acceptance against separate identity/business services and preserve local records. This directory remains the component-fixture entry point.
