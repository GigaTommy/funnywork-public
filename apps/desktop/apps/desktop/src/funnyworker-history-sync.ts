/** Account history replication; credentials remain exclusively in the main process. */
import { readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { hostname } from 'node:os'
import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import { downloadMobileAttachments } from './funnyworker-mobile-attachments.ts'
import { RemoteWorkspaceOperations, WorkspaceOperationRejected } from './funnyworker-workspace.ts'
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'
import { desktopMaintenanceReady, flushDesktopMaintenanceReceipts, MaintenanceDeliveryPending, maintenanceRuntime, runDesktopMaintenance } from './funnyworker-maintenance.ts'
import { runAnnualReport, flushAnnualReports } from './funnyworker-annual-report.ts'
import { nativeMaintenanceAgent, MaintenanceAgentTerminationUnconfirmed } from './funnyworker-maintenance-agent.ts'
import { codexCapabilities } from './funnyworker-codex-executor.ts'
import { runCodexSyncCheck, flushCodexSyncChecks, type DesktopProbeBuild } from './funnyworker-codex-sync-check.ts'
import { runMaintenanceTriage, flushMaintenanceTriage } from './funnyworker-maintenance-triage.ts'

export interface HistoryHost {
  invoke(method: 'replicaList' | 'replicaPage' | 'replicaState' | 'replicaFlush' | 'replicaAttachmentChunk' | 'prompt' | 'cancel' | 'answer' | 'decision' | 'create' | 'fork' | 'modelCatalog' | 'presets' | 'selectModel' | 'updateQueue' | 'workspaceOperation' | 'workspaceCreate' | 'workspaceRename' | 'workspaceDelete' | 'rename' | 'codexCapabilities' | 'codexStart' | 'codexEvents' | 'codexCancel' | 'codexReconcile', args: object): Promise<unknown>
  workspaceOperations?: boolean
  upload?(sessionId: string, name: string, data: Uint8Array): Promise<{ receiptId: string; attachmentId: string; bytes: number }>
  workspaces?(): readonly { id: string; title: string }[] | undefined
  pending?(): readonly Record<string, unknown>[]
  choices?(): Promise<Record<string, unknown>>
  ready?(): boolean
  dispose?(): void
}

/** A native Remote rejection is known; carrier failure leaves command execution uncertain. */
export class HistoryRemoteError extends Error {}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** Project independent valid choices; malformed display rows never erase another route. */
export function mobileChoices(catalog: unknown, roster: unknown): Record<string, unknown> {
  const safeId = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''
    && value.length <= 256 && !/[\r\n]/.test(value)
  const label = (value: unknown, fallback: string): string =>
    Array.from(typeof value === 'string' && value.trim() !== '' ? value.replace(/\s+/g, ' ').trim() : fallback).slice(0, 120).join('')
  const models: { provider: string; model: string; name: string }[] = []
  const presets: { id: string; name: string }[] = []
  const modelIds = new Set<string>(), presetIds = new Set<string>()
  // Reserve bounded space for build identity and negotiated maintenance capabilities.
  const catalogBudget = 1_048_576 - 4096
  let bytes = Buffer.byteLength(JSON.stringify({ models: [], presets: [] }))
  if (record(catalog) && Array.isArray(catalog.groups)) {
    for (const group of catalog.groups as unknown[]) {
      if (!record(group) || !safeId(group.id) || !Array.isArray(group.models)) continue
      for (const model of group.models as unknown[]) {
        if (!record(model) || !safeId(model.id)) continue
        const key = `${group.id}\n${model.id}`
        if (modelIds.has(key) || models.length >= 1024) continue
        const row = { provider: group.id, model: model.id, name: label(model.name, model.id) }
        const size = Buffer.byteLength(JSON.stringify(row)) + (models.length > 0 ? 1 : 0)
        if (bytes + size > catalogBudget) continue
        bytes += size; modelIds.add(key); models.push(row)
      }
    }
  }
  if (record(roster) && Array.isArray(roster.presets)) {
    for (const preset of roster.presets as unknown[]) {
      if (!record(preset) || !safeId(preset.id) || preset.broken !== undefined
        || presetIds.has(preset.id) || presets.length >= 1024) continue
      const row = { id: preset.id, name: label(preset.name, preset.id) }
      const size = Buffer.byteLength(JSON.stringify(row)) + (presets.length > 0 ? 1 : 0)
      if (bytes + size > catalogBudget) continue
      bytes += size; presetIds.add(preset.id); presets.push(row)
    }
  }
  return { models, presets }
}

function cursor(value: unknown): { id: string; nextSeq: number } {
  if (!record(value) || typeof value.id !== 'string' || !Number.isSafeInteger(value.nextSeq) || Number(value.nextSeq) < 0) throw new Error('Invalid history acknowledgement')
  return { id: value.id, nextSeq: Number(value.nextSeq) }
}

/** Divide complete records without splitting or silently dropping an oversized event. */
export function historyBatches(deviceId: string, events: readonly unknown[]): unknown[][] {
  const batches: unknown[][] = []
  let batch: unknown[] = []
  const envelopeBytes = Buffer.byteLength(JSON.stringify({ deviceId, events: [] }))
  let bytes = envelopeBytes
  for (const event of events) {
    const eventBytes = Buffer.byteLength(event === undefined ? 'null' : JSON.stringify(event))
    if (envelopeBytes + eventBytes > 900_000) throw new Error('History event exceeds the upload limit')
    if (batch.length === 100 || bytes + eventBytes + (batch.length === 0 ? 0 : 1) > 900_000) {
      batches.push(batch); batch = []; bytes = envelopeBytes
    }
    bytes += eventBytes + (batch.length === 0 ? 0 : 1)
    batch.push(event)
  }
  if (batch.length > 0) batches.push(batch)
  return batches
}

/** Original synchronization owner and its native-task cancellation hook. */
export type HistorySyncDisposer = (() => Promise<void>) & { interruptMaintenance(): Promise<void> }

/**
 * One polling owner, retrying from authoritative cloud cursors after every reconnect.
 * @param account - authenticated main-process request capability.
 * @param home - existing account directory, never renamed by synchronization.
 * @param host - current authenticated local Host or undefined during restart.
 * @param status - user-visible synchronization state, without record or credential diagnostics.
 * @param environment - existing Host login-shell environment for maintenance tools.
 * @param clientVersion - packaged FunnyWork version for explicit synchronization checks.
 * @param build - immutable installed-package build identity, absent for legacy/development packages.
 */
export async function startHistorySync(account: FunnyWorkerAccount, home: string, host: () => HistoryHost | undefined, status: (state: 'saved' | 'pending' | 'error') => void, environment: NodeJS.ProcessEnv = process.env, clientVersion = '', build?: DesktopProbeBuild): Promise<HistorySyncDisposer> {
  const path = join(home, 'history-device-id')
  try { await writeFile(path, randomUUID(), { flag: 'wx', mode: 0o600 }) }
  catch (error) { if (!(error instanceof Error && 'code' in error && error.code === 'EEXIST')) throw error }
  const deviceId = (await readFile(path, 'utf8')).trim()
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(deviceId)) throw new Error('Invalid history device identity')
  let maintenanceAbort = new AbortController()
  let maintenanceWork: Promise<void> | undefined
  let maintenanceRecovery = false
  let maintenanceSuspended = false
  let featuresAt = 0
  let maintenanceSupported = false
  let triageSupported = false
  let deliverySupported = false
  let annualSupported = false
  let clientBuildSupported = false
  let codexSupported = false
  let codexReady: Record<string, unknown> | undefined
  let codexReadinessAt = 0
  let readinessAt = 0
  let annualReady = false
  let maintenanceReady: { executor: string; gitVersion: string } | undefined
  const runtime = {
    agent: nativeMaintenanceAgent(host),
    run: (argv: readonly string[], cwd: string, signal: AbortSignal, timeoutMs: number, maxOutputBytes: number) =>
      maintenanceRuntime.run(argv, cwd, signal, timeoutMs, maxOutputBytes, environment),
  }
  const interruptMaintenance = async (): Promise<void> => {
    maintenanceSuspended = true
    maintenanceAbort.abort()
    await maintenanceWork
    maintenanceAbort = new AbortController()
  }
  let stopped = false
  const isStopped = (): boolean => stopped
  let busy = false
  let lastArchiveSession: string | undefined
  const verified = new Map<string, { revision: string; at: number }>()
  const attachmentScans = new Map<string, { revision: string; cloudId: string; after: string }>()
  const cold = new Map<string, boolean>()
  let current: HistoryHost | undefined
  let epoch = randomUUID()
  let controlling = false
  const acknowledgements = new Map<string, object>()
  let activePreviews = new Map<string, string>()
  let controlWork: Promise<void> | undefined
  let archiveWork: Promise<void> | undefined
  let lastPublication: { local: HistoryHost; body: Record<string, unknown> } | undefined
  let heartbeatBusy = false
  let heartbeatWork: Promise<void> = Promise.resolve()
  // Both publication paths must include the bridge's current pending interactions.
  // replicaState alone omits them; a heartbeat must not erase a real approval.
  const attachPending = (sessions: Record<string, unknown>, local: HistoryHost): void => {
    for (const [id, controllable] of cold) {
      if (sessions[id] === undefined) sessions[id] = { seq: 0, running: false, turnStart: null, controllable, projections: {} }
    }
    for (const item of Object.values(sessions)) if (record(item)) item.pending = []
    for (const pending of local.pending?.() ?? []) {
      const item = typeof pending.agentId === 'string' ? sessions[pending.agentId] : undefined
      if (record(item) && Array.isArray(item.pending)) item.pending.push(pending)
    }
  }
  const runHeartbeat = (): Promise<void> => {
    if (heartbeatBusy || isStopped() || lastPublication === undefined) return Promise.resolve()
    const { local, body } = lastPublication
    if (host() !== local || local.ready?.() === false) return Promise.resolve()
    heartbeatBusy = true
    heartbeatWork = (async () => {
      try {
        const sessions = await local.invoke('replicaState', {})
        if (isStopped() || host() !== local) return
        // Never claim another batch while a slow native operation is executing.
        if (!record(sessions)) throw new Error('Invalid native execution state')
        attachPending(sessions, local)
        if (Buffer.byteLength(JSON.stringify(sessions)) > 900_000) {
          for (const item of Object.values(sessions)) if (record(item)) delete item.liveAssistant
        }
        if (Buffer.byteLength(JSON.stringify(sessions)) > 900_000) throw new Error('Native execution state exceeds limit')
        await account.syncRequest(`/devices/${deviceId}/heartbeat`, { ...body, sessions })
      } catch { if (!isStopped()) status('error') }
      finally { heartbeatBusy = false }
    })()
    return heartbeatWork
  }
  const runControl = (): Promise<void> => controlWork ??= control().finally(() => { controlWork = undefined })
  const runArchive = (): Promise<void> => archiveWork ??= tick().finally(() => { archiveWork = undefined })
  const control = async (): Promise<void> => {
    const local = host()
    if (isStopped() || controlling || local === undefined) return
    controlling = true
    try {
      if (current !== local) {
        await interruptMaintenance(); current?.dispose?.(); current = local; maintenanceSuspended = false
        readinessAt = 0; codexReadinessAt = 0; codexReady = undefined; epoch = randomUUID()
        acknowledgements.clear(); activePreviews.clear()
      }
      const sessions = await local.invoke('replicaState', {})
      if (!record(sessions)) throw new Error('Invalid native execution state')
      const nextPreviews = new Map<string, string>()
      for (const [id, item] of Object.entries(sessions)) {
        if (record(item) && record(item.liveAssistant) && typeof item.liveAssistant.attemptId === 'string') {
          nextPreviews.set(id, item.liveAssistant.attemptId)
        }
      }
      const settledPreview = [...activePreviews].some(([id, attempt]) => nextPreviews.get(id) !== attempt)
      activePreviews = nextPreviews
      attachPending(sessions, local)
      // Optional live text must not prevent command or approval synchronization.
      if (Buffer.byteLength(JSON.stringify(sessions)) > 900_000) {
        for (const item of Object.values(sessions)) if (record(item)) delete item.liveAssistant
      }
      if (isStopped() || host() !== local) return
      const baseWorkspaces = local.workspaces?.() ?? []
      let publishedChoices: Record<string, unknown> = { models: [], presets: [] }
      if (local.choices !== undefined) {
        try {
          const choices = await local.choices()
          const models = new Map<string, Record<string, unknown>>()
          const presets = new Map<string, Record<string, unknown>>()
          for (const model of Array.isArray(choices.models) ? choices.models : []) {
            if (record(model)) models.set(`${String(model.provider)}\n${String(model.model)}`, model)
          }
          for (const preset of Array.isArray(choices.presets) ? choices.presets : []) {
            if (record(preset) && typeof preset.id === 'string') presets.set(preset.id, preset)
          }
          for (const item of Object.values(sessions)) {
            if (!record(item) || !record(item.projections)) continue
            const projections = item.projections
            const projection = projections.modelSelection
            const selection = record(projection)
              ? (item.running === true ? projection.lastUsed ?? projection.next : projection.next ?? projection.lastUsed)
              : undefined
            if (record(selection)) {
              const model = models.get(`${String(selection.provider)}\n${String(selection.model)}`)
              if (model !== undefined) item.modelName = model.name
            }
            const preset = typeof projections.agentPreset === 'string' ? presets.get(projections.agentPreset) : undefined
            if (preset !== undefined) item.agentPresetName = preset.name
          }
          publishedChoices = { ...choices }
        } catch { /* Catalog discovery cannot block cancellation or state publication. */ }
      }
      if (Buffer.byteLength(JSON.stringify(sessions)) > 900_000) {
        for (const item of Object.values(sessions)) if (record(item)) {
          delete item.modelName; delete item.agentPresetName; delete item.liveAssistant
          if (record(item.projections)) {
            delete item.projections.modelSelection; delete item.projections.agentPreset; delete item.projections.tokenUsage
          }
        }
      }
      if (isStopped() || host() !== local) return
      if (local.workspaceOperations === true) publishedChoices.workspaceOperations = true
      if (Date.now() - featuresAt > 30000) {
        try {
          const features = await account.syncRequest('/maintenance/features')
          clientBuildSupported = record(features) && features.clientBuild === true
          maintenanceSupported = record(features) && features.executor === 'funnywork-native-v1'
          triageSupported = record(features) && features.triage === true
          deliverySupported = record(features) && features.delivery === true
          codexSupported = record(features) && record(features.syncCheck) && features.syncCheck.executor === 'funnywork-codex-v1' && features.syncCheck.protocolVersion === 1
          annualSupported = record(features) && features.annualReport === 'annual-report.v1'
          featuresAt = Date.now()
        } catch (error) {
          clientBuildSupported = false
          if (error instanceof HistorySyncHttpError && error.status === 404) {
            maintenanceSupported = false; codexSupported = false; featuresAt = Date.now()
          }
        }
      }
      if (maintenanceSupported && !maintenanceRecovery && !maintenanceSuspended) {
        if (maintenanceWork === undefined && Date.now() - readinessAt > 30000) {
          try { await runtime.agent.ready(); annualReady = true; maintenanceReady = await desktopMaintenanceReady(home, runtime) }
          catch { annualReady = false; maintenanceReady = undefined }
          readinessAt = Date.now()
        }
        if (annualReady && annualSupported) publishedChoices.annualReport = 'annual-report.v1'
        if (maintenanceReady !== undefined) publishedChoices.maintenance = {
          ...maintenanceReady, ...(triageSupported ? { triage: true } : {}), ...(deliverySupported ? { delivery: true } : {}),
        }
      }
      if (codexSupported && !maintenanceRecovery && !maintenanceSuspended && clientVersion !== '') {
        if (maintenanceWork === undefined && Date.now() - codexReadinessAt > 30000) {
          try {
            const capability = await codexCapabilities(local)
            const git = await runtime.run(['git', '--version'], home, maintenanceAbort.signal, 10000, 4096)
            if (git.exitCode !== 0 || !/^git version [0-9]/.test(git.output.trim())) throw new Error('Git probe unavailable')
            codexReady = { executor: capability.executor, protocolVersion: capability.protocolVersion,
              executorVersion: capability.executorVersion, capabilities: capability.capabilities,
              gitVersion: git.output.trim().slice(0, 120) }
          } catch (error) {
            codexReady = undefined
            if (error instanceof MaintenanceAgentTerminationUnconfirmed) {
              maintenanceRecovery = true
              delete publishedChoices.maintenance
              delete publishedChoices.annualReport
              delete publishedChoices.maintenanceExecutors
            }
          }
          codexReadinessAt = Date.now()
        }
        if (codexReady !== undefined) publishedChoices.maintenanceExecutors = [codexReady]
      }
      if (isStopped() || host() !== local) return
      // This identity was validated against the running app's installed package manifest.
      if (clientBuildSupported && build !== undefined && build.version === clientVersion) publishedChoices.clientBuild = build
      const publication = { epoch, sessions, workspaces: baseWorkspaces, choices: publishedChoices,
        attachmentUploads: local.upload !== undefined, sessionForks: true }
      lastPublication = { local, body: publication }
      let commands: unknown
      try { commands = await account.syncRequest(`/devices/${deviceId}/state`, publication) }
      catch (error) {
        // A server rollback can precede the next feature refresh. Retry the same state without telemetry once.
        if (!(error instanceof HistorySyncHttpError) || error.status !== 400 || publishedChoices.clientBuild === undefined) throw error
        if (isStopped() || host() !== local) return
        delete publishedChoices.clientBuild
        clientBuildSupported = false; featuresAt = 0
        commands = await account.syncRequest(`/devices/${deviceId}/state`, publication)
      }
      if (maintenanceSupported && maintenanceWork === undefined) {
        try { await flushAnnualReports(home, account, deviceId, epoch) } catch (_error) { status('error') }
        if (triageSupported) try { await flushMaintenanceTriage(home, account, deviceId, epoch) } catch { status('error') }
        try { await flushDesktopMaintenanceReceipts(home, account, deviceId, epoch) }
        catch { status('error') }
      }
      if (codexSupported && maintenanceWork === undefined) {
        try { await flushCodexSyncChecks(home, account, deviceId, epoch) } catch { status('error') }
      }
      if (settledPreview) void runArchive()
      if (!Array.isArray(commands)) throw new Error('Invalid remote commands')
      for (const command of commands) {
        if (isStopped() || host() !== local) break
        if (!record(command) || typeof command.id !== 'string' || !/^[A-Za-z0-9_-]+$/.test(command.id) || typeof command.nativeId !== 'string' || !record(command.payload)) throw new Error('Invalid remote command')
        if (command.kind === 'maintenance-sync-check') {
          if (!codexSupported || command.payload.runtimeEpoch !== epoch) continue
          if (maintenanceRecovery || maintenanceSuspended || maintenanceWork !== undefined) {
            // The server may have claimed this target before a native run became busy.
            // Persist a no-start refusal rather than silently dropping that claimed target.
            try {
              await runCodexSyncCheck(home, account, deviceId, command.id, command.payload,
                new AbortController().signal, () => undefined, clientVersion, build)
            }
            catch { if (!isStopped()) status('error') }
            continue
          }
          maintenanceWork = runCodexSyncCheck(home, account, deviceId, command.id, command.payload,
            maintenanceAbort.signal, host, clientVersion, build)
            .catch((error: unknown) => { if (!(error instanceof MaintenanceDeliveryPending)) maintenanceRecovery = true; if (!isStopped()) status('error') })
            .finally(() => { maintenanceWork = undefined; codexReadinessAt = 0 })
          continue
        }
        if (command.kind === 'annual-report' || command.kind === 'maintenance' || command.kind === 'maintenance-triage') {
          if (command.payload.runtimeEpoch !== epoch || maintenanceRecovery || maintenanceSuspended || maintenanceWork !== undefined)
            continue
          maintenanceWork = (command.kind === 'annual-report'
            ? runAnnualReport(home, account, deviceId, command.id, command.payload, maintenanceAbort.signal, runtime.agent)
            : command.kind === 'maintenance-triage'
              ? runMaintenanceTriage(home, account, deviceId, command.id, command.payload, maintenanceAbort.signal, runtime)
              : runDesktopMaintenance(home, account, deviceId, command.id, command.payload, maintenanceAbort.signal, runtime))
            .catch((error: unknown) => {
              if (!(error instanceof MaintenanceDeliveryPending)) maintenanceRecovery = true
              if (!isStopped()) status('error')
            }).finally(() => { maintenanceWork = undefined; readinessAt = 0 })
          continue
        }
        let outcome = 'accepted'
        let creationStarted = false
        let promptStarted = false
        let commandResult: Record<string, unknown> = {}
        const archiveCreatedSession = async (id: string, fork?: { source: string; atSeq: number }): Promise<{ sessionId: string }> => {
          const active = (): void => { if (isStopped() || host() !== local) throw new Error('Host generation changed') }
          active()
          await local.invoke('replicaFlush', {})
          active()
          const page = await local.invoke('replicaPage', { id, offset: 0 })
          active()
          if (!record(page) || !record(page.header) || page.header.id !== id) throw new Error('Created Session is not durable')
          if (fork !== undefined && (page.header.parentSession !== fork.source || page.header.isSeeded !== true
            || page.inheritedEventCount !== fork.atSeq + 1)) throw new Error('Fork prefix mismatch')
          const archived = cursor(await account.syncRequest('/sessions', { deviceId, header: page.header, inheritedEventCount: page.inheritedEventCount }))
          return { sessionId: archived.id }
        }
        try {
          let value: unknown
          switch (command.kind) {
            case 'workspace': {
              const action = command.payload.action
              if (action === 'create') value = await local.invoke('workspaceCreate', { path: command.payload.path })
              else if (action === 'rename') value = await local.invoke('workspaceRename', { workspaceId: command.payload.workspaceId, title: command.payload.title })
              else if (action === 'delete') value = await local.invoke('workspaceDelete', { workspaceId: command.payload.workspaceId })
              else value = await local.invoke('workspaceOperation', { ...command.payload, commandId: command.id })
              commandResult = record(value) && record(value.workspace)
                ? { workspaceId: value.workspace.workspaceId, title: value.workspace.title } : record(value) ? value : {}
              break
            }
            case 'create': {
              const workspaceId = command.payload.workspaceId
              if (!local.workspaces?.()?.some(workspace => workspace.id === workspaceId)) { outcome = 'rejected'; break }
              const selection = command.payload.model
              const preset = command.payload.agentPreset
              if (selection !== undefined || preset !== undefined) {
                if (local.choices === undefined) { outcome = 'rejected'; break }
                const choices = await local.choices()
                if (selection !== undefined && (!record(selection) || !Array.isArray(choices.models)
                  || !choices.models.some(item => record(item)
                    && item.provider === selection.provider && item.model === selection.model))) {
                  outcome = 'rejected'; break
                }
                if (preset !== undefined && (!Array.isArray(choices.presets)
                  || !choices.presets.some(item => record(item) && item.id === preset))) { outcome = 'rejected'; break }
              }
              const sessionId = `mobile-${command.id}`
              creationStarted = true
              value = await local.invoke('create', {
                sessionId, workspaceId, ...(preset === undefined ? {} : { agentPreset: preset }),
              })
              if (record(selection)) await local.invoke('selectModel', { sessionId, ...selection, saveDefault: false })
              if (!record(value) || value.sessionId !== sessionId) throw new Error('Invalid created Session')
              commandResult = await archiveCreatedSession(sessionId)
              break
            }
            case 'fork': {
              const atSeq = command.payload.atSeq
              if (command.id.length > 64 || typeof atSeq !== 'number' || !Number.isSafeInteger(atSeq) || atSeq < 0) { outcome = 'rejected'; break }
              const sessionId = `mobile-${command.id}`
              creationStarted = true
              value = await local.invoke('fork', { sessionId: command.nativeId, childSessionId: sessionId, atSeq })
              if (!record(value) || value.sessionId !== sessionId) throw new Error('Invalid forked Session')
              commandResult = await archiveCreatedSession(sessionId, { source: command.nativeId, atSeq })
              void runArchive()
              break
            }
            case 'prompt': {
              const content: object[] = typeof command.payload.text === 'string' && command.payload.text.trim() !== '' ? [{ type: 'text', text: command.payload.text }] : []
              if (command.payload.attachments !== undefined) {
                if (local.upload === undefined) throw new Error('Native file upload unavailable')
                const active = (): void => { if (isStopped() || host() !== local) throw new Error('Host generation changed') }
                const files = await downloadMobileAttachments(account, command.sessionId, command.payload.attachments, active)
                for (const file of files) {
                  active()
                  const uploaded = await local.upload(command.nativeId, file.name, file.data)
                  active()
                  if (uploaded.attachmentId !== `sha256:${file.hash}` || uploaded.bytes !== file.data.length) throw new Error('Native attachment mismatch')
                  content.push({ type: 'file', receiptId: uploaded.receiptId })
                }
              }
              if (content.length === 0) throw new HistoryRemoteError('Empty prompt')
              promptStarted = true
              value = await local.invoke('prompt', { sessionId: command.nativeId, requestId: command.id, mode: command.payload.mode, ...(command.payload.mode === 'steer' ? { expectedTurn: command.payload.expectedTurn } : {}), content })
              break
            }
            case 'queueEdit': {
              const action = command.payload.action
              if (typeof command.payload.itemId !== 'string' || !['edit', 'remove', 'steer'].includes(String(action))) { outcome = 'rejected'; break }
              value = await local.invoke('updateQueue', { sessionId: command.nativeId, itemId: command.payload.itemId,
                action: action === 'edit' ? { kind: action, content: [{ type: 'text', text: command.payload.text }] } : { kind: action } })
              break
            }
            case 'cancel':
              value = await local.invoke('cancel', { sessionId: command.nativeId, expectedTurn: command.payload.expectedTurn })
              break
            case 'answer':
              value = await local.invoke('answer', { agentId: command.nativeId, callId: command.payload.callId, answer: command.payload.answer })
              if (value !== true) outcome = 'rejected'
              break
            case 'decision': {
              const eventId = command.payload.eventId
              const pending = local.pending?.().find(item => item.eventId === eventId && item.agentId === command.nativeId)
              if (pending === undefined) { outcome = 'rejected'; break }
              const value = pending.event === 'approval/request' ? command.payload.outcome : command.payload.answer
              await local.invoke('decision', { eventId: pending.eventId, outcome: { kind: 'result', value } })
              break
            }
            default: outcome = 'rejected'
          }
          if (outcome === 'accepted') await local.invoke('replicaFlush', {})
        } catch (error) { if (command.kind === 'workspace' && error instanceof HistoryRemoteError) commandResult = { error: error.message.slice(0, 256) }; outcome = (command.kind === 'prompt' && !promptStarted) || (!creationStarted && error instanceof HistoryRemoteError) ? 'rejected' : 'uncertain' }
        if (isStopped() || host() !== local) return
        const result = { epoch, status: outcome, result: commandResult }
        acknowledgements.set(command.id, result)
      }
      // Receipt failures cannot discard the rest of an already claimed batch or fence state publication.
      for (const [id, result] of acknowledgements) {
        if (isStopped() || host() !== local) return
        try {
          await account.syncRequest(`/devices/${deviceId}/commands/${id}`, result)
          acknowledgements.delete(id)
        } catch (error) {
          if (error instanceof HistorySyncHttpError && error.status === 409) acknowledgements.delete(id)
          else status('error')
        }
      }
    } catch (_error) {
      // The next poll retries state publication and receipts, never an already claimed operation.
      if (!isStopped()) status('error')
    } finally { controlling = false }
  }
  const tick = async (): Promise<void> => {
    const local = host()
    if (isStopped() || busy || local === undefined) return
    busy = true
    const deadline = Date.now() + 10_000
    status('pending')
    try {
      await account.syncRequest('/devices', { id: deviceId, name: hostname().slice(0, 120), platform: process.platform, ...(account.principalId === undefined ? {} : { expectedPrincipalId: account.principalId }) })
      await runControl()
      if (isStopped() || host() !== local) return
      const ids = await local.invoke('replicaList', {})
      if (!record(ids) || Object.values(ids).some(revision => typeof revision !== 'string')) throw new Error('Invalid local history directory')
      for (const id of cold.keys()) if (ids[id] === undefined) { cold.delete(id); verified.delete(id); attachmentScans.delete(id) }
      let failed = false
      let remaining = false
      // Resume after the last attempted session, including failures and partial uploads.
      // A large cold history must not repeatedly consume the budget ahead of other sessions.
      const entries = Object.entries(ids)
      const previousIndex = entries.findIndex(([id]) => id === lastArchiveSession)
      const ordered = [...entries.slice(previousIndex + 1), ...entries.slice(0, previousIndex + 1)]
      // Redistribute spare budget after every session has had its share. Up to twenty
      // pages per session remain possible without putting fresh sessions behind them.
      for (let round = 0; round < 10 && Date.now() < deadline; round++) {
        let attempted = false
        for (const [id, revision] of ordered) {
          if (isStopped() || host() !== local || Date.now() >= deadline) { remaining = true; break }
          const prior = verified.get(id)
          if (prior !== undefined && prior.revision === revision && Date.now() - prior.at < 300_000) continue
          attempted = true
          lastArchiveSession = id
          try {
            const first = await local.invoke('replicaPage', { id, offset: 0 })
            if (!record(first) || !record(first.header) || !Array.isArray(first.events)) throw new Error('Invalid local history page')
            cold.set(id, first.header.origin !== 'subagent')
            let receipt = cursor(await account.syncRequest('/sessions', { deviceId, header: first.header, inheritedEventCount: first.inheritedEventCount }))
            const sessionDeadline = Math.min(deadline, Date.now() + 2000)
            let transferred = false
            let unfinished = false
            pages: for (let pageIndex = 0; pageIndex < 2; pageIndex++) {
              if (isStopped() || host() !== local || transferred && Date.now() >= sessionDeadline) {
                unfinished = true; remaining = true; break
              }
              const page = receipt.nextSeq === 0 ? first : await local.invoke('replicaPage', { id, offset: receipt.nextSeq })
              if (!record(page) || !Array.isArray(page.events)) throw new Error('Invalid local history page')
              if (page.events.length === 0) break
              for (const events of historyBatches(deviceId, page.events)) {
                // An admitted session gets one transfer even when preparation or a page read
                // consumed its time slice; subsequent transfers yield to other sessions.
                if (isStopped() || host() !== local || transferred && Date.now() >= sessionDeadline) {
                  unfinished = true; remaining = true; break pages
                }
                const expected = receipt.nextSeq + events.length
                const accepted = cursor(await account.syncRequest(`/sessions/${receipt.id}/events`, { deviceId, events }))
                if (accepted.id !== receipt.id || accepted.nextSeq !== expected) throw new Error('History acknowledgement does not match uploaded prefix')
                receipt = accepted
                transferred = true
              }
              if (pageIndex === 1) { remaining = true; unfinished = true }
            }
            if (!unfinished) {
              const scan = attachmentScans.get(id)
              let attachmentAfter = scan !== undefined && scan.revision === revision && scan.cloudId === receipt.id ? scan.after : ''
              let attachmentChunks = 0
              let manifestPages = 0
              do {
                if (isStopped() || host() !== local || (transferred || manifestPages > 0) && Date.now() >= sessionDeadline) {
                  unfinished = true; remaining = true; break
                }
                const manifest = await account.syncRequest(`/sessions/${receipt.id}/attachments${attachmentAfter === '' ? '' : `?after=${attachmentAfter}`}`)
                if (!record(manifest) || !Array.isArray(manifest.items) || !Number.isSafeInteger(manifest.maxBytes)) throw new Error('Invalid attachment manifest')
                manifestPages++
                attachments: for (const item of manifest.items) {
                  if (!record(item) || item.complete === true) continue
                  if (typeof item.hash !== 'string' || !/^[a-f0-9]{64}$/.test(item.hash) || !record(item.metadata) || !record(item.metadata.attachment) || !Number.isSafeInteger(item.nextOffset) || !Number.isSafeInteger(item.metadata.attachment.bytes)) throw new Error('Invalid attachment record')
                  const size = Number(item.metadata.attachment.bytes)
                  if (size > Number(manifest.maxBytes)) throw new Error('Attachment exceeds synchronization capacity')
                  let offset = Number(item.nextOffset)
                  do {
                    if (isStopped() || host() !== local || transferred && Date.now() >= sessionDeadline || attachmentChunks >= 2) {
                      unfinished = true; remaining = true; break attachments
                    }
                    const chunk = await local.invoke('replicaAttachmentChunk', { sessionId: id, attachmentId: `sha256:${item.hash}`, offset })
                    if (!record(chunk) || typeof chunk.data !== 'string' || !Number.isSafeInteger(chunk.nextOffset) || Number(chunk.nextOffset) < offset || Number(chunk.nextOffset) > size) throw new Error('Invalid native attachment chunk')
                    const acknowledged = await account.syncRequest(`/sessions/${receipt.id}/attachments/${item.hash}`, { deviceId, offset, data: chunk.data })
                    if (!record(acknowledged) || acknowledged.nextOffset !== chunk.nextOffset || (Number(chunk.nextOffset) === offset && acknowledged.complete !== true)) throw new Error('Invalid attachment acknowledgement')
                    offset = Number(chunk.nextOffset)
                    attachmentChunks++
                    transferred = true
                  } while (offset < size)
                }
                if (!unfinished) {
                  attachmentAfter = typeof manifest.after === 'string' && /^[a-f0-9]{64}$/.test(manifest.after) ? manifest.after : ''
                  if (attachmentAfter === '') attachmentScans.delete(id)
                  else attachmentScans.set(id, { revision: String(revision), cloudId: receipt.id, after: attachmentAfter })
                }
              } while (attachmentAfter !== '' && !unfinished)
            }
            if (!unfinished && !isStopped() && host() === local) verified.set(id, { revision: String(revision), at: Date.now() })
          } catch { failed = true }
        }
        if (!attempted || failed) break
      }
      remaining = ordered.some(([id, revision]) => {
        const prior = verified.get(id)
        return prior === undefined || prior.revision !== revision || Date.now() - prior.at >= 300_000
      })
      if (!isStopped()) status(failed ? 'error' : remaining ? 'pending' : 'saved')
    } catch { if (!isStopped()) status('error') }
    finally { busy = false }
  }
  const timer = setInterval(() => { void runArchive() }, 5_000)
  const controlTimer = setInterval(() => { if (controlling) void runHeartbeat(); else void runControl() }, 2_000)
  timer.unref()
  controlTimer.unref()
  void runArchive()
  const dispose: HistorySyncDisposer = Object.assign(async () => {
    stopped = true; clearInterval(timer); clearInterval(controlTimer)
    await interruptMaintenance()
    await Promise.allSettled([archiveWork, controlWork, heartbeatWork])
    current?.dispose?.()
  }, { interruptMaintenance })
  return dispose
}

