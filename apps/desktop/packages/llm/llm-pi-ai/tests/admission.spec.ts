import { expect, it, vi } from 'vitest'
import { admissionTransport } from '../src/admission.ts'
import type { AdmissionRecord } from '../src/admission.ts'
import type { GenerateOptions } from '@deepseek-ai/dsh-llm'

const payload = JSON.stringify({ model: 'qwen', messages: [], max_tokens: 128, stream: true })
function fixture(states: string[] = ['WAITING', 'GRANTED', 'SUCCEEDED']) {
  const lastState = states.at(-1) ?? 'UNKNOWN'
  let stored: AdmissionRecord | undefined
  const calls: Array<{ url: string; init: RequestInit }> = []
  const waiting = vi.fn<(options: GenerateOptions, ticket: string, waiting: boolean) => void>()
  const removed = vi.fn()
  const transport: typeof fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    calls.push({ url, init })
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer mwi.scoped-test')
    if (url.endsWith('/execute')) return new Response('data: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream' } })
    if (url.endsWith('/cancel')) return Response.json({ id: 'ticket-1', state: 'CANCELLED' })
    return Response.json({ id: 'ticket-1', state: states.shift() ?? lastState })
  }
  const prepare = admissionTransport({
    origin: 'https://enterprise.internal', deploymentId: 'deployment-1', model: 'qwen', deviceId: 'device-1',
    pollMs: 1, requestTimeoutMs: 1000, credential: async () => 'mwi.scoped-test',
    acquire: async () => stored ??= { callId: 'call-1', logicalCallId: 'logical-1' },
    save: async (_key, record) => { stored = record },
    remove: async () => { stored = undefined; removed() }, waiting, fetch: transport,
  })
  return { prepare, calls, waiting, removed, stored: () => stored }
}

it('queues before forwarding, preserves body and excludes upstream credentials', async () => {
  const f = fixture()
  const admitted = await f.prepare({ provider: 'enterprise', model: 'qwen', messages: [] }, payload, new AbortController().signal)
  expect(f.calls.map(call => call.url.endsWith('/execute'))).toEqual([false, false])
  expect(f.waiting.mock.calls.map(call => call[2])).toContain(true)
  const response = await admitted('https://upstream.invalid', {
    method: 'POST', body: payload, headers: { 'x-api-key': 'must-never-forward', 'anthropic-beta': 'tools-beta' },
  })
  expect(await response.text()).toBe('data: [DONE]\n\n')
  await vi.waitFor(() => { expect(f.removed).toHaveBeenCalledOnce() })
  const execution = f.calls.find(call => call.url.endsWith('/execute'))!
  expect(execution.init.body).toBe(payload)
  expect(new Headers(execution.init.headers).get('x-api-key')).toBeNull()
  expect(new Headers(execution.init.headers).get('anthropic-beta')).toBe('tools-beta')
  const rawBody = f.calls[0]!.init.body
  if (typeof rawBody !== 'string') throw new Error('Expected serialized admission')
  const admission: unknown = JSON.parse(rawBody)
  expect(admission).toMatchObject({ callId: 'call-1', logicalCallId: 'logical-1', taskId: '', maxOutputTokens: 128 })
  if (typeof admission !== 'object' || admission === null || !('requestDigest' in admission)) throw new Error('Missing request digest')
  expect(typeof admission.requestDigest === 'string' && /^[a-f0-9]{64}$/.test(admission.requestDigest)).toBe(true)
  await expect(admitted('https://ignored.invalid', { body: payload })).rejects.toMatchObject({ code: 'ADMISSION_PAYLOAD_MISMATCH' })
})

it('retains uncertain executions and queries the original ticket without replay', async () => {
  const f = fixture(['GRANTED', 'UNKNOWN', 'RUNNING'])
  const options = { provider: 'enterprise', model: 'qwen', messages: [] }
  const admitted = await f.prepare(options, payload, new AbortController().signal)
  const response = await admitted('https://ignored.invalid', { body: payload })
  await response.text()
  await vi.waitFor(() => { expect(f.calls).toHaveLength(3) })
  expect(f.stored()?.ticketId).toBe('ticket-1')
  await expect(f.prepare(options, payload, new AbortController().signal)).rejects.toMatchObject({ code: 'ADMISSION_RECOVERY_REQUIRED' })
  expect(f.calls.filter(call => call.url.endsWith('/execute'))).toHaveLength(1)
  expect(f.calls.filter(call => call.url.endsWith('/tickets'))).toHaveLength(1)
})

