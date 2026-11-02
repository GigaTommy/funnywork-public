import { vi as regionalEnvironment, beforeEach as configureRegionalEntry, afterEach as restoreRegionalEntry } from 'vitest'
import { afterEach, expect, it, vi } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import Loader, { type ModuleLoaderV2 } from '@deepseek-ai/cordis-plugin-loader'
import Include from '@deepseek-ai/cordis-plugin-include'
import { pathToFileURL } from 'node:url'
import { profileComposition } from '../../../settings/settings/tests/profile-composition.ts'
import LlmRuntime, { createAssistantMessage, createToolResultMessage, createUserMessage, ToolCallId, ReasoningEffortId } from '@deepseek-ai/dsh-llm'
import LocalAttachments from '@deepseek-ai/dsh-attachment-local'
import { brandString } from '@deepseek-ai/dsh-brand'
import type { Branded } from '@deepseek-ai/dsh-brand'
import * as Pi from '../src/index.ts'
import { assemble } from './assemble.ts'
import { textEvents } from './mock-server.ts'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'

const cleanup: Array<() => Promise<void>> = []
afterEach(async () => {
  for (const close of cleanup.splice(0).reverse()) await close()
  vi.unstubAllGlobals()
})
async function fixture(protocol: 'CHAT_COMPLETIONS' | 'MESSAGES' = 'CHAT_COMPLETIONS', waiting = false, catalogFailures = 0, throughLoader = false, grouped = false, mainlandOnly = false, reasoning = false, wireModel = 'private-model', maxRequestImageBytes?: number) {
  const initiallyAvailable = catalogFailures < 3
  const home = await mkdtemp(join(tmpdir(), 'fw-enterprise-runtime-'))
  cleanup.push(() => rm(home, { recursive: true, force: true }))
  const token = 'mwi.' + 'a'.repeat(24),
    calls: Array<{ path: string; body: Record<string, unknown>; headers: import('node:http').IncomingHttpHeaders }> =
      [],
    tickets = new Map<string, { id: string; state: string; requestDigest?: string }>()
  let entries = [
      {
        ...(grouped ? { provider_id: 'enterprise-openai', provider_name: 'OpenAI' } : {}),
        id: 'model-1',
        name: '企业模型',
        model: wireModel,
        protocol,
        context_tokens: 16384,
        max_output_tokens: 128,
        capabilities: { tools: true, streamUsage: true, images: false, reasoning },
      },
    ],
    readCount = 0
  const server = createServer((request, response) => {
    void (async () => {
      const parts: Buffer[] = []
      for await (const chunk of request as AsyncIterable<Buffer>) parts.push(chunk)
      const raw = Buffer.concat(parts).toString(),
        body: Record<string, unknown> = raw ? JSON.parse(raw) as Record<string, unknown> : {},
        path = request.url ?? ''
      calls.push({ path, body, headers: request.headers })
      expect(request.headers.authorization).toBe('Bearer ' + token)
      response.setHeader('Content-Type', 'application/json')
      if (path.endsWith('/catalog')) {
        if (catalogFailures-- > 0) {
          response.statusCode = 503
          response.end('{"code":"TEMPORARILY_UNAVAILABLE"}')
          return
        }
        response.end(JSON.stringify({ items: entries }))
        return
      }
      if (path.endsWith('/tickets')) {
        const id = 'ticket-' + String(tickets.size),
          ticket = { id, state: waiting ? 'WAITING' : 'GRANTED', requestDigest: String(body.requestDigest) }
        tickets.set(id, ticket)
        response.end(JSON.stringify(ticket))
        return
      }
      const id = path.split('/')[5]!,
        ticket = tickets.get(id)!
      if (path.endsWith('/cancel')) {
        ticket.state = 'CANCELLED'
        response.end('{"updated":true}')
        return
      }
      if (path.endsWith('/execute')) {
        expect(createHash('sha256').update(raw).digest('hex')).toBe(ticket.requestDigest)
        expect(request.headers['x-api-key']).toBeUndefined()
        ticket.state = 'SUCCEEDED'
        response.setHeader('Content-Type', 'text/event-stream')
        if (protocol === 'CHAT_COMPLETIONS') response.end(textEvents.map(event => 'data: ' + event + '\n\n').join(''))
        else
          response.end(
            [
              {
                type: 'message_start',
                message: { id: 'message-1', model: 'private-model', usage: { input_tokens: 3, output_tokens: 0 } },
              },
              { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } },
              { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'hello' } },
              { type: 'content_block_stop', index: 0 },
              { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 1 } },
              { type: 'message_stop' },
            ]
              .map(event => 'event: ' + event.type + '\ndata: ' + JSON.stringify(event) + '\n\n')
              .join(''),
          )
        return
      }
      readCount++
      response.end(JSON.stringify(ticket))
    })().catch((error: unknown) => response.destroy(error instanceof Error ? error : new Error(String(error))))
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  cleanup.push(async () => {
    const done = new Promise<void>((resolve, reject) => server.close((error) => { if (error) reject(error); else resolve() }))
    server.closeAllConnections()
    await done
  })
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('No fixture port')
  const localOrigin = 'http://127.0.0.1:' + String(address.port)
  const origin = mainlandOnly ? 'https://funnywork.funnypixelsapp.com' : localOrigin
  if (mainlandOnly) {
    const originalFetch = globalThis.fetch
    vi.stubGlobal('fetch', async (input: string | URL | Request, init?: RequestInit) => {
      const target = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
      if (target.origin === origin) throw new TypeError('Overseas entry unreachable')
      if (target.origin !== 'https://regional.example.invalid') throw new Error('Unexpected entry')
      if (new Headers(init?.headers).get('User-Agent') === 'FunnyWork-entry-check/1.0') {
        expect(new Headers(init?.headers).has('Authorization')).toBe(false)
        return new Response('{}', { status: 401 })
      }
      return originalFetch(localOrigin + target.pathname + target.search, init)
    })
  }
  const credentialFile = join(home, 'credential.json')
  await writeFile(
    credentialFile,
    JSON.stringify({ origin, token, device: 'desktop-1', expires: Date.now() + 600000 }),
    { mode: 0o600 },
  )
  const ctx = new Context()
  cleanup.push(() => ctx.fiber.dispose())
  const options = {
    enterprise: {
      origin,
      credentialFile,
      stateDirectory: join(home, 'calls'),
      refreshMs: 1000,
      pollMs: 5,
      maxPollMs: 20,
      jitterRatio: 0,
      streamIdleTimeoutMs: 5000,
      ...(maxRequestImageBytes === undefined ? {} : { maxRequestImageBytes }),
    },
    providers: { deepseek: { apiKeyEnv: 'UNUSED_PUBLIC_KEY' } },
  }
  if (throughLoader) {
    ctx.baseUrl = pathToFileURL(home).href + '/'
    await ctx.plugin(Loader)
    ctx.loader.builtins.include = Include
    const modules = new Map<string, unknown>([['test-llm', LlmRuntime], ['test-pi', Pi]])
    const internal: ModuleLoaderV2 = {
      version: 'v2', loadCache: new Map(),
      import: (specifier) => {
        if (!modules.has(specifier)) throw new Error('Unexpected fixture module')
        return Promise.resolve(modules.get(specifier))
      },
      register(): never { throw new Error('Unexpected fixture registration') },
      getOrCreateModuleJob(): never { throw new Error('Unexpected fixture module job') },
      resolveSync(): never { throw new Error('Unexpected fixture resolution') },
      load(): never { throw new Error('Unexpected fixture load') },
    }
    ctx.loader.internal = internal
    const path = join(home, 'cordis.yml')
    await writeFile(path, JSON.stringify([{ id: 'llm', name: 'test-llm' }, { id: 'llm-pi-ai', name: 'test-pi', config: options }]))
    await profileComposition(ctx, home, path)
  } else {
    await ctx.plugin(LlmRuntime)
    await ctx.plugin(Pi, options)
  }
  expect(ctx.llm.listProviders().map(provider => provider.id)).toEqual(initiallyAvailable ? [grouped ? 'enterprise-openai' : 'enterprise-model-1'] : [])
  return {
    home,
    ctx,
    calls,
    tickets,
    reads: () => readCount,
    entries: () => entries,
    setEntries(value: typeof entries) {
      entries = value
    },
    setWaiting(value: boolean) {
      waiting = value
    },
  }
}

