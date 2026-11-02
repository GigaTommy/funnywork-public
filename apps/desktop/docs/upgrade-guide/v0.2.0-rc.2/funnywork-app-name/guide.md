---
kind: upgrade-guide
description: The desktop application bundle changes from FunnyWorker to FunnyWork.
---
# FunnyWork application name

English | [中文](guide.zh.md)

## Change

The desktop application displays FunnyWork in its login screen, menus, window titles and installer branding. The macOS bundle changes from `FunnyWorker.app` to `FunnyWork.app`, and the Windows executable becomes `FunnyWork.exe`. The application ID `com.funnyworker.desktop`, URL scheme, saved account credentials and account workspace paths are unchanged.

## Migration

1. Quit FunnyWorker and install FunnyWork. On macOS, replace the old application instead of keeping both bundles in Applications.
2. Update shortcuts and scripts that explicitly reference `FunnyWorker.app` or `FunnyWorker.exe` to the new name. Existing `DSH_CLIENT_TITLE=FunnyWorker` source launches remain compatible; new launches use `FunnyWork`.
3. Open FunnyWork and confirm the application menu displays its new name and the existing account workspace is available. macOS may request Keychain authorization again for a newly signed build.
