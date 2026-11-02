# 公开源码发布

**简体中文** | [English](PUBLIC_SOURCE.en.md)

公开源码使用固定提交生成独立快照。开发仓库继续保持私有，公开仓库只接收已审核的源码和后续公开提交，保留项目许可证、上游版权及第三方通知。

## 许可范围

根目录 [LICENSE](../LICENSE) 对其覆盖的自有代码和文档提供非商业许可，商业使用通过[官网](https://funnywork.funnypixelsapp.com)联系取得书面授权。`funnywork-desktop-controls` 和 `funnywork-desktop-capabilities` 两个自有包随包携带相同许可证，并禁止从当前工作区直接发布到 npm。

DeepSeek Harness、RuoYi-Vue-Plus、plus-ui 及其他上游代码继续适用各自原许可证；具体入口见[第三方声明](../THIRD_PARTY_NOTICES.md)。已依法取得的旧版本许可继续有效。

## 配置区域入口

公开构建默认连接国际官网。需要区域接入时，运营方在私有构建环境提供 `FUNNYWORK_REGIONAL_ORIGIN`，格式为不含端口、路径、查询参数和凭据的小写 HTTPS origin，例如 `https://regional.example.invalid`；示例域名仅供说明，构建时替换为已审核的实际入口。客户端只在明确配置的官方入口间选择连接，自建服务保持独立账号空间。

| 组件 | 设置方式 |
| --- | --- |
| 桌面开发、Host 或独立运行时 | 启动进程时提供 `FUNNYWORK_REGIONAL_ORIGIN` |
| 桌面安装包 | 在打包前设置同一变量，构建将其写入主进程；独立 Host 进程按其启动环境配置 |
| Android | 在执行 Gradle 前设置同一变量；写入签名应用的 BuildConfig |
| iOS | 向 `xcodebuild` 传入 `FUNNYWORK_REGIONAL_ORIGIN=https://…` 构建设置；写入签名应用的 Info.plist |
| 官网构建及线上检查 | 明确指定目标 origin；区域站点通过同一环境变量加入可用目标 |
| 区域 Nginx 与证书续期 | 在私有配置中将模板的 `regional.example.invalid` 替换为实际主机名；续期服务从 `/etc/funnywork/site.env` 读取 `FUNNYWORK_REGIONAL_HOST` |

区域入口与国际入口必须接入同一账号体系。升级已有区域版本时保留原入口配置，使历史账号目录仍按原身份映射恢复。第三方自建入口不可配置为官方别名。环境值属于运营配置，不提交到 Git；写入应用的服务地址可从安装包读取，配置外置不提供地址保密功能。

## 生成候选快照

使用 Python 3.12+，先完成改动提交并核对固定提交。以下命令从仓库根目录运行，`COMMIT` 替换为已审核的完整提交号：

```sh
python3 scripts/build/build-public-source.py \
  --ref COMMIT --output .local/releases/public-source-COMMIT
```

输出包含 `source/`、`funnywork-source.tar.gz`、`SOURCE-MANIFEST.json` 和 `SHA256SUMS`。导出只读取该 Git 提交，排除 Git 元数据、未跟踪文件和本地配置；拒绝已存在的输出目录、私有材料路径及逃逸符号链接。清单记录源提交和逐文件摘要。

## 审核与发布

1. 在候选源码中执行凭证扫描，核对合成夹具、公开验签公钥和文档摘要等命中；对未分类命中先修复或核实。
2. 核对许可证及第三方通知、官网与下载入口，并检查候选包包含必要的组件、锁文件、构建脚本和测试。
3. 对改动组件执行定向测试、构建及仓库检查，记录执行命令、结果和源提交。发布新安装包另按[客户端发布](CLIENT_RELEASES.md)验证。
4. 在空的独立仓库中初始化并提交 `source/` 内容，检查目标仓库名称和可见性后发布；不要添加私有开发仓库的 remote、推送全部分支或导入旧 Issue、PR、日志和附件。
5. 公开后启用相应 PR 检查和安全报告入口，后续同步只提交审核通过的源码变化。

生成快照不会创建 GitHub 仓库或更改现有仓库可见性。源码公开与网站、服务和安装包部署分别执行。

公开目标为 [GigaTommy/funnywork-public](https://github.com/GigaTommy/funnywork-public)。导出清单会列出排除的旧固定提交构建任务；该任务依赖私有历史，仅在开发仓库保留，常规跨平台检查继续随源码发布。
