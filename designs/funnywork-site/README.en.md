# FunnyWork website maintenance

[简体中文](README.md) | **English**

For frontend developers, content editors, and release operators. This bilingual static website uses a white background, warm orange actions, dark text, and limited blue accents. The public brand is FunnyWork; service domains and client identifiers remain stable.

Users should read the [user guide](../../manuals/USER_GUIDE.en.md). The website describes ongoing product uses; individual release changes follow the [release notes template](../../deploy/RELEASE_NOTES_TEMPLATE.md). Check asset inventories and versions for the target environment before publication.

## Content and media

Explain what users can do with data organization, charts and reports, cross-device collaboration, and enterprise deployment. Polish Chinese and English separately while retaining the same facts. Support is named 珠珠 in Chinese and Gigi in English. Limit repeated names in examples, and keep recording, testing, and takeover procedures out of product copy.

Public media comes from authorized Gigi workflows using sample business data. Preserve actual app screenshots and recordings. Use dedicated accounts and isolated environments for new captures; separately confirm authorization and sanitization before using production accounts or data. Exclude administrator history, other accounts' history, takeover tools, and synthetic application interfaces.

`site.js` uses four media families, captured and generated separately for each language; English filenames add `-en`:

| File under `media/product/` | Purpose |
| --- | --- |
| `revenue-trend.png` | Quarterly revenue trends |
| `target-completion.png` | Regional target attainment |
| `iphone-chart-reading.png` | Phone result preview and video poster |
| `iphone-chart-preview.mp4` | Phone chart-reading recording |

Retain `iphone-desktop-task.png` and its English counterpart for existing paths; the current hero does not promote them as a task dashboard. Keep capture originals, credentials, logs, and retired assets in ignored directories outside publication. When replacing media, check language mappings, dimensions, alternative text, original-image links, posters, and cache-version parameters.

## Interactions and support

Language switching updates copy, alternative text, charts, screenshots, video, and poster while preserving the selected chart. Images support in-page preview, Escape to close, and an original-image link. Videos play on user action, support mobile inline playback, and offer retry and direct-open controls after failure.

Inquiry links scroll to the support section. The form generates an email-draft preview locally; users open their mail app and confirm sending. The website server does not receive or store the inquiry. Read the recipient from `site.js` support configuration. Preserve manual copying after clipboard failure and instruct users to paste long copied content. Language choice and video position are stored locally in the browser; describe this consistently in the privacy page.

## Licensing and platforms

Home, download, and usage pages link to the software/commercial licensing section at `privacy.html#licensing`. Chinese and English match the root `LICENSE`, `COMMERCIAL-LICENSE.txt`, and `THIRD_PARTY_NOTICES.md`: permitted non-commercial use is free of license fees, enterprise internal and other commercial uses require prior written authorization, and independent third-party licenses remain applicable. Publication generates content-addressed public license files from repository originals so visitors can read the full terms without repository access.

iOS descriptions distinguish independent chat from desktop collaboration. The usage guide links to the iOS development documentation on GitHub `main` and states its repository-access requirement. Minimum iOS version is 17. Installation invitations come from the signed download manifest; avoid duplicating fixed invitations, versions, or review status in copy. Both languages' canonical URLs, hreflang, structured data, and sitemaps use the website domain.

## Local preview and checks

From the repository root:

```sh
python3 -m http.server 4313 --bind 127.0.0.1 --directory designs/funnywork-site
```

After installing desktop workspace dependencies, run checks for the affected scope:

```sh
node scripts/checks/check-site-interactions.cjs
node scripts/checks/check-site-downloads.cjs
node scripts/checks/check-site-release.mjs
python3 scripts/checks/check-repository.py
```

Manually inspect desktop and mobile browsing, long headings, language round trips, original charts, video playback/recovery, support drafts, and copy fallbacks. Browser simulation supplies automated checks; verify installation separately on target browsers, WeChat, and actual devices.

## Downloads and signatures

`download.html` is served through `/download/`. `downloads.js` verifies the signed manifest before showing available platforms. Availability follows signature, expiry, and release-eligibility checks; avoid fixing package versions or TestFlight review states in this README.

The manifest retains Ed25519 and an ECDSA P-256 compatibility signature bound to the same payload. Use the compatibility path for unsupported algorithms; reject failed signatures. Android user agents change card order without hiding other available platforms. Android WeChat shows system-browser guidance and a same-site, same-language link. When verification capability is unavailable, prompt a browser update and leave unverified download links disabled.

See [client releases and upgrades](../../deploy/CLIENT_RELEASES.md) for signing, key handling, installation/upgrade checks, channels, and TestFlight requirements. Website verification checks the manifest; client installation and updates also perform system publisher, platform-signature, and application-update checks. Site publication and access protection maintain script and public-key integrity.

## Deployment and rollback

Website: `https://funnywork.funnypixelsapp.com`. Some regions connect through local cloud services. Publish home, privacy, security, `site.js`, `styles.css`, download HTML/CSS/JS and signed manifest, and referenced brand and bilingual media. Publish the explicit asset list rather than the whole design directory or Markdown operations documents.

Before release, record live file hashes, Nginx configuration, and management/client API baselines, then back them up. Installer paths are immutable by version: upload and verify remote hashes first, then switch verified manifests and pages. Add exact public-path allowlists, validate configuration, and reload only after validation. Static website publication does not require restarting business services.

Templates: [regional access](../../deploy/nginx/FUNNYWORK_CN.md), [localized media](../../deploy/nginx/funnywork-site-localized-media.conf.example), [support avatar](../../deploy/nginx/funnywork-site-avatar.conf.example), and [download paths](../../deploy/nginx/funnywork-site-downloads.conf.example). Preserve management, client API, authentication, and streaming rules.

After release, check both languages, media hashes, video/installer Range requests, support flows, and existing business routes. Independent download verification is `scripts/checks/check-public-downloads.mjs`; mobile browser sample requests use `scripts/checks/check-mobile-download-endpoints.mjs`. Release acceptance also checks representative authenticated account flows.

Before rollback, inspect the matching backup and current version. Restore static files, manifest, and necessary routes while preserving immutable installers and new business data. Treat historical backup paths as traceability records and verify their applicability before executing recovery.
