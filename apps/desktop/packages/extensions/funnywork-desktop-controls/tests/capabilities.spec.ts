import { Context } from '@deepseek-ai/cordis'
import Tools, { defineTool } from '@deepseek-ai/dsh-tools'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import * as BrowserProvider from '@deepseek-ai/dsh-experimental-browser-use-playwright-mcp'
import * as ComputerProvider from '@deepseek-ai/dsh-experimental-computer-use-cua-driver-native'
import * as MacComputerProvider from '@deepseek-ai/dsh-experimental-cua-macos'
import { afterEach, expect, it, vi } from 'vitest'
import * as Capabilities from '../src/index.ts'
import { resolveBrowserExecutable } from '../src/browser-executable.ts'

vi.mock('../src/browser-executable.ts', () => ({ resolveBrowserExecutable: vi.fn() }))
const contexts: Context[] = []
afterEach(async () => {
  for (const ctx of contexts.splice(0)) await ctx.fiber.dispose()
  vi.restoreAllMocks()
  vi.clearAllMocks()
})

async function setup() {
  const ctx = new Context()
  contexts.push(ctx)
  await ctx.plugin(Tools)
  await ctx.plugin(SystemPrompt)
  const mounted = vi.spyOn(ctx, 'plugin').mockReturnValue(Promise.resolve() as never)
  return { ctx, mounted }
}

it('mounts installed providers for a visible browser and screenshot-free desktop without changing models', async () => {
  vi.mocked(resolveBrowserExecutable).mockReturnValue('/installed/chromium')
  const { ctx, mounted } = await setup()
  await Capabilities.apply(ctx, Capabilities.Config({}))
  expect(mounted).toHaveBeenNthCalledWith(1, BrowserProvider, expect.objectContaining({
    mode: 'launch', headless: false, executablePath: '/installed/chromium', imageResponses: 'omit',
  }))
  if (process.platform === 'darwin' || process.platform === 'win32') {
    expect(mounted).toHaveBeenNthCalledWith(2, process.platform === 'darwin' ? MacComputerProvider : ComputerProvider,
      expect.objectContaining({ mode: 'text', unavailablePolicy: 'status' }))
  } else {
    expect(mounted).toHaveBeenCalledTimes(1)
  }
  const status = await ctx.tools.execute({ name: 'browser_use_status', arguments: {}, callId: ToolCallId('status'), signal: new AbortController().signal })
  expect(status.isError).toBeFalsy()
  expect(status.content.map(block => block.type === 'text' ? block.text : '').join('\n')).toContain('"runtimeDownloads":false')
  expect(ctx.tools.schemas().map(tool => tool.name)).toContain('browser_use_status')
})

it('leaves ordinary chat services active and reports setup when Chromium is absent', async () => {
  vi.mocked(resolveBrowserExecutable).mockReturnValue(undefined)
  const { ctx, mounted } = await setup()
  await expect(Capabilities.apply(ctx, Capabilities.Config({ computer: { enabled: false } }))).resolves.toBeUndefined()
  expect(mounted).not.toHaveBeenCalled()
  const status = await ctx.tools.execute({ name: 'browser_use_status', arguments: {}, callId: ToolCallId('missing'), signal: new AbortController().signal })
  expect(status.isError).toBeFalsy()
  expect(status.content.map(block => block.type === 'text' ? block.text : '').join('\n')).toContain('browser-missing')
  expect((await ctx.systemPrompt.assemble()).sections.some(section => section.text.includes('no configured Chromium'))).toBe(true)
})

it('respects disabled capabilities and blocks an upstream runtime-install tool', async () => {
  const { ctx, mounted } = await setup()
  await Capabilities.apply(ctx, Capabilities.Config({ browser: { enabled: false }, computer: { enabled: false } }))
  expect(mounted).not.toHaveBeenCalled()
  expect(ctx.tools.schemas()).toEqual([])
  await Capabilities.apply(ctx, Capabilities.Config({ computer: { enabled: false } }))
  const execute = vi.fn(async () => 'must not run')
  ctx.tools.register(defineTool({
    name: 'mcp__playwright-mcp__browser_install', description: 'fixture', parameters: {},
    output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] }, execute,
  }))
  const denied = await ctx.tools.execute({ name: 'mcp__playwright-mcp__browser_install', arguments: {}, callId: ToolCallId('install'), signal: new AbortController().signal })
  expect(denied.isError).toBe(true)
  expect(execute).not.toHaveBeenCalled()
})

it('removes status tools and browser guidance when the capability entry unloads', async () => {
  vi.mocked(resolveBrowserExecutable).mockReturnValue(undefined)
  const { ctx, mounted } = await setup()
  mounted.mockRestore()
  const owner = await ctx.plugin({
    name: 'funnywork-capability-lifecycle-fixture',
    inject: ['tools', 'systemPrompt'],
    apply: async inner => Capabilities.apply(inner, Capabilities.Config({ computer: { enabled: false } })),
  })
  expect(ctx.tools.schemas().map(tool => tool.name)).toContain('browser_use_status')
  await owner.dispose()
  expect(ctx.tools.schemas()).toEqual([])
  expect((await ctx.systemPrompt.assemble()).sections.some(section => section.name === 'funnywork:desktop-browser')).toBe(false)
})
