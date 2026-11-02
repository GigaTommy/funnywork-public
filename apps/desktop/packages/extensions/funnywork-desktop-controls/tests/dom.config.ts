/** Browser-only local verification; this configuration never loads provider credentials. */
import { defineConfig } from 'vitest/config'
import tsconfigPaths from 'vite-tsconfig-paths'
import { standardDecoratorPlugin, vitestExecArgv } from '../../../../vitest.shared.ts'

export default defineConfig({
  plugins: [tsconfigPaths({ projects: ['./tsconfig.base.json'] }), standardDecoratorPlugin()],
  test: {
    execArgv: vitestExecArgv,
    include: ['packages/extensions/funnywork-desktop-controls/tests/dom.e2e.ts'],
    testTimeout: 60000,
    hookTimeout: 20000,
    maxWorkers: 1,
    retry: 0,
  },
})
