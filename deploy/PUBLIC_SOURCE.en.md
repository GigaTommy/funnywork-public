# Publishing source code

[简体中文](PUBLIC_SOURCE.md) | **English**

Generate an independent snapshot from a fixed commit. Keep the development repository private. Publish only reviewed source and subsequent public commits, retaining the project license, upstream copyright notices, and third-party notices.

## License scope

The root [LICENSE](../LICENSE) permits non-commercial use of the project-owned code and documentation it covers. Obtain written commercial authorization through the [website](https://funnywork.funnypixelsapp.com). The owned `funnywork-desktop-controls` and `funnywork-desktop-capabilities` packages carry copies of that license and are private npm workspaces.

DeepSeek Harness, RuoYi-Vue-Plus, plus-ui, and other upstream code retain their original licenses; see the [third-party notices](../THIRD_PARTY_NOTICES.md). Previously and lawfully acquired licenses remain valid.

## Configuring a regional entry

Public builds use the international website by default. Operators requiring a regional entry supply `FUNNYWORK_REGIONAL_ORIGIN` in their private build environment. Use a lowercase HTTPS origin without a port, path, query, or credentials, such as `https://regional.example.invalid`. The reserved example host must be replaced with the reviewed deployment entry. Clients select only explicitly configured official entries; self-hosted services retain separate account namespaces.

| Component | Configuration |
| --- | --- |
| Desktop development, Host, or standalone runtime | Supply `FUNNYWORK_REGIONAL_ORIGIN` when starting the process |
| Desktop installer | Set the variable before packaging; the build embeds it in the main process. Configure standalone Host processes through their launch environment |
| Android | Set the variable before running Gradle; it becomes part of the signed application's BuildConfig |
| iOS | Pass the `FUNNYWORK_REGIONAL_ORIGIN=https://…` build setting to `xcodebuild`; it becomes part of the signed application's Info.plist |
| Website builds and live checks | Specify the target origin; the same environment variable adds an allowed regional target |
| Regional Nginx and certificate renewal | Replace `regional.example.invalid` in private configuration. The renewal service reads `FUNNYWORK_REGIONAL_HOST` from `/etc/funnywork/site.env` |

Regional and international entries must use the same account authority. Preserve the existing regional configuration when upgrading regional builds so that account directory recovery retains the original identity mapping. Do not designate third-party self-hosted services as official aliases. Keep operational values outside Git. Service addresses embedded in an application can be read from its installer; external configuration does not conceal deployed addresses.

## Generating a candidate

Use Python 3.12+. Commit and review the changes first. Run the following from the repository root, replacing `COMMIT` with the reviewed full commit ID:

```sh
python3 scripts/build/build-public-source.py \
  --ref COMMIT --output .local/releases/public-source-COMMIT
```

The output contains `source/`, `funnywork-source.tar.gz`, `SOURCE-MANIFEST.json`, `SOURCE-FILES.zlist`, and `SHA256SUMS`. Export reads only that commit, excluding Git metadata, untracked files, and local configuration. It rejects existing output directories, private material paths, and escaping symlinks. The manifest records the source commit and per-file hashes.

## Review and publication

1. Scan the candidate source for credentials. Classify synthetic fixtures, public verification keys, and documentation hashes; resolve unclassified findings before publication.
2. Check licenses, third-party notices, website and download links, and the presence of required components, lockfiles, build scripts, and tests.
3. Run focused tests, builds, and repository checks for affected components. Record the commands, results, and source commit. Validate new installers separately using [client releases](CLIENT_RELEASES.md).
4. Initialize an empty independent repository with the contents of `source/`. Verify its name and visibility before publishing. Do not add the private development remote, push all branches, or import old Issues, PRs, logs, or attachments.
5. Enable suitable PR checks and a security reporting entry after publication. Synchronize only reviewed source changes thereafter.

Generating a snapshot does not create a GitHub repository or change an existing repository's visibility. Source publication, websites, services, and installer deployment are separate operations.

The publication target is [GigaTommy/funnywork-public](https://github.com/GigaTommy/funnywork-public). The export manifest lists the excluded one-off fixed-commit build workflow, which requires private history and remains in the development repository. Regular cross-platform checks remain in the public source.

Initialize the public repository from the exact exported file list. This retains reviewed upstream fixtures that match ignore rules while excluding newly created local material:

```sh
git -C .local/releases/public-source-COMMIT/source init -b main
git -C .local/releases/public-source-COMMIT/source add --force \
  --pathspec-from-file=../SOURCE-FILES.zlist --pathspec-file-nul
```
