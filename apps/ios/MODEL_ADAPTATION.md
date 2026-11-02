# iOS 模型管理适配与验收

本文描述“电脑任务”入口；独立手机聊天见 [手机聊天](MOBILE_CHAT.md)。电脑工作任务继续通过来源桌面执行模型和工具。手机新建对话可选择当前桌面目录中的模型和 Agent 预设；企业目录及权限由管理后台维护，并由桌面的 Harness 注册表统一执行。手机不保存上游模型密钥，不调用私有推理节点，不申请第二套容量票据。

## 行为与边界

- 在“电脑任务”的“新对话”选择在线设备、工作区、模型和预设。“跟随桌面默认”不指定模型或预设；显式模型仅影响新会话，不修改桌面默认值。
- 目录每 15 秒触发后台刷新，最长使用 30 秒内的本地目录；失败清空选项。同步仍受 30 秒运行状态有效期限制。工作区上限为 1000 项/200000 字节；设备目录单独最多 1024 个模型及 1024 个预设/1048576 字节，不再随工作区重复。超过目录边界的项不加入移动目录；512 个企业预设加 standard 完整保留。名称的换行等空白规范化，无效标识、重复项和损坏预设单独隔离。取消、审批与心跳不等待目录发现。
- 目录刷新保留仍有效的用户选择。已撤销的显式选择阻止新建并提示重新选择；设备或工作区改变时重置选择。后台提交验证工作区目录，桌面再次验证；Harness 在模型解析和预设绑定时核验当前可用性。
- 创建使用原请求 ID、epoch 和固定原生会话标识。结果丢失、失败及重新打开页面不自动创建第二个会话。创建开始后的失败标为待核对，保持原恢复规则。
- 对话中的“正在等待模型容量…”只表示正在执行的本轮模型请求等待准入。手机消息的“已排队”表示命令交付队列，两者不会混淆。停止按钮使用既有取消命令，让桌面释放或取消模型票据。
- 运行中保留本轮最后使用的模型，空闲时优先使用下一轮选择。模型与预设信息在任务详情按需查看，不常驻聊天输入区。预设名称来自目录；没有名称时使用标识。仅在线、完成状态核验的页面显示执行信息。
- 会话用量读取 Harness 的完整会话 token 投影，四项互斥计数相加，缺失或非法值不显示。手机不累加分页消息、累计流式计数或推理 token。该数字用于客户端会话展示，服务端调用账本负责后台计量与未知执行核对。
- 当前不会在已有会话中新增模型切换入口，也不让手机编辑企业供应商或创建个人预设。私有 DeepSeek/千问的兼容、工具调用和容量管理仍在来源桌面与模型入口验证。

## 自动化检查

在仓库根目录生成工程并运行模拟器检查。替换模拟器名称或 UDID，结果目录必须尚不存在。

```sh
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/FunnyWork.xcodeproj -scheme FunnyWork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath .local/ios-build CODE_SIGNING_ALLOWED=NO \
  -parallel-testing-enabled NO \
  -only-testing:FunnyWorkTests \
  -only-testing:FunnyWorkUITests/ModelManagementUITests \
  -only-testing:FunnyWorkUITests/DeviceDirectoryUITests \
  -only-testing:FunnyWorkUITests/ConversationActionsUITests \
  -only-testing:FunnyWorkUITests/MenuNavigationUITests \
  -resultBundlePath .local/ios-model-uat.xcresult test
```

| 检查面 | 覆盖内容 |
| --- | --- |
| ProtocolTests | 在线运行条件、非法等待计数、模型优先级、缓存互斥计数、缺失 usage 和重复目录标识 |
| NewConversationTests | 显式选择持久化、失效选择阻止创建、结果恢复不重复提交 |
| ConversationTests / APITransportTests | 取消、断网、迟到响应、命令身份、账号隔离及鉴权核验 |
| ModelManagementUITests | 浅色/深色选择模型和预设、新建跳转、等待提示与停止；保留页面截图 |
| DeviceDirectoryUITests | 工作区选择、离线禁用、设备停用与恢复 |
| ConversationActionsUITests / MenuNavigationUITests | 审批与问答、菜单打开、设置返回与账号交互 |
| Desktop bridge / controller tests | 新目录透传、领取后二次校验、拒绝失效选择、不覆盖桌面默认、等待生命周期 |
| MobileMenuHttpTest | 真实 HTTP 服务、目录发布与查询、模型选择幂等、撤销后拒绝、waiting 摘要 |
| CLI bridge smoke | 真实本地 Harness Host 的 modelCatalog、agentPresets/list 及同步原生调用 |

## 真实环境 UAT

使用测试账号和专用会话，记录客户端、服务端、模型版本及各步骤结果。

| 步骤 | 操作 | 验收结果 |
| --- | --- | --- |
| 1 | 后台发布两个已验证模型和一个企业预设；分别启动 Mac 与 Windows 桌面 | 两个来源设备显示当前授权目录，模型密钥始终留在服务端 |
| 2 | 手机选择指定来源、工作区、模型和预设，创建并发送简单请求 | 会话归属正确，实际路由和预设一致，桌面原默认值不变 |
| 3 | 在手机选择后停用模型或发布替代预设，再提交 | 服务端或 Harness 拒绝失效标识，不回退到其他模型或旧预设 |
| 4 | 将测试资源池容量设为 1，以第二个测试任务占用模型 | 手机显示等待容量，后台票据为 WAITING；手机命令已接收不被误报为推理开始 |
| 5 | 在等待状态按停止，再释放第一项任务 | 绑定本轮的取消生效；第二项不会迟到启动；后台资源池无超发 |
| 6 | 分别在排队、推理、回执交付时断网并恢复 | 不重复提交或推理；恢复原命令结果；未知执行由后台核对 |
| 7 | 执行多轮工具任务、子任务和上下文压缩 | 每次推理复用桌面统一准入；后台可按任务和执行尝试核对用量 |
| 8 | 加载部分历史页，比较手机与桌面会话 token | 完整会话口径一致；缺失上游 usage 不生成虚假计数；后台重试差异可解释 |
| 9 | 浅色/深色、最大字号、iPhone/iPad及后台切前台 | 选择、停止、菜单、错误和等待文案可读且可操作，后台不展示过期实时状态 |
| 10 | 撤销设备或账号权限，再打开目录并执行 | 服务器拒绝无权限操作；不切换到其他来源设备，不清除已有本地草稿 |

升级顺序为业务服务、Mac/Windows 桌面、iOS。业务数据库迁移应包含 V87，以独立保存设备模型目录；目录选择和等待信息通过同步接口传输。部署验收分别记录模型容量、Mac/Windows 安装包、iPhone/iPad 真机交互，以及 TestFlight/App Store 的签名与分发结果。
