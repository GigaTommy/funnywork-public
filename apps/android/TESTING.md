# Android 测试与联调

供 Android 开发与测试人员使用。构建见 [README](README.md)，服务契约见[桌面同步协议](../../services/business/DESKTOP_SYNC.md)。默认使用本地后端、专用测试账号和隔离数据目录，真实模型用例消耗对应账号额度。生产检查使用单独授权的账号与环境，开发回归使用本地隔离数据。

## 本地开发回归

开发联调使用 `LocalDevelopmentParityTest`、`LocalParityFeaturesTest`，显式传 `-e localDevelopment true`，仅接受 `https://localhost:8766` 和受限私密账号文件。`RecoveryConcurrencyTest` 使用隔离的加密存储和内存传输，专门验证迟到响应、重启与重复回执，不连接服务器。公网 `PublicEntriesTest` 默认跳过，只有显式传 `-e publicEntries true` 才请求官方入口。

`LocalMacGenerationTest` 是另行开启的真实模型联调：必须显式传 `-e localGeneration true -e realMacDevice <开发Mac设备ID>`，仅接受 localhost 的 Gigi 账号。先在本地后端配置容量池、上游凭证环境引用、已审核计费及 Gigi 额度，再重新打开隔离 Mac 客户端加载模型目录。模型凭证只留在服务端；本地测试额度也需要全局预算，不能用管理员身份绕过计费。

该用例创建销售复盘任务，单独点击发送，等待真实 Mac 生成图表及报告并发布固定版本，核验 PNG、PDF 的哈希与解码，再打开原生预览。任务结束状态、最终回复与成果各自同步，用例等待三者齐全后验收，避免用较早的历史快照断言最终回复缺失。可用 `-e taskPrompt <场景指令>` 和 `-e taskTitle <会话标题>` 更换场景，仍要求交付 PNG 和单页 PDF 预览。默认跳过，不伪造历史、回执或结果文件；会实际消耗模型额度。中断后以 `-e resumeTask <原会话ID>` 只续查和预览，不重发指令；加 `-e preview pdf` 可复查一页 PDF 的实际显示。修订验收可传 `-e minimumResultVersion 2`，只接受该版本及以后的图片和 PDF，并在报告记录各文件的版本，避免旧成果被误判为新版通过。私密输入仍使用应用私有的 `android-development-account.json`，结束后删除。

只验证真实 Mac 创建回执可另传 `-e creationOnly true`，保留草稿且不发送模型请求。已有终态创建记录需要释放本地跟踪时，显式传原编号 `-e clearTerminalCreationId <原命令ID>`；用例先核对服务端终态与本地记录一致，再调用现有清理入口，不重发旧创建，也不把 uncertain 改称 accepted。

## 经授权的公开入口检查

以下用例请求官方服务，默认跳过。只有明确安排该环境验收并取得账号授权后执行，不使用管理员或个人生产账号制作客户案例。

`LiveAccountTest`、`LiveWorkspaceTest` 默认跳过真实账号检查。专用调试模拟器显式传 `-e liveAccount true` 才启用；只有另加 `-e liveInference true` 才在两个官方入口各执行一次真实模型调用。前者验证实际AccountApi固定双入口、独立Android身份、目录/历史/额度、同一身份、退出和可选SSE；后者验证实际ViewModel登录、目录加载、来源切换、草稿及退出，不调用模型。

构建 `assembleDebugAndroidTest` 并安装主APK与测试APK后，从既有受限账号JSON通过标准输入写入调试应用私有文件，不把密码作为参数：

```sh
adb shell run-as com.funnyworker.android.debug mkdir -p files
adb shell run-as com.funnyworker.android.debug sh -c "'umask 077; cat > files/android-uat-account.json'" < "$ACCOUNT_JSON"
adb shell am instrument -w -r -e class com.funnyworker.android.LiveAccountTest -e liveAccount true \
  com.funnyworker.android.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`ACCOUNT_JSON` 指向已有授权的私有测试账号文件，字段为username/password；命令不会输出内容。每个真实账号测试结束后删除该临时文件，运行另一测试类前需重新供应。每次成功登录都退出本次会话；报告保存在应用私有 `files/android-uat-report.json` 或 `files/android-workspace-report.json`，可通过run-as读取到根 `.local/`。执行状态未知时读取原票据，不能重跑执行请求；实际调用次数与费用以服务端账本为准。


`LiveRemoteWorkflowTest` 仅显式传 `-e liveRemote true` 执行，使用相同私有账号供应方式。它在两个固定入口分别创建独立的受控来源，验证Android ViewModel的发送/引导/停止、审批/两类问答、附件上传、结果Range续传与审阅追加；结束撤销测试来源并退出本次会话。报告为 `files/android-remote-report.json`。该测试检查传输与交互；模型推理及 Mac／Windows Harness 执行通过真实客户端另行验收。`RemoteUiTest` 是离线UI状态测试，与真实账号测试分别记录。


`LiveMacHarnessTest` 验证真实Mac：显式传 `-e liveMac true -e realMacDevice <已核实在线设备ID> -e realWorkspace <独立规范路径工作区ID>`。它不注册模拟来源、不claim命令、不伪造ACK或历史；使用实际Android ViewModel发送命令，要求观察真实队列变化、模型问答回复和停止后的turn/end，会消耗当前账号模型额度。`LiveMacCreationTest` 用同一参数验证创建被实际Mac接受后草稿只搬一次且不自动发送，不请求推理。使用既有私有账号供应方式，每个类结束均删除临时凭据并退出本次Android会话，保留原Mac登录/设备和测试历史。

创建结果未知时只查原ID。若已通过目录及native_id精确证明本次测试对话已落盘，可用Harness测试的 `-e onlyOrigin <固定入口> -e resumeTask <本次测试会话ID> -e resumeCreationId <原创建命令ID>` 继续该对话上的独立执行验收；不会重发创建，也不能把旧uncertain改称accepted。报告为私有 `files/android-mac-report.json`、`files/android-mac-creation-report.json`。Windows 远程流程使用对应安装包、在线设备和独立工作区执行相同检查，并单独保存结果。
