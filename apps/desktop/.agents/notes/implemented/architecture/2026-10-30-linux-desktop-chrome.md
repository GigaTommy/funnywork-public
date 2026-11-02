# Agent Note: Linux shares the Mac desktop command model

Status: implemented

English | [中文](2026-10-30-linux-desktop-chrome.zh.md)

## Problem

A native Linux title bar repeats the conversation heading, and Electron role menus expose English labels in a Chinese workspace. Linux shortcut admission and dispatch also differ from the native Desktop policies, so matching menu labels alone cannot provide the same interaction model.

## Decision

Mac and Linux share localized File, Edit, View, and Help command groups in the Desktop shell. Mac retains its system application menu and hidden-inset controls. Linux uses the existing integrated caption layout with native window controls and document-synchronized colors. Historical Windows layout and IPC names remain internal compatibility details. Native popup requests retain owned-window, top-frame, coordinate, and origin checks.

Linux Desktop uses the existing native shortcut priority, single-key and chord admission, and version-2 preference writer. Version-1 documents remain readable without rewriting until a successful edit. Other profiles and Session formats are unchanged. Web retains its browser reservations. Native menu editing preserves editor-owned undo through the established key-delivery path.

## Alternatives considered

**Translate the default Electron menus only.** This leaves the repeated window heading and a different shortcut policy.

**Build a separate Linux renderer shell.** This duplicates layout, focus restoration, keyboard navigation, and palette synchronization already owned by the Desktop caption.

## Consequences

Caption menus, language changes, native fullscreen clearance, and shortcut dispatch share existing implementation paths. Linux retains right-side native window controls and task-aware quit on workspace close; it has no background tray. Mac retains its native application lifetime and Command modifier. Operating-system shortcuts intercepted before Electron remain outside application control. Platform-mocked tests verify command parity; native Linux checks verify rendered geometry and interaction. This environment does not provide a native Mac runtime for visual verification.
