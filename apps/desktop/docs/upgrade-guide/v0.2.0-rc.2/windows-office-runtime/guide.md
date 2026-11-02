---
kind: upgrade-guide
description: Windows Desktop installs its account-owned authoring runtime into a shorter directory.
---

# Windows Desktop authoring runtime directory

English | [中文](guide.zh.md)

## Change

Windows Desktop now installs its authoring runtime into `DSH_HOME/rt` instead of `DSH_HOME/dsh-runtimes/dsh-primary-runtime`. This avoids Python import failures when account directories put library files beyond the Windows legacy path limit. macOS and Linux retain their existing directories. Account homes, sessions and sandbox permissions are unchanged.

## Migration

1. Restart Desktop and call `load_workspace_dependencies` to install the bundled runtime into the new directory.
2. Use the paths returned by that tool in scripts; update any hardcoded runtime paths. Additional packages installed into the old runtime are not copied automatically.
3. Confirm Python can import `openpyxl` and save a workbook. The old runtime directory remains available for manual recovery.
