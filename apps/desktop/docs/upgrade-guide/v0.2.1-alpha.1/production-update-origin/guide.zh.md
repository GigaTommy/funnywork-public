---
kind: upgrade-guide
description: 生产桌面打包必须显式配置 HTTPS 更新服务 origin。
---
# 生产桌面更新地址

[English](guide.md) | 中文

## 变更

生产桌面打包现在要求在目标 `.env.macos` 或 `.env.windows` 中配置 `DOWNLOAD_PROD_ORIGIN`。此前会直接写入 `https://download.deepseek.com`，没有配置字段。地址缺失或无效会在构建前停止打包。已安装应用仍保留原内嵌更新地址，直至替换安装。

## 迁移

1. 将 `DOWNLOAD_PROD_ORIGIN` 设置为本部署更新服务的 HTTPS origin。不接受路径、凭证、查询或片段；shell 环境值不能替代目标 dotenv 配置。
2. 在 `dsh-desk/feeds/<target>/` 提供生产清单，在 `dsh-desk/bin/<target>/` 提供安装包。独立服务器通过其部署流程上传；COS 上传器仍要求自己的 bucket 和凭证。
3. 重新打包桌面应用，分发前核对内嵌更新 URL、清单和产物校验和。为现有安装保留稳定的应用标识和签名身份。
