/**
 * Computer use through the in-process Cua Driver native SDK and its own tools.
 * @module @deepseek-ai/dsh-experimental-computer-use-cua-driver-native
 */

import type { Context } from '@deepseek-ai/cordis'
import { isAbsolute } from 'node:path'
import Schema from '@deepseek-ai/schemastery'
import { ComputerUseProviderName } from '@deepseek-ai/dsh-computer-use/brand'
import { createMcpToolDefinition } from '@deepseek-ai/dsh-mcp-client'
import { z } from 'zod'
import type { CuaDriver as NativeDriver } from '@trycua/cua-driver'
import type { ToolExecution } from '@deepseek-ai/dsh-tools'
import type {} from '@deepseek-ai/dsh-computer-use'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type {} from '@deepseek-ai/dsh-tools'
import { rejectTextImages, textArguments, textTool } from './policy.ts'
import { DesktopTurnLock, type DesktopLease } from '@deepseek-ai/dsh-computer-use/turn-lock'

/** Cordis plugin identity for the native Cua Driver provider. */
export const name = 'experimental-computer-use-cua-driver-native'

/** Services required before the native runtime can publish tools. */
export const inject = ['computerUse', 'tools', 'systemPrompt']

/** Native observation modality and unavailable-runtime handling. */
export interface Config {
  /** `text` exposes bounded Accessibility tools without screenshots or coordinate input; `vision` preserves the upstream catalog. */
  mode: 'text' | 'vision'
  /** `status` keeps an actionable status tool available when native loading fails; `error` rejects activation. */
  unavailablePolicy: 'error' | 'status'
  /** Maximum wait for closing one host-owned native session, in milliseconds. */
  cleanupTimeoutMs: number
  /** Absolute OS-user desktop lease override; omission uses stable native IPC storage. */
  desktopLockPath?: string
  /** Wait budget for another host's whole desktop turn, in milliseconds. */
  desktopLockWaitMs: number
}

/** Resolve compatibility defaults; Desktop explicitly selects text and status handling. */
export const Config: Schema<Partial<Config>, Config> = Schema.object({
  mode: Schema.union(['text', 'vision']).default('vision'),
  unavailablePolicy: Schema.union(['error', 'status']).default('error'),
  cleanupTimeoutMs: Schema.number().min(1).default(5000),
  desktopLockPath: Schema.string(),
  desktopLockWaitMs: Schema.number().step(1).min(1).max(2 ** 31 - 1).default(2000),
})

const ToolCatalog = z.object({
  tools: z.array(z.object({
    name: z.string().min(1),
    description: z.string().optional(),
    inputSchema: z.record(z.string(), z.unknown()),
    outputSchema: z.unknown().optional(),
  })),
})

const NativeResult = z.looseObject({
  content: z.array(z.unknown()),
  structuredContent: z.record(z.string(), z.unknown()).optional(),
})

/** DeepSeek's function-name alphabet and maximum length are protocol constants. */
const TOOL_NAME = /^[A-Za-z0-9_-]{1,64}$/u

const GUIDANCE = `Cua Driver native computer-use tools operate the host desktop. Discover the exact app and window, then get a fresh window snapshot before acting. Use element_token from that snapshot, or coordinates from its screenshot. A new snapshot of that window invalidates its earlier element tokens. Select either target or the legacy pid/window_id fields; do not combine them.

Prefer background delivery. A refusal does not authorize a foreground retry. Verify the requested outcome from fresh state after an action; a delivered click alone does not prove the outcome. After cancellation, inspect current state before retrying because completed input is not rolled back. Other sessions and applications may change the same desktop.

On macOS, cursor-overlay operations may return facility_unavailable even when screenshots and input work.`

const TEXT_GUIDANCE = `Computer use is configured for Accessibility text observations. Screenshots, OCR, pixel coordinates, global input, and clipboard tools are unavailable. Use cua_driver_native__status to diagnose runtime and OS permissions. Discover the exact app and window, then call get_window_state for its tree and snapshot-bound element_token. Its include_accessibility_tree is always true and include_screenshot is always false. Act only on tokens returned by fresh state for that exact pid and window_id. Never infer click coordinates from text or guess at an empty or degraded tree.

The first desktop call reserves the OS user's desktop for the entire current turn across participating Host processes; another session receives COMPUTER_BUSY. Calls within that turn are serialized. Native session identity belongs to the host and is not a tool argument. Background delivery does not authorize an automatic foreground retry. After every action, observe or verify fresh state before claiming success. Cancellation waits for admitted native calls and cleanup before releasing ownership, but cannot undo input already delivered; inspect state before any retry. Unrelated applications and the user can still change desktop state.`

