/** A background result publication updates an already closed reply through the shipped Client stream. */
import { chromium, type Browser } from 'playwright'
import { expect, it, onTestFinished } from 'vitest'
import { SESSION_FORMAT_VERSION, SessionId } from '@deepseek-ai/dsh-session'
import { createAssistantMessage, createUserMessage, ToolCallId } from '@deepseek-ai/dsh-llm'
import { brandString } from '@deepseek-ai/dsh-brand'
import type { PublishedResult } from '@deepseek-ai/dsh-tool-present/types'
import { launchWebScaffold, seedSession, watchConsole } from './scaffold.ts'
import { newEnglishPage } from './support.ts'

it('refreshes completed and failed previews without navigation or another model turn', async () => {
  const scaffold = await launchWebScaffold({})
  const resources: { browser?: Browser } = {}
  onTestFinished(async () => { try { await resources.browser?.close() } finally { await scaffold.close() } })
  const result: PublishedResult = {
    schema: 1, id: brandString<PublishedResult['id']>('preview-result'), version: 1, turn: 1,
    original: { type: 'file', attachment: {
      attachmentId: brandString<PublishedResult['original']['attachment']['attachmentId']>(`sha256:${'a'.repeat(64)}`),
      name: 'report.xlsx', bytes: 1,
    } }, variants: [], previewState: 'pending', warnings: [],
  }
  const callId = ToolCallId('publish-preview')
  const events = [
    { type: 'turn/start', data: { turn: 1, trigger: { kind: 'message', source: { kind: 'user', rpcId: 'seed' } } } },
    { type: 'step/start', data: { turn: 1, step: 1 } },
    { type: 'user/message', data: createUserMessage({ content: [{ type: 'text', text: 'Create a report.' }], source: { kind: 'user' } }), surfaceOp: 'append' },
    { type: 'deliverables/presented', data: { turn: 1, callId, files: [{ path: 'report.xlsx', result }] } },
    { type: 'assistant/message', data: { turn: 1, step: 1, stream: [], message: createAssistantMessage({ content: [{ type: 'text', text: 'Report saved.' }], source: { provider: 'test', model: 'test' } }) }, surfaceOp: 'append' },
    { type: 'step/end', data: { turn: 1, step: 1 } },
    { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } },
  ]
  const log = [
    JSON.stringify({ type: 'session', version: SESSION_FORMAT_VERSION, id: '{{sessionId}}', createdAt: 0, cwd: '{{cwd}}', isSeeded: false, delegationDepth: 0 }),
    ...events.map((event, seq) => JSON.stringify({ ...event, seq, time: seq })),
  ].join('\n')
  const id = await seedSession(scaffold, log, 'preview-refresh', 'minimal')
  resources.browser = await chromium.launch()
  const page = await newEnglishPage(resources.browser)
  const tripwire = watchConsole(page)
  await page.addInitScript((id) => { localStorage.setItem('dsh.sessions.current', JSON.stringify({ sessionId: id })) }, id)
  await page.goto(scaffold.authenticatedUrl, { waitUntil: 'load' })
  const card = page.locator('[data-presented-file]').filter({ hasText: 'report.xlsx' })
  await card.getByText('Fixed version 1 · Preparing preview', { exact: true }).waitFor()
  await expect.poll(() => scaffold.ctx.agents.get(SessionId(id))?.status).toBe('idle')
  const session = scaffold.ctx.agents.get(SessionId(id))!.session
  for (const state of ['ready', 'failed'] as const) {
    session.append('deliverables/presented', { turn: 1, callId, files: [{ path: 'report.xlsx', result: { ...result, previewState: state } }] })
    const text = state === 'ready' ? 'Fixed version 1 · Original saved' : 'Fixed version 1 · Original saved, preview incomplete'
    await card.getByText(text, { exact: true }).waitFor()
    expect(await card.count()).toBe(1)
  }
  expect(tripwire.pageErrors).toEqual([])
})
