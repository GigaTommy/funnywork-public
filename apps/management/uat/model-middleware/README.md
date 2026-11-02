# 模型管理与 Harness 页面 UAT 工具

**简体中文** | [English](README.en.md)

本目录是开发验收工具，直接导入生产页面与客户端组件，提供可控模拟接口；不会被管理端的生产路由加载。它不调用生产服务或公网模型，也不包含真实账号或密钥。

完整测试方案和人工安装包/引擎脚本在 [MODEL_UAT_PLAN.md](../../../../services/business/MODEL_UAT_PLAN.md) 与 [MODEL_UAT_MANUAL.md](../../../../services/business/MODEL_UAT_MANUAL.md)。根 README 的环境准备仍为权威入口。

## 运行自动回归

在仓库根目录：

```sh
python3 scripts/checks/run-model-uat.py --output .local/uat/regression
```

Windows 可使用 `python`，其余参数相同。需要准备两套工作区依赖、JDK和Maven；脚本不自动安装。默认使用独立H2和本地模拟上游，主动移除继承的 `FUNNYWORK_MODEL_TEST_DB_URL`。这不是PostgreSQL多副本测试。`--skip-build`仅用于快速定位，结果保留NOT_RUN，不能作为完整验收。

输出为 `.local/uat/regression/regression-results.json`、选中Java测试XML和各步骤日志。发生失败返回非零；失败日志保存在本地，不把任意原始日志直接打印到共享终端。

## 打开真实组件的隔离测试页面

```sh
python3 scripts/checks/run-model-uat.py --mode serve
```

- 管理端：`http://127.0.0.1:19192/`
- 客户端模型选择：`http://127.0.0.1:19192/client.html`

使用源码中的 ModelMiddleware、ModelMiddlewareUsage、ModelSelect 和 UI primitives，测试桩只替代接口/目录/返回值。控制台可以切换基础/1000队列/未知/空态/授权变化数据、注入401/403/409/503/慢接口、切换语言和外观。模拟接口审计仅含虚构标识和测试配置。完整Electron登录、聊天、工具、GPU没有在此页面仿制。

## 可重放的浏览器脚本

`browser-uat.mjs`接收Codex CUA的Tab对象，内部只通过该Tab的受支持浏览器API操作真实控件。无需安装额外浏览器自动化依赖。普通Node没有CUA会话，不能直接执行它冒充浏览器验收。

在Codex CUA工具中，先创建测试页Tab并阅读其状态，然后执行（路径替换为实际仓库绝对路径）：

```javascript
var managementTab = await cua.createBrowserTab('iab', 'http://127.0.0.1:19192/', { visible: false })
```

```javascript
var runner = await import('file:///绝对仓库路径/apps/management/uat/model-middleware/browser-uat.mjs')
nodeRepl.write(await runner.runBrowserUat(managementTab, 'management', '/绝对仓库路径/.local/uat/browser', {
  ids: ['M01','M02','M03','M04','M05','M06','M07','M08','M09','M10','M11','M12']
}))
```

第二批执行 M13-401、M13-403、M13-503、M14、M15、M16、M17、M18；第三批执行 U01–U07；第四批执行 U08–U13、U14-401、U14-403、V01。实际参数使用字符串数组，不使用“U01–U13”范围字面量。管理lane必须明确传入ids，且每批最多12项，避免超过CUA单次执行时限；客户端lane可省略ids执行12项。

客户端同样先打开 `/client.html`，再执行：

```javascript
nodeRepl.write(await runner.runBrowserUat(clientTab, 'client', '/绝对仓库路径/.local/uat/browser'))
```

客户端需要先用 `cua.createBrowserTab` 创建 `clientTab`。修改脚本后重导入时给file URL增加不同查询参数，避免ESM模块缓存。重跑支持按id挑选，报告保留所有历史尝试、最新结果、plannedCases、notRun及每次截图。初次历史失败不会被删除；截图文件带开始时间，不覆盖旧证据。

脚本等待可见业务结果、当前测试轮次、真实按钮状态，关闭上一项抽屉再开始新项。不要用强制点击、修改DOM或注入组件内部状态绕过失败。M17/M18等待5.2秒是为验证五秒定时刷新，不能用瞬时截图替代。

