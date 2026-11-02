# Codex 同步检查部署与验收

[English](CODEX_SYNC_OPERATIONS.md) | 中文

## 当前交付边界

此切片需要同时部署新版 FunnyWork 桌面同步消费者、新版后台和独立 Codex Profile Bundle。默认 Desktop core 包闭包从 `@deepseek-ai/dsh` 与 `@deepseek-ai/dsh-desktop-host` 递归生成，目前不包含 `@deepseek-ai/dsh-subagent-codex`。源码可解析、Host 编译通过均不能证明安装包或用户 Profile 已安装该插件。

旧桌面包需升级；缺少真实 `version`、`dshBuildCommit`、`dshBuildTree` 或 clean 身份证据时不能通过同步检查。`CHECKED` 只表示完成版本和初始化能力观察，不代表功能全验收。此文档不授权修改生产设备、账户 Profile 或组织策略。

## 按账户显式安装与启用

1. 使用与新桌面运行时兼容、经过校验的发布 Bundle。不得把开发 workspace 的软链接当作发布产物。先启动桌面初始化 Profile，再完全退出。
2. 确认目标账户的 `DSH_HOME`。FunnyWork 登录后使用账户隔离目录，目标为该目录下的 `profiles/desktop`，不是任意默认 home 的同名 Profile。不要输出账户令牌或将凭据写入安装命令。
3. 使用目标安装所属的 `dsh` 执行 `dsh plugin --profile desktop add /absolute/path/to/verified-subagent-codex.tgz`。安装由既有插件管理器处理依赖与 Bundle 激活。必须核对 Profile 的 `package.json` 中依赖版本与 `dsh.profile.bundles`；不要通过 `allow-version` 绕过不兼容检查。
4. 在目标 Profile 的 `cordis.patch.yml` 中合并以下 id 定向配置，保留已有条目：

```yaml
- id: subagent-codex
  config:
    maintenanceEnabled: true
```

5. 重启该账户的 FunnyWork Host。安装 Bundle 默认只注册休眠 provider，`maintenanceEnabled` 默认 false；上述配置才注册 `codexMaintenance` RPC。不要启用 `tool-subagent-codex` 或更改默认模型，这些均非只读检查所需。

此配置只影响所选 Profile 的 Host。当前组织策略接口不自动安装或启用此 Bundle；后台仍要求已有设备注册、维护节点、项目资源授权和 protocol 1 功能协商。仅修改本机配置不能增加后台权限。

## 发布包闭包核查

每个平台分别检查实际安装目录，而不是源码目录：

- Bundle 内须有 `lib/index.js`、`cordis.patch.yml`、`lib/typert.host.js` 和类型声明；实际加载路径必须在目标 Profile 或其受控安装闭包内。
- `@openai/codex` 版本必须是插件声明锁定的 `0.153.4`，且 `bin/codex.js` 可读。插件通过包内解析运行固定 `app-server --stdio`，不依赖 PATH 中同名 CLI。
- 官方 wrapper 的 optional dependency 必须包含目标平台实际 payload：`@openai/codex-darwin-arm64`、`@openai/codex-darwin-x64`、`@openai/codex-win32-x64` 或 `@openai/codex-linux-x64` 等匹配项。其 npm alias 固定为相应 `0.153.4-<platform>-<arch>`；只存在 wrapper 不算完整安装。
- 保留发布 tarball 摘要、Profile lockfile 完整性、安装后解析路径、平台/架构及包 manifest 身份。不要收集完整环境变量、原始 stderr 或凭据。
- macOS 签名/公证、Windows 子进程范围停止和 Linux 发布包验收必须在对应实际产物上完成。Linux 开发机 probe 不能替代其他平台证据。

## 安全验收与回退

使用隔离测试 home 与 Profile，安装已构建 tarball 后加载真实 Host；禁止借开发 workspace fallback 掩盖缺包。观察 `codexMaintenance.capabilities` 返回真实版本且 `processStopped:true`；再注册空的私有 workspace，调用固定 start，读取有序 started/capability/terminal 事件和终态，并验证 cancel/reconcile。整个检查不调用模型 turn。

后台验收需三台已授权设备各自上报本机安装包身份，不能复制一台的结果。租约撤销或断线必须真实取消；未确认退出为 `UNKNOWN` 并保持隔离，不可人工伪造停止回执解锁。保留结构化事件摘要、任务 specHash、runtimeEpoch 和停止证明，避免原始进程输出。

停用时先停止任务并确认退出，再将 `maintenanceEnabled` 设为 false 并重启。需要移除时使用目标 Profile 的 `dsh plugin --profile desktop remove @deepseek-ai/dsh-subagent-codex`。仍有 UNKNOWN 时应保留审计记录并走受控恢复，不通过删 journal 或更换 epoch 猜测进程已死。

服务端权限、短租约与回执字段见 [协议说明](../../../../services/business/MAINTENANCE_CODEX_SYNC.md)。
