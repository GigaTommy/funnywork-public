/** Durable, initialize-only Codex checks reuse the original authenticated device channel. */
import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, writeFile, rename, readdir, lstat } from 'node:fs/promises'
import { join } from 'node:path'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import type { HistoryHost } from './funnyworker-history-sync.ts'
import { MaintenanceDeliveryPending } from './funnyworker-maintenance.ts'
import { MaintenanceAgentTerminationUnconfirmed } from './funnyworker-maintenance-agent.ts'
import { maintenanceLease } from './funnyworker-maintenance-lease.ts'
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'
import { runCodexProbe } from './funnyworker-codex-executor.ts'

function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) }
const identifier = /^[A-Za-z0-9_-]{1,64}$/
const version = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/
interface Assignment {
  taskId: string
  executor: 'funnywork-codex-v1'
  protocolVersion: 1
  runtimeEpoch: string
  repository: string
  baseSha: string
  expectedVersion: string
  requiredCapabilities: string[]
  platform: string
  timeoutMs: number
  maxOutputBytes: number
  leaseDurationMs: number
  specHash: string
}
interface Receipt {
  runtimeEpoch: string
  specHash: string
  outcome: 'CHECKED' | 'UNAVAILABLE' | 'FAILED' | 'UNKNOWN'
  clientVersion: string | null
  executorVersion: '1'
  pluginVersion: string | null
  observedSha: string | null
  buildTree: string | null
  buildDirty: boolean | null
  capabilities: string[]
  summary: string
  evidenceDigest: string
  eventCount: number
  terminalState: 'completed' | 'failed' | 'unknown'
  processStopped: boolean
}
interface Journal {
  phase: 'RUNNING' | 'READY' | 'SENT' | 'STALE'
  runtimeEpoch: string
  specHash: string
  receipt?: Receipt
}
function assignment(value: unknown): Assignment {
  if (!object(value) || Object.keys(value).some(k => !['taskId', 'executor', 'protocolVersion', 'runtimeEpoch', 'repository', 'baseSha', 'expectedVersion', 'requiredCapabilities', 'platform', 'timeoutMs', 'maxOutputBytes', 'leaseDurationMs', 'specHash'].includes(k))
    || typeof value.taskId !== 'string' || !identifier.test(value.taskId)
    || value.executor !== 'funnywork-codex-v1' || value.protocolVersion !== 1
    || typeof value.runtimeEpoch !== 'string' || !identifier.test(value.runtimeEpoch)
    || typeof value.repository !== 'string' || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(value.repository)
    || typeof value.baseSha !== 'string' || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value.baseSha)
    || typeof value.expectedVersion !== 'string' || !version.test(value.expectedVersion)
    || !Array.isArray(value.requiredCapabilities) || value.requiredCapabilities.length !== 1 || value.requiredCapabilities[0] !== 'sync-check'
    || !['macos', 'windows', 'linux'].includes(String(value.platform))
    || value.timeoutMs !== 60000 || value.maxOutputBytes !== 16384 || value.leaseDurationMs !== 15000
    || typeof value.specHash !== 'string' || !/^[a-f0-9]{64}$/.test(value.specHash)) throw new Error('Invalid Codex synchronization assignment')
  const canonical = Object.fromEntries(Object.keys(value).filter(k => k !== 'specHash').sort().map(k => [k, value[k]]))
  if (createHash('sha256').update(JSON.stringify(canonical)).digest('hex') !== value.specHash) throw new Error('Codex assignment hash mismatch')
  return { taskId: value.taskId, executor: value.executor, protocolVersion: value.protocolVersion,
    runtimeEpoch: value.runtimeEpoch, repository: value.repository, baseSha: value.baseSha, expectedVersion: value.expectedVersion,
    requiredCapabilities: ['sync-check'], platform: String(value.platform), timeoutMs: value.timeoutMs,
    maxOutputBytes: value.maxOutputBytes, leaseDurationMs: value.leaseDurationMs, specHash: value.specHash }
}
async function save(path: string, journal: Journal): Promise<void> {
  const temporary = `${path}.${randomUUID()}.tmp`
  await writeFile(temporary, JSON.stringify(journal), { flag: 'wx', mode: 0o600 }); await rename(temporary, path)
}
function parseJournal(value: unknown): Journal {
  if (!object(value) || !['RUNNING', 'READY', 'SENT', 'STALE'].includes(String(value.phase))
    || typeof value.runtimeEpoch !== 'string' || !identifier.test(value.runtimeEpoch)
    || typeof value.specHash !== 'string' || !/^[a-f0-9]{64}$/.test(value.specHash)) throw new Error('Invalid Codex synchronization journal')
  if (value.phase === 'READY' && (!object(value.receipt) || value.receipt.runtimeEpoch !== value.runtimeEpoch || value.receipt.specHash !== value.specHash)) throw new Error('Missing Codex synchronization receipt')
  const phase = value.phase
  if (phase !== 'RUNNING' && phase !== 'READY' && phase !== 'SENT' && phase !== 'STALE') throw new Error('Invalid journal phase')
  let receipt: Receipt | undefined
  if (value.receipt !== undefined) {
    const r = value.receipt
    if (!object(r) || r.runtimeEpoch !== value.runtimeEpoch || r.specHash !== value.specHash
      || !['CHECKED', 'UNAVAILABLE', 'FAILED', 'UNKNOWN'].includes(String(r.outcome))
      || !(r.clientVersion === null || typeof r.clientVersion === 'string' && version.test(r.clientVersion))
      || r.executorVersion !== '1' || !(r.pluginVersion === null || typeof r.pluginVersion === 'string' && version.test(r.pluginVersion))
      || !(r.observedSha === null || typeof r.observedSha === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(r.observedSha))
      || !(r.buildTree === null || typeof r.buildTree === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(r.buildTree))
      || !(r.buildDirty === null || typeof r.buildDirty === 'boolean')
      || !Array.isArray(r.capabilities) || r.capabilities.length > 6 || !r.capabilities.every((c: unknown): c is string => typeof c === 'string' && ['start','events','cancel','reconcile','artifacts','sync-check'].includes(c))
      || typeof r.summary !== 'string' || r.summary.length > 2000 || typeof r.evidenceDigest !== 'string' || !/^[a-f0-9]{64}$/.test(r.evidenceDigest)
      || !Number.isSafeInteger(r.eventCount) || Number(r.eventCount) < 0 || Number(r.eventCount) > 10000
      || !['completed','failed','unknown'].includes(String(r.terminalState)) || typeof r.processStopped !== 'boolean') throw new Error('Invalid stored Codex receipt')
    const outcome = r.outcome, terminalState = r.terminalState
    if (outcome !== 'CHECKED' && outcome !== 'UNAVAILABLE' && outcome !== 'FAILED' && outcome !== 'UNKNOWN') throw new Error('Invalid stored outcome')
    if (terminalState !== 'completed' && terminalState !== 'failed' && terminalState !== 'unknown') throw new Error('Invalid stored terminal state')
    receipt = { runtimeEpoch: value.runtimeEpoch, specHash: value.specHash, outcome, clientVersion: r.clientVersion,
      executorVersion: r.executorVersion, pluginVersion: r.pluginVersion, observedSha: r.observedSha,
      capabilities: r.capabilities,
      buildTree: r.buildTree, buildDirty: r.buildDirty, summary: r.summary, evidenceDigest: r.evidenceDigest,
      eventCount: Number(r.eventCount), terminalState, processStopped: r.processStopped }
  }
  return { phase, runtimeEpoch: value.runtimeEpoch, specHash: value.specHash, ...(receipt === undefined ? {} : { receipt }) }
}
/** Actual installed-package metadata, never inferred from a requested baseline. */
export interface DesktopProbeBuild { version: string; commit: string; tree: string; dirty: boolean }
/**
 * @param manifest Installed application package.json.
 * @param appVersion Electron's running app version.
 * @returns Consistent recorded build identity, or undefined for unqualified development/legacy packages.
 */
