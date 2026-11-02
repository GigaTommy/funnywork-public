/** Linux preparation checks native build prerequisites before creating runtime resources. */
import { readdir } from 'node:fs/promises'
import { dirname } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import { promisify } from 'node:util'
vi.mock('../scripts/desktop-smoke-font.ts', () => ({ desktopSmokeFont: () => '/fixture/DejaVuSans.ttf' }))

const state = vi.hoisted(() => ({ missingTool: '', missingHeaders: false, failMuslStage: '', commands: [] as { command: string; args: string[] }[] }))
vi.mock('node:child_process', () => {
  const execFile = (): void => {}
  Object.defineProperty(execFile, promisify.custom, { value: async (command: string, args: string[]) => {
    state.commands.push({ command, args })
    if (command === 'musl-gcc' && args.includes(state.failMuslStage)) throw new Error('fixture compile failure')
    if (command === state.missingTool) throw new Error(`${command} unavailable`)
    return { stdout: 'probe.txt\n', stderr: '' }
  } })
  return { execFile }
})
vi.mock('node:fs/promises', async importOriginal => ({
  ...await importOriginal<typeof import('node:fs/promises')>(),
  access: vi.fn(async () => { if (state.missingHeaders) throw new Error('headers unavailable') }),
}))

import { probeDesktopToolchain, requireDesktopToolchain } from '../scripts/desktop-toolchain-preflight.ts'

afterEach(() => { state.missingTool = ''; state.missingHeaders = false; state.failMuslStage = ''; state.commands = []; vi.restoreAllMocks() })

it('accepts the complete Linux native toolchain', async () => {
  vi.spyOn(process.report, 'getReport').mockReturnValue({ header: { glibcVersionRuntime: '2.36' } })
  expect(await probeDesktopToolchain('linux')).toEqual([])
})

it('reports musl, missing compiler and absent Node headers together', async () => {
  vi.spyOn(process.report, 'getReport').mockReturnValue({ header: {} })
  state.missingTool = 'cc'
  state.missingHeaders = true
  expect((await probeDesktopToolchain('linux')).map(failure => failure.tool)).toEqual(['glibc', 'cc', 'Node-API headers'])
  await expect(requireDesktopToolchain('linux')).rejects.toThrow(/Linux Desktop requires a glibc-based build host/u)
})


it('probes static and shared musl output in one private directory and removes it', async () => {
  vi.spyOn(process.report, 'getReport').mockReturnValue({ header: { glibcVersionRuntime: '2.36' } })
  expect(await probeDesktopToolchain('linux')).toEqual([])
  const commands = state.commands.filter(row => row.command === 'musl-gcc')
  expect(commands.map(row => row.args[0])).toEqual(['-static', '-shared'])
  expect(commands[1]!.args).toContain('-fPIC')
  const source = commands[0]!.args[1]!
  expect(commands[1]!.args).toContain(source)
  await expect(readdir(dirname(source))).rejects.toMatchObject({ code: 'ENOENT' })
})

it.each(['-static', '-shared'])('reports musl %s compilation failure and still removes probe inputs', async (stage) => {
  vi.spyOn(process.report, 'getReport').mockReturnValue({ header: { glibcVersionRuntime: '2.36' } })
  state.failMuslStage = stage
  const failures = await probeDesktopToolchain('linux')
  expect(failures.map(failure => failure.tool)).toEqual(['musl-gcc'])
  expect(failures[0]!.detail).toContain(stage === '-static'
    ? 'static executable compilation failed' : 'shared library compilation failed')
  const source = state.commands.find(row => row.command === 'musl-gcc')!.args[1]!
  await expect(readdir(dirname(source))).rejects.toMatchObject({ code: 'ENOENT' })
})

it('reports a missing musl compiler before native runtime preparation', async () => {
  vi.spyOn(process.report, 'getReport').mockReturnValue({ header: { glibcVersionRuntime: '2.36' } })
  state.missingTool = 'musl-gcc'
  await expect(requireDesktopToolchain('linux')).rejects.toThrow(/musl-gcc.*static executable/u)
})

it.each(['darwin', 'win32'] as const)('does not invoke musl-gcc for %s', async (platform) => {
  await probeDesktopToolchain(platform)
  expect(state.commands.some(row => row.command === 'musl-gcc')).toBe(false)
})
