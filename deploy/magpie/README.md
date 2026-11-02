# Magpie 共享模型网关

**简体中文** | [English](README.en.md)

管理后端通过独立 Docker Compose 项目调用 Magpie；FunnyWork 桌面继续使用现有模型目录、短期入口凭证、授权、预算和票据。客户端不保存 ChatGPT OAuth 或 Magpie Gateway Key。

## 部署

本目录 Compose 固定 Magpie v0.1.1290 对应的镜像摘要，运行非 root 进程，将网关 3425、管理页面 3430 仅发布到宿主机回环地址。运行数据使用独立命名卷；删除容器不删除授权，禁止使用 `down -v` 作为日常更新方式。

将 `compose.yml` 与根据 `magpie.env.example` 创建的 `magpie.env` 放到服务器独立目录。生成随机管理密钥，配置文件权限设为 0600，然后执行：

```sh
docker compose -p funnyworker-magpie up -d
docker compose -p funnyworker-magpie exec magpie magpie gateway-key add "FunnyWork backend"
docker compose -p funnyworker-magpie exec magpie magpie accounts add codex
```

网关密钥只保存到受限的后端环境文件。当前版本不接受容器外部调用者使用示例固定密钥 `magpie`。管理页面通过 SSH 隧道访问，不公开管理端口。

使用同一 ChatGPT 账号在 VPS 单独完成授权。不要导入日常桌面正在使用的会话：当前上游导入会刷新令牌，并明确说明原工具需要重新登录。浏览器回调的 localhost 是浏览器所在电脑；远端登录需要安全转交完整回调或配置受限回调转发。回调地址含短期凭证，不写日志、工单或版本库。

## FunnyWork 接入

业务后端与网关共享宿主机网络命名空间时，可配置：

```dotenv
# 合并已有 origin，不能覆盖现有供应商。
FUNNYWORK_MODEL_INFERENCE_ORIGINS="https://api.deepseek.com,http://127.0.0.1:3425"
FUNNYWORK_MODEL_MAGPIE_KEY=<专用网关密钥>
```

环境变更前备份，确认没有 RUNNING/STOPPING 模型调用，再仅重建 business 服务；UNKNOWN 票据和预算保留。后端若改为 bridge 网络，应重新配置私有访问地址，不能沿用宿主机回环。

在管理 API 中创建共享容量池、停用的 CHAT_COMPLETIONS 部署，baseUrl 为 `http://127.0.0.1:3425/v1`，凭证引用为 `FUNNYWORK_MODEL_MAGPIE_KEY`。模型标识从授权后的实际模型目录选择；上下文与输出上限按实际模型核验。通过用量、流式、工具调用及工具结果回放验证后再声明相应能力并启用。部署仍须运营计费审核与成员额度；上游订阅用量按其账号策略核对，供应商价格按实际配置维护，调用继续受本系统预算控制。

Mac/Windows 原客户端从现有 HTTPS FunnyWork 入口读取授权目录，按账号额度共享上游；上游密钥由服务端保存。手机目录有独立业务实现，不因桌面模型部署自动支持新供应商。

## 验证和回滚

检查容器健康、无密钥返回 401、授权目录、重启后授权持久化，以及经 FunnyWork 票据执行的真实文本、SSE 用量、工具回放和并发排队。分别使用目标平台安装包验证登录、模型目录、完整工具循环和结果显示。HTTP 断开后核对原票据与上游执行状态，确认停止后再释放占用。

回滚先停用新增部署，确认在途请求，再还原后端环境并仅重建 business。停止独立 Magpie 项目，保留命名卷和受限备份。不要修改旧 UNKNOWN 记录、清空预算或切换既有维护政策来完成回滚。

参考：[上游部署与网关说明](https://github.com/yetone/magpie/blob/main/docs/reference.md)、[账号认证实现](https://github.com/yetone/magpie/blob/main/docs/subsystems/providers-accounts.md)、[项目模型中间层](../../services/business/MODEL_MIDDLEWARE.md)。

## 来源与许可

上游仓库：[yetone/magpie](https://github.com/yetone/magpie)。当前 Compose 模板采用 v0.1.1290 对应镜像；Copyright (c) 2026 yetone，完整 [MIT 许可原文](MAGPIE-LICENSE.txt) 随部署资料保留。本目录提供部署配置，Magpie 以独立服务运行。

向客户交付源码、修改版本、二进制或镜像副本时，保留上游版权、完整 MIT 文本和适用的第三方通知。FunnyWork 的商业许可不覆盖或限制 Magpie 原许可证授予的权利。参见[第三方声明](../../THIRD_PARTY_NOTICES.md)。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。
