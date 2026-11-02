/** Original feedback windows exercise sandboxed rendering and main-process account submissions. */
import { beforeEach, expect, test, vi } from 'vitest'
const harness = vi.hoisted(() => ({ listeners: new Map<string, (...args: unknown[]) => void>(), html: '', open: vi.fn(async () => {}) })) // Electron emits heterogeneous native callback arguments.
vi.mock('electron', () => ({ app: { getVersion: () => '1.0' }, shell: { openExternal: harness.open }, BrowserWindow: class {
  webContents = {
    setWindowOpenHandler: vi.fn(),
    on: (name: string, listener: (...args: unknown[]) => void) => harness.listeners.set(name, listener),
  }
  setMenu() {} isDestroyed() { return false } show() {} focus() {}
  on(name: string, listener: (...args: unknown[]) => void) { harness.listeners.set(name, listener) } // Native event fixture.
  async loadURL(url: string) { harness.html = decodeURIComponent(url.split(',', 2)[1]!) }
} }))
import { openFeedbackWindow } from '../src/funnyworker-feedback-window.ts'
import { en, zh } from '../src/locale.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'
beforeEach(() => { harness.listeners.get('closed')?.(); harness.listeners.clear(); harness.html = ''; vi.useRealTimers() })
const account = (send: (path: string, body?: object) => Promise<unknown>): FunnyWorkerAccount => ({ username: 'reporter', dataKey: 'data-key-not-for-rendering', budget: async () => { throw new Error('unused') }, check: async () => true, logout: async () => {}, policyRequest: async () => {}, syncRequest: send })
test.each([['en', en], ['zh', zh]] as const)('original feedback form has localized sandboxed output %s', async (_locale, messages) => {
  await openFeedbackWindow(account(async () => [{ id: 'project', slug: 'owner/project' }]), messages)
  expect(harness.html).toContain("default-src 'none'"); expect(harness.html).not.toContain('data-key-not-for-rendering'); expect(harness.html).toMatchSnapshot()
})
test('submission stays in the original account channel and restores a failed form for retry', async () => {
  const send = vi.fn(async (path: string) => { if (path === '/feedback') throw new Error('offline'); return [{ id: 'project', slug: 'owner/project' }] })
  await openFeedbackWindow(account(send), en)
  const preventDefault = vi.fn(), params = new URLSearchParams({ repositoryId: 'project', title: '<script>bad</script>', actual: 'Missing file', expected: 'Valid example', steps: 'Inspect project' })
  harness.listeners.get('will-navigate')?.({ preventDefault }, `https://feedback.funnywork.invalid/submit?${params}`)
  await vi.waitFor(() => { expect(harness.html).toContain(en.feedbackError) })
  expect(preventDefault).toHaveBeenCalled(); expect(send.mock.calls.at(-1)?.[0]).toBe('/feedback'); expect(harness.html).toContain('&lt;script&gt;bad&lt;/script&gt;')
  expect(harness.html).not.toContain('<button disabled>')
})
test('feedback receipts expose only a validated PR link and actual acceptance state', async () => {
  vi.useFakeTimers()
  const send = vi.fn(async (path: string) => path === '/feedback/repositories' ? [{ id: 'project', slug: 'owner/project' }] : path === '/feedback' ? { intakeId: 'received' } : { state: 'ADMITTED', repair: { state: 'ACCEPTED', prUrl: 'https://github.com/owner/project/pull/1' } })
  await openFeedbackWindow(account(send), en)
  harness.listeners.get('will-navigate')?.({ preventDefault: vi.fn() }, 'https://feedback.funnywork.invalid/submit?repositoryId=project&title=Missing&actual=Missing&expected=Example&steps=Inspect')
  await vi.waitFor(() => { expect(harness.html).toContain(en.feedbackReceived) }); await vi.advanceTimersByTimeAsync(5000)
  expect(harness.html).toContain(en.feedbackAccepted); expect(harness.html).toContain(en.feedbackPr)
  harness.listeners.get('will-navigate')?.({ preventDefault: vi.fn() }, 'https://feedback.funnywork.invalid/pr')
  expect(harness.open).toHaveBeenCalledWith('https://github.com/owner/project/pull/1')
})

