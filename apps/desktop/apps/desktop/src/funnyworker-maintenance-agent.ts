/** Native maintenance reuses the desktop Host, model policy, approvals and visible sessions. */
import { setTimeout as delay } from 'node:timers/promises'
import type { HistoryHost } from './funnyworker-history-sync.ts'
import type { MaintenanceAgent, MaintenanceAgentRequest } from './funnyworker-maintenance.ts'
/** Repository ownership must remain blocked until native execution termination is confirmed. */
export class MaintenanceAgentTerminationUnconfirmed extends Error {}
function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
/** @param currentHost Desktop-owned authenticated Host. @returns Existing Agent execution adapter. */
export function nativeMaintenanceAgent(currentHost: () => HistoryHost | undefined): MaintenanceAgent {
  const host = (): HistoryHost => {
    const value = currentHost()
    if (value === undefined) throw new Error('FunnyWork Host is unavailable')
    return value
  }
  const selection = async (connection: HistoryHost): Promise<{ provider: string; model: string }> => {
    const catalog = await connection.invoke('modelCatalog', {})
    if (!record(catalog) || !record(catalog.default) || typeof catalog.default.provider !== 'string'
      || typeof catalog.default.model !== 'string' || !Array.isArray(catalog.routableProviders)
      || !catalog.routableProviders.includes(catalog.default.provider))
      throw new Error('Configure an available model in the FunnyWork management backend before enabling maintenance')
    return { provider: catalog.default.provider, model: catalog.default.model }
  }
  const idle = async (connection: HistoryHost, sessionId: string): Promise<boolean> => {
    const states = await connection.invoke('replicaState', {})
    const state = record(states) ? states[sessionId] : undefined
    return record(state) && state.running === false && state.admissionWaiting === 0
      && (!Array.isArray(state.queue) || state.queue.length === 0)
  }
  return {
    async ready() { await selection(host()) },
    async run(request: MaintenanceAgentRequest) {
      request.signal.throwIfAborted()
      const connection = host(), model = await selection(connection)
      const setup: HistoryHost['invoke'] = async (method,args) => {
        try { return await connection.invoke(method,args) }
        catch (error) { throw new Error(`Native setup failed: ${method}`, { cause: error }) }
      }
      const workspace = await setup('workspaceCreate', { path: request.cwd })
      if (!record(workspace) || !record(workspace.workspace) || typeof workspace.workspace.workspaceId !== 'string')
        throw new Error('Native maintenance workspace unavailable')
      if (workspace.created === true) await setup('workspaceRename', { workspaceId: workspace.workspace.workspaceId, title: request.sessionId.startsWith('annual-') ? `年报 ${request.sessionId.slice(-12)}` : `维护 ${request.sessionId.slice(12, 20)}` })
      await setup('create', { sessionId: request.sessionId, workspaceId: workspace.workspace.workspaceId })
      await setup('rename', { sessionId: request.sessionId, title: Array.from(request.title).slice(0, 80).join('') })
      await setup('selectModel', { sessionId: request.sessionId, ...model, saveDefault: false })
      let offset = 0
      if (request.iteration > 0) {
        const states = await connection.invoke('replicaState', {})
        const state = record(states) ? states[request.sessionId] : undefined
        if (!record(state) || !Number.isSafeInteger(state.seq) || Number(state.seq) < 0)
          throw new Error('Native maintenance session cursor unavailable')
        offset = Number(state.seq)
      }
      let completed = false, bytes = 0, calls = 0
      let inputTokens: number | null = 0, outputTokens: number | null = 0, cachedInputTokens: number | null = 0
      const sum = (prior: number | null, value: unknown): number | null =>
        prior !== null && Number.isSafeInteger(value) && Number(value) >= 0 ? prior + Number(value) : null
      try {
        await connection.invoke('prompt', { sessionId: request.sessionId,
          requestId: `${request.sessionId}-${request.iteration}`, mode: 'queue', content: [{ type: 'text', text: request.prompt }] })
        const deadline = Date.now() + request.timeoutMs
        while (Date.now() < deadline) {
          request.signal.throwIfAborted()
          if (currentHost() !== connection) throw new MaintenanceAgentTerminationUnconfirmed('FunnyWork Host generation changed during maintenance')
          const page = await connection.invoke('replicaPage', { id: request.sessionId, offset })
          if (!record(page) || !Array.isArray(page.events) || !Number.isSafeInteger(page.nextSeq) || Number(page.nextSeq) < offset)
            throw new Error('Invalid native maintenance event page')
          bytes += Buffer.byteLength(JSON.stringify(page.events))
          if (bytes > request.maxOutputBytes) throw new Error('Native maintenance event budget exceeded')
          offset = Number(page.nextSeq)
          for (const event of page.events) {
            if (!record(event) || !record(event.data)) continue
            if (event.type === 'assistant/message' || event.type === 'assistant/attempt') {
              calls++
              const usage = record(event.data.usage) ? event.data.usage : {}
              inputTokens = sum(inputTokens, usage.inputTokens)
              outputTokens = sum(outputTokens, usage.outputTokens)
              cachedInputTokens = sum(cachedInputTokens, usage.cacheReadTokens)
            }
            if (event.type === 'turn/end' && record(event.data.reason)) {
              if (event.data.reason.kind !== 'completed') {
                const failure = record(event.data.reason.error) ? event.data.reason.error : {}
                // Only machine classifications cross into reports, never raw provider messages.
                const code = typeof failure.code === 'string' && /^(ADMISSION_[A-Z_]{1,80}|ENTRY_[A-Z_]{1,80}|TRANSPORT|TIMEOUT|ABORTED)$/.test(failure.code)
                  ? ` [${failure.code}]` : ''
                throw new Error(`FunnyWork Agent ended without completing maintenance: ${String(event.data.reason.kind)}${code}`)
              }
              completed = true
            }
          }
          if (completed && await idle(connection, request.sessionId)) {
            await connection.invoke('replicaFlush', {})
            return { sessionId: request.sessionId, modelUsage: { calls, inputTokens, outputTokens, cachedInputTokens } }
          }
          await delay(250, undefined, { signal: request.signal })
        }
        throw new Error('FunnyWork maintenance turn timed out')
      } catch (error) {
        let stopped = false
        try {
          if (currentHost() !== connection) throw new Error('Host generation lost')
          await connection.invoke('cancel', { sessionId: request.sessionId })
          const until = Date.now() + 15000
          while (Date.now() < until) {
            if (await idle(connection, request.sessionId)) { stopped = true; break }
            await delay(250)
          }
        } catch { /* Carrier loss leaves execution ownership unresolved. */ }
        if (!stopped) throw new MaintenanceAgentTerminationUnconfirmed('Native maintenance stop is unconfirmed; inspect the FunnyWork session before rework')
        throw error
      }
    },
  }
}