it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)('preserves %s vision selection and screenshot content through the Loader', async (protocol) => {
  const f = await fixture(protocol, false, 0, true)
  await f.ctx.plugin(LocalAttachments, { dshHome: f.home })
  const attachment = await f.ctx.attachments.saveImage({ data: await readFile(new URL('../../llm-deepseek/tests/fixtures/red.png', import.meta.url)), mediaType: 'image/png' })
  f.setEntries(f.entries().map(row => ({ ...row, model: 'deepseek-flash', capabilities: { ...row.capabilities, images: true } })))
  await vi.waitFor(async () => {
    expect((await f.ctx.llm.resolveModelInfo('enterprise-model-1', 'deepseek-flash')).inputModalities).toEqual(['text', 'image'])
  }, { timeout: 3000 })
  for (const kind of ['upload', 'computer_screenshot', 'browser_screenshot']) {
    const callId = ToolCallId('capture-' + kind)
    const messages = kind === 'upload'
      ? [createUserMessage({ source: { kind: 'user' }, content: [{ type: 'image', attachment }] })]
      : [createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Inspect the screen' }] }),
        createAssistantMessage({ source: { provider: 'enterprise-model-1', model: 'deepseek-flash' }, content: [{ type: 'tool-call', id: callId, name: kind, arguments: '{}' }] }),
        createToolResultMessage({ callId, isError: false, content: [{ type: 'text', text: 'Accessible button: Reports' }, { type: 'image', attachment }] })]
    expect((await assemble(f.ctx, { provider: 'enterprise-model-1', model: 'deepseek-flash', messages })).finish.kind).toBe('stop')
    const executed = f.calls.filter(call => call.path.endsWith('/execute')).at(-1)!
    const wire = JSON.stringify(executed.body)
    expect(wire).toContain(protocol === 'MESSAGES' ? '"type":"base64"' : 'data:image/png;base64,')
    expect(wire).not.toContain('image omitted')
    expect(executed.body.model).toBe('deepseek-flash')
  }
  expect(f.calls.filter(call => call.path.endsWith('/execute'))).toHaveLength(3)
  expect(f.calls.some(call => call.path.includes('/files'))).toBe(false)
})

