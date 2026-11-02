/** Chromium browser tools from the pinned Playwright MCP server. @module */

import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Context } from '@deepseek-ai/cordis'
import Schema from '@deepseek-ai/schemastery'
import { BrowserMcpConfig, mountSessionMcp, validateBrowserMcpConfig } from '@deepseek-ai/dsh-experimental-browser-use-runtime/mcp'
import type {} from '@deepseek-ai/dsh-working-directory'

/** Cordis identity for the Playwright MCP browser provider. */
export const name = 'experimental-browser-use-playwright-mcp'

/** Services required for scoped MCP startup and prompt readiness checks. */
export const inject = ['browserUse', 'agents', 'tools', 'systemPrompt', 'workingDirectory']

/** Fixed Chromium launch or existing-browser attachment settings. */
export type Config = BrowserMcpConfig & {
  /** Whether the server returns image blocks; omit keeps accessibility snapshots usable with text-only models. */
  imageResponses: 'allow' | 'omit'
}

type ConfigInput = Parameters<typeof BrowserMcpConfig>[0] & { imageResponses?: 'allow' | 'omit' }

/** Validate the launch or attachment configuration before activation. */
export const Config: Schema<ConfigInput, Config> = Schema.intersect([
  BrowserMcpConfig,
  Schema.object({ imageResponses: Schema.union(['allow', 'omit']).default('allow') }),
])

/**
 * Expose Playwright's upstream tools in each live Session's scope.
 * The pinned npm server runs under the current Node executable; browser state is not persisted by DSH.
 * Linux launches require Chromium's sandbox; unavailable sandbox support never enables an unconfined retry.
 * @param ctx - provider context supplying browser use, Agents, and tools.
 * @param config - validated browser choice and optional tool timeout.
 */
export function apply(ctx: Context, config: Config): void {
  validateBrowserMcpConfig(config)
  const cli = join(dirname(fileURLToPath(import.meta.resolve('@playwright/mcp/package.json'))), 'cli.js')
  // Upstream environment options can otherwise replace the configured browser
  // mode or import an unrelated profile. Empty values mean absent to its parser.
  const env = Object.fromEntries(Object.keys(process.env)
    .filter(key => key.toUpperCase().startsWith('PLAYWRIGHT_MCP_'))
    .map(key => [key, '']))
  env.PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
  const args = [cli, '--browser', 'chromium']
  if (config.imageResponses === 'omit') args.push('--image-responses', 'omit', '--snapshot-mode', 'full')
  if (config.mode === 'attach') {
    args.push('--cdp-endpoint', config.endpoint)
  } else {
    args.push('--isolated')
    if (process.platform === 'linux') args.push('--sandbox')
    if (config.headless) args.push('--headless')
    if (config.executablePath !== undefined) args.push('--executable-path', config.executablePath)
  }
  mountSessionMcp(ctx, {
    name: 'playwright-mcp',
    exclusive: config.mode === 'attach',
    command: process.execPath,
    args,
    env,
    ...config.toolCallTimeoutMs === undefined ? {} : { toolCallTimeoutMs: config.toolCallTimeoutMs },
  })
}
