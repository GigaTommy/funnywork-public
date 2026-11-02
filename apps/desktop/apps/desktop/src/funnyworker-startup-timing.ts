/** Bounded startup diagnostics containing stage names and durations, never account or credential data. */
import { appendFile, mkdir, rename, stat } from 'node:fs/promises'
import { dirname } from 'node:path'

/** Fixed diagnostic stages admitted to the startup log. */
export type FunnyWorkerStartupStage = 'authentication' | 'login-request' | 'session-check' | 'startup-config'
  | 'model-bootstrap' | 'model-credential' | 'model-catalog' | 'model-configuration'
  | 'organization-policy' | 'workspace-runtime' | 'workspace-visible'

/** A recorder independent of the diagnostic storage implementation. */
export interface FunnyWorkerStartupRecorder {
  /**
   * @param stage - Fixed operation name.
   * @param action - Operation whose success and duration are recorded.
   * @returns The operation result.
   */
  measure<T>(stage: FunnyWorkerStartupStage, action: () => Promise<T>): Promise<T>
}

/** One startup attempt; logs rotate at one MiB and cannot fail application initialization. */
export class FunnyWorkerStartupTimings implements FunnyWorkerStartupRecorder {
  private readonly start = performance.now()
  private pending = Promise.resolve()
  /** @param path - Private JSONL log under the existing application data directory. */
  constructor(private readonly path: string) {}

  async measure<T>(stage: FunnyWorkerStartupStage, action: () => Promise<T>): Promise<T> {
    const start = performance.now()
    let outcome: 'ok' | 'error' = 'error'
    try {
      const value = await action()
      outcome = value instanceof Response && !value.ok ? 'error' : 'ok'
      return value
    } finally {
      const event = JSON.stringify({ schema: 1, stage, outcome, durationMs: Math.round(performance.now() - start),
        elapsedMs: Math.round(performance.now() - this.start), time: new Date().toISOString() }) + '\n'
      this.pending = this.pending.then(async () => {
        await mkdir(dirname(this.path), { recursive: true, mode: 0o700 })
        try {
          if ((await stat(this.path)).size >= 1048576) await rename(this.path, `${this.path}.previous`)
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
        }
        await appendFile(this.path, event, { mode: 0o600 })
      }).catch((_diagnosticStorageUnavailable: unknown) => { /* Diagnostics must not block sign-in. */ })
    }
  }

  /** @returns Resolves when currently queued records have been written or safely discarded. */
  async flush(): Promise<void> { await this.pending }
}