it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)('keeps document screenshots above the former 512 KiB cap under the default %s enterprise policy', async (protocol) => {
  const f = await fixture(protocol, false, 0, true)
  await f.ctx.plugin(LocalAttachments, { dshHome: f.home })
  const attachment = await f.ctx.attachments.saveImage({ data: await readFile(new URL('./fixtures/qr-code.png', import.meta.url)), mediaType: 'image/png' })
  const projected = await f.ctx.attachments.readImageRequest(attachment, {
    width: attachment.width, height: attachment.height, maxBytes: 2097152,
  })
  const count = Math.ceil(600000 / (4 * Math.ceil(projected.bytes / 3)))
  f.setEntries(f.entries().map(row => ({ ...row, capabilities: { ...row.capabilities, images: true } })))
  await vi.waitFor(async () => { expect((await f.ctx.llm.resolveModelInfo('enterprise-model-1', 'private-model')).inputModalities).toContain('image') }, { timeout: 3000 })
  const result = await assemble(f.ctx, { provider: 'enterprise-model-1', model: 'private-model', messages: [createUserMessage({ source: { kind: 'user' }, content: Array.from({ length: count }, () => ({ type: 'image' as const, attachment })) })] })
  expect(result.finish.kind).toBe('stop')
  const execution = f.calls.find(call => call.path.endsWith('/execute'))!
  const wire = JSON.stringify(execution.body)
  expect(Buffer.byteLength(wire)).toBeGreaterThan(524288)
  expect(wire).not.toContain('image omitted')
  expect(wire.match(protocol === 'MESSAGES' ? /"type":"base64"/gu : /data:image\/(?:png|jpeg);base64,/gu)).toHaveLength(count)
  expect(f.calls.filter(call => call.path.endsWith('/execute'))).toHaveLength(1)
})

