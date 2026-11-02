import { beforeEach as configureRegionalEntry, afterEach as restoreRegionalEntry } from 'vitest'
import { expect, it, vi } from 'vitest'
import { funnyWorkOrigins, funnyWorkIdentity, funnyWorkTransport } from '../src/funnywork.ts'

const international = 'https://funnywork.funnypixelsapp.com'
const mainland = 'https://regional.example.invalid'
const session = '/api/account/session'

it('races credential-free probes and sends credentials only to the winning approved entry', async () => {
  const send = vi.fn(async (url: string, init?: RequestInit) => {
    if (!new Headers(init?.headers).has('Authorization')) {
      if (url.startsWith(international)) return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>{  reject(new Error('Probe cancelled')) }, { once: true })
      })
      return new Response('{}', { status: 401 })
    }
    return Response.json({ ok: true })
  })
  const transport = funnyWorkTransport(international, send)
  expect(await (await transport.fetch(international + session, { headers: { Authorization: 'Bearer fixture' } })).json()).toEqual({ ok: true })
  expect(send.mock.calls.at(-1)?.[0]).toBe(mainland + session)
  expect(send.mock.calls.slice(0, 2).every(([, init]) => !new Headers(init?.headers).has('Authorization'))).toBe(true)
})

it('does not replay a failed POST and switches the next request to the other entry', async () => {
  const posted: string[] = []
  const send = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST') { posted.push(url); throw new Error('Unknown execution outcome') }
    if (new Headers(init?.headers).has('Authorization')) return Response.json({ ok: true })
    return new Response('{}', { status: 401 })
  })
  const transport = funnyWorkTransport(international, send)
  const first = await transport.ready()
  await expect(transport.fetch(international + '/api/account/inference/tickets/id/execute', { method: 'POST', body: '{}', headers: { Authorization: 'Bearer fixture' } })).rejects.toThrow('Unknown execution outcome')
  expect(posted).toHaveLength(1)
  await transport.fetch(international + session, { headers: { Authorization: 'Bearer fixture' } })
  expect(send.mock.calls.at(-1)?.[0]).toBe((first === international ? mainland : international) + session)
})

it('retries a safe GET once on a network failure without treating permission errors as routing failures', async () => {
  let attempts = 0
  const send = vi.fn(async (_url: string, init?: RequestInit) => {
    if (!new Headers(init?.headers).has('Authorization')) return new Response('{}', { status: 401 })
    if (++attempts === 1) throw new Error('Connection lost')
    return new Response('{}', { status: 403 })
  })
  const transport = funnyWorkTransport(international, send)
  expect((await transport.fetch(international + session, { headers: { Authorization: 'Bearer fixture' } })).status).toBe(403)
  expect(attempts).toBe(2)
})

it('keeps self-hosted origins isolated and refuses credential leakage to unrelated URLs or management paths', async () => {
  const send = vi.fn(async () => Response.json({ ok: true }))
  const custom = funnyWorkTransport('https://private.example', send)
  await custom.fetch('https://private.example/api/account/session')
  expect(send).toHaveBeenCalledTimes(1)
  await expect(custom.fetch(mainland + session)).rejects.toThrow('mismatch')
  const official = funnyWorkTransport(international, send)
  await expect(official.fetch('https://regional.example.invalid.evil.example' + session)).rejects.toThrow('mismatch')
  await expect(official.fetch(mainland + '/api/v1/tasks')).rejects.toThrow('mismatch')
  expect(funnyWorkIdentity(mainland)).toBe(international)
  expect(funnyWorkIdentity('https://private.example')).toBe('https://private.example')
})

it('rejects unusable probes before sending any authenticated operation', async () => {
  const send = vi.fn(async () => new Response('unavailable', { status: 503 }))
  await expect(funnyWorkTransport(international, send).fetch(international + session, { headers: { Authorization: 'Bearer fixture' } })).rejects.toBeInstanceOf(AggregateError)
  expect(send).toHaveBeenCalledTimes(2)
})

it('does not retry an explicitly aborted request', async () => {
  const controller = new AbortController()
  const send = vi.fn(async (_url: string, init?: RequestInit) => {
    if (!new Headers(init?.headers).has('Authorization')) return new Response('{}', { status: 401 })
    controller.abort()
    throw new Error('Cancelled')
  })
  await expect(funnyWorkTransport(international, send).fetch(international + session, { headers: { Authorization: 'Bearer fixture' }, signal: controller.signal })).rejects.toThrow('Cancelled')
  expect(send).toHaveBeenCalledTimes(3)
})

configureRegionalEntry(() => { vi.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', 'https://regional.example.invalid') })
restoreRegionalEntry(() => { vi.unstubAllEnvs() })


it('defaults to only the canonical origin and rejects malformed operator routing', () => {
  vi.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', '')
  expect(funnyWorkOrigins()).toEqual([international])
  expect(funnyWorkIdentity(mainland)).toBe(mainland)
  for (const value of ['http://regional.example', 'https://u:p@regional.example', 'https://regional.example/path', 'https://regional.example?token=x', 'https://regional.example:8443', 'https://regional.example/']) {
    vi.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', value)
    expect(() => funnyWorkOrigins()).toThrow()
  }
})
