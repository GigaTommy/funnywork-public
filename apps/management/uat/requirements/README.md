# 需求池隔离交互夹具

**简体中文** | [English](README.en.md)

使用真实 RequirementPool 组件和虚构接口数据，验证分页、编辑、幂等重试、入队冻结、影响展示、依赖表单和撤权清理。地址增加 `?shell=1&tc_view=requirements&tc_requirement=req-1` 时，加载真实任务中心壳、维护、反馈和组织任务组件，验证执行终端下钻与返回需求；仅未使用的个人任务／范围设置保留占位。项目目录接口延迟返回，以检查初始加载竞态。`readonly=1` 使用只读菜单权限；写权限检查复用真实 checkPermi。它不访问真实账户、仓库或模型，不替代后端权限与自动流程测试。

在仓库根目录通过外接盘存储包装器启动管理端 Vite，配置文件为 `apps/management/uat/requirements/vite.config.mjs`，监听 `127.0.0.1:19201`。构建缓存写入 `.local/agent/packages/requirements-vite/`。验收结束关闭进程，不发布此夹具。

管理端需已有依赖。自动化浏览器使用独立临时用户目录，放在项目 `.local/agent/tmp/`，关闭后清理；不要复用用户 Chrome 会话。
