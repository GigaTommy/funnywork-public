# FunnyWork iOS 开发

**简体中文** | [English](README.en.md)

原生 SwiftUI 客户端，最低 iOS 17。使用者请阅读 [使用指南](../../manuals/USER_GUIDE.md)；本页供开发人员准备环境、构建与分发。

| 需要查阅 | 文档 |
| --- | --- |
| 登录、额度、任务与阅读行为及兼容边界 | [客户端行为参考](CLIENT_REFERENCE.md) |
| 本地回归、调试入口与验证范围 | [测试与调试](TESTING.md) |
| 手机聊天协议与恢复规则 | [手机聊天](MOBILE_CHAT.md) |
| 模型目录、预设与计量接入 | [模型管理适配](MODEL_ADAPTATION.md) |
| 对外发布条件与更新维护 | [客户端发布与升级](../../deploy/CLIENT_RELEASES.md) |

## 功能与实现入口

| 功能 | 入口与实现 |
| --- | --- |
| 独立聊天、模型和历史 | 菜单“聊天”；[手机聊天界面](Sources/MobileChatRootView.swift) |
| 电脑、工作区与远程对话 | 菜单“远程”“设备”；[新建对话](Sources/NewConversationView.swift)、[设备页](Sources/DeviceDirectoryView.swift) |
| 扫码连接、提醒配置 | [连接设备](Sources/DeviceLinkView.swift)、[提醒设置](Sources/PushSettingsView.swift) |
| 文件、固定版本成果和审阅 | [成果阅读](Sources/ResultFileView.swift)、[审阅](Sources/RemoteResultReview.swift) |
| 语音输入与消息阅读 | [语音输入](Sources/VoiceInputView.swift)、[消息阅读](Sources/MessageReading.swift) |

账号、附件、恢复规则和平台条件见[客户端行为参考](CLIENT_REFERENCE.md)。远程任务要求电脑在线并允许跨设备操作；提醒需要系统通知授权和服务端 APNs 配置。

## 构建

需要 macOS、Xcode 26 或更新版本（Swift 6.2+，含 iOS Simulator）与 XcodeGen。从仓库根目录运行：

```sh
sh scripts/build/build-ios.sh
open apps/ios/FunnyWork.xcodeproj
```

消息解析采用 Swift Markdown 与 Swift CMark 0.9.0，版本固定在 `project.yml`，首次构建需要下载依赖。许可原文随应用打包，见 [Swift Markdown](Sources/ThirdParty/SwiftMarkdown-LICENSE.txt) 和 [Swift CMark](Sources/ThirdParty/SwiftCMark-COPYING.txt)。

生成的工程不入库，`project.yml` 为唯一工程配置来源。`AppIcon` 使用 1024 × 1024 不透明品牌肖像，登录页小标记保持独立；两者随 `scripts/build/update-brand-assets.mjs` 同步更新。选择 FunnyWork scheme 与 iPhone 模拟器运行。真机运行与分发需要在本机 Xcode 中配置自己的签名团队，团队、凭证和描述文件不写入工程源码。App Store 分发需准备商店截图、隐私政策及发布资料。iOS 构建在 macOS 上执行。

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .local/ios-build CODE_SIGNING_ALLOWED=NO test
```

真机目标也可以在不签名、不安装的情况下检查编译与资源打包：

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -sdk iphoneos -destination 'generic/platform=iOS' -configuration Release \
  -derivedDataPath .local/ios-device-review CODE_SIGNING_ALLOWED=NO build
```

`Sources/PrivacyInfo.xcprivacy` 声明当前实际使用的必要 API：`CA92.1` 用于本应用的外观设置，`C617.1` 用于应用容器内缓存文件信息与清理排序，`3B52.1` 用于用户主动选择文件后的大小等信息读取，`35F9.1` 用于连接码在应用内的剩余时间计算；系统启动时间及其派生值不发送到服务端。依据见 [Apple 必要 API 用途说明](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api)及[用途代码](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons)。该清单随应用资源打包；发布时同时维护部署的数据处理说明、隐私政策和 App Store 隐私标签。

