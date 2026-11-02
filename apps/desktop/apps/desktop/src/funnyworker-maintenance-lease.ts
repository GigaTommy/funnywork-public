/** Bounded renewal of an existing backend lease; failures never extend its confirmed duration. */
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'

/** Owns renewal, expiry cancellation and publication-time confirmation for one attempt. */
export interface MaintenanceLease {
  readonly signal: AbortSignal
  /** Reconfirm the lease before publishing a candidate. */
  confirm(): Promise<void>
  /** Stop timers and await the last in-flight request. */
  dispose(): Promise<void>
}

/**
 * Start only after a successful renewal; tolerate transport failures within the confirmed lease.
 * @param durationMs Conservative duration supplied in the backend assignment.
 * @param request Existing authenticated renewal request.
 * @param parent Original App cancellation.
 * @returns A lease owner whose signal stops at rejection, expiry or App cancellation.
 */
export async function maintenanceLease(
  durationMs: number, request: () => Promise<unknown>, parent: AbortSignal,
): Promise<MaintenanceLease> {
  if (!Number.isSafeInteger(durationMs) || durationMs <= 0 || durationMs > 2147483647)
    throw new Error('Invalid maintenance lease duration')
  const controller = new AbortController()
  let expiry: ReturnType<typeof setTimeout> | undefined
  let pending: Promise<void> | undefined
  let disposed = false
  const isDisposed = (): boolean => disposed
  let failure = 'NO_RENEWAL'
  const abort = (): void => { controller.abort(parent.reason) }
  parent.addEventListener('abort', abort, { once: true })
  if (parent.aborted) abort()
  const confirm = (): Promise<void> => {
    if (isDisposed()) return Promise.reject(new Error('Maintenance lease disposed'))
    if (pending !== undefined) return pending
    pending = (async () => {
      controller.signal.throwIfAborted()
      const started = performance.now()
      try {
        const result = await request()
        if (isDisposed()) throw new Error('Maintenance lease disposed')
        controller.signal.throwIfAborted()
        if (typeof result !== 'object' || result === null || !('expiresAt' in result) ||
          typeof result.expiresAt !== 'number' || !Number.isSafeInteger(result.expiresAt)) {
          controller.abort(new Error('Maintenance lease response invalid'))
          throw controller.signal.reason
        }
        const remaining = durationMs - (performance.now() - started)
        if (remaining <= 0) {
          controller.abort(new Error('Maintenance lease confirmation arrived too late'))
          throw controller.signal.reason
        }
        clearTimeout(expiry)
        expiry = setTimeout(() => { controller.abort(new Error(`Maintenance lease expired after renewal failure (${failure})`)) }, remaining)
        failure = 'NO_RENEWAL'
      } catch (error) {
        failure = error instanceof HistorySyncHttpError ? `HTTP_${error.status}` : 'TRANSPORT'
        if (error instanceof HistorySyncHttpError && error.status >= 400 && error.status < 500
          && error.status !== 408 && error.status !== 429)
          controller.abort(new Error(`Maintenance lease rejected (${failure})`))
        throw error
      }
    })().finally(() => { pending = undefined })
    return pending
  }
  try { await confirm() }
  catch (error) {
    clearTimeout(expiry)
    parent.removeEventListener('abort', abort)
    throw error
  }
  const timer = setInterval(() => {
    if (pending !== undefined || controller.signal.aborted) return
    void confirm().catch(() => { /* Expiry remains armed; only a successful renewal moves it. */ })
  }, Math.max(1, Math.floor(Math.min(10000, durationMs / 3))))
  return {
    signal: controller.signal,
    confirm,
    async dispose() {
      disposed = true
      clearInterval(timer)
      clearTimeout(expiry)
      parent.removeEventListener('abort', abort)
      try { await pending } catch (_renewalFailed) { /* Execution already owns the renewal outcome. */ }
    },
  }
}
