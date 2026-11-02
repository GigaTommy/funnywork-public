import { existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import { Context } from '@deepseek-ai/cordis'
import { afterEach, expect, it, vi } from 'vitest'
import { mountSessionMcp } from '@deepseek-ai/dsh-experimental-browser-use-runtime/mcp'
import * as Provider from '../src/index.ts'

vi.mock('@deepseek-ai/dsh-experimental-browser-use-runtime/mcp', async importOriginal => ({
  ...await importOriginal<typeof import('@deepseek-ai/dsh-experimental-browser-use-runtime/mcp')>(),
  mountSessionMcp: vi.fn(),
}))
const platformDescriptor = Object.getOwnPropertyDescriptor(process, 'platform')!
const platforms = ['linux', 'darwin', 'win32'] as const
afterEach(() => {
  Object.defineProperty(process, 'platform', platformDescriptor)
  vi.clearAllMocks()
  vi.unstubAllEnvs()
})

it.each(platforms)('uses the pinned Playwright executable with isolated %s launch settings', (platform) => {
  Object.defineProperty(process, 'platform', { ...platformDescriptor, value: platform })
  vi.stubEnv('PLAYWRIGHT_MCP_CDP_ENDPOINT', 'http://invalid.example:1')
  vi.stubEnv('PLAYWRIGHT_MCP_SANDBOX', 'false')
  Provider.apply(new Context(), Provider.Config({ mode: 'launch', executablePath: '/custom/chromium', toolCallTimeoutMs: 123 }))
  const options = vi.mocked(mountSessionMcp).mock.calls[0]![1]
  expect(existsSync(options.args[0]!)).toBe(true)
  expect(options).toMatchObject({ name: 'playwright-mcp', exclusive: false, command: process.execPath, toolCallTimeoutMs: 123 })
  expect(options.env?.PLAYWRIGHT_MCP_CDP_ENDPOINT).toBe('')
  expect(options.env?.PLAYWRIGHT_MCP_SANDBOX).toBe('')
  expect(options.env?.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD).toBe('1')
  expect(options.args.slice(1)).toEqual(['--browser', 'chromium', '--isolated',
    ...platform === 'linux' ? ['--sandbox'] : [], '--headless', '--executable-path', '/custom/chromium'])
  expect('default' in Provider).toBe(false)
})

it.each(platforms)('supports headed %s launch and attaches without changing the external browser', (platform) => {
  Object.defineProperty(process, 'platform', { ...platformDescriptor, value: platform })
  Provider.apply(new Context(), Provider.Config({ mode: 'launch', headless: false }))
  expect(vi.mocked(mountSessionMcp).mock.calls[0]![1].args.slice(1)).toEqual(['--browser', 'chromium', '--isolated',
    ...platform === 'linux' ? ['--sandbox'] : []])
  Provider.apply(new Context(), Provider.Config({ mode: 'attach', endpoint: 'http://127.0.0.1:9222' }))
  const options = vi.mocked(mountSessionMcp).mock.calls[1]![1]
  expect(options.exclusive).toBe(true)
  expect(options.args.slice(1)).toEqual(['--browser', 'chromium', '--cdp-endpoint', 'http://127.0.0.1:9222'])
})

it('rejects malformed endpoints before mounting any browser resources', () => {
  expect(() => { Provider.apply(new Context(), Provider.Config({ mode: 'attach', endpoint: 'http://localhost:bad/path' })) }).toThrow('browser endpoint')
  expect(mountSessionMcp).not.toHaveBeenCalled()
})

it.each(platforms)('keeps %s DOM snapshots and omits image responses for a text-only model', (platform) => {
  Object.defineProperty(process, 'platform', { ...platformDescriptor, value: platform })
  Provider.apply(new Context(), Provider.Config({ mode: 'launch', headless: false, executablePath: '/custom/chromium', imageResponses: 'omit' }))
  expect(vi.mocked(mountSessionMcp).mock.calls[0]![1].args.slice(1)).toEqual([
    '--browser', 'chromium', '--image-responses', 'omit', '--snapshot-mode', 'full',
    '--isolated', ...platform === 'linux' ? ['--sandbox'] : [], '--executable-path', '/custom/chromium',
  ])
  expect(Provider.Config({ mode: 'launch' }).imageResponses).toBe('allow')
  expect(() => Provider.Config({ mode: 'launch', imageResponses: 'invalid' } as never)).toThrow()
})

it('does not retry failed Linux provider setup without the sandbox', () => {
  Object.defineProperty(process, 'platform', { ...platformDescriptor, value: 'linux' })
  const failure = new Error('browser sandbox unavailable')
  vi.mocked(mountSessionMcp).mockImplementationOnce(() => { throw failure })
  expect(() => { Provider.apply(new Context(), Provider.Config({ mode: 'launch' })) }).toThrow(failure)
  expect(mountSessionMcp).toHaveBeenCalledTimes(1)
  const options = vi.mocked(mountSessionMcp).mock.calls[0]![1]
  expect(options.args).toContain('--sandbox')
  expect(options.args).not.toContain('--no-sandbox')
})

/** Only the pinned MCP parser/configuration API is used; no CLI action or browser is started. */
interface McpCommand {
  parseOptions(args: readonly string[]): void
  opts(): Record<string, unknown>
}

it('resolves the shipped Linux launch arguments to an enabled sandbox in pinned Playwright MCP', async () => {
  const requireProvider = createRequire(new URL('../package.json', import.meta.url))
  const requireMcp = createRequire(requireProvider.resolve('@playwright/mcp/package.json'))
  const { program } = requireMcp('playwright-core/lib/utilsBundle') as {
    program: { constructor: new () => McpCommand }
  }
  const { tools } = requireMcp('playwright-core/lib/coreBundle') as { tools: {
    decorateMCPCommand(command: McpCommand, version: string): void
    resolveCLIConfigForMCP(options: Record<string, unknown>, env: Record<string, string>): Promise<{
      browser: { isolated: boolean; launchOptions: { chromiumSandbox: boolean; executablePath: string } }
    }>
  } }
  Object.defineProperty(process, 'platform', { ...platformDescriptor, value: 'linux' })
  vi.stubEnv('PLAYWRIGHT_MCP_SANDBOX', 'false')
  Provider.apply(new Context(), Provider.Config({ mode: 'launch', executablePath: '/custom/chromium' }))
  const options = vi.mocked(mountSessionMcp).mock.calls[0]![1]
  const command = new program.constructor()
  tools.decorateMCPCommand(command, '0.0.80')
  command.parseOptions(options.args.slice(1))
  const config = await tools.resolveCLIConfigForMCP(command.opts(), options.env ?? {})
  expect(config.browser.isolated).toBe(true)
  expect(config.browser.launchOptions.chromiumSandbox).toBe(true)
  expect(config.browser.launchOptions.executablePath).toBe('/custom/chromium')
  expect(options.args).not.toContain('--no-sandbox')
})
