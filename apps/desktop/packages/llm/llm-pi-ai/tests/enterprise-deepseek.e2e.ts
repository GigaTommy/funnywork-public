/** Opt-in checks through enterprise admission and the real DeepSeek service. */
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import LlmRuntime, { createUserMessage, createToolResultMessage, ReasoningEffortId } from '@deepseek-ai/dsh-llm'
import * as Pi from '../src/index.ts'
import { assemble } from './assemble.ts'
import { createServer } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createHash } from 'node:crypto'
import type { ClientRequest } from 'node:http'

type Entry = { id: string; name: string; model: string; protocol: 'MESSAGES' | 'CHAT_COMPLETIONS'; provider_id: string }
type Wire = {
  ticket: string
  model: string
  thinking: unknown
  reasoning_effort: unknown
  output_config: unknown
  sha256: string
  status: number
}
const credentialPath = process.env.FUNNYWORK_ENTERPRISE_TEST_CREDENTIAL
const output = process.env.FUNNYWORK_ENTERPRISE_TEST_OUTPUT
const wires: Wire[] = []
const results: object[] = []
const active = new Set<ClientRequest>()
let home: string | undefined
let ctx: Context | undefined
let server: ReturnType<typeof createServer> | undefined
let entries: Entry[] = []

describe.skipIf(!credentialPath)('published enterprise DeepSeek models', () => {
  beforeAll(async () => {
    if (!credentialPath) throw new Error('Scoped enterprise credential path is required')
    const credential = JSON.parse(await readFile(credentialPath, 'utf8')) as { origin: string; token: string; expires: number; device: string }
    const target = new URL(credential.origin)
    if (target.protocol !== 'https:') throw new Error('Live enterprise checks require HTTPS')
    home = await mkdtemp(join(tmpdir(), 'enterprise-deepseek-'))
    server = createServer((request, response) => {
      void (async () => {
        const chunks: Buffer[] = []
        for await (const chunk of request as AsyncIterable<Uint8Array>) chunks.push(Buffer.from(chunk))
        const raw = Buffer.concat(chunks)
        const body = raw.length ? JSON.parse(raw.toString()) as Record<string, unknown> : {}
        const wire = request.url?.endsWith('/execute') ? {
          ticket: request.url.split('/').at(-2) ?? '', model: String(body.model), thinking: body.thinking,
          reasoning_effort: body.reasoning_effort, output_config: body.output_config,
          sha256: createHash('sha256').update(raw).digest('hex'), status: 0,
        } : undefined
        if (wire) wires.push(wire)
        const headers = { ...request.headers, host: target.host, 'user-agent': 'Mozilla/5.0' }
        const upstream = httpsRequest(new URL(request.url ?? '/', target), { method: request.method, headers }, (received) => {
          if (wire) wire.status = received.statusCode ?? 0
          response.writeHead(received.statusCode ?? 502, received.headers)
          received.pipe(response)
        })
        active.add(upstream)
        upstream.once('close', () => active.delete(upstream))
        upstream.once('error', () => { if (!response.headersSent) response.writeHead(502); response.end() })
        upstream.setTimeout(90000, () => upstream.destroy(new Error('Enterprise live request timed out')))
        upstream.end(raw)
      })().catch(() => { if (!response.headersSent) response.writeHead(500); response.end() })
    })
    await new Promise<void>(resolve => server!.listen(0, '127.0.0.1', resolve))
    const address = server.address()
    if (!address || typeof address === 'string') throw new Error('No proxy port')
    const origin = `http://127.0.0.1:${address.port}`
    const response = await fetch(`${origin}/api/account/inference/catalog`, { headers: { Authorization: `Bearer ${credential.token}` } })
    expect(response.status).toBe(200)
    const catalog = await response.json() as { items: Entry[] }
    entries = catalog.items.filter(row => row.model === 'deepseek-flash' || row.model === 'deepseek-v4-pro')
    expect(entries).toHaveLength(4)
    await writeFile(join(home, 'credential.json'), JSON.stringify({ ...credential, origin }), { mode: 0o600 })
    ctx = new Context()
    await ctx.plugin(LlmRuntime)
    await ctx.plugin(Pi, { enterprise: { origin, credentialFile: join(home, 'credential.json'), stateDirectory: join(home, 'calls'), streamIdleTimeoutMs: 90000 } })
  }, 120000)

  afterAll(async () => {
    await ctx?.fiber.dispose()
    for (const request of active) request.destroy()
    if (server) {
      const closed = new Promise<void>((resolve, reject) => {
        server!.close((error) => { if (error) reject(error); else resolve() })
      })
      server.closeAllConnections()
      await closed
    }
    if (output) await writeFile(output, JSON.stringify({ entries, results, wires }, undefined, 2) + '\n')
    if (home) await rm(home, { recursive: true, force: true })
  }, 120000)

  for (const model of ['deepseek-flash', 'deepseek-v4-pro']) {
    for (const protocol of ['MESSAGES', 'CHAT_COMPLETIONS'] as const) {
      it(`${model} ${protocol} displays and sends all four efforts`, async () => {
        const entry = entries.find(row => row.model === model && row.protocol === protocol)!
        if (!ctx || !entry) throw new Error('Published deployment is missing')
        const info = await ctx.llm.resolveModelInfo(entry.provider_id, entry.id)
        expect(info.reasoning?.efforts.map(effort => effort.id)).toEqual(['off', 'low', 'high', 'max'])
        for (const effort of ['off', 'low', 'high', 'max']) {
          const message = createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'What is 17 times 19? Reply only the number.' }] })
          const result = await assemble(ctx, {
            provider: entry.provider_id, model: entry.id, reasoningEffort: ReasoningEffortId(effort),
            messages: [message], maxTokens: 512,
          })
          const wire = wires.at(-1)!
          expect(result.finish.kind).toBe('stop')
          expect(wire.status).toBe(200)
          expect(wire.model).toBe(model)
          expect(wire.thinking).toEqual({ type: effort === 'off' ? 'disabled' : 'enabled' })
          expect(wire.reasoning_effort).toBe(protocol === 'CHAT_COMPLETIONS' && effort !== 'off' ? effort : undefined)
          expect(wire.output_config).toEqual(protocol === 'MESSAGES' && effort !== 'off' ? { effort } : undefined)
          const reasoning = result.message.content.filter(block => block.type === 'reasoning').map(block => block.text).join('')
          const text = result.message.content.filter(block => block.type === 'text').map(block => block.text).join('')
          expect(text).toContain('323')
          expect(reasoning.length > 0).toBe(effort !== 'off')
          results.push({
            model, protocol, effort, ticket: wire.ticket, status: wire.status,
            thinkingChars: reasoning.length, text, usage: result.usage,
          })
        }
      }, 240000)

      it(`${model} ${protocol} preserves thinking across a tool result`, async () => {
        const entry = entries.find(row => row.model === model && row.protocol === protocol)!
        if (!ctx || !entry) throw new Error('Published deployment is missing')
        const user = createUserMessage({ source: { kind: 'user' }, content: [{ type: 'text', text: 'Call multiply with a=17 and b=19. You must use the tool before replying with only the result.' }] })
        const tools = [{ name: 'multiply', description: 'Multiply two integers.', parameters: { type: 'object' as const, properties: { a: { type: 'integer' as const }, b: { type: 'integer' as const } }, required: ['a', 'b'] } }]
        const options = { provider: entry.provider_id, model: entry.id, reasoningEffort: ReasoningEffortId('high'), tools, maxTokens: 1024 }
        const first = await assemble(ctx, { ...options, messages: [user] })
        const call = first.message.content.find(block => block.type === 'tool-call')
        expect(call?.name).toBe('multiply')
        if (!call) throw new Error('DeepSeek did not call the requested tool')
        const result = createToolResultMessage({ callId: call.id, content: [{ type: 'text', text: '323' }], isError: false })
        const second = await assemble(ctx, { ...options, messages: [user, first.message, result] })
        expect(second.finish.kind).toBe('stop')
        expect(wires.at(-1)?.status).toBe(200)
        const text = second.message.content.filter(block => block.type === 'text').map(block => block.text).join('')
        expect(text).toContain('323')
        results.push({ model, protocol, toolReplay: true, ticket: wires.at(-1)?.ticket, text })
      }, 240000)
    }
  }
})
