---
description: "Declare accessible files as deliverables with present; configuration, Session ownership, and source-file opening."
kind: "package-reference"
---

# @deepseek-ai/dsh-tool-present

English | [中文](README.zh.md)

## Summary

Use `present` to declare final files accessible through the Session filesystem, including files created through shell commands. Users open the current source files in their default application. The tool records paths and optional descriptions without copying file contents.

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

`present` validates paths against the Session's current working directory and records absolute paths, so later directory changes do not redirect delivered files.

The `standard`, `ptc`, and `cordis` agent presets mount this plugin. Call `present` with `files: [{ path, description? }]` after creating the files. Files must be regular files accessible through the Session filesystem. Relative paths resolve against the Session working directory; absolute paths may name files outside it, including `/tmp` or Downloads. Missing files, directories, final symbolic links, and provider-denied paths fail the call. Files in a shell sandbox’s private `/tmp` must first be written somewhere the Session filesystem can access.

Mount it in an agent's Cordis composition with `tools`, `fs`, and the `turnBoundary` Session projection available:

```yaml
- name: '@deepseek-ai/dsh-tool-present'
  config:
    maxFiles: 8
```

| Field | Default | Meaning |
|---|---|---|
| `maxFiles` | `8` | Positive maximum file count per call |

The file-count limit is validated at mount. The tool requires an agent Session with a workspace and an open turn. Delivery belongs to the calling Session; a parent must call `present` itself to declare files created by a subagent.

-----

## Fixed result publishing

The FunnyWork presets also mount `@deepseek-ai/dsh-tool-present/publish`. `publish_result` snapshots an existing `.docx`, `.xlsx`, `.pptx` or `.png` into immutable attachment storage and appends an optional `result` manifest to the existing `deliverables/presented` event. `present` retains its path-only semantics. The native replica uploads referenced original and preview attachments through its existing chunk protocol. A declaration does not imply upload completion.

Publication requires `tools`, `fs`, `attachments`, `sessionProjections` and `systemPrompt`. Original files default to 32 MiB; each preview defaults to 32 MiB, combined previews to 64 MiB, result identities to 100 and stored versions to 1,000 per Session. Configure `maxBytes`, `maxPreviewBytes`, `maxPreviewTotalBytes`, `maxResults`, `maxWorkbookCells`, `workbookTimeoutMs` and `workbookHeapMiB` on this plugin, and align them with server attachment limits. The original PNG bytes are preserved. A changing source, unsupported extension, denied path or exhausted capacity rejects publication.

Read a selected fixed original with `inspect_result(resultId, version?)`; omission selects the latest version. Copy the inspected original to a new workspace file before editing, then publish with `resultId` and `expectedVersion`. A stale expectation fails rather than overwriting a newer version. The system prompt requests fixed publication for generated Office and PNG results. Model output reports the identity, version and saved-original state; it does not promise that synchronization has finished.

Preview preparation runs in a storage-scoped serial background queue and resumes pending versions on Agent restoration. `prepare_result_preview(resultId, version)` retries without changing the original. Disposal aborts and drains jobs. PNG uses a bounded thumbnail; Word and PowerPoint require `officeToPdf`; Excel emits visible-sheet sparse windows with saved formula values, number formats and merge metadata. Parsing runs in an owned Worker with a 30-second default deadline and a 512 MiB old-generation heap limit; timeout, cancellation and memory exhaustion terminate and drain the Worker before returning an error, preserving the original. If inherited host V8 flags raise the actual heap beyond the Worker budget, parsing fails before loading the workbook; remove those overriding flags to restore previews. Combined JSON output is capped at 64 MiB or the smaller configured preview budget before transferring buffers to the host. Source runs use the repository ESM launcher; packaged runs load the emitted parser module. Formulas and external links are never evaluated. Charts, drawings and conditional formatting are omitted from worksheet previews. Preview failure retains the original and completed variants.

<a id="understand-the-implementation"></a>
## Understand the implementation

<details>
<summary>Implementation internals — click to expand</summary>

The tool resolves paths through the configured filesystem provider and checks regular-file metadata without reading contents. Successful final `tools/result` notifications append `deliverables/presented`, including nested calls. A later enclosing program failure does not revoke an already completed declaration. Blocked results publish none. Each plugin instance records only calls it executed; scoped tools with the same name cannot publish through another instance.

The pure `./types` entry declares `PresentedFile` and the Session event without importing Host runtime code. The Web consumer validates persisted declarations before displaying or opening them. New declarations store absolute paths. Legacy relative declarations retain the viewed Session's original workspace as their base.

</details>

-----

<a id="further-exploration"></a>
## Further Exploration

- [Filesystem subsystem](../../../docs/subsystems/filesystem.md) — provider paths and errors.
- [Web deliverables](../../client/ui-deliverables/README.md) — source-file opening and cards.
- [Delivery decision](../../../.agents/notes/implemented/feature/2026-09-08-present-workspace-source-files.md) — Session ownership and required-on-read events.

<a id="model-experience"></a>
## Model Experience

### present

#### What the model sees

The [present schema](../../../docs/tool-catalog.md#present) asks for existing accessible files: “Declare existing files as final deliverables for the user. Use it when the user needs a separate file, especially Office documents, spreadsheets, and slide decks; prefer your final response when that suffices. The user opens the current files; their contents are not copied.” The `files` parameter asks for the 1-2 most important deliverables and at most 4 per call. Results report `Presented <path>` for each file; the program result and durable event contain paths and optional descriptions.

#### Token effect

One tool schema per mounted agent and one result line per delivered file. File bytes do not enter model messages.

#### KV Cache effect

The tool schema is static for the mount lifetime. Delivery result text extends the conversation without rewriting its prompt prefix.

## Known Limitations and Deferred Work

<a id="known-limitations-and-deferred-work"></a>

- Metadata and Host-path checks cannot atomically prevent replacement before a desktop application opens a file.
- Edits change what opens. Deleted or moved source files cannot be opened from their declarations.
- Session ZIP exports contain declarations, not file contents. Fixed versions require the separate `/publish` entry and attachment replication.

<a id="dev-note"></a>
### Dev Note

<details>
<summary>Working context for maintainers — click to expand</summary>

None.

</details>
