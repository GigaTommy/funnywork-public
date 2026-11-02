# FunnyWork management frontend development

[简体中文](README.md) | **English**

The default FunnyWork Vue 3 management frontend is customized from plus-ui and uses business components from `packages/management-react/`. `services/business/` provides the business entry point; `services/identity/` contains the customized identity backend. Both are included in the repository.

Administrators should read the [administrator guide](../../manuals/ADMIN_GUIDE.en.md). This page covers development setup, startup, and builds.

## Business entry points

| Page | Managed content |
| --- | --- |
| 员工业务授权 (Member business access) | Account bindings, business roles, and zones |
| 模型配置 (Model configuration) | Models and capacity, budgets and pricing, Agent presets, and client publication |
| 客户端策略 (Client policies) | Member devices, organization policies, and receipts |
| 任务管理 / 会话历史 (Tasks / History) | Tasks and execution devices, issue maintenance, directories, and authorized content |
| 执行资源 / 年报 (Execution resources / Annual reports) | Projects, pools, purposes, routes, and structured business summaries |

[Initialization SQL](../../scripts/setup/ruoyi-business-menus.sql) registers menus and feature permissions. Vue pages live in `src/views/funnyworker/`; shared business components live in `packages/management-react/`. The business service checks roles, tenants, and zones in addition to menu permissions.

The Quill 2 editor removes scripts, event attributes, executable links, form controls and page-positioning styles when loading and saving HTML. It retains supported text markup, format classes and text styles, images and HTTPS video embeds from YouTube and Vimeo.

## Setup and startup

Prepare the local backend using the [root development guide](../../README.en.md#local-development). Use the pnpm version in `package.json` and the lockfile. Repository setup scripts generate local `.env.development` / `.env.production`; preserve existing configuration and keep credentials out of Git.

From the repository root:

```sh
pnpm --dir apps/management install --frozen-lockfile
pnpm --dir apps/management dev
```

Development listens on `127.0.0.1:5173` by default. For cross-device testing, explicitly use `pnpm dev --host <LAN-address>` and restrict network access. Configure identity and business proxies with `VITE_IDENTITY_PROXY` and `VITE_BUSINESS_PROXY`. Use local services and dedicated test accounts for integration.

## Build and checks

```sh
pnpm --dir apps/management build:prod
pnpm --dir apps/management typecheck
```

The first build generates auto-import and component declarations; run type checks afterward. `build:prod` itself performs the production build, while repository service-build entry points also run the corresponding checks. Production assets require the correct `/admin/` path and environment templates, verified by the [artifact checker](../../scripts/checks/check-management-artifact.py).

Component acceptance entry points: [model management](uat/model-middleware/README.en.md) and [task center](uat/task-center/README.en.md). Component checks use isolated data; release acceptance separately verifies authenticated business flows.

`VITE_*` values become public browser assets; use them only for client parameters. Legacy RSA wrapping is disabled by default and template keys are empty. Keep provider secrets, server private keys, and passwords in restricted backend configuration. Treat browser-side material as public client configuration when enabling compatibility wrapping.

## Release materials

Record management frontend, business JAR, and identity service versions separately. Replacing the business JAR does not update an independently served management frontend. Check its static target and matching APIs during release.

## Upstream and licensing

See the original [plus-ui README](UPSTREAM_README.md), [LICENSE](LICENSE), and root [third-party notices](../../THIRD_PARTY_NOTICES.md) for attribution and licensing. FunnyWork feature entry points are described above and in the administrator guide.

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.