/** Minimal Workspace catalog; paths and Session memberships never leave the Host. */
export class HistoryWorkspaceCatalog {
  private rows = new Map<string, { id: string; title: string }>()
  private paths = new Map<string, string>()
  idForPath(path: string): string | undefined { return [...this.paths].find(([, value]) => value === path)?.[0] }
  path(id: string): string | undefined { return this.ready ? this.paths.get(id) : undefined }
  private ready = false
  /** Clear the previous stream generation before reconnect. */
  clear(): void { this.rows.clear(); this.paths.clear(); this.ready = false }
  /** @returns the current generation's bounded selectable Workspace identities. */
  list(): readonly { id: string; title: string }[] | undefined {
    if (!this.ready) return undefined
    const result: { id: string; title: string }[] = []
    let bytes = 2
    for (const row of [...this.rows.values()].sort((a, b) => a.id.localeCompare(b.id))) {
      const size = Buffer.byteLength(JSON.stringify(row)) + (result.length === 0 ? 0 : 1)
      if (result.length === 1000 || bytes + size > 200_000) break
      result.push(row); bytes += size
    }
    return result
  }
  /** @param value - one Workspace follow frame received over authenticated local RPC. */
  accept(value: unknown): void {
    if (!record(value)) throw new Error('Invalid Workspace frame')
    const put = (row: unknown): void => {
      if (!record(row) || typeof row.workspaceId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(row.workspaceId) || typeof row.title !== 'string') throw new Error('Invalid Workspace')
      if (typeof row.path === 'string') this.paths.set(row.workspaceId, row.path)
      this.rows.set(row.workspaceId, { id: row.workspaceId, title: row.title.trim().slice(0, 200) || row.workspaceId })
    }
    if (value.type === 'baseline') {
      if (!record(value.value) || !Array.isArray(value.value.items)) throw new Error('Invalid Workspace baseline')
      this.clear()
      for (const row of value.value.items) put(row)
      this.ready = true
    } else if (this.ready && value.type === 'upsert') put(value.workspace)
    else if (this.ready && value.type === 'remove' && typeof value.workspaceId === 'string') { this.rows.delete(value.workspaceId); this.paths.delete(value.workspaceId) }
    // Ordering, archived Sessions, and pinned Sessions do not change creation targets.
  }
}

