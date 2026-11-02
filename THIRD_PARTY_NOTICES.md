# 第三方声明

FunnyWork 包含以下上游项目的源码及定制修改。各项目的版权归原作者或相应权利人所有；项目根目录的源码可用非商业许可证和商业协议仅适用于其明确覆盖的自有部分，上游版权与许可声明继续适用。

| 项目 | 来源 | 本仓库目录 | 许可文本 |
| --- | --- | --- | --- |
| DeepSeek Harness | [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | `apps/desktop/` | [MIT License](apps/desktop/LICENSE) |
| RuoYi-Vue-Plus | [dromara/RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) | `services/identity/` | [MIT License](services/identity/LICENSE) |
| plus-ui | [JavaLionLi/plus-ui](https://github.com/JavaLionLi/plus-ui) | `apps/management/` | [MIT License](apps/management/LICENSE) |

若依前后端的引入分支与固定提交见 [README 的上游说明](README.md#上游项目与致谢)。这些目录包含本项目修改，并非未经修改的上游镜像。

DeepSeek Harness 内部使用的第三方组件、资源及其声明见 [Harness 第三方通知](apps/desktop/THIRD_PARTY_NOTICES.md)，其 vendored 组件的许可证保留在对应源码目录中。

本文件提供直接纳入仓库的主要上游项目的许可入口，不是所有传递依赖的完整清单。通过 Python、Maven 或 pnpm 安装的依赖，以及随构建产物分发的运行时和资源，仍以各自随附的许可证及通知为准。分发源码或构建产物时，应一并保留适用的版权声明、许可文本与第三方通知。

原生 iOS 客户端通过 Swift Package Manager 使用 Swift Markdown 0.9.0 和 Swift CMark 0.9.0。许可原文分别见 [Swift Markdown](apps/ios/Sources/ThirdParty/SwiftMarkdown-LICENSE.txt) 和 [Swift CMark](apps/ios/Sources/ThirdParty/SwiftCMark-COPYING.txt)，随 iOS 应用资源一并分发。

原生公式渲染使用 [iosMath 2.5.0](https://github.com/kostub/iosMath/tree/2.5.0)，许可原文见 [MIT License](apps/ios/Sources/ThirdParty/iosMath-LICENSE.txt)。上游字体、字体许可与说明文件随其 Swift Package 资源包完整分发，包括 GUST Font License 与 SIL Open Font License；未修改字体。

桌面二维码生成使用 [node-qrcode 1.5.4](https://github.com/soldair/node-qrcode/tree/v1.5.4)，遵循 MIT License，版权归 Ryan Day（2012）所有。该运行依赖及其传递依赖随桌面安装包分发，保留包内原始许可文本。

## 可选部署依赖

| 项目 | 来源与版本 | 集成方式 | 许可文本 |
| --- | --- | --- | --- |
| Magpie | [yetone/magpie](https://github.com/yetone/magpie)，部署模板对应 v0.1.1290 | `deploy/magpie/` 提供独立容器配置，业务服务通过 HTTP 模型接口调用；仓库未引入其源码 | [MIT License](deploy/magpie/MAGPIE-LICENSE.txt)，Copyright (c) 2026 yetone |

镜像摘要固定在[Compose 配置](deploy/magpie/compose.yml)，操作流程见[部署指南](deploy/magpie/README.md)。交付 Magpie 源码、修改版本、二进制或容器镜像副本时，保留其完整版权与许可文本，并核对该版本的第三方通知。Magpie 的独立 MIT 权利不受 FunnyWork 自有部分的非商业或商业许可限制。

## 分发与许可适用

包含上表代码或其重要部分的源码及二进制分发，应随附对应版权声明和完整 MIT 许可文本，并保留各组件要求的第三方通知。仅列出项目名称、网页链接或学术引用不能代替随附许可文本。

项目根许可证适用于其明确覆盖且未由独立许可声明覆盖的自有代码。文件、目录或组件的独立许可声明优先适用于其覆盖部分；第三方代码按各自许可证使用，上表目录的 MIT 许可及元数据保持原样。商业协议应明确其覆盖的自有代码、修改和服务，不对收件人根据第三方原许可证取得的权利追加付费条件。DeepSeek Harness 的学术引用格式及产品来源说明见[README 引用方法](README.md#引用与分发方法)。

FunnyWork 自有的 `funnywork-desktop-controls` 与 `funnywork-desktop-capabilities` 包按各自随附的根许可证副本使用，商业使用须取得书面授权。上游 Harness、若依、plus-ui 及其原有代码的独立许可保持不变。