test('delivered receipt distinguishes merged sources from candidate acceptance', async () => {
  vi.useFakeTimers()
  await openFeedbackWindow(account(async path=>path==='/feedback/repositories'?[{ id:'project',slug:'owner/project' }]:path==='/feedback'?{ intakeId:'received' }:{ state:'ADMITTED',repair:{ state:'ACCEPTED',deliveryState:'DELIVERED',prUrl:'https://github.com/owner/project/pull/1' } }),zh)
  harness.listeners.get('will-navigate')?.({ preventDefault:vi.fn() },'https://feedback.funnywork.invalid/submit?repositoryId=project&title=Missing&actual=Missing&expected=Example&steps=Inspect')
  await vi.waitFor(() => { expect(harness.html).toContain(zh.feedbackReceived) });await vi.advanceTimersByTimeAsync(5000)
  expect(harness.html).toContain(zh.feedbackDelivered);expect(harness.html).toMatchSnapshot()
})

const navigate = async (path: string): Promise<void> => {
  harness.listeners.get('will-navigate')?.({ preventDefault: vi.fn() }, `https://feedback.funnywork.invalid${path}`)
  await new Promise<void>((resolve) => { queueMicrotask(() => { queueMicrotask(resolve) }) })
}

test('history survives reopening and supports next, previous and detail without a new submission', async () => {
  const send = vi.fn(async (path: string) => {
    if (path === '/feedback/repositories') return []
    if (path === '/feedback?after=&limit=20') return { items: [{ id: 'older', title: '<old report>', state: 'NEEDS_INFORMATION' }], nextCursor: 'older' }
    if (path === '/feedback?after=older&limit=20') return { items: [{ id: 'oldest', title: 'Last page', state: 'ADMITTED' }], nextCursor: '' }
    return { id: 'older', title: '<old report>', state: 'NEEDS_INFORMATION', messages: [{ content: '<prior note>' }] }
  })
  await openFeedbackWindow(account(send), en)
  await navigate('/history')
  await vi.waitFor(() => { expect(harness.html).toContain('&lt;old report&gt;') })
  expect(harness.html).not.toContain('invalid/previous')
  await navigate('/next')
  await vi.waitFor(() => { expect(harness.html).toContain('Last page') })
  expect(harness.html).toContain('invalid/previous'); expect(harness.html).not.toContain('invalid/next')
  await navigate('/previous')
  await vi.waitFor(() => { expect(harness.html).toContain('&lt;old report&gt;') })
  expect(harness.html).not.toContain('invalid/previous')
  expect(harness.html).toMatchSnapshot('saved reports first page')
  await navigate('/detail?id=older')
  await vi.waitFor(() => { expect(harness.html).toContain('&lt;prior note&gt;') })
  expect(harness.html).toContain('name="content"')
  expect(harness.html).toMatchSnapshot('saved report with follow-up form')
  harness.listeners.get('closed')?.()
  await openFeedbackWindow(account(send), en)
  await navigate('/history')
  await vi.waitFor(() => { expect(harness.html).toContain('&lt;old report&gt;') })
  expect(send.mock.calls.some(([path]) => path === '/feedback')).toBe(false)
})

test('failed supplement preserves input and retries the same request key before requeueing', async () => {
  let attempts = 0
  const send = vi.fn(async (path: string, _body?: object) => {
    if (path === '/feedback/repositories') return [{ id: 'project', slug: 'owner/project' }]
    if (path.endsWith('/supplements')) { if (++attempts === 1) throw new Error('offline'); return { accepted: true, reused: false } }
    return { id: 'older', title: 'Existing report', state: 'NEEDS_INFORMATION', messages: [] }
  })
  await openFeedbackWindow(account(send), zh)
  await navigate('/detail?id=older')
  await vi.waitFor(() => { expect(harness.html).toContain('name="content"') })
  const path = `/supplement?${new URLSearchParams({ content: '<reproduction steps>' })}`
  await navigate(path)
  await vi.waitFor(() => { expect(harness.html).toContain(zh.feedbackError) })
  expect(harness.html).toContain('&lt;reproduction steps&gt;')
  const first = send.mock.calls.find(([url]) => url.endsWith('/supplements'))?.[1]
  await navigate(path)
  await vi.waitFor(() => { expect(harness.html).toContain(zh.feedbackSupplementSaved) })
  expect(send.mock.calls.at(-1)?.[1]).toEqual(first)
  expect(harness.html).not.toContain('name="content"')
  expect(harness.html).toContain('&lt;reproduction steps&gt;')
})

