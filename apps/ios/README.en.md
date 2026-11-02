# FunnyWork iOS development

[简体中文](README.md) | **English**

Native SwiftUI client requiring iOS 17 or later. Users should read the [user guide](../../manuals/USER_GUIDE.en.md); this page covers developer setup, builds, and distribution.

| Topic | Reference |
| --- | --- |
| Login, allowance, tasks, reading behavior, and compatibility | [Client behavior reference](CLIENT_REFERENCE.md) |
| Local regression, debugging, and verification scope | [Testing and debugging](TESTING.md) |
| Phone chat protocol and recovery | [Phone chat](MOBILE_CHAT.md) |
| Model catalogs, presets, and usage integration | [Model management adaptation](MODEL_ADAPTATION.md) |
| Release requirements and updates | [Client releases and upgrades](../../deploy/CLIENT_RELEASES.md) |

## Features and implementation

| Feature | Entry point and implementation |
| --- | --- |
| Independent chat, models, and history | **Chat**; [phone chat UI](Sources/MobileChatRootView.swift) |
| Computers, workspaces, and remote conversations | **Remote** and **Devices**; [new conversation](Sources/NewConversationView.swift), [device page](Sources/DeviceDirectoryView.swift) |
| QR pairing and notifications | [Device pairing](Sources/DeviceLinkView.swift), [notification settings](Sources/PushSettingsView.swift) |
| Files, fixed-version results, and review | [Result reading](Sources/ResultFileView.swift), [review](Sources/RemoteResultReview.swift) |
| Voice input and message reading | [Voice input](Sources/VoiceInputView.swift), [message reading](Sources/MessageReading.swift) |

See the [client reference](CLIENT_REFERENCE.md) for accounts, attachments, recovery, and platform requirements. Remote tasks require an online computer and permission for cross-device operations. Notifications require system authorization and server-side APNs configuration.

## Build

Use macOS, Xcode 26 or later with Swift 6.2+ and iOS Simulator, and XcodeGen. Run from the repository root:

```sh
sh scripts/build/build-ios.sh
open apps/ios/FunnyWork.xcodeproj
```

Message parsing uses Swift Markdown and Swift CMark 0.9.0, pinned in `project.yml`. The first build downloads dependencies. Their license texts are bundled with the app: [Swift Markdown](Sources/ThirdParty/SwiftMarkdown-LICENSE.txt) and [Swift CMark](Sources/ThirdParty/SwiftCMark-COPYING.txt).

The generated project is not tracked; `project.yml` is the sole project configuration source. `AppIcon` uses the 1024 × 1024 opaque brand portrait; the smaller login mark remains separate. Both are synchronized by `scripts/build/update-brand-assets.mjs`. Select the FunnyWork scheme and an iPhone simulator. For physical devices and distribution, configure your own signing team in local Xcode settings; keep teams, credentials, and provisioning profiles out of project source. App Store distribution requires store screenshots, a privacy policy, and release materials. Build iOS on macOS.

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .local/ios-build CODE_SIGNING_ALLOWED=NO test
```

Check physical-device compilation and resource packaging without signing or installing:

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -sdk iphoneos -destination 'generic/platform=iOS' -configuration Release \
  -derivedDataPath .local/ios-device-review CODE_SIGNING_ALLOWED=NO build
```

`Sources/PrivacyInfo.xcprivacy` declares required APIs currently used: `CA92.1` for application appearance settings, `C617.1` for cache file information and cleanup ordering within the app container, `3B52.1` for metadata such as file size after the user selects a file, and `35F9.1` for in-app pairing-code expiry calculations. System uptime and derived values are not sent to the server. See Apple's [required-reason API documentation](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) and [reason codes](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons). The manifest is bundled with app resources; maintain deployment data-handling information, the privacy policy, and App Store privacy labels when releasing.

### Development signing and physical devices

The build entry point defaults to an unsigned simulator app. Physical-device development builds retain full push entitlements and require an Apple Development certificate, a team, and a development provisioning profile covering `com.funnyworker.ios`. Wildcard profiles do not support this app's Push Notifications / `aps-environment` entitlement; validate the full client with the entitlement present.

```sh
# Use existing signing settings; stop if no suitable profile exists
sh scripts/build/build-ios.sh --device --team YOURTEAMID

# Use an installed profile with manual signing for the application target
sh scripts/build/build-ios.sh --device --team YOURTEAMID --profile 'funnywork iOS Development'

# Explicitly allow Xcode to register identifiers, enable capabilities, and create profiles
sh scripts/build/build-ios.sh --device --team YOURTEAMID --allow-provisioning-updates
```

Replace `YOURTEAMID` with your ten-character team ID, or set `FUNNYWORK_IOS_TEAM_ID`. Select a profile through `FUNNYWORK_IOS_PROFILE_NAME`. Manual profiles and `--allow-provisioning-updates` are mutually exclusive. Profile and manual-signing settings apply only to the app target; third-party Swift packages do not inherit them, and default simulator builds do not enable them. These commands build without installing, launching, or publishing. The development app is `.local/ios-device-build/Build/Products/Debug-iphoneos/FunnyWork.app`. Automatic signing requires Xcode to be signed into an Apple developer account with sufficient permissions and a team supporting Push Notifications. Development push uses `development`; distribution requires separate signing, profiles, and release materials. Notifications also require business-service APNs authentication; see the [push configuration template](../../deploy/mobile-push.env.example).

### Prepare TestFlight

Use TestFlight when a phone cannot connect to the Mac by USB or a reachable network. Development profiles are for development installation. TestFlight requires distribution signing, an App Store Connect app record, and an uploaded, processed build. Internal testers belong to the App Store Connect team; the first external testing build requires Beta review. See [Apple's TestFlight guide](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/).

First create an unsigned Release archive to inspect compilation and complete resources:

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -archivePath .local/ios-testflight/FunnyWork.xcarchive \
  -derivedDataPath .local/ios-device-review CODE_SIGNING_ALLOWED=NO archive
```

Use this unsigned archive for compilation and resource checks. For publication, create a signed Release archive with all entitlements, export using `app-store-connect`, and inspect the final IPA's actual signing entitlements, including retained `aps-environment`.

Release defaults to the `production` service push environment; Debug uses `development`, and the development build entry point explicitly selects development push. When archiving with development signing, match the archive's push entitlement to the development profile while retaining `PushEnvironment=production` in the Release `Info.plist`. After distribution export, confirm actual `aps-environment=production`, `get-task-allow=false`, the signature, and the embedded profile. Actual push also requires server-side APNs authentication and a real-device token. Before release, check app name, version/build, privacy manifest, brand resources, third-party licenses, and the absence of UI-test entry points in Release.

The archive's `ITSAppUsesNonExemptEncryption` is currently Boolean `false`. The client uses system URLSession HTTPS, Keychain/file protection, and CryptoKit SHA-256 verification. Current dependencies contain no standalone encryption library or custom data-encryption implementation. This setting follows [Apple's encryption information guidance](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations). Reassess it when changing encryption code or dependencies.

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.

Regional entries use private build configuration; public builds default to the international website. See [source publication](../../deploy/PUBLIC_SOURCE.en.md#configuring-a-regional-entry) for configuration and upgrade conditions.
