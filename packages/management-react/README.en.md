# Management React components

[简体中文](README.md) | **English**

This directory contains React business components still used by the Vue management frontend and the retained original management development shell. `apps/management/` builds the default management site.

The main consumers are `apps/management/src/views/funnyworker/BusinessModule.vue` and `shared.tsx`. When changing shared components, verify the Vue production build and relevant business pages. Vite's `dedupe` unifies React and React DOM instances in the Vue build.

To maintain the original shell separately, install dependencies here and run `pnpm dev`. This directory's `pnpm build` writes to the business service's management static-assets directory; keep its outputs separate from the default Vue site build.

See the [root README](../../README.en.md) for directory boundaries and the [contribution guide](../../CONTRIBUTING.md) for collaboration.

For project licensing and commercial-use conditions, see the [root licensing section](../../README.en.md#license). Separately licensed portions retain their own licenses.