it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)('retains recent %s screenshots when only one old image exceeds the enterprise budget', async (protocol) => {
  const f = await fixture(protocol, false, 0, true, false, false, false, 'private-model', 1024)
  await f.ctx.plugin(LocalAttachments, { dshHome: f.home })
  const attachment = await f.ctx.attachments.saveImage({ data: await readFile(new URL('../../llm-deepseek/tests/fixtures/red.png', import.meta.url)), mediaType: 'image/png' })
  const version = await f.ctx.attachments.readImageRequest(attachment, { width: 1, height: 1, maxBytes: 2097152 })
  const encoded = 4 * Math.ceil(version.bytes / 3)
  expect(encoded).toBeLessThanOrEqual(512)
  const count = Math.floor(1024 / encoded) + 1
  f.setEntries(f.entries().map(row => ({ ...row, capabilities: { ...row.capabilities, images: true } })))
  await vi.waitFor(async () => {
    expect((await f.ctx.llm.resolveModelInfo('enterprise-model-1', 'private-model')).inputModalities).toContain('image')
  }, { timeout: 3000 })
  const images = Array.from({ length: count }, () => ({ type: 'image' as const, attachment }))
  const message = createUserMessage({ source: { kind: 'user' }, content: images })
  const rejected = await assemble(f.ctx, { provider: 'enterprise-model-1', model: 'private-model', messages: [message] })
  expect(rejected.finish).toMatchObject({ kind: 'error', failure: { code: 'IMAGE_OFFLOAD_REQUIRED', offloadImages: 1 } })
  expect(f.calls.some(call => call.path.endsWith('/tickets'))).toBe(false)
  const retained = { ...message, content: images.map((image, index) => index === 0 ? { ...image, offloaded: true as const } : image) }
  expect((await assemble(f.ctx, { provider: 'enterprise-model-1', model: 'private-model', messages: [retained] })).finish.kind).toBe('stop')
  const wire = JSON.stringify(f.calls.find(call => call.path.endsWith('/execute'))!.body)
  expect(wire).toContain('image omitted to fit request image limits')
  expect(wire.match(protocol === 'MESSAGES' ? /"type":"base64"/gu : /data:image\/png;base64,|data:image\/jpeg;base64,/gu)).toHaveLength(count - 1)
})

it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)('retains %s browser/desktop text observations for a text-only route', async (protocol) => {
  const f = await fixture(protocol, false, 0, true)
  await f.ctx.plugin(LocalAttachments, { dshHome: f.home })
  const attachment = await f.ctx.attachments.saveImage({ data: await readFile(new URL('../../llm-deepseek/tests/fixtures/red.png', import.meta.url)), mediaType: 'image/png' })
  const callId = ToolCallId('text-observation')
  const messages = [createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Inspect the page' }] }),
    createAssistantMessage({ source: { provider: 'enterprise-model-1', model: 'private-model' }, content: [{ type: 'tool-call', id: callId, name: 'browser_snapshot', arguments: '{}' }] }),
    createToolResultMessage({ callId, isError: false, content: [{ type: 'text', text: 'Accessible button: Reports' }, { type: 'image', attachment }] })]
  expect((await assemble(f.ctx, { provider: 'enterprise-model-1', model: 'private-model', messages })).finish.kind).toBe('stop')
  const wire = JSON.stringify(f.calls.find(call => call.path.endsWith('/execute'))!.body)
  expect(wire).toContain('Accessible button: Reports')
  expect(wire).toContain('image omitted because this model accepts text only')
  expect(wire).not.toContain('base64')
})

