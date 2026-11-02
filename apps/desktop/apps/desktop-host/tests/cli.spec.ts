/** CLI package operations must use the same physical pnpm payload as the installed app. */
import { delimiter, join } from 'node:path'
import { expect, it, vi } from 'vitest'
import { runCli } from '@deepseek-ai/dsh/lib/bin.js'
import { installOfficeEngineResolution } from '../src/office-engine.ts'
import { runDesktopCli } from '../src/cli.ts'

vi.mock('@deepseek-ai/dsh/lib/bin.js', () => ({ runCli: vi.fn(async () => {}) }))
vi.mock('../src/office-engine.ts', () => ({ installOfficeEngineResolution: vi.fn(), runtimeArchivePath: vi.fn() }))

it('uses the primary-runtime pnpm distribution for Desktop profile operations', async () => {
  const runtime = join('Application with spaces', 'Resources', 'app.asar', 'dsh')
  const support = join('Application with spaces', 'Resources', 'runtime')
  await runDesktopCli(runtime, support)
  expect(installOfficeEngineResolution).toHaveBeenCalledWith(runtime)
  expect(runCli).toHaveBeenCalledWith({
    manageDesktopProfile: true,
    packageManager: {
      command: process.execPath,
      args: ['--expose-internals', join(support, 'primary-runtime', 'dependencies', 'pnpm', 'bin', 'pnpm.mjs')],
      env: {
        ELECTRON_RUN_AS_NODE: '1', DSH_DESKTOP_NODE_EXECUTABLE: process.execPath,
        PATH: `${join(support, 'bin')}${delimiter}${process.env.PATH ?? ''}`,
      },
    },
  })
})
