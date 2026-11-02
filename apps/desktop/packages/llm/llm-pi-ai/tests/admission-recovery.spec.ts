import { expect, it } from 'vitest'
import { admissionTransport, type AdmissionRecord } from '../src/admission.ts'
const payload = JSON.stringify({ model: 'qwen', max_tokens: 128, stream: true })
const options = { provider: 'enterprise', model: 'qwen', messages: [] }
function fixture(state = 'GRANTED', executionFailure = false, persistent = false) {
  let stored: AdmissionRecord = { callId: 'stable', logicalCallId: 'logical' }
  const bodies: string[] = []
  let executions = 0
  const failure = () => new TypeError('https://secret.invalid?token=private', { cause: Object.assign(new Error('secret bearer'), { code: 'UND_ERR_CONNECT_TIMEOUT' }) })
  const prepare = admissionTransport({ origin: 'https://enterprise.internal', deploymentId: 'd', model: 'qwen', deviceId: 'device', pollMs: 1, requestTimeoutMs: 1000,
    credential: async () => 'mwi.test', acquire: async () => stored, save: async (_key, record) => { stored = record }, remove: async () => {},
    fetch: async (input, init) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (url.endsWith('/tickets')) {
        if (typeof init?.body !== 'string') throw new Error('Expected serialized admission body')
        bodies.push(init.body)
        if (bodies.length === 1 || persistent) throw failure()
        return Response.json({ id: 'same-permit', state })
      }
      if (url.endsWith('/execute')) { executions++; if (executionFailure) throw failure(); return new Response('done') }
      return Response.json({ id: 'same-permit', state: executionFailure ? 'UNKNOWN' : 'SUCCEEDED' })
    },
  })
  return { prepare, bodies, stored: () => stored, executions: () => executions }
}
it('reconciles a committed permit after response loss using identical durable admission identity', async () => {
  const f = fixture()
  try {
    const execute = await f.prepare(options, payload, new AbortController().signal)
    expect(f.bodies).toHaveLength(2)
    expect(f.bodies[0]).toBe(f.bodies[1])
    expect(f.stored()).toMatchObject({ callId: 'stable', logicalCallId: 'logical', ticketId: 'same-permit' })
    expect(await (await execute('https://ignored.invalid', { body: payload })).text()).toBe('done')
    expect(f.executions()).toBe(1)
  } finally { await f.prepare.dispose() }
})
it.each(['RUNNING', 'UNKNOWN', 'SUCCEEDED'])('does not execute a reconciled %s permit', async (state) => {
  const f = fixture(state)
  try {
    await expect(f.prepare(options, payload, new AbortController().signal)).rejects.toMatchObject({ code: 'ADMISSION_RECOVERY_REQUIRED' })
    expect(f.bodies).toHaveLength(2)
    expect(f.executions()).toBe(0)
  } finally { await f.prepare.dispose() }
})
it('bounds reconciliation and redacts native diagnostics', async () => {
  const f = fixture('GRANTED', false, true)
  try {
    await expect(f.prepare(options, payload, new AbortController().signal)).rejects.toMatchObject({ code: 'ADMISSION_CONTROL_CONNECT_TIMEOUT', message: 'Model transport phase CONTROL: CONNECT_TIMEOUT; request identity retained for reconciliation' })
    expect(f.bodies).toHaveLength(2)
  } finally { await f.prepare.dispose() }
})
it('retains the permit and never replays an uncertain execute', async () => {
  const f = fixture('GRANTED', true)
  try {
    const execute = await f.prepare(options, payload, new AbortController().signal)
    await expect(execute('https://ignored.invalid', { body: payload })).rejects.toMatchObject({ code: 'ADMISSION_EXECUTE_CONNECT_TIMEOUT' })
    await expect(f.prepare(options, payload, new AbortController().signal)).rejects.toMatchObject({ code: 'ADMISSION_RECOVERY_REQUIRED' })
    expect(f.executions()).toBe(1)
  } finally { await f.prepare.dispose() }
})
