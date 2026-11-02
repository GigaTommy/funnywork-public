---
kind: upgrade-guide
description: Desktop installer filenames now use the funnywork brand instead of deepseek-harness.
---
# funnywork installer filenames

English | [中文](guide.zh.md)

## Change

New desktop installers use `funnywork-<version>-<os>-<arch>.<ext>`. Previously they used `deepseek-harness-<version>-<os>-<arch>.<ext>`. The unsigned suffix remains `-unsigned`.

This affects scripts that construct installer filenames. Build numbering and installed-update qualification continue accepting existing legacy artifact names. Application identifiers, updater cache directories and user data paths retain their existing values.

## Migration

1. Update external filename patterns from `deepseek-harness-` to `funnywork-` for newly generated DMG, ZIP and EXE files. Keep existing published files available.
2. Generate update feeds and upload plans with the current packaging scripts; they reference the new versioned artifact names.
3. Verify the feed filename, artifact size and SHA-512 against the generated package before publishing. Existing clients obtain the filename from the feed.
