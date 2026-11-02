---
description: "Operate web pages and desktop accessibility controls with the existing FunnyWork text model."
kind: "package-reference"
---

# @deepseek-ai/dsh-funnywork-desktop-controls

English | [中文](README.zh.md)

This FunnyWork-specific package uses the [source-available non-commercial license](LICENSE); commercial use requires written authorization. Its upstream provider dependencies retain their own licenses.

## Summary

This provider plugin is selected by the [FunnyWork Desktop bundle](../../bundle/funnywork-desktop-capabilities/README.md) to operate web pages and desktop accessibility controls with text models that support tool calls. Browser controls use a separate visible browser window and text snapshots. Computer controls use accessibility information without screenshots. Ordinary Web and headless profiles do not select this plugin.

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

The Desktop profile selects the bundle, whose patch loads this plugin from a declared production dependency. The bundle itself contains no running provider code. Its `funnywork-desktop-capabilities` entry accepts `browser.enabled` and `computer.enabled` to disable either capability. Existing customized profile bundle lists are preserved. Desktop recovery disables this bundle; later launches preserve that choice.

Browser controls require an installed Chrome, Edge, Chromium, or Brave executable. Set `browser.executablePath` to select an exact executable or add complete candidates to `browser.searchPaths`. An unavailable explicit selection does not choose another browser. `browser.headless` hides the controlled window and `browser.toolCallTimeoutMs` bounds each call. No npm command or browser download runs during startup. When Chromium is missing, `browser_use_status` explains the prerequisite and ordinary chat remains usable.

The user can sign in manually in the controlled browser. Each live Session has an isolated context, separate from sidebar tabs and personal browser profiles. Session unload and Host shutdown close the owned process and discard that browser state. Cancellation reaches pending calls but does not reverse delivered actions.

-----

<a id="understand-the-implementation"></a>
## Understand the implementation

<details>
<summary>Implementation internals — click to expand</summary>

The [Desktop bundle patch](../../bundle/funnywork-desktop-capabilities/cordis.patch.yml) installs the browser and computer registries, then this [plugin](src/index.ts) selects installed providers. Browser operations use the pinned [Playwright MCP provider](../../experimental/browser-use-playwright-mcp/README.md) with full accessibility snapshots and omitted image responses. macOS uses the [native CUA helper](../../experimental/cua-macos/README.md); Windows uses [Cua Driver](../../experimental/computer-use-cua-driver-native/README.md). The computer providers are never mounted together. Production dependencies carry the providers into the packaged runtime.

</details>

-----

<a id="further-exploration"></a>
## Further Exploration

See [profiles](../../boot/app-boot/README.md) for bundle ordering and [browser ownership](../../experimental/browser-use-runtime/README.md) for Session cleanup.

-----

<a id="model-experience"></a>
## Model Experience

### Browser status and instructions

#### What the model sees

This plugin exposes `browser_use_status` and platform-specific computer status, while selected providers own action tools. Its browser instructions explain the separate controlled window, text snapshots, manual sign-in, and cleanup. A missing browser contributes the text below.

##### Unavailable browser instructions

```markdown
FunnyWork browser controls are unavailable because no configured Chromium executable was found. Use browser_use_status to report setup requirements; do not download a browser runtime automatically.
```

#### Token effect

The status tool and browser instructions add definitions and prompt text. `browser_use_status` adds a small JSON result; provider actions append text snapshots and results through the normal Session log. Image responses are omitted.

#### KV Cache effect

Stable tool definitions and browser instructions retain their request prefix. Each call appends its arguments and text result; a profile reload can change the available providers and instructions.

## Known Limitations and Deferred Work

<a id="known-limitations-and-deferred-work"></a>

- The model deployment must support tool calls. Enabling this plugin never changes model capabilities or organization policy.
- Browser state is not restored from conversation history.
- Computer controls support macOS and Windows; other platforms receive an unsupported status. Browser and computer providers report missing local prerequisites independently.

<a id="dev-note"></a>
### Dev Note

<details>
<summary>Working context for maintainers — click to expand</summary>

None.

</details>
