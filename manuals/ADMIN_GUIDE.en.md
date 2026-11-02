# FunnyWork administrator guide

[简体中文](ADMIN_GUIDE.md) | **English**

Use this guide to provision members, configure models and allowances, manage devices and organization policies, and inspect task execution. Management pages require both role permissions and business access. Deployment staff prepare initial deployment, the identity bridge, and credentials using the [development and deployment entry point](../README.en.md#configuration-and-deployment).

## Management entry point and responsibilities

Sign in at the management address provided by deployment staff. Maintain users and roles in **系统管理** (System Management) and business settings in **工作平台** (Business Platform). Menu and button names below retain their Chinese labels to help locate the controls.

| Page | Purpose | Requirements |
| --- | --- | --- |
| 用户管理 / 角色管理 (Users / Roles) | Members, departments, enabled state, and feature permissions | Corresponding user and role permissions in the identity platform |
| 员工业务授权 (Member business access) | Business roles and authorized zones | Business administrator identity and access-maintenance permission |
| 模型配置 (Model configuration) | Models and capacity, budgets and pricing, Agent presets, and client publication | Model read/write permissions; the designated operator separately manages global budgets and pricing |
| 客户端策略 (Client policies) | Member devices, organization policies, and application receipts | Client read/write permissions and the corresponding business scope |
| 任务管理 / 会话历史 (Tasks / Conversation history) | Tasks, execution devices, history, and results | Corresponding read permissions; content, input, and artifacts require their specific permissions |
| 执行资源 / 年报 (Execution resources / Annual reports) | Execution resource setup and structured annual business summaries | Module permissions plus project and zone access |

If a menu is missing, ask a role administrator to check feature permissions first. If a visible page rejects an operation, check business roles and zones next. The business access page can grant only roles and zones held by the current administrator.

## Provision a member

1. Open **系统管理 → 用户管理** and create or select a member. Set username, nickname, department, and roles, then save and leave the account enabled. Members synchronized from an identity directory are maintained by that source.
2. In **角色管理**, grant the member's role **工作平台 → 客户端策略 → 桌面客户端使用** (desktop client access). Assign business administration features separately by responsibility.
3. Open **工作平台 → 员工业务授权**, choose **配置授权** (Configure access), search for the account, select a permission template, business roles, and zones, then **保存** (Save). **任务操作与查看** is the general task-operation and viewing template.
4. Return to the user list and open the member's **使用状态** (Readiness). Check account enablement, desktop permission, business access, and zones.
5. Configure available models and member allowance as described below. Have the member sign in on desktop and send a short test task, then use the same phone account to verify chat and desktop tasks.

If saving times out or the list fails to refresh, inspect the existing account and readiness before retrying. **撤销** in business access stops business access; disable the account through user management.

## Configure model services

Open **工作平台 → 模型配置**. **模型与容量** (Models and capacity) manages desktop inference deployments. **客户端发布** (Client publication) maintains official model settings for the phone-chat service. Save and verify these separately.

### Models and capacity

1. Open the capacity-pool page in **模型与容量**. Set name, zone, concurrency, queue, and waiting settings, then create or save the pool. Its zone must match member and business access.
2. Open deployments, select a pool and preset, and enter name, model identifier, protocol, service address, and credential reference. Check context size, maximum output, and tool/image capabilities before creating the deployment. For changes to model, address, or capabilities, create a new deployment and disable the old one.
3. Have deployment staff configure the referenced credentials on the server. Enter only the reference name in the form; keep secrets in restricted server configuration.
4. Complete pricing and allowance configuration in **预算与计价** (Budgets and pricing). Select the model from an authorized client and send a short text request. Verify tool and image tasks separately when needed.

Pools control concurrency and waiting; member allowance controls spend. Protocol, tool, and image support follows deployment configuration. See the [model middleware reference](../services/business/MODEL_MIDDLEWARE.md) for integration fields.

### Official models for phone chat

1. Open **客户端发布** and enter model name, service address, thinking mode, maximum output, and credential reference.
2. Choose **检查连接** (Check connection) and inspect connectivity, credential readiness, and discovered models.
3. Choose **发布新版本** (Publish new version), confirm the scope, and check the new version and parameters in publication history. Verify an actual phone-chat reply.
4. To restore earlier settings, choose **以新版本恢复** (Restore as a new version) in history. The restored settings continue to read secrets through the current credential reference.

Connection checks inspect connectivity and model discovery; verify inference through a client with configured allowance. For a version conflict, refresh and inspect the latest settings before choosing an editing baseline. See [model publication](../services/business/MODEL_PUBLICATION.md) for formats and loading.

## Set member allowances and global budgets

Open **模型配置 → 预算与计价**. Enter amounts in CNY; daily budgets reset on Beijing time.

1. The designated operator selects a deployment in **模型计费** (Model pricing), configures billing mode, input/output price bounds, and provider maximum input Tokens according to the actual provider, then saves.
2. The same operator sets cumulative and daily limits in **全站预算** (Global budgets), checks **暂停全站推理** (Pause global inference), and saves.
3. In **成员额度** (Member allowance), select a member and choose **读取** (Read). Set cumulative budget, daily budget, validity, cross-device concurrency, inference calls per minute, and maximum output per call. Enable **允许使用** (Allow use) and save.
4. Send a short request through the member's client, then read settled and reserved amounts on the allowance page to verify calls and accounting.

**手工充值** (Manual credit) increases cumulative allowance. Configure the daily limit, expiry, and allowed-use state separately. For a pending credit result, use **核对充值结果** (Check credit result) to recover the original request. **推广赠送** (Promotional grants) configures tenant, mode, amount, and grant cap; it is disabled by default.

Inference reserves cost first and settles actual usage afterward. Unconfirmed upstream calls retain their reservations until operators reconcile provider results. Review prices and input bounds against the actual configuration because these determine reservations. See [model allowances and budgets](../services/business/MODEL_BUDGETS.md).

## Manage computer access and organization policies

### Member devices

1. Open **工作平台 → 客户端策略 → 成员设备** and search by device or member name and state.
2. Inspect the latest report time, client version, model state, and organization-policy version. Open **详情** (Details) to review the device. **在线** (Online) means valid runtime state was received within the last 30 seconds.
3. To block synchronization and remote control, choose **撤销访问** (Revoke access) and confirm. Queued commands expire; confirm and stop local operations already running on the computer itself.
4. Choose **恢复访问** (Restore access), have the computer reconnect, and refresh its reported state. Expired commands remain invalid.

Revoking a device retains its local data. Manage the member's other login sessions through the account system. See [member device management](../services/business/MANAGED_DEVICES.md) for permissions and state fields.

### Organization policies

1. In the policy section of the same page, set **允许跨设备访问** (Allow cross-device access), **允许远程控制** (Allow remote control), default theme, and language.
2. Preview the combined effect of the draft policy and personal settings, then choose **发布策略** (Publish policy).
3. Check versions in publication history and client receipts for fetched and applied versions. Recheck offline devices after reconnection.
4. To restore a policy, choose **恢复为新版本** (Restore as a new version), then recheck receipts.

Disabling cross-device access blocks cloud conversation reads, pairing codes, and cross-device actions; desktop archive uploads remain available. Disabling remote control rejects new commands and expires queued ones. Check operations already running on the original computer. Theme and language are defaults; personal preferences follow policy rules. See [desktop organization policies](../services/business/DESKTOP_POLICY.md).

## Inspect tasks, history, and results

Select a task in **任务管理** and inspect state, current attempt, waiting reason, and execution device. When resources are unavailable, check model capacity, allowance, device connectivity, and execution grants. Reconcile the original request when a result is pending. Inspect acceptance receipts, execution completion, uploads, and preview preparation at their respective stages.

**会话历史** provides directory searches and authorized content reading. Directory, content, and artifact access have separate permissions. Grant support roles the specific permissions and business scope they need. For result problems, preserve the original file and version, inspect upload state, size, and conversion fonts, and request a new version from the computer when needed.

## Configure execution resources and annual reports

Configure repository maintenance through the issue and repository entries under **任务管理 → 代码维护**. In **执行资源**, prepare projects, pools, members, purposes, project grants, and routes in that order. Use **终端预览** (Device preview) to inspect candidates and exclusion reasons. Development and verification use their respective purposes; **执行终端** in task details shows actual assignments. See [execution resource management](../services/business/EXECUTION_RESOURCES_R4.md) and [maintenance operations](../services/business/MAINTENANCE_AUTOMATION.md).

**年报** compiles a fixed-template business summary from structured revenue, cost, and prior-year revenue. Create a data snapshot, configure a COMPILE purpose, data access, and route within the same project, then start compilation. Inspect validation results and download final files. Create a new snapshot for revisions. See [annual reports](../services/business/ANNUAL_REPORTS_R5.md) for metric definitions and output formats.

## Upgrades and support

Deployment staff provide a version list for the business service, identity service, management frontend, and clients, plus recovery instructions. Schedule updates using [client releases and upgrades](../deploy/CLIENT_RELEASES.md), then check member login, model calls, phone connections, and representative result flows.

For troubleshooting, record time, component versions, member and task identifiers, sanitized errors, and checks already completed. If a successful write is followed by a failed refresh, read the current state before operating again. Resolve cross-member, model, or policy version conflicts by checking the latest values.

For project licensing and commercial-use conditions, see the [root licensing section](../README.en.md#license). Separately licensed portions retain their own licenses.
