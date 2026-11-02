# Codex sync-check deployment and qualification

English | [中文](CODEX_SYNC_OPERATIONS.zh.md)

## Current delivery scope

This slice requires the updated FunnyWork desktop sync consumer, backend and separate Codex Profile Bundle. The default Desktop core package closure is generated recursively from `@deepseek-ai/dsh` and `@deepseek-ai/dsh-desktop-host`; it currently excludes `@deepseek-ai/dsh-subagent-codex`. Source resolution and a successful Host build do not establish that an installer or user Profile contains the plugin.

Older desktop packages need an upgrade. Missing actual `version`, `dshBuildCommit`, `dshBuildTree` or clean identity evidence prevents a successful sync check. `CHECKED` records a completed version and initialization-capability observation, not full feature qualification. This document does not authorize production device, account Profile or organization-policy changes.

## Explicit per-account installation and opt-in

1. Use a verified release Bundle compatible with the updated desktop runtime. Development workspace symlinks are not release artifacts. Start the desktop to initialize its Profile, then quit it completely.
2. Confirm the target account's `DSH_HOME`. FunnyWork uses an account-isolated directory after login; target its `profiles/desktop`, not a same-named Profile in an arbitrary default home. Do not print account tokens or place credentials in installation commands.
3. Use the `dsh` belonging to the target installation: `dsh plugin --profile desktop add /absolute/path/to/verified-subagent-codex.tgz`. The existing plugin manager handles dependencies and Bundle activation. Check dependency versions and `dsh.profile.bundles` in the Profile's `package.json`; do not bypass compatibility checks with `allow-version`.
4. Merge this id-targeted configuration into the target Profile's `cordis.patch.yml`, preserving existing entries:

```yaml
- id: subagent-codex
  config:
    maintenanceEnabled: true
```

5. Restart that account's FunnyWork Host. Installing the Bundle only registers a dormant provider by default; `maintenanceEnabled` defaults to false. This configuration registers the `codexMaintenance` RPC. Do not enable `tool-subagent-codex` or change the default model; neither is needed for read-only checks.

This configuration affects only the selected Profile's Host. The organization-policy API does not automatically install or enable this Bundle. The backend still requires existing device registration, a maintenance node, project/resource authorization and protocol 1 negotiation. Local configuration cannot grant backend permissions.

## Release package closure checks

Inspect the actual installation directory separately on each platform, not the source directory:

- The Bundle must contain `lib/index.js`, `cordis.patch.yml`, `lib/typert.host.js` and type declarations. Its actual loading path must belong to the target Profile or its controlled installation closure.
- `@openai/codex` must match the plugin's pinned `0.153.4`, with readable `bin/codex.js`. The plugin resolves the package-local fixed `app-server --stdio` invocation; it does not rely on a same-named CLI on PATH.
- The official wrapper's optional dependencies must include the actual target-platform payload, such as `@openai/codex-darwin-arm64`, `@openai/codex-darwin-x64`, `@openai/codex-win32-x64` or `@openai/codex-linux-x64`. The npm alias pins the corresponding `0.153.4-<platform>-<arch>`; the wrapper alone is not a complete installation.
- Retain release tarball digests, Profile lockfile integrity, installed resolution paths, platform/architecture and package manifest identity. Do not collect full environment variables, raw stderr or credentials.
- macOS signing/notarization, Windows subprocess-range termination and Linux release-package qualification require the corresponding actual artifacts. A Linux development probe does not replace other-platform evidence.

## Safety qualification and rollback

Use an isolated test home and Profile, install built tarballs and load the real Host. Do not conceal missing packages with a development workspace fallback. Observe a real version and `processStopped:true` from `codexMaintenance.capabilities`; then register an empty private workspace, invoke the fixed start, read ordered started/capability/terminal events and terminal state, and verify cancel/reconcile. The check does not invoke a model turn.

Backend qualification requires three authorized devices to report their own installed package identities; copying one device's result is invalid. Lease revocation or disconnection must actually cancel execution. Unconfirmed exit remains `UNKNOWN` and quarantined; do not fabricate stop receipts to unlock it. Retain structured event digests, task specHash, runtimeEpoch and stop evidence, avoiding raw process output.

To disable, first stop tasks and confirm exit, then set `maintenanceEnabled` to false and restart. To remove, use `dsh plugin --profile desktop remove @deepseek-ai/dsh-subagent-codex` on the target Profile. If UNKNOWN remains, retain audit records and use controlled recovery; do not delete the journal or change epochs to assume the process is dead.

See the [server protocol](../../../../services/business/MAINTENANCE_CODEX_SYNC.md) for authorization, short leases and receipt fields.
