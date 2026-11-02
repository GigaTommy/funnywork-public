/** Fixed, non-model Codex process probes through the opt-in Host plugin. */
import { createHash } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import type { HistoryHost } from './funnyworker-history-sync.ts'
import { MaintenanceAgentTerminationUnconfirmed } from './funnyworker-maintenance-agent.ts'

const capabilities = ['start', 'events', 'cancel', 'reconcile', 'artifacts', 'sync-check']
const states = ['running', 'succeeded', 'failed', 'cancelled', 'unknown']
function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
async function abortable<T>(pending: Promise<T>, signal: AbortSignal): Promise<T> {
  signal.throwIfAborted()
  return await new Promise<T>((resolve, reject) => {
    const aborted = (): void => { reject(signal.reason instanceof Error ? signal.reason : new Error('Codex probe cancelled')) }
    signal.addEventListener('abort', aborted, { once: true })
    pending.then(resolve, reject).finally(() => { signal.removeEventListener('abort', aborted) }).catch(() => {})
  })
}
/** Explicit availability; installed binaries alone never authorize execution. */
export interface CodexCapability {
  executor: 'funnywork-codex-v1'
  protocolVersion: 1
  executorVersion: '1'
  pluginVersion: string
  capabilities: string[]
}
/** Safe probe evidence, containing no process output or model content. */
export interface CodexProbeEvidence {
  pluginVersion: string | null
  capabilities: string[]
  eventCount: number
  evidenceDigest: string
  terminalState: 'completed' | 'failed'
  processStopped: true
}
/** @param host Current authenticated Host. @returns Verified opt-in probe capabilities. */
export async function codexCapabilities(host: HistoryHost): Promise<CodexCapability> {
  const value = await host.invoke('codexCapabilities', {})
  if (object(value) && value.processStopped === false) throw new MaintenanceAgentTerminationUnconfirmed('Codex capability probe stop is unconfirmed')
  if (!object(value) || value.processStopped !== true || value.available !== true || value.executor !== 'funnywork-codex-v1'
    || value.protocolVersion !== 1 || value.executorVersion !== '1'
    || typeof value.pluginVersion !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(value.pluginVersion)
    || !Array.isArray(value.capabilities) || !value.capabilities.includes('sync-check')
    || value.capabilities.length > capabilities.length || new Set(value.capabilities).size !== value.capabilities.length
    || !value.capabilities.every((c: unknown): c is string => typeof c === 'string' && capabilities.includes(c))) throw new Error('Codex probe unavailable')
  return { executor: value.executor, protocolVersion: value.protocolVersion, executorVersion: value.executorVersion,
    pluginVersion: value.pluginVersion, capabilities: value.capabilities }
}
/** Run identity belongs to the backend target and its exact Host generation. */
export interface CodexProbeRequest {
  requestId: string
  runtimeEpoch: string
  workspaceId: string
  baselineSha: string
  timeoutMs: number
  maxOutputBytes: number
}
/**
 * Start a fixed initialize-only probe and retain ownership until its managed process stops.
 * @param currentHost Current authenticated Host, used to fence restarts.
 * @param request Bounded backend target and registered workspace.
 * @param signal Application-owned cancellation.
 * @returns Structured observations after confirmed process quiescence.
 */
export async function runCodexProbe(
  currentHost: () => HistoryHost | undefined, request: CodexProbeRequest, signal: AbortSignal,
): Promise<CodexProbeEvidence> {
  signal.throwIfAborted()
  const host = currentHost()
  if (host === undefined) throw new Error('Codex Host unavailable')
  const identity = { runId: request.requestId, runtimeEpoch: request.runtimeEpoch }
  const active = (): void => { if (currentHost() !== host) throw new MaintenanceAgentTerminationUnconfirmed('Codex Host generation changed') }
  const receipt = (value: unknown): Record<string, unknown> => {
    if (!object(value) || value.runId !== identity.runId || value.runtimeEpoch !== identity.runtimeEpoch
      || !states.includes(String(value.state)) || typeof value.processStopped !== 'boolean'
      || (value.state === 'running' || value.state === 'unknown') && value.processStopped)
      throw new Error('Invalid Codex probe receipt')
    return value
  }
  let started = false
  try {
    active(); signal.throwIfAborted(); started = true
    receipt(await abortable(host.invoke('codexStart', request), signal))
    let cursor = 0, bytes = 0
    let pluginVersion: string | null = null
    const evidence: Record<string, unknown>[] = []
    const deadline = Date.now() + request.timeoutMs
    while (Date.now() < deadline) {
      active(); signal.throwIfAborted()
      const page = receipt(await abortable(host.invoke('codexEvents', { ...identity, after: cursor }), signal))
      if (!Array.isArray(page.events) || !Number.isSafeInteger(page.nextSeq) || Number(page.nextSeq) < cursor)
        throw new Error('Invalid Codex probe cursor')
      bytes += Buffer.byteLength(JSON.stringify(page))
      if (bytes > request.maxOutputBytes) throw new Error('Codex probe evidence limit exceeded')
      for (const event of page.events) {
        if (!object(event) || event.seq !== cursor + 1 || !['started', 'capability', 'terminal'].includes(String(event.kind))
          || Object.keys(event).some(k => !['seq', 'kind', 'state', 'pluginVersion', 'protocolVersion'].includes(k))
          || event.state !== undefined && (typeof event.state !== 'string' || !states.includes(event.state))
          || event.pluginVersion !== undefined && (typeof event.pluginVersion !== 'string' || !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(event.pluginVersion))
          || event.protocolVersion !== undefined && event.protocolVersion !== 1) throw new Error('Invalid Codex probe event')
        if (typeof event.pluginVersion === 'string') pluginVersion = event.pluginVersion
        cursor++; evidence.push(event)
      }
      if (page.nextSeq !== cursor || cursor > 10000) throw new Error('Invalid Codex probe event sequence')
      active()
      if (page.state === 'unknown') throw new MaintenanceAgentTerminationUnconfirmed('Codex process stop is unknown')
      if (page.state !== 'running' && page.processStopped === true) {
        if (evidence.at(-1)?.kind !== 'terminal' || !evidence.some(e => e.kind === 'started')
          || page.state === 'succeeded' && (pluginVersion === null || !evidence.some(e => e.kind === 'capability'))) throw new Error('Codex terminal evidence missing')
        return { pluginVersion, capabilities: pluginVersion === null ? [] : ['sync-check'],
          eventCount: cursor, evidenceDigest: createHash('sha256').update(JSON.stringify(evidence)).digest('hex'),
          terminalState: page.state === 'succeeded' ? 'completed' : 'failed', processStopped: true }
      }
      await delay(100, undefined, { signal })
    }
    throw new Error('Codex probe timed out')
  } catch (error) {
    if (started) {
      try {
        active()
        const stopped = receipt(await host.invoke('codexCancel', identity))
        active()
        if (stopped.processStopped !== true) {
          const reconciled = receipt(await host.invoke('codexReconcile', identity))
          active()
          if (reconciled.processStopped !== true) throw new Error('Stop unconfirmed')
        }
      } catch { throw new MaintenanceAgentTerminationUnconfirmed('Codex process stop is unconfirmed; inspection required') }
    }
    throw error
  }
}