test('polling leaves follow-up input untouched and stops after the feedback window closes', async () => {
  vi.useFakeTimers()
  const send = vi.fn(async (path: string) => path === '/feedback/repositories' ? [] : { state: 'NEEDS_INFORMATION' })
  await openFeedbackWindow(account(send), en)
  await navigate('/detail?id=older')
  await vi.waitFor(() => { expect(harness.html).toContain('name="content"') })
  send.mockClear()
  await vi.advanceTimersByTimeAsync(15000)
  expect(send).not.toHaveBeenCalled()
  harness.listeners.get('closed')?.()
  await vi.advanceTimersByTimeAsync(10000)
  expect(send).not.toHaveBeenCalled()
})

test('history network failure retains the current page and does not advance its cursor', async () => {
  const send = vi.fn(async (path: string) => {
    if (path === '/feedback/repositories') return []
    if (path.includes('after=older')) throw new Error('offline')
    return { items: [{ id: 'older', title: 'Keep this page', state: 'RECEIVED' }], nextCursor: 'older' }
  })
  await openFeedbackWindow(account(send), en)
  await navigate('/history')
  await vi.waitFor(() => { expect(harness.html).toContain('Keep this page') })
  await navigate('/next')
  await vi.waitFor(() => { expect(harness.html).toContain(en.feedbackError) })
  expect(harness.html).toContain('Keep this page'); expect(harness.html).not.toContain('invalid/previous')
})

test('revoked feedback access clears cached history and detail', async () => {
  const { HistorySyncHttpError } = await import('../src/funnyworker-sync-error.ts')
  let revoked = false
  const send = vi.fn(async (path: string) => {
    if (revoked) throw new HistorySyncHttpError(403)
    if (path === '/feedback/repositories') return []
    return { title: 'Private report', state: 'NEEDS_INFORMATION', messages: [{ content: 'Private details' }] }
  })
  await openFeedbackWindow(account(send), en)
  await navigate('/detail?id=older')
  await vi.waitFor(() => { expect(harness.html).toContain('Private details') })
  revoked = true
  await navigate('/refresh')
  await vi.waitFor(() => { expect(harness.html).toContain(en.feedbackError) })
  expect(harness.html).not.toContain('Private report'); expect(harness.html).not.toContain('Private details')
  expect(harness.html).not.toContain('name="content"')
})


test.each([['en',en],['zh',zh]] as const)('resolved historical reports distinguish a merged fix from installation %s',async(_locale,messages)=>{
  await openFeedbackWindow(account(async path=>path==='/feedback/repositories'?[]:path.startsWith('/feedback?')?{ items:[{ id:'historical',title:'Fix login',state:'DUPLICATE',repair_state:'DELIVERED' }],nextCursor:'' }:{ title:'Fix login',state:'DUPLICATE',repair:{ state:'DELIVERED',deliveryState:'DELIVERED' },resolution:{ code:'FIX_MERGED',title:'Fix login',installationConfirmed:false } }),messages)
  await navigate('/history');await vi.waitFor(()=>{expect(harness.html).toContain(messages.feedbackDelivered)})
  await navigate('/detail?id=historical');await vi.waitFor(()=>{expect(harness.html).toContain(messages.feedbackFixMerged.replace('{title}','Fix login'))})
  expect(harness.html).toMatchSnapshot()
})

test('held supplementary information remains visible without claiming reanalysis',async()=>{
  await openFeedbackWindow(account(async path=>path==='/feedback/repositories'?[]:path.endsWith('/supplements')?{ accepted:true,reused:false,held:true }:{ title:'Original report',state:'NEEDS_INFORMATION',messages:[] }),zh)
  await navigate('/detail?id=held-source');await vi.waitFor(()=>{expect(harness.html).toContain('name="content"')})
  await navigate(`/supplement?${new URLSearchParams({ content:'不限token使用' })}`);await vi.waitFor(()=>{expect(harness.html).toContain(zh.feedbackSupplementHeld)})
  expect(harness.html).toContain(zh.feedbackNoteHeld);expect(harness.html).toContain('name="content"');expect(harness.html).not.toContain(zh.feedbackSupplementSaved);expect(harness.html).toMatchSnapshot()
})