浏览器报告：`results-management.json`、`results-client.json`、每项PNG/JPEG。判定完整L2通过需plannedCases分别36和12、notRun为空、所有最新status为PASS、每项截图存在。浏览器或脚本失败必须保留FAIL；fixture审计不能当成真实后端证据。

## 手工复测

按完整方案逐行操作，同步检查界面、测试桩请求和截图。管理页中状态/权限错误要区分“请求失败”和“结果未确认”；运行取消不是GPU停止证明。客户端的恢复按钮位于测试控制台，点它会正常关闭模型弹出菜单，重新展开后才继续；脚本不把这一正常的外部点击行为误判为产品故障。

结束后停止测试服务、关闭工具创建的临时Tab。证据放在 `.local/`，不要提交截图、生成网页、日志、依赖和数据库。

## 汇总并校验证据

```sh
python3 scripts/checks/summarize-model-uat.py --regression .local/uat/regression-final --browser .local/uat/browser --manual .local/uat/manual-pending.json --output .local/uat
```

`--regression`指向实际回归输出目录；如果按上面的初次示例输出到 `.local/uat/regression`，这里也使用该目录。先复制本目录 `manual-results.example.json` 到 `.local/uat/manual-pending.json`，真实环境执行后填写每项状态/证据；不修改源模板假装完成。

汇总器检查必需R01–R08、48个页面用例ID、最新PASS、未执行项和截图实际编码，生成 `UAT_REPORT.md` 与 `acceptance-summary.json`。截图按工具返回的实际PNG/JPEG格式命名，并记录SHA-256。历史失败不清除；任一自动检查/页面项失败或缺少证据时返回非零。即使源码和模拟UAT全部通过，生产验收始终保留人工签字要求。

企业 Agent 预设补充：测试页面可选择“企业 Agent 预设”，直接加载生产 `EnterpriseAgentPresets` 组件。`agent-browser-uat.mjs` 提供 7 项交互脚本，覆盖只保存当前版本、编辑、禁止模板表达式、替换发布、个人预设策略、版本冲突和权限失效清理。

```javascript
var agentUat = await import('file:///Volumes/SSD/Code/funnyworker/apps/management/uat/model-middleware/agent-browser-uat.mjs')
await agentUat.runAgentBrowserUat(managementTab, '/Volumes/SSD/Code/funnyworker/.local/uat/enterprise-agents')
```

输出是独立的 `results-enterprise-agents.json` 和逐项截图；既有 `summarize-model-uat.py` 的两条模型/用量页面通道不包含这个补充通道。人工记录模板增加 EP01–EP10，两平台安装包与真实后端检查仍需逐项签署。

## 模型限额页面

选择“模型限额”加载生产 `ModelBudgets` 组件。新增 `budget-browser-uat.mjs` 覆盖新成员默认拒绝、金额精确换算与保存回显、全站零预算暂停、部署计费、冲突保留草稿、权限失效清理、中英文深浅色、全站权限拒绝的范围隔离及普通组织管理员的计费只读边界。全部使用模拟数据，不修改真实额度或调用供应商。

```javascript
var budgetUat = await import('file:///Volumes/SSD/Code/funnyworker/apps/management/uat/model-middleware/budget-browser-uat.mjs')
nodeRepl.write(await budgetUat.runBudgetBrowserUat(managementTab, '/Volumes/SSD/Code/funnyworker/.local/uat/model-budgets-run1'))
```

每次复测使用新的输出目录，保留此前失败记录。输出 `results-model-budgets.json` 与十项 JPEG 截图；它是独立补充通道，不纳入既有 48 项汇总。后端预算及 PostgreSQL 并发验证见 [模型限额说明](../../../../services/business/MODEL_BUDGETS.md)。

限额浏览器验收同时覆盖推广策略（一次性赠送 1 元、余额上限及全站发放上限）与手工充值金额、请求去重标识；所有操作仅修改隔离模拟数据。

充值恢复验收覆盖响应丢失后组件重新创建：已提交则核对到账，未提交则以原编号重试且金额锁定。`account.html` 使用真实 Mac/Windows 账号菜单组件检查额度、领取、中英文、浅深色和断网展示；仍为隔离模拟环境。
