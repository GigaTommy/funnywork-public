# 客户端发布与升级 / Client distribution

本文供发布、交付和运维人员使用，描述发布规则，不是当前安装包清单或客户版本说明。客户安装与使用见 [使用指南](../manuals/USER_GUIDE.md)；每个实际发布版本另按 [版本说明模板](RELEASE_NOTES_TEMPLATE.md) 编写变更说明。平台开放状态以已核实的签名清单和分发渠道为准。

This reference is for release, delivery and operations staff. It defines publication requirements, not the currently available builds or customer release notes. Installation guidance belongs in the user guide; each distributed version needs its own verified change summary.

下载入口为[官网](https://funnywork.funnypixelsapp.com/download/?lang=zh)。只有通过平台签名、安装和升级验收的安装包才能进入网站的签名下载清单。未完成的目标保留“准备中”，不发布未签名包或失效邀请。

Public downloads use the website above. Add a platform only after its signature, installation and upgrade checks pass. Unqualified packages and unusable invitations remain unavailable.

## Mac 与 Windows 更新

`DSH_DESKTOP_UPDATE_CHANNEL=nightly` 保留现有预发布通道；`latest` 为稳定通道，拒绝预发布版本。通道写入签名应用的 manifest 与 `app-update.yml`，不能根据版本号自动猜测。稳定通道二进制使用独立的 `bin/<target>/latest/` 路径；一次发布只更新所选通道，不覆盖另一通道。禁止自动降级，下载和安装仍分别由用户确认。

The signed application selects `nightly` or `latest` independently of its version. Stable builds reject prereleases and use a separate binary directory. Publishing updates only the selected channel. Downloads and installation require separate user actions; automatic downgrade remains disabled.

新构建将 `signedMetadataRequired: true` 写入完成记录。发布工具对最终改写过下载 URL 的 YAML 生成 Ed25519 签名信封，以 `<channel>[-mac].yml.signed.json` 上传。平台配置文件需提供 `DSH_DESKTOP_UPDATE_SIGNING_KEY_FILE`（受限、本机绝对路径）和 `DSH_DESKTOP_UPDATE_EXPIRES`（明确的 ISO 到期时间）。私钥与网站清单私钥、代码签名凭证分开保管，不进入 Git 或 Web 主机；缺少密钥、到期时间或密钥不匹配时，发布在上传前失败。签名信封在二进制之后、兼容的 YAML 清单之前上传。

New build records require signed metadata. Configure a restricted local `DSH_DESKTOP_UPDATE_SIGNING_KEY_FILE` and explicit `DSH_DESKTOP_UPDATE_EXPIRES`. Publication signs the final YAML after rewriting its artifact URLs, refuses missing or mismatched keys, and uploads binaries before signed metadata and legacy YAML. Keep update, website and platform signing keys separate and off Web hosts.

客户端从应用内置公钥验证原始清单内容、有效期（最长 90 天）、架构、通道、HTTPS 来源及文件摘要格式，拒绝跨域安装包和未签名回退。之后仍使用 electron-updater 的 SHA-512 校验及平台代码签名校验。客户端与官网分别校验清单；旧客户端在升级后使用新版验签能力。清单到期前需续签，版本比较阻止降级；较旧但仍有效的签名版本可能继续出现在未升级设备上，发布时需核对客户端实际获取的版本。

The installed public key authenticates the exact manifest, expiry (at most 90 days), architecture, channel and same-origin HTTPS artifact URLs. There is no unsigned fallback. SHA-512 and native platform signature verification remain enabled. Existing clients retain their original behavior until upgraded. Renew expiring metadata before its deadline; version comparison prevents downgrade. Older installations may still receive an older, validly signed release, so verify the version obtained by the installed client.

## 安装与发布顺序

1. 确认发布版本、干净的源码提交、目标系统和签名身份。 构建时优先复用已校验缓存，但安装目标架构依赖时允许从配置的包源补齐原生预编译包；强制离线安装可能静默省略这些可选依赖。Windows 需要 Windows 构建/验收机器及可用的 Authenticode 签名配置；Intel Mac 需核对 x64 原生依赖，Intel 目标分别完成 Rosetta 检查及 Intel 真机验收。
2. 生成安装包和更新 ZIP/EXE。Mac 验证 Developer ID、Team ID、公证票据与 Gatekeeper；Windows 验证发布者、完整 Authenticode 信任链与时间戳。
3. 使用独立应用与数据目录验证新安装，再从现有发布版升级，核对账号、历史、配置、任务和成果预览保留。检查错误密钥、篡改、过期、错误架构、通道串用和降级均被拒绝。
4. 先上传不可变版本文件并核对摘要，随后发布所选更新清单和官网签名下载清单。先完成一端验收再开放对应入口；保留旧通道、旧包和回退快照。
5. 对照发布前后的后台/客户端接口响应与 Nginx 业务路由；分别检查未认证响应和已登录业务流程。独立机器上的 `scripts/checks/check-public-downloads.mjs` 可检查下载入口，告警与执行计划需由运维配置。

Builds reuse verified cached packages while allowing missing native prebuilds to be fetched from the configured registry. Forced offline installation can silently omit optional native dependencies. Confirm the source/version and signing identity, qualify native installers, test clean installation and an upgrade from the existing release with isolated data, and verify rejection paths. Publish immutable binaries first, selected-channel metadata next, and website links last. Preserve prior feeds and rollback snapshots. Compare API and routing behavior before and after deployment, and distinguish unauthenticated health checks from logged-in business regression.

## Android 下载路由

APK 按版本保存在 `/dsh-desk/android/<版本>/FunnyWork-<版本>-android.apk`。配置通用下载前缀，避免为每次升级新增单文件白名单；仅开放符合命名规则且实际存在的 APK，禁止目录浏览和写入请求。安装包仍须先通过签名、非调试构建及完整摘要检查，再进入此目录和签名清单。

对已有站点，将配置路径替换为目标环境的实际路径，先预览更新脚本的差异：

```sh
python3 scripts/setup/update-site-download-routing.py --config /etc/nginx/sites-available/funnywork.conf --host funnywork.funnypixelsapp.com
```

核对后加上 `--apply --expect-sha256 EXPECTED_SHA256 --backup-dir BACKUP_DIRECTORY`。将 `EXPECTED_SHA256` 替换为已核对的当前配置摘要，`BACKUP_DIRECTORY` 替换为本次发布的绝对备份目录。官网域名使用 `funnywork.funnypixelsapp.com`，各环境配置由运维人员提供。脚本保留已有路由和服务器安全响应头，在配置校验通过后平滑重载；失败时恢复配置。已有的不一致前缀会被拒绝，需先检查。

部署后运行 `node scripts/checks/check-mobile-download-endpoints.mjs --full-android`，检查官网的附件响应头、分段下载及完整 APK 摘要，再核对旧版下载与业务 API。手机实机另行验证安装和升级流程。

Versioned APKs use a bounded download prefix rather than a new single-file rule for every release. Preview the routing helper before applying it with the reviewed configuration hash and an absolute backup directory. It preserves existing locations and server security headers, validates Nginx, reloads gracefully, and restores the configuration on failure. Verify attachment headers, byte ranges, complete APK hashes, old links and API behavior after deployment. Verify installation and upgrade on target physical devices as well.

## iOS TestFlight

使用有效且非仅限内部测试的构建创建外部组，填写审核联系人、专用审核账号和测试说明，提交 Apple Beta App Review。只有 Apple 批准、组内构建可供外部测试且公开链接启用后，才将真实的 `testflight.apple.com/join/…` 地址加入官网；发布前通过真实邀请检查外部测试用户可以安装。保留既有内部测试组，不提前终止旧构建。

External TestFlight distribution requires an eligible build, an external group, review contact details, a dedicated review account and Apple Beta App Review approval. Publish the actual invitation only after an external build is available and the public link is enabled. Verify installation with the actual external invitation before publication. Preserve existing internal groups and builds.

获批的 TestFlight 邀请与版本、构建号及 Apple 构建到期时间一起写入网站签名清单的 `testflight` 字段。仅允许 `https://testflight.apple.com/join/` 下的八位邀请；邀请到期后隐藏 iOS 入口，Mac 与 Android 下载继续可用。续签清单时重新核对 Apple 外部测试资格。

Approved invitations are included in the signed website catalog with their version, build and Apple build expiry. Only exact Apple TestFlight join URLs are allowed. An expired beta hides the iOS link without blocking desktop or Android downloads. Recheck external eligibility when renewing the catalog.

## 浏览器下载校验兼容

官网清单保留 Ed25519 签名，并增加 ECDSA P-256 / SHA-256 兼容签名。兼容签名同时绑定原始清单字节与 Ed25519 签名，两个公钥均随官网代码发布。浏览器只有在 Ed25519 算法不受支持时才使用兼容通道；原生验签返回失败时不回退。缺少全部 WebCrypto 能力时保持入口禁用，并提示更新浏览器。网络、兼容性与签名错误分别提示。客户端自动更新的验签协议保持不变。

生成官网清单时额外设置 `FUNNYWORK_RELEASE_COMPAT_SIGNING_KEY_FILE` 为本机受限的 P-256 私钥绝对路径；对应公钥为 `deploy/release-compat-public-key.pem`。私钥与回退副本存放在外接盘，不进入仓库、安装包或服务器。签名工具拒绝缺失、权限过宽和公钥不匹配的兼容密钥。

The website retains Ed25519 and adds a P-256 / SHA-256 signature bound to the same payload and original signature. Both public keys are pinned in the website code. Unsupported Ed25519 implementations use the compatibility path; a failed native verification never falls back. Configure the restricted local P-256 private key with `FUNNYWORK_RELEASE_COMPAT_SIGNING_KEY_FILE` when signing catalogs. Keep it and its backup off Git and web hosts. Desktop updater signatures are unchanged.

## Apple 上传线路 / Apple upload transport

S3 加速线路上传缓慢时，可在本机 `.env.macos` 设置 `DSH_DESKTOP_MACOS_NOTARIZATION_S3_ACCELERATION=false`，仅将公证文件上传改走 Apple 普通 S3 线路。默认保持 `true`；两种方式都需 Apple 接受、装订票据并通过签名与 Gatekeeper 检查。此配置不改变系统代理或 VPN。

Set `DSH_DESKTOP_MACOS_NOTARIZATION_S3_ACCELERATION=false` in the local `.env.macos` if Apple's accelerated upload stalls. The default remains `true`. Both routes retain Apple acceptance, stapling, signature and Gatekeeper checks. System proxies and VPN settings remain unchanged.