it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)(
  'routes %s through admission and preserves local usage',
  async (protocol) => {
    const f = await fixture(protocol),
      sessionId = brandString<Branded<'SessionId'>>('session-1'),
      message = createUserMessage({ content: [{ type: 'text', text: 'hello' }], source: { kind: 'user' } })
    expect(f.ctx.llm.listProviders().map(provider => provider.id)).toEqual(['enterprise-model-1'])
    const result = await assemble(f.ctx, {
      provider: 'enterprise-model-1',
      model: 'private-model',
      sessionId,
      messages: [message],
    })
    expect(result.message.content).toEqual([{ type: 'text', text: 'hello' }])
    expect(result.finish).toEqual({ kind: 'stop' })
    expect(result.usage).toMatchObject({ inputTokens: 3, outputTokens: 1, totalTokens: 4 })
    const admission = f.calls.find(call => call.path.endsWith('/tickets'))!
    expect(admission.body).toMatchObject({
      sessionId: 'session-1',
      taskId: message.id,
      deviceId: 'desktop-1',
      maxOutputTokens: 128,
    })
    const execute = f.calls.find(call => call.path.endsWith('/execute'))!
    if (protocol === 'CHAT_COMPLETIONS') expect(execute.body).toHaveProperty('stream_options.include_usage', true)
    else expect(execute.headers['anthropic-version']).toBe('2023-06-01')
  },
)
it('queues and cancels without sending an inference request', async () => {
  const f = await fixture('CHAT_COMPLETIONS', true),
    controller = new AbortController(),
    counts: number[] = []
  f.ctx.on('llm/admission-updated', (status) => {
    counts.push(status.waiting)
  })
  const pending = assemble(f.ctx, {
    provider: 'enterprise-model-1',
    model: 'private-model',
    sessionId: brandString<Branded<'SessionId'>>('session-1'),
    messages: [],
    signal: controller.signal,
  })
  await vi.waitFor(() => {
    expect(counts).toContain(1)
  })
  controller.abort(new Error('Stopped by user'))
  const result = await pending
  expect(result.finish.kind).toBe('aborted')
  await vi.waitFor(() => {
    expect([...f.tickets.values()][0]?.state).toBe('CANCELLED')
  })
  expect(f.calls.some(call => call.path.endsWith('/execute'))).toBe(false)
  expect(counts.at(-1)).toBe(0)
})
it('withdraws disabled catalog routes without restarting the Host', async () => {
  const f = await fixture()
  f.setEntries([])
  await vi.waitFor(
    () => {
      expect(f.ctx.llm.listProviders()).toEqual([])
    },
    { timeout: 3000 },
  )
})
it('gates auxiliary calls using the same enterprise entry', async () => {
  const f = await fixture()
  for (const purpose of ['compaction', 'session-title'] as const) {
    const result = await assemble(f.ctx, {
      provider: 'enterprise-model-1',
      model: 'private-model',
      purpose,
      messages: [],
    })
    expect(result.finish.kind).toBe('stop')
  }
  expect(f.calls.filter(call => call.path.endsWith('/tickets')).map(call => call.body.purpose)).toEqual([
    'compaction',
    'session-title',
  ])
})

it('uses explicit DeepSeek thinking controls behind an enterprise gateway', async () => {
  const f = await fixture('CHAT_COMPLETIONS')
  const first = f.entries()[0]!
  f.setEntries([{ ...first, model: 'deepseek-flash', capabilities: { ...first.capabilities, reasoning: true } }])
  await vi.waitFor(async () => {
    expect((await f.ctx.llm.listModels('enterprise-model-1'))[0]?.id).toBe('deepseek-flash')
  }, { timeout: 3000 })
  for (const purpose of [undefined, 'session-title', 'compaction'] as const) {
    const result = await assemble(f.ctx, {
      provider: 'enterprise-model-1', model: 'deepseek-flash', messages: [],
      reasoningEffort: ReasoningEffortId('high'), ...(purpose === undefined ? {} : { purpose }), maxTokens: 64,
    })
    expect(result.finish.kind).toBe('stop')
  }
  const executions = f.calls.filter(call => call.path.endsWith('/execute'))
  expect(executions.map(call => call.body.thinking)).toEqual([
    { type: 'enabled' }, { type: 'disabled' }, { type: 'disabled' },
  ])
  expect(executions[0]?.body.reasoning_effort).toBe('high')
  expect(executions.slice(1).every(call => !('reasoning_effort' in call.body))).toBe(true)
  expect(executions.every(call => call.body.max_tokens === 64)).toBe(true)
  expect(f.calls.filter(call => call.path.endsWith('/tickets')).every(call => call.body.maxOutputTokens === 64)).toBe(true)
})

it('recovers initial catalog failures before exposing the enterprise adapter without starting inference', async () => {
  const f = await fixture('CHAT_COMPLETIONS', false, 2, true)
  expect(f.calls.filter(call => call.path.endsWith('/catalog'))).toHaveLength(3)
  expect(f.tickets.size).toBe(0)
  expect(f.ctx.llm.listProviders().map(provider => provider.id)).toEqual(['enterprise-model-1'])
  const llm = f.ctx.llm
  await f.ctx.fiber.dispose()
  expect(llm.listProviders()).toEqual([])
})

it('keeps catalog recovery alive after all initial reads fail without admitting model calls', async () => {
  const f = await fixture('CHAT_COMPLETIONS', false, 3)
  expect(f.ctx.llm.listProviders()).toEqual([])
  expect(f.tickets.size).toBe(0)
  await vi.waitFor(() => { expect(f.ctx.llm.listProviders().map(provider => provider.id)).toEqual(['enterprise-model-1']) }, { timeout: 10000 })
  expect(f.tickets.size).toBe(0)
})