it('cancels a queued ticket on Stop and does not send an inference request', async () => {
  const f = fixture(['WAITING'])
  const controller = new AbortController()
  const pending = f.prepare({ provider: 'enterprise', model: 'qwen', messages: [] }, payload, controller.signal)
  await vi.waitFor(() => { expect(f.waiting).toHaveBeenCalled() })
  controller.abort(new Error('user stopped'))
  await expect(pending).rejects.toThrow('user stopped')
  await vi.waitFor(() => { expect(f.calls.some(call => call.url.endsWith('/cancel'))).toBe(true) })
  expect(f.calls.some(call => call.url.endsWith('/execute'))).toBe(false)
})

it('rejects a changed request before consuming its permit', async () => {
  const f = fixture(['GRANTED'])
  const admitted = await f.prepare({ provider: 'enterprise', model: 'qwen', messages: [] }, payload, new AbortController().signal)
  await expect(admitted('https://ignored.invalid', { body: `${payload} ` })).rejects.toMatchObject({ code: 'ADMISSION_PAYLOAD_MISMATCH' })
  expect(f.calls.some(call => call.url.endsWith('/execute'))).toBe(false)
})
it('disposal cancels and waits for the queue cleanup before returning',async()=>{
  const f=fixture(['WAITING'])
  const pending=f.prepare({ provider:'enterprise',model:'qwen',messages:[] },payload,new AbortController().signal)
  const settled=expect(pending).rejects.toMatchObject({ code:'ABORTED' })
  await vi.waitFor(()=>{expect(f.waiting).toHaveBeenCalled()})
  await f.prepare.dispose();await settled
  expect(f.calls.filter(call=>call.url.endsWith('/cancel'))).toHaveLength(1)
  expect(f.calls.some(call=>call.url.endsWith('/execute'))).toBe(false)
})

it('reports quota exhaustion before execution without exposing raw server diagnostics', async () => {
  const transport = vi.fn<typeof fetch>(async () => Response.json({ code: 'USER_MODEL_BUDGET_EXHAUSTED', message: 'secret upstream diagnostic' }, { status: 429 }))
  const prepare = admissionTransport({ origin: 'https://enterprise.internal', deploymentId: 'deployment-1', model: 'qwen', deviceId: 'device-1', pollMs: 1, requestTimeoutMs: 1000, credential: async () => 'mwi.scoped-test', acquire: async () => ({ callId: 'call-1', logicalCallId: 'logical-1' }), save: async () => {}, remove: async () => {}, fetch: transport })
  await expect(prepare({ provider: 'enterprise', model: 'qwen', messages: [] }, payload, new AbortController().signal)).rejects.toMatchObject({ code: 'USER_MODEL_BUDGET_EXHAUSTED', message: 'Insufficient model allowance. Check your balance or contact your administrator for a top-up.' })
  expect(transport).toHaveBeenCalledTimes(1)
  await prepare.dispose()
})

it.each(['USER_MODEL_FUNDS_RESERVED', 'GLOBAL_MODEL_FUNDS_RESERVED'])('distinguishes %s from spending exhaustion without retrying an execution', async (code) => {
  const transport = vi.fn<typeof fetch>(async () => Response.json({ code, message: 'private diagnostic' }, { status: 429 }))
  const prepare = admissionTransport({ origin: 'https://enterprise.internal', deploymentId: 'deployment-1', model: 'qwen', deviceId: 'device-1', pollMs: 1, requestTimeoutMs: 1000, credential: async () => 'mwi.scoped-test', acquire: async () => ({ callId: 'call-1', logicalCallId: 'logical-1' }), save: async () => {}, remove: async () => {}, fetch: transport })
  try {
    const attempt = prepare({ provider: 'enterprise', model: 'qwen', messages: [] }, payload, new AbortController().signal)
    await expect(attempt).rejects.toMatchObject({ code })
    await expect(attempt).rejects.toThrow('reserved by unfinished tasks')
    expect(transport).toHaveBeenCalledTimes(1)
  } finally { await prepare.dispose() }
})
