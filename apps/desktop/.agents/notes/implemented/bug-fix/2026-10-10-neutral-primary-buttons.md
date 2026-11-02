# Agent Note: Neutral primary-button contrast

Status: implemented

English | [中文](2026-10-10-neutral-primary-buttons.zh.md)

## Problem

The light palette used a pale blue primary fill and dark foreground, contradicting the project’s neutral primary-action direction and the settled Settings appearance snapshots.

## Decision

Restore the theme-owned primary fill to the existing neutral brand alias and its foreground to white. Hover uses the existing dark neutral palette. Dark-mode aliases stay unchanged. Shared buttons and the account top-up link consume those same aliases.

## Validation

The Settings browser regression compares the existing appearance snapshots in both palettes and independently measures rendered text contrast and neutral fill spread at rest and on hover. The model Save action also checks its rendered text contrast. Existing focus regressions cover keyboard and pointer paint.

Full Web checks also exposed outdated welcome, return-button and plugin-warning copy in older fixtures; those assertions now use current copy and approved brand artwork. The home identity assertion distinguishes the FunnyWork build from the default preview build. The installed Web app metadata uses the documented FunnyWork client name.

Reload checks wait for Host onboarding-step persistence because visible steps are optimistic. Stream-replay reloads wait for bootstrap catalog queries to finish; uncaught page errors remain fatal. Blank-session fixtures checkpoint their cold-list metadata before releasing writers. Queued submissions wait for client running state. Terminal checks observe browser exit frames and owned process completion. Refused-session coverage targets its prepared Workspace explicitly. Draft navigation uses the platform's document and line shortcuts. Settled-turn snapshots wait for the client to leave running state. Steering replay pauses at an explicit stream barrier until queued admission is observed. Model editors wait for credential and saved-directory refreshes before reopening. Upload-switch reload checks wait for an editable composer; subagent navigation uses the keyboard entry point after opening its menu. Terminal startup proves command execution with a split-string readiness probe instead of depending on the first shell prompt. Queue snapshots wait for their streamed partial text. Preset catalog checks require a loaded positive entry before asserting removed commands. Restore and permission reinstall checks observe client list and catalog readiness. ARIA stability comparisons cross a browser task via an owned MessageChannel, including paused simulated-clock scenarios. Funded credit snapshots observe RPC JSON consumption across a browser task before entering the credit step and wait for the balance-derived action, blank-session reloads wait for their durable list checkpoint, and inspector profiling observes start/stop response completion. Final transcripts await their completed projection, while reminder refresh and file-open capability checks await their loaded state. Locale snapshots target the selected-language dialog, and multi-turn Goal snapshots establish their authored scroll position. Terminal completion checks distinguish executed output from input echo and observe rename responses; palette checks await rendered glyph colors. Long-scroll tool checks await running state. Context-layout submissions wait for each admission response, reloaded Workspace operations wait for the editable restored composer, and rapid handoff fixtures await the seeded completed projection before holding later history frames. These barriers preserve the functional assertions without accepting unsettled snapshots or widening timeouts.

## Alternatives considered

**Refresh snapshots to the blue palette.** This would accept a visual regression rather than restore the documented neutral appearance.

**Override individual buttons.** Local overrides would leave other consumers inconsistent. The shared theme owns the fix.

## Consequences

Feature components need no local color overrides. Existing snapshots remain the expected neutral appearance; they are not refreshed to accept the accidental blue palette.