/** Authenticated local RPC bridge, retaining the current Host's cookie. */
export function historyHost(origin: string, cookie: string): HistoryHost {
  let socket: WebSocket | undefined
  let clientId = ''
  let disposed = false
  const lifetime = new AbortController()
  const pending = new Map<string, Record<string, unknown>>()
  const workspaces = new HistoryWorkspaceCatalog()
  const workspaceOperations = new RemoteWorkspaceOperations(id => workspaces.path(id))
  const connect = (): void => {
    if (socket !== undefined || disposed) return
    const url = new URL('/api/remote.mux', origin); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    const connection = new WebSocket(url, { headers: { cookie, origin: new URL(origin).origin }, maxPayload: 1_048_576 })
    socket = connection
    connection.on('open', () => {
      connection.send(JSON.stringify({ type: 'open', streamId: 'history', endpoint: '$events', payload: { args: {} } }))
      connection.send(JSON.stringify({ type: 'open', streamId: 'workspaces', endpoint: 'workspace/follow', payload: { args: {} } }))
    })
    connection.on('message', (data) => {
      try {
        const envelope: unknown = JSON.parse((Array.isArray(data) ? Buffer.concat(data) : Buffer.isBuffer(data) ? data : Buffer.from(data)).toString('utf8'))
        if (!record(envelope)) return
        if (envelope.streamId === 'workspaces') {
          if (envelope.type === 'item') workspaces.accept(envelope.value)
          else if (envelope.type === 'error' || envelope.type === 'end') workspaces.clear()
          return
        }
        if (envelope.type !== 'item' || !record(envelope.value)) return
        const frame = envelope.value
        if (frame.type === 'ready' && typeof frame.clientId === 'string') clientId = frame.clientId
        if (frame.type === 'cancel' && typeof frame.eventId === 'string') pending.delete(frame.eventId)
        if (frame.type === 'waterfall' && typeof frame.eventId === 'string') {
          if (frame.event === 'approval/request' || frame.event === 'user-questions/request') pending.set(frame.eventId, frame)
          else void invoke('decision', { eventId: frame.eventId, outcome: { kind: 'next' } }).catch(() =>{  connection.close() })
        }
      } catch (_error) { connection.close() }
    })
    connection.on('error', () => { connection.close() })
    connection.on('close', () => { if (socket === connection) { socket = undefined; clientId = ''; pending.clear(); workspaces.clear() } })
  }
  const invoke: HistoryHost['invoke'] = async (method, args) => {
    if (disposed) throw new Error('History bridge has stopped')
    if (method === 'workspaceOperation') {
      const payload = args as Record<string, unknown>
      const removedRegistration = payload.action === 'worktreeRemove' && typeof payload.path === 'string' ? workspaces.idForPath(payload.path) : undefined
      let result: unknown
      try { result = await workspaceOperations.run(args as Record<string, unknown>) } catch (error) {
        if (error instanceof WorkspaceOperationRejected || ['files', 'read', 'git'].includes(String((args as Record<string, unknown>).action))) throw new HistoryRemoteError(error instanceof Error ? error.message : 'Workspace operation rejected')
        throw error
      }
      if (payload.action === 'worktreeAdd' && record(result) && typeof result.path === 'string') {
        try { const created = await invoke('workspaceCreate', { path: result.path }); if (record(created) && record(created.workspace)) return { path: result.path, workspaceId: created.workspace.workspaceId } }
        catch { throw new Error('Worktree created; registration needs verification') }
      }
      if (payload.action === 'worktreeRemove' && typeof payload.path === 'string') {
        const registered = removedRegistration
        if (registered !== undefined) { try { await invoke('workspaceDelete', { workspaceId: registered }) } catch { throw new Error('Worktree removed; registration needs verification') } }
      }
      return result
    }
    const rpcId = randomUUID()
    const remote = method.startsWith('codex') ? `codexMaintenance/${method.slice('codex'.length).toLowerCase()}` : method.startsWith('workspace') ? `workspace/${method.slice('workspace'.length).toLowerCase()}` : method === 'decision' ? '$events/result' : method === 'answer' ? 'userQuestions/answer' : method === 'presets' ? 'agentPresets/list' : `session/${method}`
    if (method === 'decision' && clientId === '') throw new HistoryRemoteError('Native decision generation unavailable')
    if (method === 'decision' && 'eventId' in args && typeof args.eventId === 'string') pending.delete(args.eventId)
    const response = await fetch(new URL(`/api/${remote}`, origin), {
      method: 'POST', redirect: 'error', signal: AbortSignal.any([lifetime.signal, AbortSignal.timeout(15_000)]),
      headers: { 'content-type': 'application/json', cookie, origin: new URL(origin).origin },
      body: JSON.stringify({ type: 'client-request', rpcId, method: remote, payload: { args: method === 'decision' ? { ...args, clientId } : method === 'replicaList' || method === 'replicaState' || method === 'replicaFlush' || method === 'modelCatalog' || method === 'presets' || method === 'codexCapabilities' || method === 'answer' ? args : { request: args } } }),
    })
    if (!response.ok) throw new Error('Local history service unavailable')
    const envelope: unknown = await response.json()
    if (!record(envelope) || envelope.rpcId !== rpcId || !record(envelope.result)) throw new Error('Local history response failed')
    if (envelope.result.ok !== true) throw new HistoryRemoteError('Native operation rejected')
    return envelope.result.value
  }
  const upload: NonNullable<HistoryHost['upload']> = async (sessionId, name, data) => {
    if (disposed) throw new Error('History bridge has stopped')
    const url = new URL('/api/session/uploadFileBinary', origin)
    url.searchParams.set('sessionId', sessionId); url.searchParams.set('name', name)
    const response = await fetch(url, {
      method: 'POST', redirect: 'error', signal: AbortSignal.any([lifetime.signal, AbortSignal.timeout(60_000)]),
      headers: { 'content-type': 'application/octet-stream', cookie, origin: new URL(origin).origin },
      body: new Uint8Array(data).buffer,
    })
    if (!response.ok) throw new Error('Native file upload unavailable')
    const result: unknown = await response.json()
    if (!record(result) || result.ok !== true || !record(result.value)) throw new HistoryRemoteError('Native file upload rejected')
    const value = result.value
    if (typeof value.receiptId !== 'string' || value.receiptId.length === 0 || value.receiptId.length > 128 || !record(value.file) || typeof value.file.attachmentId !== 'string' || !Number.isSafeInteger(value.file.bytes)) throw new Error('Invalid native file receipt')
    return { receiptId: value.receiptId, attachmentId: value.file.attachmentId, bytes: Number(value.file.bytes) }
  }
  let choiceCache: { at: number; value: Record<string, unknown> } | undefined
  let choiceRefresh: Promise<void> | undefined
  let choiceAttempt: number | undefined
  const refreshChoices = async (): Promise<void> => {
    try {
      const [catalog, roster] = await Promise.all([invoke('modelCatalog', {}), invoke('presets', {})])
      if (!record(catalog) || !Array.isArray(catalog.groups) || !record(roster) || !Array.isArray(roster.presets)) {
        throw new Error('Invalid mobile model catalog')
      }
      if (!disposed) choiceCache = { at: Date.now(), value: mobileChoices(catalog, roster) }
    } catch { choiceCache = undefined }
  }
  const choices = (): Promise<Record<string, unknown>> => {
    // Slow discovery never holds up cancellation, approvals, or runtime heartbeats.
    if ((choiceCache === undefined || Date.now() - choiceCache.at >= 15_000)
      && (choiceAttempt === undefined || Date.now() - choiceAttempt >= 15_000)) {
      choiceAttempt = Date.now()
      choiceRefresh ??= refreshChoices().finally(() => { choiceRefresh = undefined })
    }
    return Promise.resolve(choiceCache !== undefined && Date.now() - choiceCache.at < 30_000
      ? choiceCache.value : { models: [], presets: [] })
  }
  return { invoke, upload, choices, workspaceOperations: true, workspaces: () => { connect(); return workspaces.list() }, pending: () => { connect(); return [...pending.values()] }, ready: () => clientId !== '', dispose: () => { disposed = true; lifetime.abort(); socket?.terminate(); pending.clear(); workspaces.clear() } }
}
