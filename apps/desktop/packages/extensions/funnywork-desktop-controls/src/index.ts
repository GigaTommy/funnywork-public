/** Desktop-only text-model browser and computer composition for FunnyWork. @module */

import type { Context } from '@deepseek-ai/cordis'
import Schema from '@deepseek-ai/schemastery'
import { defineTool } from '@deepseek-ai/dsh-tools'
import * as BrowserProvider from '@deepseek-ai/dsh-experimental-browser-use-playwright-mcp'
import * as ComputerProvider from '@deepseek-ai/dsh-experimental-computer-use-cua-driver-native'
import * as MacComputerProvider from '@deepseek-ai/dsh-experimental-cua-macos'
import { resolveBrowserExecutable } from './browser-executable.ts'
import type {} from '@deepseek-ai/dsh-system-prompt'

/** Loader identity for the Desktop profile bundle's runtime entry. */
export const name = 'funnywork-desktop-controls'

/** Both provider registries are installed by this bundle's patch before runtime activation. */
export const inject = ['browserUse', 'computerUse', 'agents', 'tools', 'systemPrompt', 'workingDirectory']

/** Local capability switches; model permissions and image capabilities are never changed. */
export interface Config {
  /** Isolated browser selection and launch behavior. */
  browser: {
    /** Register browser controls and status when enabled. */
    enabled: boolean
    /** Hide the controlled browser window when true. */
    headless: boolean
    /** Complete Chromium-family executable path; an unavailable selection is reported. */
    executablePath?: string
    /** Additional executable candidates, useful for external-volume installations. */
    searchPaths: string[]
    /** Maximum time for one browser tool call. */
    toolCallTimeoutMs: number
  }
  /** Accessibility-based desktop control. */
  computer: {
    /** Register native accessibility controls and status when enabled. */
    enabled: boolean
  }
}

/** Default to a visible isolated browser and screenshot-free accessibility control. */
export const Config: Schema<Partial<{ browser: Partial<Config['browser']>; computer: Partial<Config['computer']> }>, Config> = Schema.object({
  browser: Schema.object({
    enabled: Schema.boolean().default(true),
    headless: Schema.boolean().default(false),
    executablePath: Schema.string().pattern(/\S/u),
    searchPaths: Schema.array(Schema.string().pattern(/\S/u)).default([]),
    toolCallTimeoutMs: Schema.number().min(1).default(60000),
  }).default({}),
  computer: Schema.object({ enabled: Schema.boolean().default(true) }).default({}),
})

/**
 * Mount the installed providers without model changes, runtime downloads, or personal browser profiles.
 * Missing Chromium leaves a status tool available and does not reject normal Session creation.
 * @param ctx Desktop Host services shared by the providers and their Session scopes.
 * @param config Validated capability switches and browser selection.
 * @returns Once each enabled provider is registered or reports its unavailable state.
 */
export async function apply(ctx: Context, config: Config): Promise<void> {
  if (config.browser.enabled) {
    const executablePath = resolveBrowserExecutable(config.browser)
    ctx.effect(() => ctx.tools.register(defineTool({
      name: 'browser_use_status',
      description: 'Report FunnyWork browser availability. Browser actions use an isolated controlled browser window and accessibility snapshots, separate from the sidebar browser and personal browser profiles. They require a model that supports tool calls; image input is not required.',
      parameters: {},
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      // oxlint-disable-next-line typescript/require-await -- Status tools retain the asynchronous Tool.execute contract.
      async execute(_args, exec) {
        exec.signal.throwIfAborted()
        return JSON.stringify({
          status: executablePath === undefined ? 'browser-missing' : 'configured',
          mode: 'accessibility-dom',
          visible: !config.browser.headless,
          imageResponses: 'omit',
          runtimeDownloads: false,
          ...(executablePath === undefined ? {
            nextStep: 'Install Chrome, Edge, or Chromium locally, or select its executable in the FunnyWork Desktop Controls plugin configuration, then restart FunnyWork. Ordinary chat remains available.',
          } : { executablePath }),
        })
      },
    })))
    ctx.on('tools/pre-execute', async (exec, next) => {
      if (exec.name === 'mcp__playwright-mcp__browser_install') {
        return { kind: 'deny', reason: 'Browser runtime installation is disabled. Install a local Chromium-family browser and restart FunnyWork.' }
      }
      return next()
    })
    ctx.effect(() => ctx.systemPrompt.section({
      name: 'funnywork:desktop-browser',
      order: ctx.systemPrompt.getSectionOrder('TOOL_COMPUTER_USE'),
      text: executablePath === undefined
        ? 'FunnyWork browser controls are unavailable because no configured Chromium executable was found. Use browser_use_status to report setup requirements; do not download a browser runtime automatically.'
        : 'FunnyWork browser tools operate a separate isolated browser window. Use browser_snapshot accessibility references and DOM tools to inspect and act, then verify the resulting page. Snapshots are text; image responses are disabled. The sidebar browser and personal browser profiles are separate. Ask the user to sign in manually in the controlled window when needed. Browser state persists within this live Session and is discarded when the Session unloads or FunnyWork signs out. Cancellation does not undo actions already delivered.',
    }))
    if (executablePath !== undefined) {
      await ctx.plugin(BrowserProvider, {
        mode: 'launch', headless: config.browser.headless, executablePath,
        imageResponses: 'omit', toolCallTimeoutMs: config.browser.toolCallTimeoutMs,
      })
    }
  }
  if (config.computer.enabled) {
    if (process.platform === 'darwin') {
      await ctx.plugin(MacComputerProvider, { mode: 'text', unavailablePolicy: 'status' })
    } else if (process.platform === 'win32') {
      await ctx.plugin(ComputerProvider, { mode: 'text', unavailablePolicy: 'status' })
    } else {
      ctx.effect(() => ctx.tools.register(defineTool({
        name: 'computer_use_status', description: 'Report why FunnyWork computer controls are unavailable on this operating system.',
        parameters: {},
        output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
        // oxlint-disable-next-line typescript/require-await -- Status tools retain the asynchronous Tool.execute contract.
        async execute(_args, exec) {
          exec.signal.throwIfAborted()
          return JSON.stringify({ status: 'unsupported-platform', platform: process.platform, nextStep: 'Computer controls currently support macOS and Windows. Browser controls remain independently available.' })
        },
      })))
    }
  }
}