/**
 * Own one native runtime and expose its catalog through the MCP result adapter.
 * Startup failures roll back every registration. Unload removes tools, aborts
 * calls and image admission, awaits settlement and SDK shutdown, then releases computer use.
 * @param ctx - context providing the exclusive registration and tool services.
 * @param config - resolved observation, availability, and cleanup policy; omission retains the upstream catalog.
 * @returns after native import, runtime creation, and tool discovery complete.
 */
export async function apply(ctx: Context, config: Config = Config({})): Promise<void> {
  if (config.desktopLockPath !== undefined && !isAbsolute(config.desktopLockPath)) throw new Error('Native Computer Use desktopLockPath must be absolute')
  const lifetime = new AbortController()
  const pending = new Set<Promise<unknown>>()
  let driver: NativeDriver | undefined
  let ownership: DesktopTurnLock | undefined
  // Cordis announces disposal before it awaits asynchronous plugin startup.
  ctx.on('internal/plugin', (fiber) => {
    if (fiber === ctx.fiber && fiber.uid === null) lifetime.abort()
  }, { global: true })
  let ready: Promise<void> = Promise.resolve()
  const dispose = ctx.effect(function* () {
    yield ctx.computerUse.register(ComputerUseProviderName('cua-driver-native'))
    yield async () => {
      lifetime.abort()
      // apply() reports startup failure; teardown still owns its native handle.
      await ready.catch(() => {})
      await Promise.allSettled(pending)
      await ownership?.dispose()
      if (driver !== undefined) {
        await driver.shutdown()
        driver.uniffiDestroy()
      }
    }
    const child = ctx.plugin({
      name: 'computer-use-cua-driver-native-runtime',
      inject: ['tools', 'systemPrompt'],
      apply: mountRuntime,
    })
    yield child.dispose
    ready = Promise.resolve(child).then(() => {})
  }, 'computer-use-cua-driver-native.runtime')
  try {
    await ready
  } catch (error) {
    await dispose()
    throw error
  }

  /** The child owns tool registrations; the outer effect owns native teardown. */
  async function mountRuntime(inner: Context): Promise<void> {
    const names = new Set<string>()
    const leases = new WeakMap<ToolExecution, DesktopLease>()
    let startupFailure: string | undefined
    let nativeSessionCleanup = false
    ownership = new DesktopTurnLock(inner, async (lease) => {
      if (driver === undefined || !nativeSessionCleanup) return
      const result = await driver.callTool('end_session', JSON.stringify({ session: lease.nativeSession }), { signal: AbortSignal.timeout(config.cleanupTimeoutMs) })
      const value: unknown = JSON.parse(result.rawJson)
      if (value !== null && typeof value === 'object' && 'isError' in value && value.isError === true) {
        throw new Error('Native session cleanup was refused')
      }
    }, config.mode === 'text' ? { path: config.desktopLockPath, waitMs: config.desktopLockWaitMs } : undefined)
    const lock = ownership
    const statusName = 'cua_driver_native__status'
    if (config.mode === 'text' || config.unavailablePolicy === 'status') {
      names.add(statusName)
      inner.tools.register(createMcpToolDefinition(inner, {
        name: statusName,
        rawName: 'status',
        description: 'Read computer-use runtime availability, observation mode, desktop ownership, and host OS permission status without prompting or capturing the screen.',
        inputSchema: { type: 'object', properties: {}, additionalProperties: false },
        async call(_args, execution) {
          let permissions: unknown = null
          let permissionError: string | undefined
          if (driver !== undefined && startupFailure === undefined) {
            try {
              const permissionResult: unknown = JSON.parse((await driver.callTool('check_permissions', '{"prompt":false}', { signal: execution.signal })).rawJson)
              if (permissionResult !== null && typeof permissionResult === 'object' && 'structuredContent' in permissionResult) permissions = permissionResult.structuredContent
            } catch (error) {
              permissionError = error instanceof Error ? error.message : String(error)
            }
          }
          const status = {
            available: driver !== undefined && startupFailure === undefined && lock.cleanupFailure === undefined,
            mode: config.mode,
            platform: process.platform,
            desktopBusy: lock.busy,
            permissions,
            ...(startupFailure === undefined ? {} : { code: 'COMPUTER_UNAVAILABLE', error: startupFailure }),
            ...(permissionError === undefined ? {} : { permissionError }),
            ...(lock.cleanupFailure === undefined ? {} : { cleanupError: lock.cleanupFailure }),
            nextAction: process.platform === 'darwin'
              ? 'Allow the launching FunnyWork application in macOS System Settings > Privacy & Security > Accessibility, then restart it. Screen Recording is required only for vision mode. If the native runtime is missing or cleanup failed, restart or repair the application; text chat remains available.'
              : 'Use a supported graphical desktop and grant the launching application its OS desktop permissions. If the native runtime is missing or cleanup failed, restart or repair the application; text chat remains available.',
          }
          return { content: [{ type: 'text', text: JSON.stringify(status) }], structuredContent: status }
        },
      }))
    }
    inner.on('tools/execute', async (exec, next) => {
      if (!names.has(exec.name)) return next()
      const upstream = exec.signal
      exec.signal = AbortSignal.any([upstream, lifetime.signal])
      const operation = Promise.resolve().then(async () => {
        if (exec.name === statusName || exec.name === 'cua_driver_native__check_permissions') return await next()
        return await lock.run(exec, async (lease) => {
          const wrapped = exec.signal
          exec.signal = AbortSignal.any([wrapped, lease.signal])
          leases.set(exec, lease)
          try {
            return await next()
          } finally {
            leases.delete(exec)
            exec.signal = wrapped
          }
        })
      })
      pending.add(operation)
      try {
        return await operation
      } finally {
        pending.delete(operation)
        exec.signal = upstream
      }
    })
    inner.systemPrompt.section({
      name: 'computer-use:cua-driver-native',
      order: inner.systemPrompt.getSectionOrder('TOOL_COMPUTER_USE'),
      text: config.mode === 'text' ? TEXT_GUIDANCE : GUIDANCE,
    })

    try {
      await discoverTools()
    } catch (error) {
      if (lifetime.signal.aborted || config.unavailablePolicy === 'error') throw error
      startupFailure = error instanceof Error ? error.message : String(error)
      inner.logger.warn(`Computer use is unavailable; use ${statusName} for diagnostics: ${startupFailure}`)
    }

    async function discoverTools(): Promise<void> {
      const { CuaDriver } = await import('@trycua/cua-driver')
      lifetime.signal.throwIfAborted()
      // The constructor returns an owned handle omitted from its declared interface.
      const activeDriver = driver = CuaDriver.create(undefined) as NativeDriver
      const catalog = ToolCatalog.parse(JSON.parse(await activeDriver.listToolsJson({ signal: lifetime.signal })))
      lifetime.signal.throwIfAborted()
      const catalogNames = new Set<string>()
      for (const tool of catalog.tools) {
        const publicName = `cua_driver_native__${tool.name}`
        if (!TOOL_NAME.test(publicName)) throw new Error(`Cua Driver tool "${tool.name}" exceeds the supported function-name format`)
        if (catalogNames.has(publicName) || names.has(publicName)) throw new Error(`Cua Driver listed tool "${tool.name}" more than once`)
        catalogNames.add(publicName)
      }
      nativeSessionCleanup = catalog.tools.some(tool => tool.name === 'end_session')
      for (const original of catalog.tools) {
        const tool = config.mode === 'text' ? textTool(original) : original
        if (tool === undefined) continue
        const publicName = `cua_driver_native__${tool.name}`
        names.add(publicName)
        const definition = createMcpToolDefinition(inner, {
          name: publicName,
          rawName: tool.name,
          description: tool.description ?? '',
          inputSchema: tool.inputSchema,
          outputSchema: tool.outputSchema,
          async call(args, execution) {
            const combined = AbortSignal.any([execution.signal, lifetime.signal])
            combined.throwIfAborted()
            const nativeArgs = config.mode === 'text' ? textArguments(tool, args) : args
            const lease = leases.get(execution)
            const properties = original.inputSchema.properties
            if (config.mode === 'text' && lease !== undefined && execution.agent !== undefined && properties !== null && typeof properties === 'object' && 'session' in properties) {
              nativeArgs.session = lease.nativeSession
              lease.usedNativeSession = true
            }
            const result = await activeDriver.callTool(tool.name, JSON.stringify(nativeArgs), { signal: combined })
            combined.throwIfAborted()
            const raw: unknown = JSON.parse(result.rawJson)
            if (config.mode === 'text') rejectTextImages(raw)
            const parsed = NativeResult.parse(raw)
            return parsed.structuredContent === undefined ? parsed : {
              ...parsed,
              content: [...parsed.content, { type: 'text', text: JSON.stringify(parsed.structuredContent) }],
            }
          },
        })
        inner.tools.register(definition)
      }
    }
  }
}