export function desktopProbeBuild(manifest: unknown, appVersion: string): DesktopProbeBuild | undefined {
  if (!object(manifest) || manifest.version !== appVersion || !version.test(appVersion)
    || typeof manifest.dshBuildCommit !== 'string' || !/^[a-f0-9]{40}$/.test(manifest.dshBuildCommit)
    || typeof manifest.dshBuildTree !== 'string' || !/^[a-f0-9]{40}$/.test(manifest.dshBuildTree)
    || typeof manifest.dshBuildDirty !== 'boolean') return undefined
  return { version: appVersion, commit: manifest.dshBuildCommit, tree: manifest.dshBuildTree, dirty: manifest.dshBuildDirty }
}
/**
 * Execute a fixed backend-authorized probe; a durable RUNNING record is never automatically rerun.
 * @param home Original private account storage.
 * @param account Original authenticated device transport.
 * @param deviceId Registered device identity.
 * @param targetId Immutable target identity.
 * @param payload Frozen, hashed assignment.
 * @param signal Application cancellation.
 * @param currentHost Authenticated Host generation accessor.
 * @param clientVersion Packaged FunnyWork app version, never the Codex version.
 * @param build Identity recorded by the existing application packaging pipeline.
 */
export async function runCodexSyncCheck(home: string, account: Pick<FunnyWorkerAccount, 'syncRequest'>, deviceId: string,
  targetId: string, payload: unknown, signal: AbortSignal,
  currentHost: () => HistoryHost | undefined, clientVersion: string, build?: DesktopProbeBuild): Promise<void> {
  if (!identifier.test(targetId) || !identifier.test(deviceId)) throw new Error('Invalid Codex synchronization identity')
  const spec = assignment(payload), path = join(home, `codex-sync-${targetId}.json`)
  let prior: Journal | undefined
  try { prior = parseJournal(JSON.parse(await readFile(path, 'utf8'))) }
  catch (error) { if (!object(error) || error.code !== 'ENOENT') throw error }
  if (prior !== undefined) {
    if (prior.runtimeEpoch !== spec.runtimeEpoch || prior.specHash !== spec.specHash) throw new MaintenanceAgentTerminationUnconfirmed('Codex synchronization identity changed')
    if (prior.phase === 'RUNNING') throw new MaintenanceAgentTerminationUnconfirmed('Interrupted Codex synchronization requires inspection')
    if (prior.phase === 'READY') await flushCodexSyncChecks(home, account, deviceId, spec.runtimeEpoch)
    return
  }
  signal.throwIfAborted()
  await save(path, { phase: 'RUNNING', runtimeEpoch: spec.runtimeEpoch, specHash: spec.specHash })
  const receipt: Receipt = { runtimeEpoch: spec.runtimeEpoch, specHash: spec.specHash, outcome: 'UNAVAILABLE',
    clientVersion: version.test(clientVersion) ? clientVersion : null, executorVersion: '1', pluginVersion: null,
    observedSha: build?.commit ?? null, buildTree: build?.tree ?? null, buildDirty: build?.dirty ?? null, capabilities: [], summary: 'The fixed synchronization probe is unavailable.',
    evidenceDigest: createHash('sha256').update('unavailable').digest('hex'), eventCount: 0, terminalState: 'failed', processStopped: true }
  const host = currentHost(), deadline = Date.now() + spec.timeoutMs
  const parent = AbortSignal.any([signal, AbortSignal.timeout(spec.timeoutMs)])
  let lease: Awaited<ReturnType<typeof maintenanceLease>> | undefined
  let unknown = false
  try {
    lease = await maintenanceLease(spec.leaseDurationMs, () => account.syncRequest(`/devices/${deviceId}/maintenance-sync-checks/${targetId}/renew`, { runtimeEpoch: spec.runtimeEpoch, specHash: spec.specHash }), parent)
    const bounded = lease.signal
    const platform = process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'linux'
    if (receipt.clientVersion === null || build === undefined || build.version !== clientVersion || build.dirty || host === undefined || spec.platform !== platform) throw new Error('Probe environment unavailable')
    const active = (): void => { bounded.throwIfAborted(); if (currentHost() !== host) throw new MaintenanceAgentTerminationUnconfirmed('Codex Host generation changed') }
    const root = join(home, 'codex-sync-workspaces'), cwd = join(root, targetId)
    await mkdir(root, { recursive: true, mode: 0o700 })
    if ((await lstat(root)).isSymbolicLink()) throw new Error('Probe workspace root must not be a link')
    await mkdir(cwd, { mode: 0o700 })
    active()
    const registered = await host.invoke('workspaceCreate', { path: cwd })
    if (!object(registered) || !object(registered.workspace) || typeof registered.workspace.workspaceId !== 'string') throw new Error('Probe workspace registration unavailable')
    active()
    const evidence = await runCodexProbe(currentHost, { requestId: targetId, runtimeEpoch: spec.runtimeEpoch,
      workspaceId: registered.workspace.workspaceId, baselineSha: spec.baseSha,
      timeoutMs: Math.max(1, deadline - Date.now()), maxOutputBytes: spec.maxOutputBytes }, bounded)
    Object.assign(receipt, evidence)
    receipt.evidenceDigest = createHash('sha256').update(JSON.stringify({ probeDigest: evidence.evidenceDigest,
      specHash: spec.specHash, clientVersion: receipt.clientVersion, observedSha: receipt.observedSha, buildTree: receipt.buildTree, buildDirty: receipt.buildDirty })).digest('hex')
    active()
    receipt.outcome = evidence.terminalState === 'completed' ? 'CHECKED' : 'FAILED'
    receipt.summary = evidence.terminalState === 'completed'
      ? 'Installed package commit, tree and version observed; Codex initialized and stopped. No model or repair was run.'
      : 'Codex initialization failed after confirmed process stop.'
  } catch (error) {
    unknown = error instanceof MaintenanceAgentTerminationUnconfirmed
    receipt.outcome = unknown ? 'UNKNOWN' : build === undefined || build.dirty || host === undefined ? 'UNAVAILABLE' : 'FAILED'
    receipt.terminalState = unknown ? 'unknown' : 'failed'; receipt.processStopped = !unknown
    receipt.summary = unknown ? 'Process stop or Host ownership is unconfirmed; inspect before another probe.' : 'The bounded synchronization observation did not complete.'
    receipt.evidenceDigest = createHash('sha256').update(JSON.stringify({ outcome: receipt.outcome, observedSha: receipt.observedSha, processStopped: receipt.processStopped })).digest('hex')
  }
  await lease?.dispose()
  await save(path, { phase: 'READY', runtimeEpoch: spec.runtimeEpoch, specHash: spec.specHash, receipt })
  try { await flushCodexSyncChecks(home, account, deviceId, spec.runtimeEpoch) }
  catch (error) { throw new MaintenanceDeliveryPending('Codex synchronization receipt saved; delivery will retry', { cause: error }) }
  if (unknown) throw new MaintenanceAgentTerminationUnconfirmed('Codex synchronization ownership remains unknown')
}
/**
 * Retry durable receipts only, without starting Codex again.
 * @param home Original private account storage.
 * @param account Original authenticated device transport.
 * @param deviceId Registered device identity.
 * @param runtimeEpoch Current Host generation.
 */
