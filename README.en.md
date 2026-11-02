# FunnyWork

[简体中文](README.md) | **English**

FunnyWork is an AI assistant for organizing materials, analyzing data, creating documents, and working across devices. The desktop client processes files in a workspace, calls tools, and operates browsers or the computer. iOS and Android support independent text chat and can connect to a computer on the same account to start tasks, handle approvals, review replies, and download results. The management console provides centralized control of member access, models, allowances, devices, and organization policies.

This repository contains client, management, and service source code for developers and deployment teams. Users install the application from the website or their organization's distribution channel. For daily operations, see the [user guide](manuals/USER_GUIDE.en.md).

## Core features

| Feature | Typical use | Requirements and entry point |
| --- | --- | --- |
| Desktop tasks | Organize spreadsheets, analyze data, and create charts and documents | Open a workspace on the computer and select a model and the required tools |
| Browser and computer controls | Read web pages and operate application interfaces | Install a compatible browser; computer controls require the platform provider and system permissions described in [control setup](#browser-and-computer-controls) |
| Independent phone chat | Ask questions, discuss ideas, and manage chat history | Use **Chat** on the phone with an available model and allowance |
| Remote collaboration | Select a computer and workspace, send attachments, handle approvals and questions, and stop tasks | Use **Remote** on the phone; the computer must be online and organization policy must allow cross-device access and remote control |
| History and results | Search records, download fixed file versions, preview content, and submit review comments | Read synced content online or cached content offline |
| Enterprise management | Provision members, publish models and policies, set capacity and budgets, and manage devices and history access | Requires management permissions, business roles, and zone access |
| Business execution | Maintain repository issues, configure execution resources, and compile structured annual business summaries | Configure the project, resource grants, and execution purpose; see the [business service index](services/business/README.en.md) |

Computers execute workspace tasks. Phones read synced records and submit commands through the business service. The business service connects to the identity service and provides model admission, authorization, and task scheduling; the management frontend configures these resources. When a computer is offline, the phone can still use independent chat and read records and files already stored by the service.

## Platforms and requirements

| Client | Source and build guide | Platform requirements |
| --- | --- | --- |
| macOS / Windows | [Desktop customization](apps/desktop/FUNNYWORKER.md), [packaging and distribution](#desktop-packaging-and-distribution) | Build targets: macOS ARM64, macOS x64, and Windows x64 |
| iOS | [iOS development](apps/ios/README.en.md) | iOS 17+; QR pairing and notification settings are available on iOS; notifications require server-side Apple push configuration |
| Android | [Android development](apps/android/README.en.md) | Android 10 / API 29+; connect through the device list and receive task updates while the app is in the foreground |
| Linux x64 | [Local preview builds](apps/desktop/apps/desktop/README.md#linux-x64-local-prerelease) | glibc desktop environment and an unlocked keyring; browser controls with manual installation and upgrades |

The [website](https://funnywork.funnypixelsapp.com/download.en.html) provides available releases and the iOS TestFlight invitation. See the [manuals and release index](manuals/README.en.md) for version changes. Source capabilities and distributed releases are maintained separately; see the [client release process](deploy/CLIENT_RELEASES.md).

## Start here

| Reader | Entry point |
| --- | --- |
| Members and evaluation users | [User guide](manuals/USER_GUIDE.en.md) |
| Organization administrators | [Administrator guide](manuals/ADMIN_GUIDE.en.md) |
| Developers | This README, the [contribution guide](CONTRIBUTING.md), and component READMEs |
| Deployment and operations teams | [Self-hosted deployment](deploy/SERVER_DEPLOYMENT.md), [client releases and upgrades](deploy/CLIENT_RELEASES.md) |

For maintenance tasks, execution resources, and annual reports, see the [business service documentation index](services/business/README.en.md). Source code is provided under the [project license](LICENSE); users install applications from the website or their organization's distribution channel.

## Repository layout

See the [contribution guide](CONTRIBUTING.md) for collaboration and the [design guidelines](DESIGN.md) for interface and brand conventions. Desktop and management applications live in `apps/`, Java services in `services/`, and shared business components in `packages/`.

| Directory | Purpose |
| --- | --- |
| [`apps/ios/`](apps/ios) | Native SwiftUI client for independent phone chat and interaction with desktop tasks; see the [iOS guide](apps/ios/README.en.md) |
| [`apps/android/`](apps/android) | Native Kotlin / Compose Android client; see [build, signing, and integration instructions](apps/android/README.en.md) |
| [`apps/desktop/`](apps/desktop) | Customized DeepSeek Harness workspace containing the Electron desktop client, Host, plugins, and runtime |
| [`funnyworker/`](funnyworker) | Python desktop launcher, device model configuration, and prototype service |
| [`services/business/`](services/business) | Spring Boot business service, management page entry point, and RuoYi API proxy |
| [`apps/management/`](apps/management) | Vue 3 management frontend based on plus-ui; the default management page build source |
| [`services/identity/`](services/identity) | RuoYi-Vue-Plus backend source and project customizations |
| [`packages/management-react/`](packages/management-react) | Retained React management frontend and business components still used by the Vue management frontend |
| [`scripts/`](scripts) | Environment setup, build, launch, and integration-check scripts |

The desktop client is based on [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). The business service uses Spring Boot; the management frontend and identity service are based on plus-ui and RuoYi-Vue-Plus, respectively. The user-facing name is FunnyWork. Repository identifiers, the Python import path, and existing application identifiers retain `funnyworker`. Local runtime data and generated configuration live in `.local/`.

## Development environment

Install the dependencies for the components you intend to develop. Desktop developers connecting to an existing service do not need Java, PostgreSQL, or Redis.

| Component | Requirements |
| --- | --- |
| Python launcher and configuration tools | Python 3.10+ and a repository-root `.venv` |
| Harness / Electron desktop | Node.js 22.19+ on the 22.x line, or 24+; pnpm 11.28.5 as specified in `apps/desktop/package.json`; management uses its own pinned version |
| iOS app | macOS, Xcode 26+ with Swift 6.2+ and iOS Simulator, and XcodeGen; minimum runtime: iOS 17 |
| Android app | JDK 17 and Android SDK 36; minimum runtime: Android 10 / API 29; use the checked-in Gradle Wrapper |
| Java services | JDK 17; repository scripts prepare Maven 3.9.9 |
| Management frontend | Node.js 20.19+ and pnpm 11.7.0, as specified in `apps/management/package.json` |
| Local backend on macOS | PostgreSQL 16 and Redis; PostgreSQL defaults to `/opt/homebrew/opt/postgresql@16/bin`, overridable with `FUNNYWORKER_PG_BIN` |
| Local backend on Windows | Docker Desktop with the Linux container engine; scripts start PostgreSQL 16 and Redis through Compose |

### Choose a development path

- For desktop or mobile changes, prepare the corresponding toolchain and connect to an existing HTTPS service. See [local development](#local-development) for desktop launch commands and the mobile READMEs for builds.
- For backend or management changes, start the local services below, then use the local administrator account to provision members, models, and allowances.
- For installers, follow [desktop packaging and distribution](#desktop-packaging-and-distribution) or the mobile build guides and prepare separate signing and release configuration.

## Get the source

```sh
git clone https://github.com/GigaTommy/funnywork-public.git funnyworker
cd funnyworker
```

Harness and the RuoYi frontend and backend sources are included in this repository. Keep each workspace's lockfile and install dependencies with `--frozen-lockfile`.

## Local development

Run all commands from the repository root. The first build downloads dependencies.

### Start local services on macOS

Prepare the Python environment:

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

Prepare the local management environment and Maven, then build and start the services:

```sh
.venv/bin/python scripts/prepare-ruoyi.py
sh scripts/mvn.sh -version
sh scripts/build-server.command
sh scripts/start.command
```

`prepare-ruoyi.py` creates a separate local PostgreSQL data directory, Redis configuration, and administrator account. `build-server.command` builds the management frontend, RuoYi backend, and business service, and runs Maven verification for the business service. The launch script stays in the foreground.

### Start local services on Windows

Install JDK 17, Node.js, and pnpm, and start Docker Desktop's Linux container engine:

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev-windows.txt
.\scripts\start.ps1 -Build
```

This script prepares local databases and Redis, builds the services, and starts both Java processes. Omit `-Build` on subsequent runs when the source has not changed. For a separate build, use `scripts/build-server.ps1`. Stop running services before rebuilding so Windows can replace the JAR files.

The Windows build script runs selected Java integration tests. A complete `mvn verify` also requires the external `funnywork` scripts and a POSIX runtime for legacy node integration scenarios. Set the script directory with the Maven property `-Dfunnywork.http-scenarios=/absolute/path/funnywork/scripts`.

For installed Windows client checks covering login, history, phone connections, and real model calls, see [Windows installer checks](#windows-installer-checks).

### Open the management console

After the services start, open the [local management console](http://127.0.0.1:8765/admin/index.html).

| Service | Default address |
| --- | --- |
| Business service and management page entry point | `127.0.0.1:8765` |
| RuoYi management service | `127.0.0.1:8767`, accessed through the entry service's `/ruoyi/` proxy |
| Management PostgreSQL | `127.0.0.1:16432` |
| Management Redis | `127.0.0.1:16379` |

The local administrator username is `admin`; its random password is stored in `.local/ruoyi/admin-login.json`. Repeating environment setup preserves existing credentials and data. For development members, configure desktop permissions, business access, models, and allowances as described in [provisioning a member](manuals/ADMIN_GUIDE.en.md#provision-a-member). Sign in on desktop with that member account, send a short request, and check the reply. Sign in with the same account on the phone and check its device and workspace lists.

### Start the desktop client

The desktop client requires a reachable management service and an active account. Configuration retrieval and renewal after login are described in [client connections and model configuration](#client-connections-and-model-configuration).

macOS:

```sh
pnpm --dir apps/desktop install --frozen-lockfile
./scripts/start-desktop.command --build --server http://127.0.0.1:8765
```

Windows, after creating `.venv` and installing Python dependencies as described above:

```powershell
pnpm --dir apps/desktop install --frozen-lockfile
.\scripts\start-desktop.ps1 -Build -Server http://127.0.0.1:8765
```

On Windows, `-Build` first installs the Electron development runtime. After the initial build, omit `--build` / `-Build` if desktop source has not changed. Use `--check` / `-Check` to validate the service address and prepare desktop configuration without launching Electron.

Linux x64 with glibc, for local preview: run inside a graphical session with an unlocked desktop keyring. On Ubuntu / Debian, install `build-essential musl-tools bubblewrap fonts-dejavu-core python3-venv`. For other distributions, prepare equivalent dependencies from the [Linux build requirements](apps/desktop/apps/desktop/packaging/README.md#linux-x64-local-prerelease), along with the Node.js and pnpm versions listed above.

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
pnpm --dir apps/desktop install --frozen-lockfile
pnpm --dir apps/desktop --filter @deepseek-ai/dsh-desktop exec install-electron
./scripts/start-desktop.sh --build --server https://funnywork.funnypixelsapp.com
```

The Linux entry point reuses account configuration and the `.local/desktop/` data directory, supports `--check` and `--server`, and allows subsequent launches without `--build` when source has not changed. Run the client as a regular user with the Chromium sandbox enabled. If the distribution restricts user namespaces, configure an appropriate system policy for the application path first.

For a remote service, replace the address with its actual `https://...` management service root. The client allows HTTP only for the local development entry point `http://127.0.0.1:8765`. Build and deploy the server and clients separately; clients need network access to their configured service. Conversation archives, remote interaction, attachment APIs, execution ownership, and capacity settings are described in the [desktop synchronization protocol](services/business/DESKTOP_SYNC.md).

### iOS app development

The SwiftUI iOS client supports independent phone chat, desktop tasks, attachments and result reading, code review, QR pairing, and notification settings. Source lives in `apps/ios/Sources/`; `apps/ios/project.yml` defines the generated Xcode project.

Install Xcode 26 or later and XcodeGen on macOS, then run:

```sh
sh scripts/build/build-ios.sh
open apps/ios/FunnyWork.xcodeproj
```

The script generates the Xcode project and builds the simulator app under `.local/ios-build/`. In Xcode, select the FunnyWork scheme and an iPhone or iPad simulator, then sign in with a provisioned account. For desktop task integration, sign in to the same service and account on the desktop client and open a workspace.

Physical-device development requires an Apple development team and matching signing configuration. Build parameters, tests, APNs, and TestFlight distribution are described in the [iOS development guide](apps/ios/README.en.md). Account and desktop collaboration steps are in the [user guide](manuals/USER_GUIDE.en.md).

### Android app development

The native Kotlin and Jetpack Compose Android client supports independent phone chat, device and workspace selection, desktop task interaction, attachments, result files, and review comments. Source lives in `apps/android/app/src/main/`.

Prepare JDK 17 and Android SDK 36, then build a debug APK from the repository root:

```sh
sh scripts/build/build-android.sh assembleDebug
```

APKs are written under `.local/agent/apps/android-build/app/outputs/`. Install on an Android emulator or device and sign in with a provisioned account. For desktop tasks, open **Remote** and select a computer and workspace on the same account. See the [Android development guide](apps/android/README.en.md) for unit tests, lint, development certificates, and release signing.

### Browser and computer controls

The default desktop configuration includes the FunnyWork Desktop Controls bundle. A text model with tool-calling support can read web page structure and the system accessibility tree. Browser actions use a separate visible Chromium window isolated by session; the right-hand browser panel remains available for manual browsing. The client discovers an existing Chrome, Edge, Chromium, or Brave installation, or uses an executable specified in bundle configuration. Browser installation is handled before running tasks. Sign in to websites manually in the controlled browser window.

On macOS 14+, the client uses the DSH native accessibility provider; Windows uses Cua Driver in text mode. The default mode reads structured information without sending screenshots or requiring image input. Grant Accessibility permission to the Mac client; Screen Recording is required only for screenshots. Linux provides browser controls; computer-control requests return an unsupported status. Missing browsers, native executors, or permissions are reported by the status tool while ordinary chat remains available.

Installers include the control plugins and the Mac native helper for the target architecture. The helper is compiled during packaging, so users do not need Xcode. Existing default desktop configuration receives the bundle once; custom bundles, explicit disabling, and recovery settings retain their selected configuration. See [Desktop Controls](apps/desktop/packages/bundle/funnywork-desktop-capabilities/README.md) for feature switches, browser selection, and requirements.

Maintainers can use the [packaged application acceptance script](scripts/checks/check-browser-computer-packaged.mjs) to verify a Mac `.app`'s default configuration, tool loop, and native helper. It accepts `--app`, a new isolated `--home` directory, and `--report`. Use `--fixture-app` for the accessibility test application and `--gui` to check the regular login window. Tests use a local simulated text model and test pages without submitting account credentials. Permission reports distinguish the test launch chain from a user's first client launch. Test directories, reports, and temporary paths must be on the external volume, with `--home` under this workspace's `.local/`. Set an absolute `DSH_DESKTOP_LOG_DIR` for isolated GUI logs.

## Configuration and deployment

The repository tracks credential-free `.example` configuration templates. To prepare missing local configuration separately, run:

```sh
python3 scripts/prepare-config-templates.py
```

This command creates only missing files and preserves existing configuration. Management environment setup and frontend build scripts also run it.

- Setup scripts write management runtime configuration and account credentials to `.local/ruoyi/`.
- `services/business/src/main/resources/application.yml` reads configuration from environment variables and is tracked with source.
- Local launch scripts use development settings. The Windows launcher enables `FUNNYWORK_LOCAL_MANAGEMENT_BOOTSTRAP` for restricted local administrator initialization; leave it disabled in production.

Remote services expose client access through HTTPS. Keep databases and Redis on the server's private network. See [self-hosted deployment](deploy/SERVER_DEPLOYMENT.md) for service layout, environment variables, identity connectivity, proxy limits, upgrades, and rollback, and the [Nginx template](deploy/nginx/funnyworker.conf.example) for configuration.

The [website](https://funnywork.funnypixelsapp.com) uses static files from `designs/funnywork-site/`. Some regions connect through local cloud services, and clients select an available connection automatically. Enterprise deployments use a separate HTTPS address. See [website deployment](deploy/nginx/FUNNYWORK_COM.md) and [regional access](deploy/nginx/FUNNYWORK_CN.md).

For enterprise model pools, admission queues, protocol forwarding, usage accounting, and client integration, see the [model middleware reference](services/business/MODEL_MIDDLEWARE.md). Before exposing the service publicly, configure [model limits and global budgets](services/business/MODEL_BUDGETS.md). Promotional grants are disabled by default; deployments can configure a one-time new-user grant or daily sign-in grants. Legacy shared-key distribution is disabled.

Organization policy restrictions, defaults, publication versions, and receipt protocols are described in [desktop organization policies](services/business/DESKTOP_POLICY.md).

Device queries, scope permissions, and revocation behavior are described in [member device management](services/business/MANAGED_DEVICES.md).

For username/password API authentication, user provisioning, and update synchronization with a unified identity platform, see the [identity directory integration guide](services/identity/IDENTITY_DIRECTORY.md). Apply its database migrations before upgrading the identity service.

## Desktop packaging and distribution

### Client and server deployment

Build and deploy the desktop client independently from management services. The server runs the management console, identity service, and business APIs. The website distributes versioned client installers with signature verification. Installer download locations and the management service address used by clients are configured separately.

| Item | Deployment or storage location |
| --- | --- |
| Management console, business service, and identity service | Your deployed server |
| macOS / Windows desktop installers | [Website](https://funnywork.funnypixelsapp.com/download.en.html), for platforms that have completed release acceptance |
| Service address, account, and device authorization settings | Runtime configuration on the target device |
| Application signing certificates and private keys | Restricted configuration directory on the build machine |
| Model provider credentials | Restricted business service configuration or credential references |

The source launcher accepts the management service root through `--server` / `-Server` and writes separate desktop runtime configuration. Account sessions and short-lived model gateway credentials are obtained after login on the target device; provider credentials stay on the business server. Release notes should identify supported operating systems, CPU architectures, compatible server versions, and connection setup.

See [client releases](deploy/CLIENT_RELEASES.md) for signed update manifests, stable and prerelease channel separation, installation and upgrade acceptance, and external TestFlight release requirements.

### Prepare the build environment

Run all commands from the repository root. Build macOS packages on macOS and Windows packages on Windows.

```sh
pnpm --dir apps/desktop install --frozen-lockfile
pnpm --dir apps/desktop --filter @deepseek-ai/dsh-desktop exec install-electron
```

Copy the platform template to its local configuration file and fill in the settings. Edit an existing local file instead of overwriting it.

```sh
# macOS
cp apps/desktop/apps/desktop/.env.macos.example apps/desktop/apps/desktop/.env.macos
```

```powershell
# Windows
Copy-Item apps/desktop/apps/desktop/.env.windows.example apps/desktop/apps/desktop/.env.windows
```

Packaging scripts read the platform configuration file. Relative certificate paths are resolved from `apps/desktop/apps/desktop/`; environment values are not expanded by a shell.

| Setting | Purpose |
| --- | --- |
| `DSH_DESKTOP_APP_ID` | Use `com.funnyworker.desktop` consistently across releases of the same product |
| `DSH_DESKTOP_NPM_STORE_DIR` | Optional absolute pnpm cache directory for reusing verified dependencies; each target still has its own build directory |
| `DSH_DESKTOP_AUTO_UPDATE_ENV`, `DOWNLOAD_TEST_ORIGIN`, `DOWNLOAD_TEST_RELEASE_ID` | Select the update channel embedded in the package; the test channel requires an HTTPS root and a 32-character lowercase hexadecimal release ID |
| `DSH_DESKTOP_MANDATORY_UPDATE_*` | Configure the organization's version-policy service and permitted login origins |
| macOS `CSC_*`, `DSH_DESKTOP_MACOS_*`, `APPLE_*` | Developer ID signing, Team ID, and Apple notarization authentication |
| Windows `DSH_DESKTOP_WINDOWS_*` | Signing certificates, SignTool, and hardware key configuration |

Desktop packaging preserves each target's `dependency-store` package cache and `dependency-cache` npm metadata cache. Setting `DSH_DESKTOP_NPM_STORE_DIR` changes where package contents are stored. Management and desktop runtime setup first verify cached locked dependencies with `--offline`; they access the configured registry only when packages or npm metadata are missing. Management uses the official registry. Integrity, lockfile, and build errors stop the operation without a network retry. Subsequent management builds use strict dependency checks to avoid automatic reinstallation. Set `FUNNYWORK_PNPM_STORE_DIR` to an absolute management cache path. When large packages are missing on the first build, management uses a 300-second registry download timeout rather than repeatedly hitting the default 60-second limit; cache hits skip downloading. Desktop packaging caches resolved lockfiles by package versions, integrity digests, installation settings, and registry origin, reusing them when inputs match. LibreOffice downloads are reused while the version and integrity digest remain unchanged; installers are still assembled, verified, and compressed on every build. Initial builds, version updates, and explicit cache cleanup may require downloads.

Mac build machines need Xcode Command Line Tools, a Developer ID Application certificate, and an Apple notarization authentication method. Windows build machines need Visual C++ Build Tools and the Windows SDK; release signing also requires the signing tools and keys specified by the template. See the [macOS template](apps/desktop/apps/desktop/.env.macos.example), [Windows template](apps/desktop/apps/desktop/.env.windows.example), and [desktop packaging guide](apps/desktop/apps/desktop/README.md) for all parameters.

### Client connections and model configuration

Installed clients connect to the official service automatically. Some regions connect through local cloud services; connection selection retains the same account and local history directory. Self-hosted services use separate addresses, with account and history data isolated by service identity.

The source `start-desktop` launcher's `--server` selects the service address. Clients that support model middleware obtain short-lived gateway credentials and an authorized model catalog after login. Shared DeepSeek keys stay on the server. The legacy encrypted-key distribution endpoint is disabled, so legacy clients need an upgrade.

Run an installed Windows client directly through its `funnywork.exe`; Python, pnpm, and the source launcher are not required. To connect an installed client to a local backend, exit the application and configure it once from the repository:

```powershell
.venv\Scripts\python.exe scripts/setup/configure-installed-desktop.py --server http://127.0.0.1:8765
```

Configuration is stored in the current user's `~/.dsh/funnyworker-backend.json`. With a custom `DSH_HOME`, use `--home` to select the same directory. Reopen the installed client and sign in to obtain the account's authorized model catalog and short-lived gateway credentials. Deployment administrators configure models, capacity, pricing, and member allowances in the management console; see [model service setup](manuals/ADMIN_GUIDE.en.md#configure-model-services). Switching services isolates accounts and sessions by service and identity.

The official model source for phone chat uses the private configuration file selected by `FUNNYWORK_DESKTOP_MODEL_FILE`; see the [configuration template](deploy/desktop-model.example.json). Restrict this file to the service process. It contains the tenant, Ed25519 signing key, DeepSeek credentials, and an increasing configuration version. `signingKey` is a Base64 PKCS#8 private key; `signingPublicKey` is a Base64 raw 32-byte public key. Before management publication is enabled, increase `version` whenever model settings or credentials change. After publication is enabled, database publication records determine model settings and versions, while the file supplies signing material and default credentials. See [desktop model publication](services/business/MODEL_PUBLICATION.md). Desktop users need a business identity binding for the tenant and `funnyworker:desktop:read` permission.

Desktop model access uses account authentication, an authorized catalog, short-lived gateway credentials, and unified usage accounting. See [model middleware](services/business/MODEL_MIDDLEWARE.md) for configuration APIs, admission permits, and recovery. Legacy encrypted-key package and receipt compatibility is documented in [model publication receipts](services/business/MODEL_PUBLICATION.md#客户端加载回执).

### Validation and packaging commands

```sh
# macOS: validate configuration and toolchain without signing or notarization
pnpm --dir apps/desktop package:desktop:mac:arm64 --check
pnpm --dir apps/desktop package:desktop:mac:x64 --check

# macOS: local test package with ad-hoc signing and no Apple notarization
pnpm --dir apps/desktop package:desktop:mac:arm64 --unsigned

# macOS: signed and notarized DMG and ZIP packages
pnpm --dir apps/desktop package:desktop:mac:arm64
pnpm --dir apps/desktop package:desktop:mac:x64
```

```powershell
# Windows: validate configuration and toolchain for a release-signed build
pnpm --dir apps/desktop package:desktop:win:x64 --check

# Windows: signed installer
pnpm --dir apps/desktop package:desktop:win:x64

# Windows: unsigned test installer
pnpm --dir apps/desktop package:desktop:win:x64:unsigned
```

On macOS, `--unsigned` uses local ad-hoc signing, adds `-unsigned` to artifact names, and writes them to the target's `unsigned-artifacts/` directory. This mode requires no Developer ID or notarization credentials and is intended for local testing. Use the signed and notarized commands for distribution.

The `start-desktop` options `--build` / `-Build` prepare source execution. The `package:*` commands above prepare runtime and desktop assets and produce distributable installers.

Artifacts are written under `apps/desktop/apps/desktop/.desktop-build/targets/`:

| Target | Installer directory | Main artifacts |
| --- | --- | --- |
| macOS ARM64 | `mac-arm64/artifacts/` | `.dmg`, `.zip` |
| macOS x64 | `mac-x64/artifacts/` | `.dmg`, `.zip` |
| Windows x64 | `win-x64/artifacts/` | `.exe` |
| Windows x64 unsigned test build | `win-x64/unsigned-artifacts/` | `.exe` with an `-unsigned` marker |

Use the actual filenames reported by the build. The installer naming template uses the prefix `funnywork-<version>-<os>-<arch>`. Build numbering and update acceptance retain compatibility with existing legacy artifact names.

### Publish to GitHub Releases

The following process archives releases inside the private repository. Users receive installers from the website or their organization's distribution channel. Install and authenticate the [GitHub CLI](https://cli.github.com/) with an account that has repository write access. These examples use macOS / Bash. Match the release version to the version inside the installers and use `--prerelease` for prereleases.

Collect installers for each platform under `.local/releases/desktop-v0.2.0-rc.2/`, ensuring they come from the same source commit. Write and review `release-notes.md` using the [release notes template](deploy/RELEASE_NOTES_TEMPLATE.md), describing user-visible changes rather than copying the commit list. Replace `BUILD_COMMIT_SHA` below with the actual build commit.

```sh
gh auth login

# Create a draft with its tag bound to the actual build commit
gh release create desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public \
  --target BUILD_COMMIT_SHA --draft --prerelease \
  --title "FunnyWork Desktop v0.2.0-rc.2" \
  --notes-file .local/releases/desktop-v0.2.0-rc.2/release-notes.md

# Upload installers
gh release upload desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public \
  .local/releases/desktop-v0.2.0-rc.2/*.dmg \
  .local/releases/desktop-v0.2.0-rc.2/*.zip \
  .local/releases/desktop-v0.2.0-rc.2/*.exe

# Review attachments and notes, then publish within existing repository permissions
gh release edit desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public --draft=false
```

When releasing only some platforms, keep only their file arguments. For stable releases, omit `--prerelease` and follow the same draft, upload, review, and internal publication steps. See GitHub CLI documentation for [creating a release](https://cli.github.com/manual/gh_release_create), [uploading assets](https://cli.github.com/manual/gh_release_upload), and [editing a release](https://cli.github.com/manual/gh_release_edit).

This repository's GitHub Releases are internal archives for developers and release operators with repository access. Users download clients from the website or their organization's channel. Automatic updates continue to use the update channel and version-policy settings embedded during packaging; uploading release assets does not change those settings. Existing `upload:*` commands publish to upstream object storage. Use the `gh release` commands above for GitHub; COS upload credentials are not required for this workflow.

## Testing and verification

Select checks for the affected components. During development, run targeted checks; before pushing or deploying, run the corresponding regression suite. Root CI is configured to check repository structure, Python compilation on both platforms, management builds, and selected Java integration tests. Follow the desktop workspace guide for desktop checks.

```sh
# Repository layout, component references, and maintained documentation links
python3 scripts/checks/check-repository.py

# Python compilation; on Windows use .venv\Scripts\python.exe
.venv/bin/python -m compileall -q funnyworker scripts

# Full business service regression, including external reference-node HTTP scenarios
sh scripts/mvn.sh -Dfunnywork.http-scenarios=/path/to/reference-runtime/scripts verify

# Management production build
pnpm --dir apps/management build:prod

# Harness type checks and tests; install workspace dependencies first
pnpm --dir apps/desktop typecheck
pnpm --dir apps/desktop test
```

The Python HTTP scenarios in the full business regression use `computer_http_scenario.py`, `computer_worker_http_scenario.py`, and `connector_http_scenario.py` from a separate reference-node workspace. Select their directory with the system property above. These scripts are not runtime dependencies of the current desktop client. PostgreSQL-specific tests require `FUNNYWORK_TEST_POSTGRES_URL`, `FUNNYWORK_TEST_POSTGRES_USER`, and `FUNNYWORK_TEST_POSTGRES_PASSWORD` pointing to an isolated test database; they explicitly skip when these variables are unset.

To check centralized model-node configuration and Responses integration, use the reference-node workspace runtime and protocol acceptance scripts with the business service package built from this repository:

```sh
python3 scripts/checks/check-model-profile-flow.py --runtime-root /path/to/reference-runtime
```

Add `--scenario attachments` to check node discovery of task attachments, fixed-version reads, rejection of unauthorized revisions and tool calls after revocation, and preservation of output for review. The default `--scenario profile` checks centralized configuration.

This check covers protocol compatibility with an external reference node. For maintenance tasks executed by FunnyWork clients, use the client-specific checks. The reference-node check requires an installed Codex CLI, the reference workspace's `scripts/test-model-profile.py`, node runtime, and test APIs. It uses random local ports and a separate temporary database to check configuration reads, disable/recovery behavior, per-execution model updates, credential rotation, and artifact storage. It uses isolated data and simulated inference, stops temporary services, and removes temporary data when finished. Use `--server-jar` to select the business service package under test.

Some reference workspace versions read optional Host parameters that the receipt verification command does not define. If you encounter this compatibility issue, review and apply the [compatibility patch](scripts/checks/reference-worker-host-options.patch) in the reference workspace. It adds defaults for optional parameters while preserving explicit Host parent-process validation.

Detailed Harness development and verification steps are in the [development guide](apps/desktop/docs/development.md). Follow the [desktop contribution guide](apps/desktop/CONTRIBUTING.md) and applicable `AGENTS.md` files when modifying that workspace. The management build script runs type checks first and stops on failure. Run `pnpm --dir apps/management typecheck` for a separate check; `build:prod` alone performs only the production build.

### Enterprise model acceptance

For models, queues, usage accounting, and Harness client acceptance, see the [test plan](services/business/MODEL_UAT_PLAN.md) and [manual application acceptance scenarios](services/business/MODEL_UAT_MANUAL.md). Run `python3 scripts/checks/run-model-uat.py` for isolated regression. Browser scripts using real page components are described in the [UAT tool guide](apps/management/uat/model-middleware/README.en.md). Check installer flows and actual Ascend hardware capacity separately in the target environment.

### Windows installer checks

Windows service builds also check desktop device management, phone pairing, history synchronization, organization policies, remote sessions, result files, and model usage APIs. For checks against an installed application and configured backend, sign in using the same `DSH_HOME`, close the client, and run the command below. The check reads account allowance and conversation history, verifies menus and page reload, and creates and closes a phone pairing code, which requires an HTTPS backend and cross-device permissions. Reports and light/dark screenshots go to the ignored `.local/windows-desktop-check/` directory. The default checks cover accounts and synchronization; use `--model` as described below for real model replies, and verify uploads and policy publication in their corresponding scenarios.

```powershell
node scripts/checks/check-windows-desktop.cjs --executable E:\FunnyWork\funnywork.exe --commit <FULL_SOURCE_COMMIT_SHA>
```

Use `--home` for an existing signed-in desktop data directory. Install desktop dependencies in the source workspace to provide Playwright. The installer's source commit must match `--commit` so the check uses the intended application version. Adding `--model` creates an integration-test conversation, calls the account's authorized model, and checks its reply and recovery after reload. It consumes the account's model allowance and retains the conversation in account history.

## Project documentation

For desktop development, see the [development guide](apps/desktop/docs/development.md), [architecture](apps/desktop/docs/architecture.md), and [contribution guide](apps/desktop/CONTRIBUTING.md).

## Contributing

Report project issues or discuss changes through this repository's [Issues](https://github.com/GigaTommy/funnywork-public/issues), and submit code through pull requests. Include the operating system, source commit, reproduction steps, expected result, and sanitized error logs in an issue report.

Describe affected components, configuration changes, and verification results when submitting changes. Preserve copyright notices, license texts, and third-party notices when modifying upstream code, and distinguish FunnyWork customization issues from general upstream issues.

## Upstream projects and acknowledgments

FunnyWork customizes the following projects. The table identifies each source, its use in this repository, its accompanying copyright notice, and its license text. Each directory preserves the upstream license and third-party notices.

| Upstream project | Use in this repository | Accompanying copyright notice | License |
| --- | --- | --- | --- |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | `apps/desktop/`: Agent runtime, Host, plugins, and Electron desktop foundation | Copyright (c) 2026 DeepSeek | [Full MIT text](apps/desktop/LICENSE) |
| [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) | `services/identity/`: identity and management backend | Copyright (c) 2019 RuoYi-Vue-Plus | [Full MIT text](services/identity/LICENSE) |
| [plus-ui](https://github.com/JavaLionLi/plus-ui) | `apps/management/`: Vue 3 management frontend | Copyright (c) 2019 RuoYi-Vue-Plus | [Full MIT text](apps/management/LICENSE) |

Import baselines: DeepSeek Harness `dsh-v0.2.1-rc.2` / `687f282e8625c6b8d11ff58211a90524be2e4317`; RuoYi-Vue-Plus `5.X` / `815087e733eddd641dce8ee3bdb72f21e1b08e28`; plus-ui `5.X` / `d0d451967676707021b9857df529c395b27e90a7`. These directories contain FunnyWork customizations tracked in this repository's history. Upstream links identify code sources; this project maintains FunnyWork's features, releases, and support.

### Optional deployment dependencies

[Magpie](https://github.com/yetone/magpie) is an optional model gateway deployed as a separate container and called over HTTP by the business service. This repository maintains deployment configuration rather than Magpie source. The template pins the image digest for v0.1.1290; see the [Magpie deployment guide](deploy/magpie/README.en.md) for integration and credential setup. Copyright (c) 2026 yetone; Magpie retains its independent [MIT License](deploy/magpie/MAGPIE-LICENSE.txt).

### Attribution and distribution

When distributing source, installers, or other copies containing the upstream code or substantial portions of it, include the corresponding copyright notices, complete MIT license texts, and applicable third-party notices. README links and acknowledgments provide navigation; distributions must also carry the license texts. MIT permits commercial use, and its notice-preservation requirements continue to apply to upstream portions.

When describing this project's origins in product documentation or technical articles, you may use the following wording and retain the project links:

> FunnyWork's desktop client and Agent runtime are customized from DeepSeek Harness; its identity and management backend is customized from RuoYi-Vue-Plus; its management frontend is customized from plus-ui. Each upstream project's code is used under its MIT license, with copyright retained by the respective rights holders.

For academic or technical references to DeepSeek Harness, use the [upstream BibTeX entry](apps/desktop/README.md#citation):

```bibtex
@misc{deepseek-harness2026,
  title={DeepSeek Harness: Everything is a Plugin},
  author={DeepSeek-AI},
  year={2026},
  publisher={GitHub},
  howpublished={\url{https://github.com/deepseek-ai/deepseek-harness}},
}
```

The BibTeX entry and attribution wording support citation; distribution obligations are defined by the license texts. See the [third-party notices](THIRD_PARTY_NOTICES.md) and [Harness third-party notices](apps/desktop/THIRD_PARTY_NOTICES.md) for other dependencies, runtimes, and resources.

## License

FunnyWork-owned code and documentation that the project is entitled to license and that are not covered by separate license notices use a **source-available non-commercial license with a separate commercial license**. Personal learning, non-commercial teaching and academic research, and nonprofits' own non-commercial public-benefit activities may use, modify, and distribute the software non-commercially without a license fee under the [root LICENSE](LICENSE). Enterprise internal deployment, development or testing for enterprise business, SaaS / API services, commercial integration, and paid delivery require prior written commercial authorization. The LICENSE defines permitted free uses and commercial purposes.

A commercial license grants worldwide, non-exclusive, non-transferable commercial use rights within the agreed scope. A written order specifies the licensed entity, term, quantities, SaaS, distribution, and services. See the [commercial license terms](COMMERCIAL-LICENSE.txt). For purchases and inquiries, visit the [website](https://funnywork.funnypixelsapp.com) or contact [support@funnypixelsapp.com](mailto:support@funnypixelsapp.com).

This source-available license restricts commercial use. DeepSeek Harness, RuoYi, and other separately licensed components retain their original licenses. Their MIT licenses permit commercial use subject to their copyright, full-license, and notice requirements. Copies lawfully obtained under earlier MIT or other licenses remain governed by those licenses; the change does not retroactively revoke existing rights. File- or component-specific license notices take precedence for the portions they cover. The upstream directories' MIT licenses and license metadata are retained. See the [third-party notices](THIRD_PARTY_NOTICES.md) for scope.

The FunnyWork-owned `funnywork-desktop-controls` and `funnywork-desktop-capabilities` packages carry copies of the project license and require written authorization for commercial use. The independent licenses of Harness, RuoYi, plus-ui, and their upstream code remain unchanged.

See [source publication](deploy/PUBLIC_SOURCE.en.md) for source snapshots and regional entry configuration.
