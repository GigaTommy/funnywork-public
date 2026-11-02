# FunnyWork business service development reference

[简体中文](README.md) | **English**

`services/business/` provides account entry points, model middleware, desktop synchronization, task scheduling, and management APIs. It uses a separate business database and connects to the identity service. See the [root development guide](../../README.en.md#local-development) for setup and startup and the [administrator guide](../../manuals/ADMIN_GUIDE.en.md) for operations.

## Find a reference

| Topic | Reference |
| --- | --- |
| Accounts and unified identity | [Identity directory integration](../identity/IDENTITY_DIRECTORY.md) |
| Model protocols, pools, and admission | [Model middleware](MODEL_MIDDLEWARE.md) |
| Spending, reservations, pricing review, and failures | [Model allowances and budgets](MODEL_BUDGETS.md) |
| Model catalog publication and client loading | [Model publication](MODEL_PUBLICATION.md) |
| Desktop/phone records, remote commands, and recovery | [Desktop synchronization](DESKTOP_SYNC.md) |
| Fixed-version results, downloads, and previews | [Result files](RESULT_FILES.md) |
| Device access, connections, and receipts | [Device management](MANAGED_DEVICES.md) |
| Organization restrictions, defaults, and publication | [Desktop policies](DESKTOP_POLICY.md) |

## Business modules

| Module | Implementation and protocols |
| --- | --- |
| Feedback and maintenance | [Configuration](MAINTENANCE.md), [intake](MAINTENANCE_INTAKE.md), [client protocol](MAINTENANCE_PROTOCOL.md), [controlled delivery](MAINTENANCE_DELIVERY.md) |
| Execution resources | [Resources and authorization](EXECUTION_RESOURCES.md), [management module](EXECUTION_RESOURCES_R4.md) |
| Annual reports | [Annual business summary workflow](ANNUAL_REPORTS_R5.md) |

## Checks and delivery

See [self-hosted deployment](../../deploy/SERVER_DEPLOYMENT.md) for service configuration, identity connectivity, reverse proxying, and database upgrades.

Model integration checks are described in the [regression plan](MODEL_UAT_PLAN.md) and [manual acceptance scenarios](MODEL_UAT_MANUAL.md). See [automated maintenance](MAINTENANCE_AUTOMATION.md) for operation, configuration, and recovery. Choose checks for the affected components using [root testing and verification](../../README.en.md#testing-and-verification).

Record business service, identity service, management frontend, and client versions separately. Verify login, models, synchronization, and result flows using [client releases and upgrades](../../deploy/CLIENT_RELEASES.md).

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.
