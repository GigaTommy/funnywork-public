/** Bounded, non-model Codex initialization checks. No thread or turn is created. */
import type { SubprocessHandle, SubprocessSpawnSpec } from '@deepseek-ai/dsh-subprocess'
import { codexAppServerArgv, disposeCodexChild } from './run.ts'
import { CodexAppServerWire } from './wire.ts'

import type { MaintenanceCapabilities, MaintenanceState, MaintenanceStart, MaintenanceAddress, MaintenanceCursor, MaintenanceReceipt, MaintenanceEvent, MaintenanceEvents } from './maintenance-types.ts'
export type * from './maintenance-types.ts'

/** Host-owned process and workspace services. */
export interface MaintenanceDependencies {
  resolveWorkspace(id: string): string | undefined
  spawn(spec: SubprocessSpawnSpec): SubprocessHandle
  probeCwd: string
  disposeGraceMs: number
}
interface Run {
  request: MaintenanceStart
  receipt: MaintenanceReceipt
  events: MaintenanceEvent[]
  abort: AbortController
  done: Promise<void>
}
/** Fixed capabilities describe initialization lifecycle only, not model execution. */
export const MAINTENANCE_CAPABILITIES = ['start', 'events', 'cancel', 'reconcile', 'sync-check']

function validId(value: string): boolean { return typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,160}$/.test(value) }
function validate(request: MaintenanceStart): void {
  if (!validId(request.requestId) || !validId(request.workspaceId) || !validId(request.runtimeEpoch)
    || !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(request.baselineSha)
    || !Number.isInteger(request.timeoutMs) || request.timeoutMs < 1 || request.timeoutMs > 60_000
    || !Number.isInteger(request.maxOutputBytes) || request.maxOutputBytes < 1024 || request.maxOutputBytes > 16_384) {
    throw new Error('Invalid Codex maintenance request')
  }
}
function bounded<T>(pending: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { reject(new Error('Codex stop unconfirmed')) }, ms)
    pending.then((value) => { clearTimeout(timer); resolve(value) }, (error: unknown) => {
      clearTimeout(timer)
      reject(error instanceof Error ? error : new Error('Codex operation failed', { cause: error }))
    })
  })
}
/** In-memory run ownership. Restart recovery is deliberately unknown. */
export class CodexMaintenanceController {
  private readonly runs = new Map<string, Run>()
  private probe: Promise<string | undefined> | undefined
  private probeAbort: AbortController | undefined
  private poisoned = false
  private disposed = false
  constructor(private readonly deps: MaintenanceDependencies) {}

  /** Probe the actual executable through initialize; cache concurrent calls only.
   * @returns Observed capabilities with explicit probe process-stop evidence.
   */
  async capabilities(): Promise<MaintenanceCapabilities> {
    if (this.disposed || this.poisoned || [...this.runs.values()].some(run => !run.receipt.processStopped)) {
      return { available: false, processStopped: !this.poisoned && ![...this.runs.values()].some(run => !run.receipt.processStopped) && !this.probe, executor: 'funnywork-codex-v1' as const, protocolVersion: 1 as const, executorVersion: '1', pluginVersion: null, capabilities: [] as string[] }
    }
    if (!this.probe) {
      this.probeAbort = new AbortController()
      this.probe = this.check(this.deps.probeCwd, this.probeAbort, 5000, 16384)
        .then((result) => {
          if (!result.stopped) this.poisoned = true
          return result.stopped ? result.version : undefined
        }).catch(() => undefined)
    }
    let version: string | undefined
    try { version = await this.probe } finally { this.probe = undefined; this.probeAbort = undefined }
    return { available: version !== undefined, processStopped: !this.poisoned, executor: 'funnywork-codex-v1' as const, protocolVersion: 1 as const,
      executorVersion: '1', pluginVersion: version ?? null, capabilities: version === undefined ? [] : [...MAINTENANCE_CAPABILITIES] }
  }

  /** Admit a fixed request once; identical retries retain the same run.
   * @param request Dispatch identity, registered workspace and bounded probe limits.
   * @returns The existing or newly admitted run state, not a completion guarantee.
   */
  start(request: MaintenanceStart): MaintenanceReceipt {
    validate(request)
    const previous = this.runs.get(request.requestId)
    if (previous) {
      if ((Object.keys(previous.request) as (keyof MaintenanceStart)[]).some(key => previous.request[key] !== request[key])) throw new Error('Codex request identity conflict')
      return { ...previous.receipt }
    }
    if (this.disposed || this.poisoned || this.probe || this.runs.size >= 128 || [...this.runs.values()].some(run => !run.receipt.processStopped)) throw new Error('Codex maintenance capacity reached')
    const cwd = this.deps.resolveWorkspace(request.workspaceId)
    if (!cwd) throw new Error('Codex workspace unavailable')
    const run: Run = { request: { ...request }, receipt: { runId: request.requestId, runtimeEpoch: request.runtimeEpoch, state: 'running', processStopped: false },
      events: [{ seq: 1, kind: 'started', state: 'running' }], abort: new AbortController(), done: Promise.resolve() }
    this.runs.set(request.requestId, run)
    run.done = this.execute(run, cwd)
    return { ...run.receipt }
  }

