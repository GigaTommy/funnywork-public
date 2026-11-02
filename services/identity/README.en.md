# FunnyWork identity service development

[简体中文](README.md) | **English**

This directory contains the identity and management backend customized from RuoYi-Vue-Plus. It maintains accounts, departments, roles, menu permissions, and sessions, and provides identity verification to the FunnyWork business service. The management frontend is in `apps/management/`; the business entry point is in `services/business/`.

See the [administrator guide](../../manuals/ADMIN_GUIDE.en.md) for operations and the [root development guide](../../README.en.md#local-development) for setup and complete startup.

## Features and implementation

| Feature | Implementation and reference |
| --- | --- |
| Local accounts, departments, and roles | User and role management under System Management, reusing RuoYi accounts and permissions |
| Desktop and phone identity bridge | [FunnyWorkerIdentityController](ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/FunnyWorkerIdentityController.java); authentication results include tenant, account, department, nickname, and permissions |
| Member readiness | [FunnyWorkerUsageController](ruoyi-modules/ruoyi-system/src/main/java/org/dromara/system/controller/system/FunnyWorkerUsageController.java), used by the management frontend to check access requirements |
| Enterprise authentication and synchronization | [Identity directory integration](IDENTITY_DIRECTORY.md); local and external users share permission rules while maintaining separate password and source ownership |

The customized `/funnyworker/identity` endpoint returns `nickname`, using an empty string when absent. The business service passes it through `/api/account/session` for desktop greetings and account controls. `username` remains the login identifier.

## Setup and startup

Prepare JDK 17, repository-managed Maven 3.9.9, PostgreSQL, and Redis. From the repository root:

```sh
.venv/bin/python scripts/prepare-ruoyi.py
sh scripts/start-ruoyi.command
```

Create the Python environment and prepare database tools using the root README first. Setup preserves existing accounts and data and writes local settings under `.local/ruoyi/`. The random administrator password is stored in `.local/ruoyi/admin-login.json`. The identity service defaults to `127.0.0.1:8767`, accessed through the business entry point's `/ruoyi/` proxy. On Windows, use the root `scripts/start.ps1 -Build` to start the matching services.

Production deployments use separate restricted configuration, with databases and Redis on a private network. See [self-hosted deployment](../../deploy/SERVER_DEPLOYMENT.md) for service connectivity, HTTPS, backup, and rollback.

## Identity directories and upgrades

Without an enterprise directory, create local users in management and assign roles and business access. Set `FUNNYWORK_IDENTITY_DIRECTORY_FILE` for external directory configuration and reference credentials through environment variables. Before the first sync, prepare tenant, department mappings, migrations, and directory permissions. See [identity directory integration](IDENTITY_DIRECTORY.md) for protocols, migration order, synchronization rules, and audit.

## Build and checks

The root `scripts/build-server.command` or `scripts/build-server.ps1` builds the matching services. To run only targeted directory tests, execute from the repository root:

```sh
.local/tools/apache-maven-3.9.9/bin/mvn -f services/identity/pom.xml -pl ruoyi-admin -am \
  -Dtest=DirectorySyncTest,HttpDirectoryAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Use isolated test accounts and databases to verify local login, directory sync, disabled accounts, permissions, and business access. Back up before database upgrades, apply migrations in the directory guide's order, then deploy matching services and management frontend.

For credential setup and validation of standalone Docker examples, see the [Docker guide](script/docker/README.en.md).

## Upstream and licensing

The previous RuoYi introduction is preserved in full in the [upstream reference](UPSTREAM_README.md). See [LICENSE](LICENSE) for this component's copyright and independent MIT license. See the [root licensing section](../../README.en.md#license) and [third-party notices](../../THIRD_PARTY_NOTICES.md) for FunnyWork's overall scope and commercial-use conditions.

When enabling SnailJob, set the server environment variable `FUNNYWORK_SNAIL_JOB_TOKEN` to the dedicated token used by the scheduler service.

The identity service defaults to same-origin proxy access. For a separate browser origin, set the server variable `RUOYI_CORS_ALLOWED_ORIGINS` to comma-separated exact origins (scheme, host and port), such as `https://admin.example.com`; loopback HTTP such as `http://localhost:5173` is supported for development. Send identity through the `Authorization` and `clientid` headers without cross-origin browser credentials. Wildcards, paths and non-loopback HTTP origins fail startup validation.

The identity service exposes only Actuator `health` by default and omits component details. Requests are denied when the monitoring username or password is missing. To enable monitoring, set `spring.boot.admin.client.username` and `spring.boot.admin.client.password` in restricted server configuration (the environment template reads the password from `RUOYI_PASSWORD`), restrict the endpoints to the operations network, and expose additional endpoints individually as needed.

When upgrading an existing environment, also set `management.endpoints.web.exposure.include=health` and `management.endpoint.health.show-details=never` in its configuration. Setup preserves existing configuration files; editing a template does not overwrite them.