it('stops catalog retries and leaves no refresh timer when disposed during the first catalog request', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-enterprise-dispose-'))
  cleanup.push(() => rm(home, { recursive: true, force: true }))
  const origin = 'https://enterprise.internal'
  const credentialFile = join(home, 'credential.json')
  await writeFile(credentialFile, JSON.stringify({ origin, token: 'mwi.' + 'a'.repeat(24), device: 'desktop-1', expires: Date.now() + 600000 }))
  const ctx = new Context()
  cleanup.push(() => ctx.fiber.dispose())
  await ctx.plugin(LlmRuntime)
  const fetchCatalog = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
    await ctx.fiber.dispose()
    throw new Error('Catalog request cancelled during disposal')
  })
  const interval = vi.spyOn(globalThis, 'setInterval')
  try {
    const { applyEnterprise } = await import('../src/enterprise-runtime.ts')
    await applyEnterprise(ctx, {
      origin, credentialFile, stateDirectory: join(home, 'calls'), refreshMs: 1000,
      pollMs: 5, maxPollMs: 20, jitterRatio: 0, requestTimeoutMs: 1000,
      streamIdleTimeoutMs: 5000, maxRequestImageBytes: 524288,
    })
    expect(fetchCatalog).toHaveBeenCalledTimes(1)
    expect(interval).not.toHaveBeenCalled()
  } finally {
    fetchCatalog.mockRestore()
    interval.mockRestore()
  }
})


it.each(['CHAT_COMPLETIONS', 'MESSAGES'] as const)('groups %s models by maker through the Loader while binding each selection to its deployment', async (protocol) => {
  const f = await fixture(protocol, false, 0, true, true)
  const first = f.entries()[0]!
  f.setEntries([
    first,
    { ...first, id: 'model-2', name: 'Second deployment', max_output_tokens: 64 },
    { ...first, id: 'model-3', name: 'DeepSeek', model: 'deepseek', provider_id: 'enterprise-deepseek', provider_name: 'DeepSeek' },
  ])
  await vi.waitFor(() => {
    expect(f.ctx.llm.listProviders()).toEqual([
      { id: 'enterprise-openai', name: 'OpenAI' }, { id: 'enterprise-deepseek', name: 'DeepSeek' },
    ])
  }, { timeout: 3000 })
  const models = await f.ctx.llm.listModels('enterprise-openai')
  expect(models.map(model => ({ id: model.id, name: model.name }))).toEqual([
    { id: 'model-1', name: '企业模型' }, { id: 'model-2', name: 'Second deployment' },
  ])
  expect({ providers: f.ctx.llm.listProviders(), models: models.map(({ id, name }) => ({ id, name })) }).toMatchSnapshot()
  for (const id of ['model-1', 'model-2']) {
    const result = await assemble(f.ctx, { provider: 'enterprise-openai', model: id, messages: [] })
    expect(result.finish.kind).toBe('stop')
  }
  expect(f.calls.filter(call => call.path.endsWith('/tickets')).map(call => call.body.deploymentId)).toEqual(['model-1', 'model-2'])
  expect(f.calls.filter(call => call.path.endsWith('/execute')).map(call => [call.body.model, call.body.max_tokens ?? call.body.max_completion_tokens])).toEqual([
    ['private-model', 128], ['private-model', 64],
  ])
  await expect(f.ctx.llm.prepareCall({ provider: 'enterprise-openai', model: 'model-3' })).rejects.toMatchObject({ code: 'MODEL_UNAVAILABLE' })
  const llm = f.ctx.llm
  await f.ctx.fiber.dispose()
  expect(llm.listProviders()).toEqual([])
})

it('refreshes a grouped deployment configuration and withdraws only the removed model', async () => {
  const f = await fixture('CHAT_COMPLETIONS', false, 0, false, true)
  const first = f.entries()[0]!
  f.setEntries([first, { ...first, id: 'model-2' }])
  await vi.waitFor(async () => {
    expect(await f.ctx.llm.listModels('enterprise-openai')).toHaveLength(2)
  }, { timeout: 3000 })
  f.setEntries([{ ...first, max_output_tokens: 64, context_tokens: 8192 }])
  await vi.waitFor(async () => {
    expect(await f.ctx.llm.listModels('enterprise-openai')).toHaveLength(1)
    expect(await f.ctx.llm.prepareCall({ provider: 'enterprise-openai', model: 'model-1' })).toMatchObject({ context: { contextWindow: 8192 } })
  }, { timeout: 3000 })
  await assemble(f.ctx, { provider: 'enterprise-openai', model: 'model-1', messages: [] })
  expect(f.calls.find(call => call.path.endsWith('/tickets'))?.body.maxOutputTokens).toBe(64)
  await expect(f.ctx.llm.prepareCall({ provider: 'enterprise-openai', model: 'model-2' })).rejects.toMatchObject({ code: 'MODEL_UNAVAILABLE' })
})

