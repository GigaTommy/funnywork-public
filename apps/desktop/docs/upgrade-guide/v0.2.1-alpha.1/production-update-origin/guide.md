---
kind: upgrade-guide
description: Production desktop packaging requires an explicit HTTPS update-service origin.
---
# Production desktop update origin

English | [中文](guide.zh.md)

## Change

Production desktop packaging now requires `DOWNLOAD_PROD_ORIGIN` in the target `.env.macos` or `.env.windows`. Previously it embedded `https://download.deepseek.com` without a configuration field. Missing or invalid origins stop packaging before building. Existing installed applications retain their embedded update address until replaced.

## Migration

1. Set `DOWNLOAD_PROD_ORIGIN` to the HTTPS origin of your deployment's update service. Paths, credentials, queries and fragments are rejected; shell environment values do not replace target dotenv settings.
2. Serve production feeds under `dsh-desk/feeds/<target>/` and binaries under `dsh-desk/bin/<target>/`. Upload to an independently hosted server through its deployment process; the COS uploader still requires its own bucket and credentials.
3. Repackage the desktop application and verify its embedded update URL, feed and artifact checksums before distributing it. Keep application identifiers and signing identity stable for existing installations.
