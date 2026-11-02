# FunnyWork Android 开发

**简体中文** | [English](README.en.md)

Kotlin + Jetpack Compose 原生客户端，使用与 iOS 相同的账号、模型中间层、电脑任务及结果文件协议。使用者请阅读 [使用指南](../../manuals/USER_GUIDE.md)；本页供开发人员构建应用。

| 需要查阅 | 文档 |
| --- | --- |
| 本地模型、真实 Mac、头像及恢复用例 | [测试与联调](TESTING.md) |
| 正式包分发与升级要求 | [客户端发布与升级](../../deploy/CLIENT_RELEASES.md) |

最低 Android 10 / API 29，编译和目标 SDK 为 36。客户端通过前台刷新及回到前台后的恢复读取任务更新；设备连接使用同账号设备列表。版本与构建号由 [Gradle 配置](app/build.gradle.kts)维护。

## 功能与实现入口

| 功能 | 入口与实现 |
| --- | --- |
| 独立文字聊天、模型选择与历史管理 | 菜单“聊天”；[主界面](app/src/main/java/com/funnyworker/android/MainActivity.kt) |
| 设备和工作区选择、新建电脑任务 | 菜单“远程”“设备”；[设备界面](app/src/main/java/com/funnyworker/android/DeviceUi.kt) |
| 电脑任务附件、停止、批准与问答 | [远程操作界面](app/src/main/java/com/funnyworker/android/RemoteUi.kt)、[状态管理](app/src/main/java/com/funnyworker/android/AppViewModel.kt) |
| 固定版本成果、预览与审阅草稿 | [结果版本](app/src/main/java/com/funnyworker/android/ResultVersions.kt)、[差异审阅](app/src/main/java/com/funnyworker/android/ReviewDiffUi.kt) |

### 同步与恢复

头像支持系统选图、预览、取消和保存，账号资料定期刷新以同步多端更新。电脑任务提供阶段与未读提示、完整目录分页，以及标题／电脑名称和历史消息搜索。创建请求持久化后提交；结果未知时核对原命令，关闭页面后迟到的成功结果保留打开入口。

模型调用因未完成任务暂时预留资金时，由后端保留原票据排队。资金预留与额度耗尽分别提示，客户端不自动重放已开始的调用。计量规则见 [额度参考](../../services/business/MODEL_BUDGETS.md)。

电脑历史按服务端返回的 `nextSeq` 连续读取固定窗口，覆盖按字节截短的分页；新增记录留待下次刷新。账号或任务切换会取消旧读取。

手机聊天保留标题搜索、分组、重命名和归档；设置提供外观、额度与下载缓存管理。成果按固定版本查看，预览重试加入草稿，原文件不被覆盖。

## 构建

需要 JDK 17、Android SDK 36 与可用的 Gradle 依赖源。使用提交的 Wrapper，不依赖全局 Gradle。

```sh
sh scripts/build/build-android.sh assembleDebug testDebugUnitTest lintDebug
```

本机 HTTPS 联调可显式添加 `--dev-ca /绝对路径/开发CA.crt`。该 CA 仅对 debug 包的 localhost／127.0.0.1 生效，release 构建拒绝此选项；证书和生成配置保存在 `.local/agent/`，不得提交私钥。

Windows 从根目录执行 `scripts/build/build-android.ps1`，使用同等参数。构建入口显式指定 SDK、缓存、编译产物、临时目录与 debug 签名路径。本机外接卷存储要求见[配置与数据](../../CONTRIBUTING.md#配置与数据)。

本机前台命令可通过 `~/.codex/bin/with-external-storage --project /Volumes/SSD/Code/funnyworker -- sh scripts/build/build-android.sh assembleDebug` 执行，保留已指向外接卷的 `GRADLE_USER_HOME` 等环境。

输出位于根 `.local/agent/apps/android-build/app/outputs/`；debug 包标识 `com.funnyworker.android.debug`，不覆盖正式包。正式产物整理到 `.local/releases/android/<版本>/`，记录 APK 哈希、提交与签名指纹；密钥/SDK/缓存/产物不入库。

客户端接入 HTTPS，官方服务使用稳定账号身份，自建服务按服务身份隔离。会话和账号资料使用 Android Keystore 管理的密钥加密并排除系统备份；密码仅用于登录，模型供应商密钥留在服务端。品牌与图标遵循[设计规范](../../DESIGN.md)。

## 发布构建

正式包标识为 `com.funnyworker.android`，使用原发布密钥签名。通过受限本机文件配置 `FUNNYWORK_ANDROID_SIGNING_PROPERTIES`，字段为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；相对 keystore 路径以该属性文件所在目录为基准。缺少签名配置或误用调试密钥时，正式打包停止。凭证文件和密钥不提交 Git、不上传 Web 服务器。

配置好签名文件环境变量后执行：

```sh
sh scripts/build/build-android.sh assembleRelease testDebugUnitTest lintRelease
```

版本名称维护在 `app/build.gradle.kts`。发布 `versionCode` 必须大于上一公开包，可用 `FUNNYWORK_ANDROID_VERSION_CODE` 明确指定。`FUNNYWORK_ANDROID_TEST_BUILD_TYPE=release` 可将 instrumentation 指向 release 目标，默认仍为 debug；模拟器本地后端回归使用带开发 CA 的 debug 包，不能把开发 CA 带入正式包。正式 APK 还需验证签名指纹、非调试属性、最低系统和旧包覆盖安装，再进入官网签名下载清单。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。

区域入口由私有构建配置提供，公开构建默认使用国际官网；配置与升级条件见[公开源码发布](../../deploy/PUBLIC_SOURCE.md#配置区域入口)。
