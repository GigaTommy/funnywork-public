# FunnyWork Android development

[简体中文](README.md) | **English**

Native Kotlin and Jetpack Compose client using the same account, model middleware, desktop-task, and result-file protocols as iOS. Users should read the [user guide](../../manuals/USER_GUIDE.en.md); this page covers application builds.

| Topic | Reference |
| --- | --- |
| Local models, real Mac integration, avatars, and recovery cases | [Testing and integration](TESTING.md) |
| Release distribution and upgrades | [Client releases and upgrades](../../deploy/CLIENT_RELEASES.md) |

Minimum Android 10 / API 29; compile and target SDK 36. The client reads task updates through foreground refresh and recovery when returning to the foreground. Select computers through the same-account device list. Versions and build numbers are maintained in [Gradle configuration](app/build.gradle.kts).

## Features and implementation

| Feature | Entry point and implementation |
| --- | --- |
| Independent text chat, model selection, and history | **Chat**; [main UI](app/src/main/java/com/funnyworker/android/MainActivity.kt) |
| Device/workspace selection and desktop-task creation | **Remote** and **Devices**; [device UI](app/src/main/java/com/funnyworker/android/DeviceUi.kt) |
| Desktop-task attachments, stopping, approvals, and questions | [Remote UI](app/src/main/java/com/funnyworker/android/RemoteUi.kt), [state management](app/src/main/java/com/funnyworker/android/AppViewModel.kt) |
| Fixed-version results, previews, and review drafts | [Result versions](app/src/main/java/com/funnyworker/android/ResultVersions.kt), [diff review](app/src/main/java/com/funnyworker/android/ReviewDiffUi.kt) |

### Synchronization and recovery

Avatars support system image selection, preview, cancellation, and saving. Periodic account refresh synchronizes changes across devices. Desktop tasks provide stage and unread indicators, complete directory pagination, title/computer-name searches, and message searches. Creation requests are persisted before submission. Reconcile the original command when a result is unknown; a late success retains an opening control after leaving the page.

When in-flight work temporarily reserves funds, the backend keeps the original ticket queued. Reserved funds and exhausted allowance have distinct messages. The client does not automatically replay calls that have started. See [allowance accounting](../../services/business/MODEL_BUDGETS.md).

Desktop history reads fixed windows continuously using the server's `nextSeq`, including byte-truncated pages. New records wait for the next refresh. Switching accounts or tasks cancels older reads.

Phone chat supports title search, grouping, renaming, and archiving. Settings provide appearance, allowance, and download-cache controls. Results are read at fixed versions; preview retries enter the draft and retain the original file.

## Build

Prepare JDK 17, Android SDK 36, and a reachable Gradle dependency source. Use the checked-in Wrapper instead of a global Gradle installation.

```sh
sh scripts/build/build-android.sh assembleDebug testDebugUnitTest lintDebug
```

For local HTTPS integration, explicitly add `--dev-ca /absolute/path/development-CA.crt`. The CA applies only to localhost / 127.0.0.1 in debug packages; release builds reject it. Certificates and generated settings go under `.local/agent/`; keep private keys out of Git.

On Windows, run `scripts/build/build-android.ps1` from the root with equivalent parameters. Build entry points explicitly select SDK, cache, output, temporary, and debug-signing paths. See [configuration and data](../../CONTRIBUTING.md#配置与数据) for this machine's external-volume requirements.

Run local foreground commands through `~/.codex/bin/with-external-storage --project /Volumes/SSD/Code/funnyworker -- sh scripts/build/build-android.sh assembleDebug`, retaining variables such as `GRADLE_USER_HOME` when already pointed at the external volume.

Output is under the repository root's `.local/agent/apps/android-build/app/outputs/`. The debug identifier is `com.funnyworker.android.debug`, separate from the release app. Collect release artifacts under `.local/releases/android/<version>/`, recording APK hash, source commit, and signing fingerprint. Keep keys, SDKs, caches, and outputs out of Git.

Clients use HTTPS. Official services use a stable account identity; self-hosted data is isolated by service identity. Account and session data is encrypted with Android Keystore-managed keys and excluded from system backups. Passwords are used for login; model-provider secrets stay on the server. Follow the [design guidelines](../../DESIGN.md) for branding and icons.

## Release builds

The release identifier is `com.funnyworker.android`; use the existing release signing key. Set `FUNNYWORK_ANDROID_SIGNING_PROPERTIES` to a restricted local file containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. Relative keystore paths resolve from that file's directory. Release packaging stops for missing signing settings or a debug key. Keep credential files and keys out of Git and web servers.

After configuring the signing-file environment variable, run:

```sh
sh scripts/build/build-android.sh assembleRelease testDebugUnitTest lintRelease
```

Maintain version names in `app/build.gradle.kts`. Release `versionCode` must exceed the previous public package; set it explicitly with `FUNNYWORK_ANDROID_VERSION_CODE`. Set `FUNNYWORK_ANDROID_TEST_BUILD_TYPE=release` to target release instrumentation; debug is the default. Local backend emulator regression uses a debug package with the development CA; exclude that CA from release packages. Verify signing fingerprint, non-debuggable state, minimum OS, and installation over the previous package before adding the APK to the website's signed download manifest.

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.

Regional entries use private build configuration; public builds default to the international website. See [source publication](../../deploy/PUBLIC_SOURCE.en.md#configuring-a-regional-entry) for configuration and upgrade conditions.
