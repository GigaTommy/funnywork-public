# Management React components

**简体中文** | [English](README.en.md)

本目录包含 Vue 管理端仍在使用的 React 业务组件，以及保留的原管理端开发壳。默认管理站由 `apps/management/` 构建。

主要消费者为 `apps/management/src/views/funnyworker/BusinessModule.vue` 和 `shared.tsx`。修改共享组件时，请验证 Vue 管理端生产构建及对应业务页面。Vue 构建通过 Vite 的 `dedupe` 统一 React 和 React DOM 实例。

单独维护原开发壳时，可在本目录安装依赖并运行 `pnpm dev`。该目录的 `pnpm build` 会写入业务服务的管理静态资源目录，因此不要将它与默认 Vue 管理站的构建产物混用。

目录边界见 [根目录 README](../../README.md)，协作流程见 [贡献指南](../../CONTRIBUTING.md)。

项目许可及商业使用条件见[根目录许可说明](../../README.md#许可)；具有独立许可声明的部分按各自许可使用。
