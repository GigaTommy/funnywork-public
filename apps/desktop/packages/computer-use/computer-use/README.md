---
description: "Computer-use provider registration for deployments that enable one desktop driver at a time."
kind: "package-reference"
---

# @deepseek-ai/dsh-computer-use

English | [中文](README.zh.md)

## Summary

Enable one computer-use provider at a time; loading another fails with the registered provider name. Providers supply their own tools and operations. The registry adds no model-visible tools or Session coordination.

Providers can opt into whole-turn locking across Sessions and independent Host processes. Ownership lasts through native cleanup; cleanup failure blocks further control until restart.

## Table of Contents

- [Use this package](#use-this-package)
- [Understand the implementation](#understand-the-implementation)
- [Further Exploration](#further-exploration)
- [Model Experience](#model-experience)
- [Known Limitations and Deferred Work](#known-limitations-and-deferred-work)
- [Dev Note](#dev-note)

-----

<a id="use-this-package"></a>
## Use this package

Mount the service once beside the chosen provider in a Cordis composition:

```yaml
- name: '@deepseek-ai/dsh-computer-use'
```

The service has no configuration. Provider plugins inject `computerUse` and call `ctx.computerUse.register(ComputerUseProviderName(name))`; the brand is exported from `@deepseek-ai/dsh-computer-use/brand`. The returned effect disposer releases that registration.

Providers stop admitting tool calls, close their resources, and await owned work before releasing the registration. `ctx.computerUse.providerName` reports the registered name until release.

Providers can separately use the `./turn-lock` export to reserve a provider instance for a complete Agent turn. It serializes that owner's calls and rejects another Session with `COMPUTER_BUSY`; cancellation, turn completion, and Session or Agent disposal retain ownership until admitted native calls and provider cleanup settle. Cleanup failure blocks further work until restart. Calls outside a known turn reserve only their invocation. Passing process-lease options also coordinates independent Host processes through the existing atomic-write lock. The lease is held through native settlement and cleanup; cleanup failure retains it until process exit. Applications outside this protocol remain independent.

-----

<a id="understand-the-implementation"></a>
## Understand the implementation

<details>
<summary>Implementation internals — click to expand</summary>

One private name owns the slot. Cordis effects remove contributions when their plugin unloads; a repeated disposer cannot remove a later registration. The [source](src/index.ts) contains no driver object, operation interface, or provider selector.

</details>

-----

<a id="further-exploration"></a>
## Further Exploration

- [Computer use](../../../docs/subsystems/computer-use.md) — provider selection and shared-desktop limits.
- [Cua Driver MCP provider](../../experimental/computer-use-cua-driver-mcp/README.md) — use an installed driver.
- [Cua Driver native provider](../../experimental/computer-use-cua-driver-native/README.md) — use the npm runtime.

-----

<a id="model-experience"></a>
## Model Experience

None, as this registry only records provider names.

#### KV Cache effect

Registration does not alter model requests. Provider-owned tools and guidance determine their own request-prefix effects.

## Known Limitations and Deferred Work

<a id="known-limitations-and-deferred-work"></a>

The service limits registrations within its Cordis service instance.

- **Shared desktop** — concurrent Sessions and separate DSH processes can operate the same desktop; callers coordinate whole computer-use workflows.
- **Provider selection** — configuration selects the provider; the model cannot switch registered drivers at runtime.

<a id="dev-note"></a>
### Dev Note

<details>
<summary>Working context for maintainers — click to expand</summary>

None.

</details>
