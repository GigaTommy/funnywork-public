/** Deterministic lease timing uses restored fake clocks and no model or network. */
import { afterEach, expect, test, vi } from 'vitest'
import { maintenanceLease } from '../src/funnyworker-maintenance-lease.ts'
import { HistorySyncHttpError } from '../src/funnyworker-sync-error.ts'
afterEach(() => { vi.useRealTimers() })
const renewed = () => ({ expiresAt: 1800000000000 })
function abortMessage(signal: AbortSignal): string {
  const reason: unknown = signal.reason
  if (!(reason instanceof Error)) throw new Error('Expected an Error cancellation reason')
  return reason.message
}
test('a temporary outage recovers within the original confirmed lease', async () => {
  vi.useFakeTimers()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockRejectedValueOnce(new HistorySyncHttpError(503)).mockResolvedValue(renewed())
  const lease = await maintenanceLease(9000, request, new AbortController().signal)
  try {
    await vi.advanceTimersByTimeAsync(3000)
    expect(lease.signal.aborted).toBe(false)
    await vi.advanceTimersByTimeAsync(6001)
    expect(lease.signal.aborted).toBe(false)
  } finally { await lease.dispose() }
  const calls = request.mock.calls.length
  await vi.advanceTimersByTimeAsync(30000)
  expect(request).toHaveBeenCalledTimes(calls)
})
test('outages do not extend the confirmed lease or expose transport diagnostics', async () => {
  vi.useFakeTimers()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockRejectedValue(new Error('secret diagnostic'))
  const lease = await maintenanceLease(9000, request, new AbortController().signal)
  try {
    await vi.advanceTimersByTimeAsync(9000)
    expect(lease.signal.aborted).toBe(true)
    expect(abortMessage(lease.signal)).toContain('TRANSPORT')
    expect(abortMessage(lease.signal)).not.toContain('secret')
    await expect(lease.confirm()).rejects.toThrow('expired')
  } finally { await lease.dispose() }
})
test.each([401, 403, 404, 409, 410])('HTTP %s rejects the lease immediately', async (status) => {
  vi.useFakeTimers()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockRejectedValue(new HistorySyncHttpError(status))
  const lease = await maintenanceLease(9000, request, new AbortController().signal)
  try {
    await vi.advanceTimersByTimeAsync(3000)
    expect(lease.signal.aborted).toBe(true)
    expect(abortMessage(lease.signal)).toContain(`HTTP_${status}`)
  } finally { await lease.dispose() }
})
test('overlapping confirmation shares one request and expiry cancels a hung renewal', async () => {
  vi.useFakeTimers()
  const waiting = Promise.withResolvers<unknown>()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockReturnValue(waiting.promise)
  const lease = await maintenanceLease(9000, request, new AbortController().signal)
  try {
    await vi.advanceTimersByTimeAsync(3000)
    const rejection = expect(lease.confirm()).rejects.toThrow('expired')
    await vi.advanceTimersByTimeAsync(6000)
    expect(lease.signal.aborted).toBe(true)
    expect(request).toHaveBeenCalledTimes(2)
    waiting.resolve(renewed())
    await rejection
  } finally { waiting.resolve(renewed()); await lease.dispose() }
})
test('parent cancellation survives a late successful renewal', async () => {
  vi.useFakeTimers()
  const parent = new AbortController(), waiting = Promise.withResolvers<unknown>()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockReturnValue(waiting.promise)
  const lease = await maintenanceLease(9000, request, parent.signal)
  try {
    await vi.advanceTimersByTimeAsync(3000)
    parent.abort(new Error('App stopped'))
    waiting.resolve(renewed())
    await vi.advanceTimersByTimeAsync(0)
    expect(abortMessage(lease.signal)).toBe('App stopped')
    await expect(lease.confirm()).rejects.toThrow('App stopped')
  } finally { waiting.resolve(renewed()); await lease.dispose() }
})
test('malformed or late initial confirmation never starts execution', async () => {
  vi.useFakeTimers()
  await expect(maintenanceLease(9000, async () => ({}), new AbortController().signal)).rejects.toThrow('response invalid')
  await expect(maintenanceLease(9000, async () => { throw new TypeError('private detail') }, new AbortController().signal)).rejects.toThrow(TypeError)
  const waiting = Promise.withResolvers<unknown>()
  const result = maintenanceLease(9000, () => waiting.promise, new AbortController().signal)
  const rejection = expect(result).rejects.toThrow('too late')
  await vi.advanceTimersByTimeAsync(9001)
  waiting.resolve(renewed())
  await rejection
})
test('disposal waits for an in-flight renewal without arming a late timer', async () => {
  vi.useFakeTimers()
  const waiting = Promise.withResolvers<unknown>()
  const request = vi.fn().mockResolvedValueOnce(renewed()).mockReturnValue(waiting.promise)
  const lease = await maintenanceLease(9000, request, new AbortController().signal)
  await vi.advanceTimersByTimeAsync(3000)
  const disposed = lease.dispose()
  waiting.resolve(renewed())
  await disposed
  expect(vi.getTimerCount()).toBe(0)
  await vi.advanceTimersByTimeAsync(30000)
  expect(lease.signal.aborted).toBe(false)
})
