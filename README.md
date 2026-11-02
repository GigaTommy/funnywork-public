# FunnyWork

**简体中文** | [English](README.en.md)

FunnyWork 是用于资料整理、数据分析、文档生成和多端协作的 AI 工作助手。电脑端在工作区内处理文件、调用工具并操作浏览器或电脑；iOS 和 Android 提供独立文字聊天，也可连接同账号电脑，发起任务、处理审批、审阅回复并下载成果。企业管理端统一维护成员授权、模型、额度、设备和组织策略。

本仓库包含客户端、管理端和配套服务源码，供开发、交付与运维人员使用。客户通过官网或企业分发入口安装应用，日常操作见[使用指南](manuals/USER_GUIDE.md)。

## 核心功能

| 功能 | 典型用途 | 使用条件与入口 |
| --- | --- | --- |
| 电脑任务 | 整理表格、分析数据、生成图表和文档 | 在电脑打开工作区，选择模型及所需工具 |
| 浏览器与电脑操作 | 读取网页、执行界面操作 | 安装兼容浏览器；电脑操作需对应平台提供方及系统权限，见[操作配置](#浏览器与电脑操作) |
| 手机独立聊天 | 问答、讨论思路、管理聊天历史 | 手机菜单“聊天”，配置可用模型和额度 |
| 手机远程协作 | 选择电脑和工作区、发送附件、处理批准和问答、停止任务 | 手机菜单“远程”，电脑在线且组织允许跨设备访问和远程控制 |
| 历史与成果 | 搜索记录、下载固定版本文件、预览和提交审阅意见 | 已同步内容可联网读取，已缓存内容可离线阅读 |
| 企业管理 | 开通成员、发布模型与策略、配置容量和预算、管理设备与历史权限 | 管理端功能权限、业务角色及区域授权 |
| 业务执行 | 仓库问题维护、执行资源配置、结构化年度经营摘要 | 配置对应项目、资源授权与执行用途，见[业务服务索引](services/business/README.md) |

客户端与服务的关系：电脑执行工作区任务，手机通过业务服务读取同步记录和提交指令；业务服务接入身份服务，提供模型中间层、权限校验和业务调度；管理前端配置这些资源。电脑离线时，手机仍可独立聊天和读取服务端已保存的记录、文件。

## 平台与使用条件

| 客户端 | 源码与构建入口 | 平台条件 |
| --- | --- | --- |
| macOS / Windows | [桌面定制说明](apps/desktop/FUNNYWORKER.md)、[打包与分发](#桌面打包与分发) | 构建目标为 macOS ARM64、macOS x64、Windows x64 |
| iOS | [iOS 开发](apps/ios/README.md) | iOS 17+；扫码连接及提醒设置由 iOS 提供，提醒需服务端 Apple 推送配置 |
| Android | [Android 开发](apps/android/README.md) | Android 10 / API 29+；通过设备列表连接电脑，前台刷新获取任务更新 |
| Linux x64 | [本地预览构建](apps/desktop/apps/desktop/README.md#linux-x64-local-prerelease) | glibc 图形环境及已解锁密钥环；提供浏览器操作，采用手动安装与升级 |

[官网](https://funnywork.funnypixelsapp.com/download/?lang=zh)提供已开放版本及 iOS TestFlight 入口；版本变化见[手册与更新索引](manuals/README.md)。源码功能与发布版本分别维护，发布流程见[客户端发布与升级](deploy/CLIENT_RELEASES.md)。

## 按读者选择入口

| 读者 | 入口 |
| --- | --- |
| 员工与体验用户 | [使用指南](manuals/USER_GUIDE.md) |
| 客户管理员 | [管理指南](manuals/ADMIN_GUIDE.md) |
| 开发人员 | 本文、[贡献指南](CONTRIBUTING.md)及各组件 README |
| 交付与运维人员 | [自建服务部署](deploy/SERVER_DEPLOYMENT.md)、[客户端发布与升级](deploy/CLIENT_RELEASES.md) |

维护任务、执行资源和年报等业务模块的协议与研发资料见 [业务服务文档索引](services/business/README.md)。源码按[项目许可](LICENSE)提供，客户通过官网或企业分发入口安装应用。

## 目录结构

协作流程见 [贡献指南](CONTRIBUTING.md)，界面与品牌约定见 [设计规范](DESIGN.md)。桌面与管理前端位于 `apps/`，Java 服务位于 `services/`，共享业务组件位于 `packages/`。

| 目录 | 用途 |
| --- | --- |
| [`apps/ios/`](apps/ios) | 原生 SwiftUI iOS 客户端，独立手机聊天，并监控、交互电脑工作任务；构建见 [iOS 说明](apps/ios/README.md) |
| [`apps/android/`](apps/android) | Kotlin / Compose 原生安卓客户端；构建、签名和联调见 [Android 说明](apps/android/README.md) |
| [`apps/desktop/`](apps/desktop) | 定制的 DeepSeek Harness 工作区，包含 Electron 桌面、Host、插件及运行时 |
| [`funnyworker/`](funnyworker) | Python 桌面启动器、设备模型配置与原型服务 |
| [`services/business/`](services/business) | Spring Boot 业务服务、管理页面入口及若依接口代理 |
| [`apps/management/`](apps/management) | 基于 plus-ui 的 Vue 3 管理端，默认管理页面的构建来源 |
| [`services/identity/`](services/identity) | 纳入仓库的 RuoYi-Vue-Plus 后端源码及项目定制 |
| [`packages/management-react/`](packages/management-react) | 保留的 React 管理端源码及业务组件，其业务组件仍由 Vue 管理端引用 |
| [`scripts/`](scripts) | 环境准备、构建、启动和联调检查脚本 |

桌面基于 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)，业务服务使用 Spring Boot，管理端与身份服务分别基于 plus-ui 和 RuoYi-Vue-Plus。对外名称为 FunnyWork；仓库标识、Python 导入名和已有应用标识保留 `funnyworker`。本地运行数据和生成配置位于 `.local/`。

## 开发环境

根据需要开发的部分准备依赖。仅连接已有服务的桌面开发者，无需安装 Java、PostgreSQL 或 Redis。

| 组件 | 环境要求 |
| --- | --- |
| Python 启动器与配置工具 | Python 3.10+，仓库根目录的 `.venv` |
| Harness / Electron 桌面 | Node.js 22.19+（22 系列）或 24+，pnpm 11.28.5（以 `apps/desktop/package.json` 为准；管理端沿用自身固定版本） |
| iOS App | macOS、Xcode 26+（Swift 6.2+，含 iOS Simulator）、XcodeGen；最低运行版本 iOS 17 |
| Android App | JDK 17、Android SDK 36；最低运行版本 Android 10 / API 29，使用仓库内 Gradle Wrapper |
| Java 服务 | JDK 17；仓库脚本准备 Maven 3.9.9 |
| 管理前端 | Node.js 20.19+、pnpm 11.7.0；使用 `apps/management/package.json` 指定版本 |
| macOS 本地后端 | PostgreSQL 16、Redis；PostgreSQL 默认路径为 `/opt/homebrew/opt/postgresql@16/bin`，可用 `FUNNYWORKER_PG_BIN` 覆盖 |
| Windows 本地后端 | Docker Desktop，运行 Linux 容器引擎；脚本通过 Compose 启动 PostgreSQL 16 和 Redis |

### 选择开发路径

- 只修改桌面或手机客户端：准备对应工具链，连接已有的 HTTPS 服务；桌面启动见[本地开发](#本地开发)，手机构建见各端 README。
- 修改后端或管理端：先按下文启动本地服务，再使用本地管理账号开通成员、模型和额度。
- 构建安装包：使用[桌面打包与分发](#桌面打包与分发)及手机端构建指南，准备独立签名与发布配置。

## 获取源码

```sh
git clone https://github.com/GigaTommy/funnywork-public.git funnyworker
cd funnyworker
```

Harness 与若依前后端源码已包含在仓库中，无需另行克隆上游项目。安装依赖时保留各工作区的锁文件，并使用 `--frozen-lockfile`。

## 本地开发

以下命令均从仓库根目录执行。首次构建需要下载依赖。

### macOS：启动本地服务

准备 Python 环境：

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

准备本地管理环境与 Maven，然后构建并启动：

```sh
.venv/bin/python scripts/prepare-ruoyi.py
sh scripts/mvn.sh -version
sh scripts/build-server.command
sh scripts/start.command
```

`prepare-ruoyi.py` 创建独立的本地 PostgreSQL 数据目录、Redis 配置及管理账号。`build-server.command` 构建管理前端、若依后端与业务服务，并运行业务服务的 Maven 验证。启动脚本保持前台运行。

### Windows：启动本地服务

先安装 JDK 17、Node.js、pnpm，并启动 Docker Desktop 的 Linux 容器引擎，然后执行：

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-dev-windows.txt
.\scripts\start.ps1 -Build
```

该脚本准备本地数据库与 Redis、构建服务并启动两个 Java 进程。后续未修改源码时可省略 `-Build`；单独构建使用 `scripts/build-server.ps1`。重新构建前请停止运行中的服务，避免 Windows 锁定 JAR 文件。

Windows 构建脚本运行指定的 Java 集成测试。运行完整 `mvn verify` 时，旧节点联调测试还需要外部 `funnywork` 脚本及 POSIX 运行时；可通过 Maven 参数 `-Dfunnywork.http-scenarios=/绝对路径/funnywork/scripts` 指定脚本目录。

Windows 安装包的登录、历史、手机连接及真实模型检查见[Windows 安装包检查](#windows-安装包检查)。

### 访问管理端

服务启动后打开 [本地管理端](http://127.0.0.1:8765/admin/index.html)。

| 服务 | 默认地址 |
| --- | --- |
| 业务服务与管理页面入口 | `127.0.0.1:8765` |
| 若依管理服务 | `127.0.0.1:8767`，通过入口服务的 `/ruoyi/` 代理访问 |
| 管理 PostgreSQL | `127.0.0.1:16432` |
| 管理 Redis | `127.0.0.1:16379` |

本地管理用户名为 `admin`，随机密码保存在 `.local/ruoyi/admin-login.json`。重复准备环境会保留已有凭证和数据。开发用成员需按[管理员指南](manuals/ADMIN_GUIDE.md#开通一名成员)配置桌面使用权限、业务授权、模型与额度。完成后用成员账号登录桌面，发送简短请求并核对回复；手机登录同一账号后检查设备及工作区。

### 启动桌面客户端

桌面启动前需要可访问的管理服务及有效账号。登录后的配置获取与续期见 [客户端连接与模型配置](#客户端连接与模型配置)。

macOS：

```sh
pnpm --dir apps/desktop install --frozen-lockfile
./scripts/start-desktop.command --build --server http://127.0.0.1:8765
```

Windows（先按前文创建 `.venv` 并安装 Python 依赖）：

```powershell
pnpm --dir apps/desktop install --frozen-lockfile
.\scripts\start-desktop.ps1 -Build -Server http://127.0.0.1:8765
```

Windows 的 `-Build` 会先安装 Electron 开发运行时。首次构建后，未修改桌面源码时可省略 `--build` / `-Build`。使用 `--check` / `-Check` 可验证服务地址并准备桌面配置，不启动 Electron。

Linux x64（glibc，本地预览）：在已解锁桌面密钥环的图形会话中运行。Ubuntu / Debian 先安装 `build-essential musl-tools bubblewrap fonts-dejavu-core python3-venv`，其他系统按[Linux 构建要求](apps/desktop/apps/desktop/packaging/README.md#linux-x64-local-prerelease)准备等效依赖，并安装前文要求的 Node.js 和 pnpm。

```sh
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
pnpm --dir apps/desktop install --frozen-lockfile
pnpm --dir apps/desktop --filter @deepseek-ai/dsh-desktop exec install-electron
./scripts/start-desktop.sh --build --server https://funnywork.funnypixelsapp.com
```

Linux 入口复用账号配置及 `.local/desktop/` 数据目录，支持 `--check` 和 `--server`；后续未修改源码时可省略 `--build`。不要以 root 启动客户端或关闭 Chromium 沙箱；若发行版限制用户命名空间，需先配置适用于该应用路径的系统策略。

连接远程服务时，将地址替换为实际的 `https://...` 管理服务根地址。当前客户端仅允许本机开发入口 `http://127.0.0.1:8765` 使用 HTTP。服务端部署和客户端构建可分别进行，客户端只需能访问所配置的服务地址。 对话归档、远端交互及附件传输的接口、执行归属和容量配置见[桌面同步协议](services/business/DESKTOP_SYNC.md)。

### iOS App 开发

iOS 客户端使用 SwiftUI，包含手机独立聊天、电脑任务、附件与成果阅读、代码审阅、扫码连接和提醒设置。源码位于 `apps/ios/Sources/`，工程配置由 `apps/ios/project.yml` 维护。

在 macOS 安装 Xcode 26 或更新版本及 XcodeGen，然后从仓库根目录执行：

```sh
sh scripts/build/build-ios.sh
open apps/ios/FunnyWork.xcodeproj
```

构建脚本生成 Xcode 工程并构建模拟器应用，产物位于 `.local/ios-build/`。在 Xcode 中选择 FunnyWork scheme 和 iPhone 或 iPad 模拟器运行，使用已开通账号登录。电脑任务联调时，让桌面客户端登录同一服务和账号，并打开工作区。

真机开发需要 Apple 开发团队及匹配的签名配置；构建参数、测试命令、APNs 和 TestFlight 分发见 [iOS 开发指南](apps/ios/README.md)，账号与电脑协作流程见[使用指南](manuals/USER_GUIDE.md)。

### Android App 开发

Android 客户端使用 Kotlin 和 Jetpack Compose，包含手机独立聊天、设备与工作区选择、电脑任务交互、附件、成果文件和审阅意见。源码位于 `apps/android/app/src/main/`。

准备 JDK 17 和 Android SDK 36，从仓库根目录构建调试包：

```sh
sh scripts/build/build-android.sh assembleDebug
```

APK 位于 `.local/agent/apps/android-build/app/outputs/`。安装到 Android 模拟器或设备后，使用已开通账号登录；电脑任务通过“远程”选择同账号电脑和工作区。单元测试、Lint、开发证书及正式签名配置见 [Android 开发指南](apps/android/README.md)。

### 浏览器与电脑操作

桌面默认配置包含 FunnyWork Desktop Controls 能力组合，使用支持工具调用的文本模型即可读取网页结构和系统辅助功能树。浏览器操作使用独立、可见且按会话隔离的 Chromium 窗口；右侧浏览器继续用于手动浏览。客户端查找本机已有的 Chrome、Edge、Chromium 或 Brave，也可在能力组合配置中指定可执行文件，不会在任务期间自动安装浏览器。登录网站时在受控窗口中手动登录。

macOS 14 及以上使用 DSH 原生辅助功能提供方，Windows 使用 Cua Driver 文本模式。默认不发送截图，不依赖模型图片输入；Mac 需要为运行客户端授权“辅助功能”，仅截图功能需要屏幕录制权限。Linux 当前只提供浏览器操作，电脑控制会返回未支持状态。缺少浏览器、原生执行器或权限时，状态工具说明原因，不阻止普通聊天。

安装包包含操作插件及目标架构的 Mac 原生 helper；helper 在打包时编译，终端用户无需 Xcode。原有默认桌面配置会一次性补入能力组合；自定义组合、主动禁用及故障恢复配置保持原选择。能力开关、浏览器选择与限制见 [Desktop Controls](apps/desktop/packages/bundle/funnywork-desktop-capabilities/README.zh.md)。

维护者可用[生产包验收脚本](scripts/checks/check-browser-computer-packaged.mjs)验证 Mac `.app` 中的默认配置、真实工具循环及原生程序。脚本接受 `--app`、全新隔离目录 `--home` 和 `--report`；`--fixture-app` 指定用于验收的 AX 测试应用，`--gui` 另验正常登录窗。验收使用本机模拟文本模型和测试页面，不提交账号凭据；操作权限的报告会区分测试启动链与用户首次启动的客户端。测试目录、报告及临时路径必须位于外接卷，`--home` 必须位于本工作区 `.local/`。隔离 GUI 日志可通过绝对路径 `DSH_DESKTOP_LOG_DIR` 指定。

## 配置与部署

仓库保留无真实凭证的 `.example` 配置模板。需要单独准备模板时执行：

```sh
python3 scripts/prepare-config-templates.py
```

该命令只创建缺失的本地配置，不覆盖已有文件。管理环境准备及前端构建脚本也会执行这一步。

- 管理端运行配置与账号凭证由准备脚本写入 `.local/ruoyi/`。
- `services/business/src/main/resources/application.yml` 通过环境变量引用配置，可随源码提交。
- 本地脚本采用开发配置。Windows 启动脚本启用 `FUNNYWORK_LOCAL_MANAGEMENT_BOOTSTRAP`，用于受限的本机管理员身份初始化；正式部署不要启用。

远程服务通过 HTTPS 提供客户端接入，数据库和 Redis 保持在服务端私有网络中。服务组成、配置变量、身份服务连接、代理限制及升级回退见[自建服务部署](deploy/SERVER_DEPLOYMENT.md)，配置示例见 [Nginx 模板](deploy/nginx/funnyworker.conf.example)。

官网地址为 `https://funnywork.funnypixelsapp.com`，静态资源位于 `designs/funnywork-site/`。部分区域通过当地云服务跳转接入，客户端自动选择可用连接。企业自建服务使用独立 HTTPS 地址。部署流程见[官网部署](deploy/nginx/FUNNYWORK_COM.md)和[区域接入](deploy/nginx/FUNNYWORK_CN.md)。

企业模型中间层的容量池、准入队列、协议透传、用量口径与客户端接入边界，见[模型中间层说明](services/business/MODEL_MIDDLEWARE.md)。公网开放前先配置[模型限额与全站预算](services/business/MODEL_BUDGETS.md)：推广赠送默认关闭，可配置新用户一次性赠送或每日登录领取；旧共享密钥下发已关闭。

桌面组织策略的强制限制、默认设置、发布版本和回执协议，见[组织策略说明](services/business/DESKTOP_POLICY.md)。

成员桌面设备的查询、范围权限与撤销语义见 [成员设备管理](services/business/MANAGED_DEVICES.md)。

统一身份平台的用户名密码 API 认证、用户初始化与更新同步，见 [统一身份接入说明](services/identity/IDENTITY_DIRECTORY.md)。升级身份服务前需先执行说明中的数据库迁移。

## 桌面打包与分发

### 客户端与服务端的部署关系

桌面客户端与管理服务独立构建和部署。服务端运行管理控制台、身份服务及业务接口；官网提供按版本分发、签名校验的客户端安装包。安装包下载地址与客户端连接的管理服务地址分别配置。

| 内容 | 部署或保存位置 |
| --- | --- |
| 管理控制台、业务服务和身份服务 | 自行部署的服务端 |
| macOS / Windows 桌面安装包 | [官网](https://funnywork.funnypixelsapp.com/download/?lang=zh)，仅开放已验收的平台 |
| 服务地址、账号及设备授权配置 | 目标设备的运行配置 |
| 应用签名证书和私钥 | 构建机受限配置目录 |
| 模型供应商凭证 | 业务服务的受限配置或凭证引用 |

源码启动器通过 `--server` / `-Server` 指定管理服务根地址，写入独立的桌面运行配置。账号会话和短期模型入口凭证在目标设备登录后取得；供应商凭证保存在业务服务端。发布说明应列出支持的操作系统、CPU 架构、服务端兼容版本及接入配置方式。

签名更新清单、稳定/预发布通道隔离、安装升级验收及 TestFlight 外部发布要求见[客户端发布说明](deploy/CLIENT_RELEASES.md)。

### 准备构建环境

所有命令从仓库根目录执行。macOS 包在 macOS 构建机生成；Windows 包在 Windows 构建机生成。

```sh
pnpm --dir apps/desktop install --frozen-lockfile
pnpm --dir apps/desktop --filter @deepseek-ai/dsh-desktop exec install-electron
```

将对应平台的模板复制为本地文件，然后填写配置。已有本地配置时直接编辑，不重复覆盖。

```sh
# macOS
cp apps/desktop/apps/desktop/.env.macos.example apps/desktop/apps/desktop/.env.macos
```

```powershell
# Windows
Copy-Item apps/desktop/apps/desktop/.env.windows.example apps/desktop/apps/desktop/.env.windows
```

平台配置文件由打包脚本读取。相对证书路径以 `apps/desktop/apps/desktop/` 为基准，环境变量值不做 Shell 展开。

| 配置 | 用途 |
| --- | --- |
| `DSH_DESKTOP_APP_ID` | 设置为 `com.funnyworker.desktop`，同一产品发布保持一致 |
| `DSH_DESKTOP_NPM_STORE_DIR` | 可选的绝对 pnpm 缓存目录，重复打包可复用已校验依赖；各目标的构建目录仍独立 |
| `DSH_DESKTOP_AUTO_UPDATE_ENV`、`DOWNLOAD_TEST_ORIGIN`、`DOWNLOAD_TEST_RELEASE_ID` | 选择打包内嵌的更新通道；测试通道需要 HTTPS 根地址及 32 位小写十六进制发布 ID |
| `DSH_DESKTOP_MANDATORY_UPDATE_*` | 配置组织使用的版本策略服务地址与允许的登录来源 |
| macOS 的 `CSC_*`、`DSH_DESKTOP_MACOS_*`、`APPLE_*` | Developer ID 签名、Team ID 与 Apple 公证认证 |
| Windows 的 `DSH_DESKTOP_WINDOWS_*` | 签名证书、SignTool 与硬件密钥配置 |

桌面打包保留各目标的 `dependency-store` 包缓存与 `dependency-cache` npm 元数据缓存；指定 `DSH_DESKTOP_NPM_STORE_DIR` 时改用该目录保存包内容。管理端和桌面运行时先使用 `--offline` 校验锁定版本的缓存，仅缺少包或 npm 元数据时才访问配置源（管理端固定官方源）；完整性、锁文件或构建错误不会触发联网重试。管理端后续构建采用严格依赖检查，避免自动重新安装。可用 `FUNNYWORK_PNPM_STORE_DIR` 为管理端指定绝对缓存路径。首次缺少大型包时，管理端官方源下载超时设为 300 秒，避免默认 60 秒反复中断；缓存命中时不会进入下载阶段。桌面按包集合的版本、完整性摘要、安装配置和来源保存解析后的锁文件，输入一致时直接复用。LibreOffice 版本与完整性摘要不变时复用缓存，每次仍重新组装、校验和压缩安装包。首次构建、版本更新或显式清理缓存时可能下载依赖。

macOS 构建机需要 Xcode Command Line Tools、Developer ID Application 证书及一种 Apple 公证认证方式。Windows 构建机需要 Visual C++ Build Tools 和 Windows SDK；正式签名构建还需要模板指定的签名工具及密钥。完整参数见 [macOS 模板](apps/desktop/apps/desktop/.env.macos.example)、[Windows 模板](apps/desktop/apps/desktop/.env.windows.example) 和 [桌面打包指南](apps/desktop/apps/desktop/README.md)。

### 客户端连接与模型配置

安装版自动连接官方服务。部分区域通过当地云服务跳转接入，连接选择保持同一账号与本地历史目录。自建服务使用独立地址，账号与历史按服务身份隔离。

`start-desktop` 的 `--server` 指定服务地址；支持模型中间层的客户端登录后获取短期入口凭证和授权模型目录。共享 DeepSeek Key 只保存在服务端；旧版加密密钥下发接口已关闭，旧客户端需升级。

安装版直接运行安装后的 `funnywork.exe`，无需 Python、pnpm 或源码启动器。连接本地后端时，先退出客户端，再从仓库执行一次配置：

```powershell
.venv\Scripts\python.exe scripts/setup/configure-installed-desktop.py --server http://127.0.0.1:8765
```

配置保存在当前用户的 `~/.dsh/funnyworker-backend.json`；使用自定义 `DSH_HOME` 时，通过 `--home` 指定同一目录。重新打开安装版并登录后，客户端通过模型中间层获取当前账号的授权模型目录及短期入口凭证。部署人员在管理端配置模型、容量、计费与成员额度，步骤见[管理员指南](manuals/ADMIN_GUIDE.md#配置模型服务)。切换服务时账号与会话按服务和身份隔离。

手机聊天的官方模型来源继续使用 `FUNNYWORK_DESKTOP_MODEL_FILE` 指定私有配置文件，字段见 [配置模板](deploy/desktop-model.example.json)。文件应仅供服务进程读取，包含对应租户、Ed25519 签名密钥、DeepSeek 凭证和递增的配置版本。`signingKey` 使用 Base64 PKCS#8 私钥，`signingPublicKey` 使用 Base64 原始 32 字节公钥；尚未使用管理端发布时，每次更改模型设置或凭证应增加 `version`。管理端发布启用后，模型设置和版本以数据库发布记录为准；文件继续提供签名材料及默认凭证。详见[桌面模型发布](services/business/MODEL_PUBLICATION.md)。桌面用户需要对应租户的业务身份绑定和 `funnyworker:desktop:read` 权限。

当前桌面模型接入使用账号鉴权、授权目录、短期入口凭证及统一计量。配置接口、许可和故障恢复见[模型中间层](services/business/MODEL_MIDDLEWARE.md)。旧版密钥包及回执格式的兼容说明集中维护在[模型发布参考](services/business/MODEL_PUBLICATION.md#客户端加载回执)。

### 检查与打包命令

```sh
# macOS：检查配置与工具链，不进行签名或公证
pnpm --dir apps/desktop package:desktop:mac:arm64 --check
pnpm --dir apps/desktop package:desktop:mac:x64 --check

# macOS：本地测试包（使用临时签名，无 Apple 公证）
pnpm --dir apps/desktop package:desktop:mac:arm64 --unsigned

# macOS：生成签名、公证后的 DMG 和 ZIP
pnpm --dir apps/desktop package:desktop:mac:arm64
pnpm --dir apps/desktop package:desktop:mac:x64
```

```powershell
# Windows：检查正式签名构建的配置与工具链
pnpm --dir apps/desktop package:desktop:win:x64 --check

# Windows：生成签名安装包
pnpm --dir apps/desktop package:desktop:win:x64

# Windows：生成未签名测试安装包
pnpm --dir apps/desktop package:desktop:win:x64:unsigned
```

macOS 的 `--unsigned` 使用本地临时签名，产物带 `-unsigned` 后缀，写入目标目录的 `unsigned-artifacts/`。此模式不需要 Developer ID 或公证凭证，仅适用于本地测试；正式分发使用签名、公证命令。

`start-desktop` 的 `--build` / `-Build` 用于源码运行。上述 `package:*` 命令负责准备运行时与桌面资源，并生成可分发的安装包。

产物根目录为 `apps/desktop/apps/desktop/.desktop-build/targets/`：

| 目标 | 安装包目录 | 主要产物 |
| --- | --- | --- |
| macOS ARM64 | `mac-arm64/artifacts/` | `.dmg`、`.zip` |
| macOS x64 | `mac-x64/artifacts/` | `.dmg`、`.zip` |
| Windows x64 | `win-x64/artifacts/` | `.exe` |
| Windows x64 未签名测试包 | `win-x64/unsigned-artifacts/` | 带 `-unsigned` 标识的 `.exe` |

以打包输出的实际文件名为准；构建脚本的安装包命名模板使用 `funnywork-<version>-<os>-<arch>` 前缀；构建编号和更新验收仍兼容已有旧名称产物。

### 发布到 GitHub Releases

以下为私有仓库内部归档流程。客户安装包通过官网或企业分发入口交付，不向客户开放私有仓库。安装并登录 [GitHub CLI](https://cli.github.com/)，发布账号需具有仓库写入权限。下例使用 macOS / Bash，版本号需与安装包内的版本一致；预发布版本使用 `--prerelease`。

将各平台的安装包汇总至 `.local/releases/desktop-v0.2.0-rc.2/`，确保这些产物来自同一源码提交。按 [版本说明模板](deploy/RELEASE_NOTES_TEMPLATE.md) 编写并审核 `release-notes.md`，不要直接把提交清单作为客户更新说明。使用该提交的 SHA 替换下例的 `BUILD_COMMIT_SHA`。

```sh
gh auth login

# 创建版本草稿，并将标签关联到实际构建提交
gh release create desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public \
  --target BUILD_COMMIT_SHA --draft --prerelease \
  --title "FunnyWork Desktop v0.2.0-rc.2" \
  --notes-file .local/releases/desktop-v0.2.0-rc.2/release-notes.md

# 上传安装包
gh release upload desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public \
  .local/releases/desktop-v0.2.0-rc.2/*.dmg \
  .local/releases/desktop-v0.2.0-rc.2/*.zip \
  .local/releases/desktop-v0.2.0-rc.2/*.exe

# 核对附件与发布说明后结束草稿状态；私有仓库权限不变
gh release edit desktop-v0.2.0-rc.2 --repo GigaTommy/funnywork-public --draft=false
```

只发布部分平台时，保留对应的文件参数。正式版本去掉 `--prerelease`，采用同样的草稿、上传、核对和内部发布步骤。命令参数见 GitHub CLI 的 [创建版本](https://cli.github.com/manual/gh_release_create)、[上传附件](https://cli.github.com/manual/gh_release_upload) 和 [编辑版本](https://cli.github.com/manual/gh_release_edit) 文档。

本仓库的 GitHub Releases 仅供有仓库权限的开发与发布人员归档，不作为客户公开下载入口。客户端自动更新仍读取打包时内嵌的更新通道和版本策略配置；上传 Release 附件不会改写这些配置。现有 `upload:*` 命令用于上游对象存储发布，发布到 GitHub 使用这里的 `gh release` 命令，无需配置 COS 上传凭证。

## 测试与验证

按修改的组件选择检查。开发时执行受影响组件的定向检查；推送或部署前执行相应全量回归。根目录持续集成验证结构、双平台 Python 编译、管理端构建和指定 Java 集成测试；桌面检查按其工作区指南运行。

```sh
# 仓库结构、组件引用与当前文档链接
python3 scripts/checks/check-repository.py

# Python 编译检查（Windows 使用 .venv\Scripts\python.exe）
.venv/bin/python -m compileall -q funnyworker scripts

# Java 业务服务全量回归（含外部参考节点 HTTP 场景）
sh scripts/mvn.sh -Dfunnywork.http-scenarios=/path/to/reference-runtime/scripts verify

# 管理端生产构建
pnpm --dir apps/management build:prod

# Harness 类型检查及测试，需要先安装该工作区依赖
pnpm --dir apps/desktop typecheck
pnpm --dir apps/desktop test
```

业务全量回归中的 Python HTTP 场景使用独立参考节点工作区的 `computer_http_scenario.py`、`computer_worker_http_scenario.py` 和 `connector_http_scenario.py`，通过上述系统属性指定脚本目录；它们不是当前桌面客户端的运行依赖。PostgreSQL 专项测试需设置 `FUNNYWORK_TEST_POSTGRES_URL`、`FUNNYWORK_TEST_POSTGRES_USER` 和 `FUNNYWORK_TEST_POSTGRES_PASSWORD`，连接独立测试数据库，未设置时会明确跳过。

模型节点的集中配置与 Responses 联调可使用参考节点工作区中的运行时及协议验收脚本，配合本仓库当前构建的服务包：

```sh
python3 scripts/checks/check-model-profile-flow.py --runtime-root /path/to/reference-runtime
```

也可添加 `--scenario attachments` 检查实际节点发现任务附件、读取固定版本、拒绝未授权修订版与撤权后的工具调用，并保存待审阅输出。默认 `--scenario profile` 保持集中配置联调。

这是外部参考节点的协议兼容测试，不是 FunnyWork 原客户端维护任务的运行前提或验收入口。此检查需要已安装的 Codex CLI，以及参考工作区的 `scripts/test-model-profile.py`、节点运行时和测试接口。它使用随机本地端口和独立临时数据库，检查节点读取配置、停用恢复、逐次执行更新模型、凭证轮换及保存产物；不连接已有数据库，也不调用真实供应商推理服务。结束时停止临时服务并清理临时数据。可用 `--server-jar` 指定本次待验证的业务服务包。

部分参考工作区版本的共享客户端读取了回执核对命令未定义的 Host 参数；如遇该兼容问题，可审阅 [兼容补丁](scripts/checks/reference-worker-host-options.patch) 后，在参考工作区应用。补丁只补齐可选参数默认值，显式 Host 父进程校验继续生效。

Harness 的详细开发与验证流程见 [开发指南](apps/desktop/docs/development.md)；修改该目录时同时遵循 [贡献指南](apps/desktop/CONTRIBUTING.md) 和适用的 `AGENTS.md`。管理端构建脚本会先执行类型检查，失败时停止构建。单独检查使用 `pnpm --dir apps/management typecheck`；直接运行 `build:prod` 仅执行生产构建。

### 企业模型接入验收

模型、队列、用量及 Harness 客户端的验收见 [详细测试方案](services/business/MODEL_UAT_PLAN.md) 和 [完整应用人工UAT脚本](services/business/MODEL_UAT_MANUAL.md)。运行 `python3 scripts/checks/run-model-uat.py` 执行隔离回归；真实页面组件的浏览器脚本见 [UAT工具说明](apps/management/uat/model-middleware/README.md)。安装包流程及实际昇腾容量在目标环境分别验收。

### Windows 安装包检查

Windows 服务构建也验证桌面设备管理、手机连接、历史同步、组织策略、远程会话、结果文件和模型计量接口。安装包与已配置后端的实机检查可执行以下命令；先在同一 `DSH_HOME` 下完成登录，并关闭客户端。检查读取账号额度及对话历史、验证应用菜单和页面重载，并生成、关闭手机连接码（需要 HTTPS 后端及跨设备权限），将报告及浅色／深色截图保存到已忽略的 `.local/windows-desktop-check/`。默认覆盖账号与同步链路；真实模型回复使用下文的 `--model`，文件上传及策略发布另按目标场景验收。

```powershell
node scripts/checks/check-windows-desktop.cjs --executable E:\FunnyWork\funnywork.exe --commit <安装包源码的完整SHA>
```

可用 `--home` 指定已有登录的桌面数据目录；源码工作区需已安装桌面依赖以提供 Playwright。检查安装包的源码提交必须与 `--commit` 一致，防止用旧安装版验证新源码。加上 `--model` 会创建一条联调对话，调用真实授权模型并核对回复及重载后的恢复，消耗当前账号模型额度；对话保留在账号历史中。

## 项目文档

桌面开发参考 [开发指南](apps/desktop/docs/development.md)、[架构文档](apps/desktop/docs/architecture.md) 和 [贡献指南](apps/desktop/CONTRIBUTING.md)。

## 参与开发

请通过本仓库的 [Issues](https://github.com/GigaTommy/funnywork-public/issues) 报告项目问题或讨论修改，通过 Pull Request 提交代码。问题报告建议包含操作系统、提交版本、复现步骤、预期结果与已脱敏的错误日志。

提交修改时说明影响的组件、配置变化和验证结果。涉及上游源码的改动，请保留版权声明、许可证与第三方通知，并区分本项目定制问题和上游通用问题。

## 上游项目与致谢

FunnyWork 基于以下项目定制开发。下表标识代码来源、使用位置、原版权声明和许可文本；各目录保留上游许可证及第三方通知。

| 上游项目 | 本仓库使用位置 | 随附版权声明 | 许可证 |
| --- | --- | --- | --- |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | `apps/desktop/`：Agent 运行时、Host、插件与 Electron 桌面底座 | Copyright (c) 2026 DeepSeek | [MIT 原文](apps/desktop/LICENSE) |
| [RuoYi-Vue-Plus（若依）](https://github.com/dromara/RuoYi-Vue-Plus) | `services/identity/`：身份与管理后端 | Copyright (c) 2019 RuoYi-Vue-Plus | [MIT 原文](services/identity/LICENSE) |
| [plus-ui](https://github.com/JavaLionLi/plus-ui) | `apps/management/`：Vue 3 管理前端 | Copyright (c) 2019 RuoYi-Vue-Plus | [MIT 原文](apps/management/LICENSE) |

引入基线：DeepSeek Harness `dsh-v0.2.1-rc.2` / `687f282e8625c6b8d11ff58211a90524be2e4317`；RuoYi-Vue-Plus `5.X` / `815087e733eddd641dce8ee3bdb72f21e1b08e28`；plus-ui `5.X` / `d0d451967676707021b9857df529c395b27e90a7`。这些目录包含 FunnyWork 定制修改，按本仓库提交历史追溯。上游链接标识来源，FunnyWork 的功能、发布及支持由本项目维护。

### 可选部署依赖

[Magpie](https://github.com/yetone/magpie) 作为可选模型网关，通过独立容器部署并由业务服务通过 HTTP 调用；本仓库维护其部署配置，不包含 Magpie 源码。当前模板固定 v0.1.1290 对应镜像摘要，接入与凭证配置见[Magpie 部署指南](deploy/magpie/README.md)。Magpie 的版权为 Copyright (c) 2026 yetone，采用独立 [MIT License](deploy/magpie/MAGPIE-LICENSE.txt)；该组件继续按其原许可证使用。

### 引用与分发方法

分发包含上述代码或其重要部分的源码、安装包或其他副本时，应随附相应版权声明、完整 MIT 许可文本及适用的第三方通知。README 中的链接和致谢用于导航，分发包仍需携带许可原文。MIT 允许商业使用，其版权及许可保留义务继续适用于上游部分。

在产品说明或技术文章中介绍本项目来源时，可使用以下表述，并保留项目链接：

> FunnyWork 的桌面客户端与 Agent 运行时基于 DeepSeek Harness 定制；身份与管理后端基于 RuoYi-Vue-Plus（若依）定制；管理前端基于 plus-ui 定制。各上游项目的源码按其 MIT 许可证使用，版权归相应权利人所有。

学术或技术资料引用 DeepSeek Harness 时，可采用[上游提供的 BibTeX](apps/desktop/README.md#citation)：

```bibtex
@misc{deepseek-harness2026,
  title={DeepSeek Harness: Everything is a Plugin},
  author={DeepSeek-AI},
  year={2026},
  publisher={GitHub},
  howpublished={\url{https://github.com/deepseek-ai/deepseek-harness}},
}
```

BibTeX 和说明文字供资料引用使用；MIT 的分发义务以各许可原文为准。其他依赖、运行时和资源的许可入口见[第三方声明](THIRD_PARTY_NOTICES.md)及 [Harness 第三方通知](apps/desktop/THIRD_PARTY_NOTICES.md)。

## 许可

FunnyWork 对其有权授权且未由独立许可声明覆盖的自有代码和文档采用“非商业源码可用＋商业许可”模式。个人学习、非商业教学与学术研究、非营利组织自身非商业公益活动可按[根 LICENSE](LICENSE)免费使用、修改及非商业分发；企业内部部署、为企业业务进行研发测试、对外 SaaS／API、商业集成和收费交付须事先取得书面商业授权。免费范围及商业用途定义以 LICENSE 为准。

商业授权提供约定范围内全球、非独占、不可转让的商业使用权，主体、期限、数量、SaaS、分发和服务范围由书面订单明确。通用条款见[商业授权协议](COMMERCIAL-LICENSE.txt)。购买与咨询：[官网](https://funnywork.funnypixelsapp.com)、[support@funnypixelsapp.com](mailto:support@funnypixelsapp.com)。

本模式是源码可用许可，包含非商业用途限制。DeepSeek Harness、若依及其他具有独立许可声明的组件继续按原许可证使用；其 MIT 授权允许商业使用，保留原版权、完整许可和通知即可按相应条款行使权利。此前依法取得的 MIT 或其他许可副本继续按原许可适用，本次变更不追溯撤销既有权利。文件或组件的独立许可声明优先适用于其覆盖部分；本次保留上述上游目录的 MIT 许可及许可元数据。第三方范围见[第三方声明](THIRD_PARTY_NOTICES.md)。

FunnyWork 自有的 `funnywork-desktop-controls` 与 `funnywork-desktop-capabilities` 包按各自随附的根许可证副本使用，商业使用须取得书面授权。上游 Harness、若依、plus-ui 及其原有代码的独立许可保持不变。

公开源码快照与区域入口配置见[公开源码发布](deploy/PUBLIC_SOURCE.md)。