it('replays grouped assistant history without changing its stored deployment source', async () => {
  const f = await fixture('CHAT_COMPLETIONS', false, 0, false, true)
  const first = await assemble(f.ctx, { provider: 'enterprise-openai', model: 'model-1', messages: [] })
  const source = JSON.stringify(first.message.source)
  const second = await assemble(f.ctx, { provider: 'enterprise-openai', model: 'model-1', messages: [first.message] })
  expect(second.finish.kind).toBe('stop')
  expect(JSON.stringify(first.message.source)).toBe(source)
  const executions = f.calls.filter(call => call.path.endsWith('/execute'))
  expect(executions[1]?.body.model).toBe('private-model')
  expect(executions[1]?.body.messages).toMatchObject([{ role: 'assistant', content: 'hello' }])
})

it('registers the grouped provider through Guangzhou when the overseas entry is unreachable', async () => {
  const f = await fixture('CHAT_COMPLETIONS', false, 0, true, true, true)
  expect(f.ctx.llm.listProviders().map(provider => provider.id)).toEqual(['enterprise-openai'])
  expect(await f.ctx.llm.listModels('enterprise-openai')).toEqual([expect.objectContaining({ id: 'model-1' })])
  expect(f.calls.some(call => call.path.endsWith('/catalog'))).toBe(true)
  expect(f.calls.some(call => call.path.endsWith('/execute'))).toBe(false)
})

it.each((['MESSAGES', 'CHAT_COMPLETIONS'] as const).flatMap(protocol =>
  ['deepseek-flash', 'deepseek-v4-pro'].map(model => [protocol, model] as const),
))('publishes %s %s efforts and binds each request to its selected effort', async (protocol, model) => {
  const f = await fixture(protocol, false, 0, true, false, false, true, model)
  const info = await f.ctx.llm.resolveModelInfo('enterprise-model-1', model)
  expect(info.reasoning?.efforts.map(effort => effort.id)).toEqual(['off', 'low', 'high', 'max'])
  expect(info.reasoning?.defaultEffort).toBe('high')
  for (const effort of ['off', 'low', 'high', 'max']) {
    await assemble(f.ctx, { provider: 'enterprise-model-1', model, messages: [], reasoningEffort: ReasoningEffortId(effort) })
    const body = f.calls.filter(call => call.path.endsWith('/execute')).at(-1)!.body
    expect(body.thinking).toEqual({ type: effort === 'off' ? 'disabled' : 'enabled' })
    expect(body.reasoning_effort).toBe(protocol === 'CHAT_COMPLETIONS' && effort !== 'off' ? effort : undefined)
    expect(body.output_config).toEqual(protocol === 'MESSAGES' && effort !== 'off' ? { effort } : undefined)
  }
  expect({ model, protocol, reasoning: info.reasoning, requests: f.calls.filter(call => call.path.endsWith('/execute')).map(call => ({ model: call.body.model, thinking: call.body.thinking, effort: call.body.reasoning_effort ?? call.body.output_config })) }).toMatchSnapshot()
})

it.each(['deepseek-flash', 'deepseek-v4-pro'])('explicitly disables %s thinking when the published capability is off', async (model) => {
  const f = await fixture('CHAT_COMPLETIONS', false, 0, true, false, false, false, model)
  await assemble(f.ctx, { provider: 'enterprise-model-1', model, messages: [] })
  const body = f.calls.find(call => call.path.endsWith('/execute'))!.body
  expect(body.thinking).toEqual({ type: 'disabled' })
  expect(body.reasoning_effort).toBeUndefined()
})

configureRegionalEntry(() => { regionalEnvironment.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', 'https://regional.example.invalid') })
restoreRegionalEntry(() => { regionalEnvironment.unstubAllEnvs() })
