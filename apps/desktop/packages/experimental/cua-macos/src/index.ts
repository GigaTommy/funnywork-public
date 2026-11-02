/** macOS native CUA provider using the shared application and window API. */
import { isAbsolute, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Context } from '@deepseek-ai/cordis'
import { CuaConfig, installCua } from '@deepseek-ai/dsh-cua'
import { TEXT_GUIDANCE } from './text-guidance.ts'
import { nativeHostArchitecture, prepareNativeExecutable } from './native-build.ts'
import { createSharedNativeChannel } from './shared-native.ts'
import { GUIDANCE } from './guidance.ts'
export { GUIDANCE } from './guidance.ts'

/** Loader plugin identity. */
export const name = 'experimental-cua-macos'
/** Services required by the shared CUA executor and the native launcher. */
export const inject = ['computerUse', 'tools', 'systemPrompt', 'subprocess', 'agents']
/** Native preparation, operation bounds, and profile-local application grants. */
export type Config = CuaConfig
/** Validate the shared configuration before preparing the macOS helper. */
export const Config: typeof CuaConfig = CuaConfig

const description = 'Inspect macOS permissions, select an application and window, read AX state, capture screenshots or dispatch input. Use appHandle from getApp; verify actions by reading fresh state.'

/**
 * Mount logged native operations with separate Agent connections to the shared helper.
 * @param ctx - Host context with computer-use, subprocess and tool services.
 * @param config - Validated native preparation and operation bounds.
 * @returns Completion after native preparation and tool registration.
 */
export async function apply(ctx: Context, config: Config): Promise<void> {
  let preparation: { kind: 'preparing' } | { kind: 'ready'; helperPath: string } | { kind: 'unavailable'; error: string } = { kind: 'preparing' }
  if (config.mode === 'text' || config.unavailablePolicy === 'status') {
    ctx.effect(() => ctx.tools.register({
      name: 'cua_status', description: 'Read native computer-use availability and macOS permission diagnostics without requesting permission or capturing screenshots.',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: { schema: {}, render: (_args, value) => [{ type: 'text', text: JSON.stringify(value) }] },
      async execute(_args, execution) {
        const status = {
          available: preparation.kind === 'ready', mode: config.mode, platform: process.platform,
          native: preparation,
          nextAction: 'For Accessibility observations and input, allow FunnyWork in macOS System Settings > Privacy & Security > Accessibility. Screen Recording is needed only for screenshots in vision mode. After changing permission, read fresh state before further input. A missing native helper requires application repair; text chat remains available.',
        }
        if (preparation.kind !== 'ready' || execution.agent === undefined) return status
        const capabilities = await ctx.tools.execute({
          name: 'cua', arguments: { method: 'getCapabilities', params: {} },
          agent: execution.agent, callId: execution.callId, rootCallId: execution.rootCallId,
          parent: execution.token, signal: execution.signal,
        })
        return { ...status, available: !capabilities.isError,
          capabilities: capabilities.isError ? { error: capabilities.content } : capabilities.value }
      },
    }), 'cua.status')
  }
  if (config.helperPath !== undefined && !isAbsolute(config.helperPath)) throw new Error('dsh-cua helperPath must be absolute')
  if (config.desktopLockPath !== undefined && !isAbsolute(config.desktopLockPath)) throw new Error('dsh-cua desktopLockPath must be absolute')
  const controller = new AbortController()
  try {
    const helperPath = config.helperPath ?? await prepareHelper()
    controller.signal.throwIfAborted()
    installCua(ctx, config, {
      helperPath, createChannel: createSharedNativeChannel,
      guidance: config.mode === 'text' ? TEXT_GUIDANCE : GUIDANCE,
      description: config.mode === 'text'
        ? 'Inspect macOS permissions and exact applications through Accessibility text. Select getApp, retain appHandle, then read getState and use its snapshotId plus elementIndex. Screenshots and pointer coordinates are unavailable.'
        : description,
    })
    preparation = { kind: 'ready', helperPath }
  } catch (error) {
    if (controller.signal.aborted || config.unavailablePolicy === 'error') throw error
    preparation = { kind: 'unavailable', error: error instanceof Error ? error.message : String(error) }
    ctx.logger.warn(`Native Computer Use is unavailable; cua_status reports the required repair: ${preparation.error}`)
  }

  async function prepareHelper(): Promise<string> {
    const architecture = nativeHostArchitecture(process.platform, process.arch)
    const prepared = prepareNativeExecutable({
      packageRoot: resolve(fileURLToPath(new URL('..', import.meta.url))),
      architecture, subprocess: ctx.subprocess,
      timeoutMs: config.buildTimeoutMs, graceMs: config.graceMs, signal: controller.signal,
    })
    ctx.effect(() => async () => {
      controller.abort()
      // Preparation failure is reported by apply; disposal waits for complete compiler cleanup.
      await prepared.catch(() => {})
    }, 'dsh-cua.native-preparation')
    return await prepared
  }
}
