/** Desktop bridge routes only the fixed probe methods through the existing authenticated RPC carrier. */
import { afterEach, expect, test, vi } from 'vitest'
import { historyHost } from '../src/funnyworker-history-sync.ts'
afterEach(() => { vi.unstubAllGlobals() })
test('uses the registered codexMaintenance namespace and exact Typert argument convention', async () => {
  const requests: { url: string; body: object }[] = []
  vi.stubGlobal('fetch', async (url: URL, init: RequestInit) => {
    if (typeof init.body !== 'string') throw new Error('Expected JSON request text')
    const body: unknown = JSON.parse(init.body)
    if (body === null || typeof body !== 'object' || !('rpcId' in body)) throw new Error('Expected RPC envelope')
    requests.push({ url: url.pathname, body })
    return { ok: true, json: async () => ({ rpcId: body.rpcId, result: { ok: true, value: { processStopped: true } } }) }
  })
  const host = historyHost('http://127.0.0.1:1', 'private-cookie')
  try {
    await host.invoke('codexCapabilities', {})
    const request = { runId: 'target', runtimeEpoch: 'epoch' }
    for (const method of ['codexStart', 'codexEvents', 'codexCancel', 'codexReconcile'] as const) await host.invoke(method, request)
    expect(requests.map(r => r.url)).toEqual(['capabilities', 'start', 'events', 'cancel', 'reconcile'].map(m => `/api/codexMaintenance/${m}`))
    expect(requests[0]?.body).toHaveProperty('payload', { args: {} })
    expect(requests.slice(1).every(r => JSON.stringify('payload' in r.body ? r.body.payload : undefined) === JSON.stringify({ args: { request } }))).toBe(true)
    expect(JSON.stringify(requests)).not.toContain('private-cookie')
  } finally { host.dispose?.() }
})
