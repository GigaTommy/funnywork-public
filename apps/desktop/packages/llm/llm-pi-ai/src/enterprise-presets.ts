/** Enterprise persona synchronization; remote data never supplies executable plugin configuration. */
import type { Context } from '@deepseek-ai/cordis'
import { funnyWorkTransport } from '@deepseek-ai/dsh-http-proxy'
import type {} from '@deepseek-ai/dsh-agent-preset-registry'
import type { EnterpriseOptions } from './enterprise-config.ts'

interface PersonaRevision {
  id: string
  name: string
  description: string
  persona: string
  selectable: boolean
  version: number
}
interface PersonaCatalog {
  allowPersonalPresets: boolean
  defaultPresetId: string
  items: PersonaRevision[]
}

/** Validate administrator-published text and version identities at the HTTP read.
 * @param value JSON received from the enterprise entry.
 * @returns A non-executable catalog accepted by the preset registry.
 */
export function parseEnterprisePresets(value: unknown): PersonaCatalog {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('schemaVersion' in value) ||
    value.schemaVersion !== 1 ||
    !('allowPersonalPresets' in value) ||
    typeof value.allowPersonalPresets !== 'boolean' ||
    !('defaultPresetId' in value) ||
    typeof value.defaultPresetId !== 'string' ||
    !('items' in value) ||
    !Array.isArray(value.items) ||
    value.items.length > 512
  )
    throw new Error('Invalid enterprise preset catalog')
  const ids = new Set<string>()
  const items = value.items.map((candidate: unknown): PersonaRevision => {
    if (typeof candidate !== 'object' || candidate === null)
      throw new Error('Invalid enterprise preset revision')
    const row = candidate as Record<string, unknown>
    if (
      typeof row.id !== 'string' ||
      !/^enterprise-[A-Za-z0-9_-]{1,64}-v[1-9][0-9]*$/.test(row.id) ||
      ids.has(row.id) ||
      row.base !== 'standard' ||
      typeof row.name !== 'string' ||
      row.name.length < 1 ||
      row.name.length > 120 ||
      typeof row.description !== 'string' ||
      row.description.length > 1000 ||
      typeof row.persona !== 'string' ||
      row.persona.length < 1 ||
      row.persona.length > 20000 ||
      row.persona.includes('{{') ||
      row.persona.includes('}}') ||
      row.persona.includes('\0') ||
      typeof row.selectable !== 'boolean' ||
      typeof row.version !== 'number' ||
      !Number.isSafeInteger(row.version) ||
      row.version < 1 ||
      !row.id.endsWith(`-v${row.version}`)
    )
      throw new Error('Invalid enterprise preset revision')
    ids.add(row.id)
    return {
      id: row.id,
      name: row.name,
      description: row.description,
      persona: row.persona,
      selectable: row.selectable,
      version: row.version,
    }
  })
  if (
    value.defaultPresetId !== 'standard' &&
    !items.some(row => row.id === value.defaultPresetId && row.selectable)
  )
    throw new Error('Enterprise preset default is unavailable')
  return {
    allowPersonalPresets: value.allowPersonalPresets,
    defaultPresetId: value.defaultPresetId,
    items,
  }
}

/** Own periodic synchronization under the optional Harness preset service.
 * @param ctx Plugin owner; all timers and registrations stop with its effect.
 * @param config Validated enterprise endpoint and refresh limits.
 * @param credentials Current device entry token reader.
 */
export function installEnterprisePresets(
  ctx: Context,
  config: EnterpriseOptions,
  credentials: () => Promise<string>,
): void {
  ctx.inject(['agentPresets', 'loader'], (child) => {
    child.agentPresets.requireEnterprisePolicy()
    child.effect(() => {
      const http = funnyWorkTransport(config.origin, (url, init) => fetch(url, init))
      const lifetime = new AbortController()
      const cancelled = () => lifetime.signal.aborted
      let pending: Promise<void> | undefined
      const refresh = async () => {
        const stopped = Promise.withResolvers<void>()
        const abort = () => { stopped.resolve() }
        lifetime.signal.addEventListener('abort', abort, { once: true })
        try {
          await Promise.race([child.loader.await(), stopped.promise])
        } finally {
          lifetime.signal.removeEventListener('abort', abort)
        }
        if (cancelled()) return
        const token = await credentials()
        const response = await http.fetch(
          config.origin + '/api/account/inference/agent-presets',
          {
            headers: { Authorization: `Bearer ${token}` },
            redirect: 'error',
            signal: AbortSignal.any([
              lifetime.signal,
              AbortSignal.timeout(config.requestTimeoutMs),
            ]),
          },
        )
        if (!response.ok)
          throw new Error(
            `Enterprise preset catalog returned ${response.status}`,
          )
        const bytes = await response.arrayBuffer()
        if (bytes.byteLength > 4194304)
          throw new Error('Enterprise preset catalog exceeds client limit')
        const catalog = parseEnterprisePresets(
          JSON.parse(new TextDecoder().decode(bytes)),
        )
        if (!cancelled())
          await child.agentPresets.synchronizeEnterprise(catalog)
      }
      const run = () => {
        if (pending !== undefined || cancelled()) return
        pending = refresh()
          .catch((error: unknown) => {
            if (!cancelled())
              child.logger.warn(
                `Enterprise preset synchronization: ${String(error)}`,
              )
          })
          .finally(() => {
            pending = undefined
          })
      }
      // Do not await Loader settlement from its own plugin activation.
      queueMicrotask(run)
      const timer = setInterval(run, config.refreshMs)
      return async () => {
        clearInterval(timer)
        lifetime.abort(new Error('Enterprise preset synchronization stopped'))
        await pending
        await child.agentPresets.disposeEnterprise()
      }
    }, 'enterprise presets')
  })
}