  /** Poll only events belonging to this exact runtime generation.
   * @param request Run identity, runtime epoch and last observed event sequence.
   * @returns Structured events after the cursor with current lifecycle and stop state.
   */
  events(request: MaintenanceCursor): MaintenanceEvents {
    if (!Number.isInteger(request.after) || request.after < 0) throw new Error('Invalid Codex event cursor')
    const run = this.find(request)
    if (!run) return { ...this.reconcile(request), events: [], nextSeq: request.after }
    if (request.after > run.events.length) throw new Error('Invalid Codex event cursor')
    return { ...run.receipt,
      events: run.events.filter(event => event.seq > request.after).map(event => ({ ...event })), nextSeq: run.events.length }
  }

  /** Cancel and wait for the managed process owner; never infer exit from intent.
   * @param request Exact run identity and runtime epoch to cancel.
   * @returns Reconciled process state after waiting for the owned run to settle.
   */
  async cancel(request: MaintenanceAddress): Promise<MaintenanceReceipt> {
    const run = this.find(request)
    if (run) { run.abort.abort(); await run.done }
    return this.reconcile(request)
  }

  /** Unknown ids/epochs cannot establish whether a former process stopped.
   * @param request Exact run identity and runtime epoch to inspect.
   * @returns Known receipt or unknown with no process-stop confirmation.
   */
  reconcile(request: MaintenanceAddress): MaintenanceReceipt {
    return { ...(this.find(request)?.receipt ?? { ...request, state: 'unknown' as const, processStopped: false }) }
  }

  /** Stop owned processes on plugin disposal. */
  async dispose(): Promise<void> {
    this.disposed = true
    this.probeAbort?.abort()
    await this.probe
    for (const run of this.runs.values()) run.abort.abort()
    await Promise.all([...this.runs.values()].map(run => run.done))
  }

  private find(request: MaintenanceAddress): Run | undefined {
    const run = this.runs.get(request.runId)
    return run?.receipt.runtimeEpoch === request.runtimeEpoch ? run : undefined
  }
  private async execute(run: Run, cwd: string): Promise<void> {
    let state: MaintenanceState = 'failed'
    try {
      const result = await this.check(cwd, run.abort, run.request.timeoutMs, run.request.maxOutputBytes)
      run.receipt.processStopped = result.stopped
      state = !result.stopped ? 'unknown' : run.abort.signal.aborted ? 'cancelled' : result.version ? 'succeeded' : 'failed'
      if (result.version) run.events.push({ seq: 2, kind: 'capability', pluginVersion: result.version, protocolVersion: 1 })
    } catch { // Spawn did not return an owned handle; no process was admitted.
      run.receipt.processStopped = true
    }
    run.receipt.state = state
    run.events.push({ seq: run.events.length + 1, kind: 'terminal', state })
  }
  private async check(cwd: string, abort: AbortController, timeoutMs: number, maxOutputBytes: number) {
    const child = this.deps.spawn({ argv: codexAppServerArgv(), cwd, env: {}, signal: abort.signal,
      graceMs: this.deps.disposeGraceMs, stdio: { stdin: 'pipe', stdout: 'pipe', stderr: { maxBytes: 0 } } })
    void child.done.catch(() => {})
    let wire: CodexAppServerWire | undefined
    let version: string | undefined
    let stopped = false
    let bytes = 0
    const onData = (chunk: Buffer | string) => { bytes += Buffer.byteLength(chunk); if (bytes > maxOutputBytes) abort.abort() }
    const timer = setTimeout(() => { abort.abort() }, timeoutMs)
    try {
      if (!child.stdout || !child.stdin) throw new Error('Codex protocol pipes unavailable')
      child.stdout.on('data', onData)
      wire = new CodexAppServerWire(child.stdout, child.stdin, 'never')
      wire.start()
      version = await wire.initialize(abort.signal)
    } catch { // Raw errors may contain secrets. Expose only fixed lifecycle facts.
      version = undefined
    } finally {
      clearTimeout(timer)
      try {
        if (wire) await bounded(disposeCodexChild(wire, child), this.deps.disposeGraceMs * 3 + 1000)
        else { child.terminate(); await bounded(child.waitForExit(), this.deps.disposeGraceMs * 3 + 1000) }
        stopped = true
      } catch { // Range teardown was not confirmed; preserve unknown state.
        stopped = false
      }
      child.stdout?.off('data', onData)
    }
    return { version, stopped }
  }
}