export async function flushCodexSyncChecks(home: string, account: Pick<FunnyWorkerAccount, 'syncRequest'>, deviceId: string, runtimeEpoch: string): Promise<void> {
  for (const name of await readdir(home)) {
    if (!/^codex-sync-[A-Za-z0-9_-]{1,64}\.json$/.test(name)) continue
    const path = join(home, name), journal = parseJournal(JSON.parse(await readFile(path, 'utf8')))
    if (journal.phase !== 'READY') continue
    if (journal.runtimeEpoch !== runtimeEpoch) { journal.phase = 'STALE'; await save(path, journal); continue }
    try { await account.syncRequest(`/devices/${deviceId}/maintenance-sync-checks/${name.slice(11, -5)}/result`, journal.receipt) }
    catch (error) {
      if (!(error instanceof HistorySyncHttpError && [403, 404, 409, 410].includes(error.status))) throw error
      if (journal.receipt?.processStopped === true) await account.syncRequest(`/devices/${deviceId}/maintenance-sync-checks/${name.slice(11, -5)}/stopped`, { runtimeEpoch: journal.runtimeEpoch, specHash: journal.specHash, evidenceDigest: journal.receipt.evidenceDigest })
      journal.phase = 'STALE'; await save(path, journal); continue
    }
    journal.phase = 'SENT'; await save(path, journal)
  }
}
