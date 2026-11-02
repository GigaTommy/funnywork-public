---
description: "Records a persistence type transition and its compatibility acknowledgement."
kind: persistence-change
---

# 2026-10-12-fixed-result-files

English | [中文](2026-10-12-fixed-result-files.zh.md)

## Summary

Adds optional fixed-result manifests to presented files.

## Table of Contents

- [Declaration](#declaration)
- [Compatibility](#compatibility)
- [Verification](#verification)
- [Dev Note](#dev-note)

<a id="declaration"></a>
## Declaration

```yaml persistence-change
schemaVersion: 1
id: 2026-10-12-fixed-result-files
baseline: false
changes:
  - root: "event:deliverables/presented"
    previous: "2026-09-11-initial"
    after: "f7d4dd9433a8f51ca0349e408afb3c1d5e861e1700c8aae7325a54b24426f903"
    decision: same-version
```

<a id="compatibility"></a>
## Compatibility

Existing path-only deliverables remain valid. Result originals and preview variants are immutable attachment references. Late preview completion is scoped to the original result version and turn; no new native event type is introduced.

<a id="verification"></a>
## Verification

Publisher Loader integration, saved-value workbook previews and delivery UI tests pass; existing presentation behavior remains covered.

<a id="dev-note"></a>
## Dev Note

None.
