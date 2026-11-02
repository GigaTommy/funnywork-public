import { writeFile } from 'node:fs/promises'
import { expect, it, vi } from 'vitest'
import { zh } from '../src/locale.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'
const state = vi.hoisted(() => ({
  html: '', listeners: new Map<string, (...args: unknown[]) => void>(), options: {} as Record<string, unknown>,
}))
vi.mock('electron', () => ({ BrowserWindow: class {
  constructor(options: Record<string, unknown>) { state.options = options }
  webContents = {
    setWindowOpenHandler: vi.fn(),
    on: (event: string, fn: (...args: unknown[]) => void) => { state.listeners.set(event, fn) },
  }
  isDestroyed() { return false }
  async loadURL(url: string) { state.html = decodeURIComponent(url.split(',').slice(1).join(',')) }
  once() {}
  executeJavaScript() { return Promise.resolve(true) }
} }))
import { openHistoryWindow } from '../src/funnyworker-history-window.ts'

it('keeps credentials out of the renderer and escapes both message forms', async () => {
  const syncRequest = vi.fn(async (path: string): Promise<unknown> => path === '/sessions' ? [{ id: 'cloud', title: '帮我整理今天的计划', device_name: '我的 Mac', updated_at: 1 }] : {
    events: [{ type: 'user/message', seq: 0, data: { content: [{ type: 'text', text: '<script>danger()</script>' }] } }, { type: 'assistant/message', seq: 1, data: { message: { content: [{ type: 'text', text: '可以，我们先列出最重要的三件事。' }] } } }], nextSeq: 2, durableNextSeq: 2,
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  await openHistoryWindow(account, zh, () => zh.historySaved)
  expect(state.options.webPreferences).toEqual({ nodeIntegration: false, contextIsolation: true, sandbox: true })
  expect(state.html).toContain('帮我整理今天的计划')
  expect(state.html).toContain("default-src 'none'")
  const preventDefault = vi.fn()
  state.listeners.get('will-navigate')?.({ preventDefault }, 'https://evil.invalid/')
  expect(syncRequest).toHaveBeenCalledTimes(1)
  state.listeners.get('will-navigate')?.({ preventDefault }, 'https://history.funnywork.invalid/cloud')
  await vi.waitFor(() => { expect(state.html).toContain('可以，我们先列出最重要的三件事。') })
  expect(state.html).not.toContain('<script>')
  expect(state.html).toContain('&lt;script&gt;')
  expect(state.html).not.toContain('Bearer')
  if (process.env.FUNNYWORK_HISTORY_PREVIEW_PATH !== undefined) await writeFile(process.env.FUNNYWORK_HISTORY_PREVIEW_PATH, state.html)
})

it('uses a distinct identity for each operation and preserves native decision correlation', async () => {
  const syncRequest = vi.fn(async (path: string): Promise<unknown> => {
    if (path === '/sessions') return [{ id: 'cloud', title: '会话', updated_at: 1 }]
    if (path.endsWith('/state')) return { online: true, epoch: 'epoch-1', state: { running: true, controllable: true, turnStart: 4, pending: [{ eventId: 'decision-1', event: 'approval/request', request: { toolName: 'read', reason: '查看文件' } }, { eventId: 'question-1', event: 'user-questions/request', request: { questions: [{ id: 'topic', question: '选择内容', options: [{ label: '<记录>', description: '保存记录' }] }] } }] } }
    if (path.endsWith('/commands')) return []
    if (path.includes('/attachments')) return { items: [] }
    return { events: [], nextSeq: 0, durableNextSeq: 0 }
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  await openHistoryWindow(account, zh, () => zh.historySaved)
  state.listeners.get('will-navigate')?.({ preventDefault: vi.fn() }, 'https://history.funnywork.invalid/cloud')
  await vi.waitFor(() => { expect(state.html).toContain('decision-1') })
  const ids = [...state.html.matchAll(/name="requestId" value="([^"]+)"/g)].map(match => match[1])
  expect(ids).toHaveLength(4)
  expect(new Set(ids).size).toBe(ids.length)
  expect(state.html).toContain('name="expectedTurn" value="4"')
  expect(state.html).toContain('name="selected_topic" value="&lt;记录&gt;"')
  state.listeners.get('will-navigate')?.({ preventDefault: vi.fn() }, `https://history.funnywork.invalid/command/cloud?kind=decision&epoch=epoch-1&requestId=${ids[3]}&eventId=question-1&answer_topic=&selected_topic=%3C记录%3E`)
  await vi.waitFor(() => { expect(syncRequest).toHaveBeenCalledWith('/sessions/cloud/commands', { id: ids[3], epoch: 'epoch-1', kind: 'decision', payload: { eventId: 'question-1', outcome: null, answer: { answers: [{ id: 'topic', selected: ['<记录>'], custom: '' }] } } }) })
  if (process.env.FUNNYWORK_HISTORY_PREVIEW_PATH !== undefined) await writeFile(process.env.FUNNYWORK_HISTORY_PREVIEW_PATH, state.html)
})
