/** Prepared calls retain their endpoint and credential generation. */
import { afterEach, expect, it } from 'vitest'
import type { AnonymousUserId } from '@deepseek-ai/dsh-anonymous-user-id'
import type { PrepareLlmHttp } from '@deepseek-ai/dsh-llm'
import { DeepSeekAdapter } from '../src/index.ts'
import { Config, plainOptions, resolveAdapterOptions } from '@deepseek-ai/dsh-llm-deepseek-api-key'
import type { ResolvedDeepSeekOptions as DeepSeekConnectionOptions } from '@deepseek-ai/dsh-llm-deepseek-api-key'
import { assemble, chunks, MODEL, options, server } from './helpers.ts'

const close: (() => Promise<void>)[] = []
afterEach(async () => {
  while (close.length) await close.pop()!()
})
async function endpoint(...args: Parameters<typeof server>) {
  const instance = await server(...args)
  close.push(() => instance.close())
  return instance
}
function adapter(connection: () => DeepSeekConnectionOptions, prepareHttp?: PrepareLlmHttp) {
  return new DeepSeekAdapter({
    options: connection,
    resolveAuth: snapshot => Promise.resolve({ headers: { 'x-api-key': `key-for-${snapshot.apiKeyEnv}` } }),
    resolveUserId: () => '00000000-0000-4000-8000-000000000001' as AnonymousUserId,
    prepareExtensions: () => Promise.resolve({ fields: {}, accept: () => Promise.resolve() }),
    ...prepareHttp === undefined ? {} : { prepareHttp },
  })
}

it('admits the finalized Messages payload and forwards identical bytes', async () => {
  const http = await endpoint()
  let forwarded = 0
  const llm = adapter(() => resolveAdapterOptions({ baseURL: http.url, streamIdleTimeoutMs: 500 }), async (_options, payload, signal) => {
    expect(signal.aborted).toBe(false)
    expect(http.requests).toHaveLength(0)
    expect((JSON.parse(payload) as { model: string }).model).toBe(MODEL)
    await new Promise(resolve => setTimeout(resolve, 600))
    expect(signal.aborted).toBe(false)
    return async (input, init) => {
      expect(init?.body).toBe(payload)
      forwarded++
      return fetch(input, init)
    }
  })
  expect((await assemble(llm.stream(options()))).message.content).toEqual([{ type: 'text', text: 'Hello 世界' }])
  expect(forwarded).toBe(1)
  expect(llm.providerRetryPolicy('deepseek-official')).toMatchObject({ mode: 'normal', maxRetries: 0 })
})

it.each([false, true])('uses Messages, schema=%s', async (schema) => {
  const http = await endpoint()
  const raw = { baseURL: http.url }
  const connection = resolveAdapterOptions(schema ? plainOptions(Config(raw)) : raw)
  const response = await assemble(adapter(() => connection).stream(options()))

  expect(response.message.content).toEqual([{ type: 'text', text: 'Hello 世界' }])
  expect(http.requests).toHaveLength(1)
  expect(http.requests[0]).toMatchObject({
    path: '/anthropic/v1/messages',
    headers: { 'x-api-key': 'key-for-DEEPSEEK_API_KEY', 'anthropic-version': '2023-06-01' },
    body: { model: MODEL, messages: [{ role: 'user', content: [{ type: 'text', text: 'hello' }] }] },
  })
})

it('keeps the prepared credential reference and endpoint after configuration changes', async () => {
  const first = await endpoint(), second = await endpoint()
  let connection = resolveAdapterOptions({ baseURL: first.url, apiKeyEnv: 'FIRST_KEY', maxTokens: 12 })
  const llm = adapter(() => connection)
  const prepared = await llm.prepareCall('deepseek-official', MODEL)
  connection = resolveAdapterOptions({ baseURL: second.url, apiKeyEnv: 'SECOND_KEY', maxTokens: 24 })
  await chunks(prepared.stream(options()))
  await chunks(prepared.stream(options()))
  expect(prepared.model.defaultMaxTokens).toBe(12)
  expect((await llm.resolveModel('deepseek-official', MODEL)).defaultMaxTokens).toBe(24)
  await chunks(llm.stream(options()))
  expect(first.requests).toHaveLength(2)
  for (const request of first.requests) expect(request).toMatchObject({
    path: '/anthropic/v1/messages', headers: { 'x-api-key': 'key-for-FIRST_KEY' }, body: { max_tokens: 12 },
  })
  expect(second.requests).toHaveLength(1)
  expect(second.requests[0]).toMatchObject({ path: '/anthropic/v1/messages', headers: { 'x-api-key': 'key-for-SECOND_KEY' } })
})