### 开发签名与真机

构建入口默认生成未签名模拟器应用。真机开发构建保留完整推送权限，需要 Apple Development 证书、开发团队及覆盖 `com.funnyworker.ios` 的开发描述文件。通配符描述文件不支持该应用的 Push Notifications／`aps-environment` 权限；不要通过移除权限来代替完整客户端验收。

```sh
# 使用已有签名配置；没有合适描述文件时停止，不修改开发者账号。
sh scripts/build/build-ios.sh --device --team YOURTEAMID

# 使用已下载并安装的描述文件，明确应用目标的手动签名配置。
sh scripts/build/build-ios.sh --device --team YOURTEAMID --profile 'funnywork iOS Development'

# 明确允许 Xcode 在开发者账号注册应用标识、启用能力及生成描述文件后使用。
sh scripts/build/build-ios.sh --device --team YOURTEAMID --allow-provisioning-updates
```

将 `YOURTEAMID` 替换为自己的十位团队 ID，也可通过 `FUNNYWORK_IOS_TEAM_ID` 指定。描述文件名称可通过 `FUNNYWORK_IOS_PROFILE_NAME` 指定；手动描述文件与 `--allow-provisioning-updates` 不能同时使用。描述文件与手动签名模式仅绑定应用目标，第三方 Swift 包不继承描述文件要求；默认模拟器构建不启用这些手动签名设置。上述构建不安装、不启动或发布应用；开发包位于 `.local/ios-device-build/Build/Products/Debug-iphoneos/FunnyWork.app`。自动签名需要 Xcode 已登录有相应权限的 Apple 开发者账号，并且团队允许 Push Notifications。开发推送使用 `development` 环境；正式分发仍需单独准备签名、描述文件和发布资料。实际提醒还需要配置业务服务 APNs 认证，见 [推送配置模板](../../deploy/mobile-push.env.example)。

### TestFlight 准备

手机无法通过 USB 或可互通网络连接 Mac 时，可以通过 TestFlight 安装测试。开发描述文件只用于开发安装，TestFlight 需要分发签名、App Store Connect 应用记录及已上传并处理完成的构建。内部测试成员需属于该 App Store Connect 团队；外部测试首次构建需要 Beta 审核，规则见 [Apple TestFlight 说明](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)。

可先生成未签名 Release 归档，检查编译及完整资源：

```sh
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -archivePath .local/ios-testflight/FunnyWork.xcarchive \
  -derivedDataPath .local/ios-device-review CODE_SIGNING_ALLOWED=NO archive
```

上述未签名归档用于编译与资源检查。发布时生成带完整权限的签名 Release 归档，再以 `app-store-connect` 方式导出，检查最终 IPA 的实际签名权限，确保 `aps-environment` 随导出保留。

Release 默认使用 `production` 服务推送环境，Debug 使用 `development`；开发构建入口明确采用开发推送环境。如果归档阶段使用开发签名，应让归档签名的推送权限匹配开发描述文件，同时保留 Release `Info.plist` 的 `PushEnvironment=production`；分发导出后必须确认实际 `aps-environment=production`、`get-task-allow=false`，并验证签名与嵌入描述文件。实际推送还需服务端 APNs 认证及真实设备令牌。发布前核对应用名称、版本／构建号、隐私清单、品牌资源和第三方许可，并确认 Release 不包含界面测试入口。

归档的 `ITSAppUsesNonExemptEncryption` 当前为 Boolean `false`。iOS 客户端使用系统 URLSession HTTPS、钥匙串／文件保护及 CryptoKit SHA-256 校验，当前依赖不包含独立加密库，也没有自定义数据加密实现；该设置依据 [Apple 加密信息说明](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations)。更改客户端加密实现或依赖时，应重新核对这一字段。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。

区域入口由私有构建配置提供，公开构建默认使用国际官网；配置与升级条件见[公开源码发布](../../deploy/PUBLIC_SOURCE.md#配置区域入口)。
