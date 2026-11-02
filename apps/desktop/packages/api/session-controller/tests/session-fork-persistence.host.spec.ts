/** Forked file and image access through production Agent and disk persistence providers. */
import { Context } from '@deepseek-ai/cordis'
import * as JsonlSessionPersistence from '@deepseek-ai/dsh-session-persistence-jsonl'
import LocalAttachmentStore from '@deepseek-ai/dsh-attachment-local'
import { SessionId } from '@deepseek-ai/dsh-session'
import { brandString } from '@deepseek-ai/dsh-brand'
import type { PublishedResultId } from '@deepseek-ai/dsh-tool-present/types'
import { ToolCallId, createUserMessage } from '@deepseek-ai/dsh-llm'
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '@deepseek-ai/dsh-agent-loop-testkit'
import { mkdtemp, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createHash } from 'node:crypto'
import { expect, it } from 'vitest'
import { MockAdapter, textResponse } from '../../../core/agent-loop/tests/mock-adapter.ts'
import { createSessionTestController } from './test-remote.ts'

it('retains only long-prefix attachments through durable fork, restart and ranged reads without starting the child', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-fork-files-'))
  const contexts: Context[] = []
  const mount = async (): Promise<Context> => {
    const ctx = new Context(); contexts.push(ctx)
    await mountAgentLoopTestDependencies(ctx)
    await ctx.plugin(JsonlSessionPersistence, { root: join(home, 'sessions'), compression: 'none' })
    await ctx.plugin(LocalAttachmentStore, { dshHome: home })
    ctx.provide('workspaceRegistry', { list: () => [], archivedSessionIds: [] } as never)
    return ctx
  }
  try {
    const ctx = await mount()
    const harness = await mountAgentLoopTestHarness(ctx)
    const adapter = new MockAdapter(Array.from({ length: 262 }, (_, index) => textResponse(`Reply ${String(index)}`)))
    ctx.llm.registerAdapter(['mock'], adapter)
    const data = Buffer.alloc(384000 * 2 + 13, 0x61)
    const file = await ctx.attachments.saveFile({ data, name: 'before.txt' })
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAACXBIWXMAAAPoAAAD6AG1e1JrAAAADElEQVQImWNgZGIGAAAOAAeCcsnOAAAAAElFTkSuQmCC', 'base64')
    const image = await ctx.attachments.saveImage({ data: png, mediaType: 'image/png' })
    const imageBytes = (await ctx.attachments.readImage(image)).data
    const later = await ctx.attachments.saveFile({ data: Buffer.from('only in the later turn'), name: 'later.txt' })
    const source = await harness.create(SessionId('source'), { provider: 'mock', model: 'mock' })
    for (let index = 0; index < 260; index++) {
      source.followup(createUserMessage({
        source: { kind: 'user' }, content: [{ type: 'text', text: `Earlier note ${String(index)}` }],
      }))
      await source.whenIdle()
    }
    source.followup(createUserMessage({ source: { kind: 'user' }, content: [
      { type: 'text', text: 'First message' }, { type: 'file', attachment: file }, { type: 'image', attachment: image },
    ] }))
    await source.whenIdle()
    const resultPreview=await ctx.attachments.saveFile({ data:Buffer.from('fixed nested preview'),name:'preview.png' })
    source.session.append('deliverables/presented',{ turn:261,callId:ToolCallId('published'),files:[{ path:'/fixed/before.txt',result:{ schema:1,id:brandString<PublishedResultId>('fixed-result'),version:1,turn:261,original:{ type:'file',attachment:file },variants:[{ role:'thumbnail',key:'thumbnail',content:{ type:'file',attachment:resultPreview } }],previewState:'ready',warnings:[] } }] })
    const boundary = source.session.seq - 1
    source.followup(createUserMessage({ source: { kind: 'user' }, content: [
      { type: 'text', text: 'Later message' }, { type: 'file', attachment: later },
    ] }))
    await source.whenIdle()
    const original = source.session.snapshotEvents()
    const controller = createSessionTestController(ctx, { cwd: home, defaultModelSelection: () => ({ provider: 'mock', model: 'mock' }) })
    const input = { sessionId: source.id, childSessionId: SessionId('mobile-fork'), atSeq: boundary }
    const requestsBeforeFork = adapter.requests.length
    expect(requestsBeforeFork).toBe(262)
    expect(boundary).toBeGreaterThan(260)
    const result = await controller.fork(input)
    await controller.replicaFlush()
    const nested=await controller.replicaAttachmentChunk({
      sessionId:result.sessionId,attachmentId:resultPreview.attachmentId,offset:0,
    },new AbortController().signal)
    expect(Buffer.from(nested.data,'base64').toString()).toBe('fixed nested preview')
    {
      await using persisted = await ctx.sessionPersistence.open(result.sessionId, 'read')
      const inherited = []
      for (let offset = 0; offset <= boundary;) {
        const page = await persisted.read(offset, Math.min(100, boundary + 1 - offset))
        expect(page.events.length).toBeGreaterThan(0)
        inherited.push(...page.events); offset += page.events.length
      }
      expect(inherited).toEqual(original.slice(0, boundary + 1))
    }
    expect(adapter.requests).toHaveLength(requestsBeforeFork)
    expect(ctx.agents.get(result.sessionId)?.status).toBe('idle')
    expect(source.session.snapshotEvents()).toEqual(original)
    const signal = new AbortController().signal
    const pieces: Buffer[] = []
    for (let offset = 0; offset < file.bytes;) {
      const chunk = await controller.replicaAttachmentChunk({
        sessionId: result.sessionId, attachmentId: file.attachmentId, offset,
      }, signal)
      pieces.push(Buffer.from(chunk.data, 'base64')); expect(chunk.nextOffset - offset).toBeLessThanOrEqual(384000); offset = chunk.nextOffset
    }
    expect(Buffer.concat(pieces)).toEqual(data)
    expect(createHash('sha256').update(Buffer.concat(pieces)).digest('hex')).toBe(file.attachmentId.slice(7))
    await expect(controller.replicaAttachmentChunk({ sessionId: result.sessionId, attachmentId: later.attachmentId, offset: 0 }, signal))
      .rejects.toMatchObject({ code: 'gateway/bad-request' })
    await ctx.fiber.dispose(); contexts.splice(contexts.indexOf(ctx), 1)

    const cold = await mount()
    const reader = createSessionTestController(cold, { cwd: home, defaultModelSelection: () => ({ provider: 'mock', model: 'mock' }) })
    expect(await reader.fork(input)).toEqual(result)
    expect(cold.agents.roots()).toHaveLength(0)
    const chunk = await reader.replicaAttachmentChunk({ sessionId: result.sessionId, attachmentId: image.attachmentId, offset: 0 }, signal)
    expect(Buffer.from(chunk.data, 'base64')).toEqual(Buffer.from(imageBytes))
    expect(chunk.nextOffset).toBe(image.bytes)
    await expect(reader.replicaAttachmentChunk({ sessionId: result.sessionId, attachmentId: later.attachmentId, offset: 0 }, signal))
      .rejects.toMatchObject({ code: 'gateway/bad-request' })
    expect(cold.agents.roots()).toHaveLength(0)
  } finally {
    await Promise.all(contexts.map(ctx => ctx.fiber.dispose()))
    await rm(home, { recursive: true, force: true })
  }
})
