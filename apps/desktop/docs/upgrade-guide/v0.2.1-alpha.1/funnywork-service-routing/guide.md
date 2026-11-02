---
kind: upgrade-guide
description: Official FunnyWork addresses select a reachable entry while retaining the original account and history namespace.
---

# FunnyWork service routing

English | [中文](guide.zh.md)

## Change

Both official HTTPS addresses now select between the mainland and international entries using credential-free readiness probes. The original international origin remains the stable account identity. The login page displays connection state instead of the configured domain. Explicit self-hosted addresses remain isolated and do not use official fallback entries. Failed writes and model streams are never automatically replayed.

## Migration

1. Update the desktop client without editing `funnyworker-account.enc`, Session files, or existing account directories. Existing official login credentials are accepted only after server identity validation.
2. An existing mainland principal mapping is reassigned to the stable official namespace while its directory stays in place. Distinct existing histories are not merged or overwritten. Retain a backup before manually resolving conflicting mappings.
3. Leave self-hosted service settings unchanged. Both official addresses enable automatic entry selection; neither is a force-region setting.
4. Verify sign-in, history, model streaming and recovery on a real mainland network without a VPN and an international network. A transient outage retains the saved credential and reports connection failure rather than deleting it or replaying execution.
