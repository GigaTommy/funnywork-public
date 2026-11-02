---
kind: upgrade-guide
description: FunnyWorker desktop accounts now use separate local Harness homes instead of the shared desktop data directory.
---
# FunnyWorker account data

English | [中文](guide.zh.md)

## Change

FunnyWorker stores each account's Harness data in `DSH_HOME/accounts/<hash>`, where the hash identifies the service origin, tenant and username. The account login and encrypted model publication stay in the installation home. A renamed username opens a new account directory. This separates application data for account switching; it does not isolate files from other processes running as the same operating-system user.

## Migration

1. Quit FunnyWorker and start it through the parent project's `scripts/start-desktop.command`.
2. Sign in with a work account. Confirm that its workspace does not show another account's sessions.
3. Preserve the previous shared home and `profiles/desktop` data. No shared sessions are assigned automatically to an account. Transfer data only after its ownership has been established.
4. A saved session rejected with HTTP 401 or 403 is cleared and requires a fresh login. Revocation during use stops the Host and exits the application at the next session check.
