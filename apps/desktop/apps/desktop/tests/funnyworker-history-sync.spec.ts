import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import { historyBatches, mobileChoices, HistoryWorkspaceCatalog, HistoryRemoteError, startHistorySync } from '../src/funnyworker-history-sync.ts'
import { desktopProbeBuild } from '../src/funnyworker-codex-sync-check.ts'
import { HistorySyncHttpError } from '../src/funnyworker-sync-error.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'

const homes: string[] = []
const stops: (() => Promise<void>)[] = []
afterEach(async () => {
  await Promise.all(stops.splice(0).map(stop => stop()))
  vi.useRealTimers()
  await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true })))
})

it.each([
  { dirty: false, supported: true, rejectOnce: false, expected: true },
  { dirty: true, supported: true, rejectOnce: false, expected: true },
  { dirty: false, supported: false, rejectOnce: false, expected: false },
  { dirty: false, supported: true, rejectOnce: true, expected: false },
])('publishes bounded installed build identity with negotiated support: %j', async ({ dirty, supported, rejectOnce, expected }) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-build-')); homes.push(home)
  const published: object[] = []
  let rejected = false
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path === '/maintenance/features') return { clientBuild: supported && !rejected }
    if (path.endsWith('/state')) {
      if (body !== undefined) published.push(structuredClone(body))
      if (rejectOnce && !rejected) { rejected = true; throw new HistorySyncHttpError(400) }
      return []
    }
    return {}
  } }
  const build = desktopProbeBuild({ version: '1.2.3', dshBuildCommit: 'a'.repeat(40), dshBuildTree: 'b'.repeat(40), dshBuildDirty: dirty }, '1.2.3')
  const catalog = { groups: Array.from({ length: 32 }, (_, i) => ({ id: '你'.repeat(250) + String(i),
    models: Array.from({ length: 32 }, (_, j) => ({ id: '好'.repeat(250) + String(j), name: '模型'.repeat(60) })) })) }
  const choices = mobileChoices(catalog, { presets: [] })
  expect(Buffer.byteLength(JSON.stringify(choices))).toBeGreaterThan(1_000_000)
  const host = { invoke: async (): Promise<unknown> => ({}), choices: async () => choices }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status, {}, '1.2.3', build))
  await vi.waitFor(() => { expect(status).toHaveBeenLastCalledWith('saved') })
  if (expected) expect(published.at(-1)).toMatchObject({ choices: { clientBuild: { version: '1.2.3', commit: 'a'.repeat(40), tree: 'b'.repeat(40), dirty } } })
  else expect(published.at(-1)).not.toHaveProperty('choices.clientBuild')
  if (rejectOnce) { expect(published[0]).toHaveProperty('choices.clientBuild'); expect(published).toHaveLength(2) }
  expect(Buffer.byteLength(JSON.stringify(published.at(-1)))).toBeLessThanOrEqual(1_048_576)
  if (rejectOnce) { await vi.advanceTimersByTimeAsync(2000); expect(published.at(-1)).not.toHaveProperty('choices.clientBuild') }
})

it.each(['stop', 'host-change'])('does not retry old runtime metadata after %s while the first state is pending', async (mode) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-build-stop-')); homes.push(home)
  let rejectFirst: (error: Error) => void = () => { throw new Error('Missing pending request') }
  const pending = new Promise<never>((_resolve, reject) => { rejectFirst = reject })
  let stateRequests = 0
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path) => {
    if (path === '/maintenance/features') return { clientBuild: true }
    if (path.endsWith('/state')) { stateRequests++; return await pending }
    return {}
  } }
  let host = { invoke: async (): Promise<unknown> => ({}) }
  const build = desktopProbeBuild({ version: '1.2.3', dshBuildCommit: 'a'.repeat(40), dshBuildTree: 'b'.repeat(40), dshBuildDirty: false }, '1.2.3')
  const stop = await startHistorySync(account, home, () => host, () => {}, {}, '1.2.3', build)
  stops.push(stop)
  await vi.waitFor(() => { expect(stateRequests).toBe(1) })
  const disposing = mode === 'stop' ? stop() : undefined
  if (mode === 'host-change') host = { invoke: async (): Promise<unknown> => ({}) }
  rejectFirst(new HistorySyncHttpError(400))
  await disposing
  await vi.advanceTimersByTimeAsync(0)
  expect(stateRequests).toBe(1)
})

it('forwards live previews, flushes settled replies promptly, and drops previews before exceeding the state budget', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-live-')); homes.push(home)
  let live: object | undefined = { attemptId: 'reply', startedAfterSeq: 4, turnStart: 1, text: 'Hello', truncated: false }
  let padding = ''
  const published: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) { if (body !== undefined) published.push(body); return [] }
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'replicaState') return { native: { seq: 5, running: true, projections: { padding }, ...live === undefined ? {} : { liveAssistant: live } } }
    return {}
  })
  const status = vi.fn()
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(published.length).toBeGreaterThan(0) })
  expect(published.at(-1)).toMatchObject({ sessionForks: true, sessions: { native: { liveAssistant: { text: 'Hello' } } } })
  const archived = invoke.mock.calls.filter(([method]) => method === 'replicaList').length
  live = undefined
  await vi.advanceTimersByTimeAsync(2000)
  expect(invoke.mock.calls.filter(([method]) => method === 'replicaList').length).toBeGreaterThan(archived)
  padding = 'x'.repeat(899_000)
  live = { attemptId: 'large', text: 'x'.repeat(2000) }
  await vi.advanceTimersByTimeAsync(2000)
  expect(JSON.stringify(published.at(-1))).not.toContain('liveAssistant')
  expect(JSON.stringify(published.at(-1))).toContain('padding')
})

it('bounds the whole encoded body without splitting or dropping multibyte records', () => {
  const event = { text: '你'.repeat(180000) }
  const batches = historyBatches('device', [event, event])
  expect(batches).toEqual([[event], [event]])
  expect(() => historyBatches('device', [{ text: '你'.repeat(310000) }])).toThrow('upload limit')
})

it.each(['registration', 'page'])('makes upload progress when %s exceeds the session time slice', async (slow) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-slow-progress-')); homes.push(home)
  let committed = slow === 'page' ? 1 : 0
  const pendingSeq = committed
  const uploads: unknown[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path === '/sessions') { if (slow === 'registration') vi.setSystemTime(Date.now() + 2500); return { id: 'cloud', nextSeq: committed } }
    if (path.endsWith('/events')) { uploads.push(body); committed++; return { id: 'cloud', nextSeq: committed } }
    if (path.endsWith('/attachments')) return { maxBytes: 33554432, items: [] }
    return path.endsWith('/state') ? [] : {}
  } }
  const host = { invoke: vi.fn(async (method: string, args: object): Promise<unknown> => {
    if (method === 'replicaList') return { native: 'r1' }
    if (method === 'replicaState') return {}
    const offset = (args as { offset: number }).offset
    if (slow === 'page' && offset === pendingSeq) vi.setSystemTime(Date.now() + 2500)
    return { header: { id: 'native', version: 4 }, inheritedEventCount: 0, events: offset <= pendingSeq ? [{ seq: offset, type: 'user/message', time: 1, data: {} }] : [] }
  }) }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(status).toHaveBeenLastCalledWith('saved'); expect(uploads).toHaveLength(1) })
  expect(committed).toBe(pendingSeq + 1)
  await vi.advanceTimersByTimeAsync(15000)
  expect(uploads).toHaveLength(1)
})

it('continues a slow attachment manifest without repeating completed pages', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-slow-attachments-')); homes.push(home)
  const after = '1'.repeat(64), hash = '2'.repeat(64)
  const manifests: string[] = []
  let uploaded = 0
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path) => {
    if (path === '/sessions') return { id: 'cloud', nextSeq: 1 }
    if (path.includes('/attachments?') || path.endsWith('/attachments')) {
      manifests.push(path);vi.setSystemTime(Date.now() + 2500)
      return path.endsWith('/attachments') ? { maxBytes: 33554432, items: Array.from({ length: 100 }, () => ({ complete: true })), after } : { maxBytes: 33554432, items: [{ hash, nextOffset: 0, metadata: { attachment: { bytes: 1 } } }], after: '' }
    }
    if (path.endsWith('/attachments/' + hash)) { uploaded++; return { nextOffset: 1, complete: true } }
    return path.endsWith('/state') ? [] : {}
  } }
  const host = { invoke: vi.fn(async (method: string): Promise<unknown> => method === 'replicaList' ? { native: 'r1' } : method === 'replicaState' ? {} : method === 'replicaAttachmentChunk' ? { data: 'AA==', nextOffset: 1 } : { header: { id: 'native', version: 4 }, inheritedEventCount: 0, events: [] }) }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(status).toHaveBeenLastCalledWith('saved'); expect(uploaded).toBe(1) })
  expect(manifests).toEqual(['/sessions/cloud/attachments', '/sessions/cloud/attachments?after=' + after])
})

it('shares an archive pass between a large backlog and a fresh session', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-fair-history-')); homes.push(home)
  const cursors: Record<string, number> = { large: 0, fresh: 0 }
  const uploads: string[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path === '/sessions' && body !== undefined && 'header' in body) {
      const id = (body.header as { id: string }).id
      return { id, nextSeq: cursors[id] }
    }
    const match = /^\/sessions\/(large|fresh)\/events$/.exec(path)
    if (match !== null && body !== undefined && 'events' in body) {
      const id = match[1]!
      uploads.push(id)
      cursors[id] = cursors[id]! + (body.events as unknown[]).length
      return { id, nextSeq: cursors[id] }
    }
    if (path.endsWith('/attachments')) return { maxBytes: 33554432, items: [] }
    return path.endsWith('/state') ? [] : {}
  } }
  const host = { invoke: vi.fn(async (method: string, args: object): Promise<unknown> => {
    if (method === 'replicaList') return { large: 'r1', fresh: 'r1' }
    if (method === 'replicaState') return {}
    const { id, offset } = args as { id: string; offset: number }
    return { header: { id, version: 4 }, inheritedEventCount: 0, events: offset < (id === 'large' ? 10 : 1) ? [{ seq: offset, type: 'user/message', time: 1, data: {} }] : [] }
  }) }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(status).toHaveBeenCalledWith('saved'); expect(cursors).toEqual({ large: 10, fresh: 1 }) })
  expect(uploads.slice(0, 3)).toEqual(['large', 'large', 'fresh'])
  await vi.advanceTimersByTimeAsync(15000)
  expect(cursors).toEqual({ large: 10, fresh: 1 })
})

it('rotates past a slow session after the archive time budget expires', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-slow-history-')); homes.push(home)
  const registrations: string[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path === '/sessions' && body !== undefined && 'header' in body) {
      const id = (body.header as { id: string }).id; registrations.push(id)
      if (id === 'slow') vi.setSystemTime(Date.now() + 11000)
      return { id, nextSeq: 0 }
    }
    if (path.endsWith('/attachments')) return { maxBytes: 33554432, items: [] }
    return path.endsWith('/state') ? [] : {}
  } }
  const host = { invoke: vi.fn(async (method: string, args: object): Promise<unknown> => method === 'replicaList' ? { slow: 'r1', fresh: 'r1' } : method === 'replicaState' ? {} : { header: { id: (args as { id: string }).id, version: 4 }, inheritedEventCount: 0, events: [] }) }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(status).toHaveBeenCalledWith('pending'); expect(registrations).toEqual(['slow']) })
  expect(registrations).toEqual(['slow'])
  await vi.advanceTimersByTimeAsync(15000)
  expect(registrations.slice(0, 2)).toEqual(['slow', 'fresh'])
})

it('recovers a committed upload after acknowledgement loss using the server cursor', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-history-')); homes.push(home)
  let committed = 0
  let loseAck = true
  const uploaded: unknown[] = []
  const request = vi.fn(async (path: string, body?: object): Promise<unknown> => {
    if (path === '/devices' || path === '/maintenance/features') return {}
    if (path.endsWith('/state')) return []
    if (path.includes('/commands')) return []
    if (path.endsWith('/attachments')) return { maxBytes: 33554432, items: [] }
    if (path === '/sessions') return { id: 'cloud', nextSeq: committed }
    uploaded.push(body);committed = 1
    if (loseAck) { loseAck = false; throw new Error('Connection lost') }
    return { id: 'cloud', nextSeq: committed }
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: request }
  const host = { invoke: vi.fn(async (method: string, args: object): Promise<unknown> => method === 'replicaState' ? {} : method === 'replicaList' ? { native: 'r1' } : { header: { id: 'native', version: 4 }, inheritedEventCount: 0, events: 'offset' in args && args.offset === 0 ? [{ seq: 0, type: 'user/message', time: 1, data: {} }] : [] }) }
  const status = vi.fn()
  stops.push(await startHistorySync(account, home, () => host, status))
  await vi.waitFor(() => { expect(status).toHaveBeenCalledWith('error') })
  await vi.advanceTimersByTimeAsync(15000)
  expect(status).toHaveBeenLastCalledWith('saved')
  expect(uploaded).toHaveLength(1)
  const registrations = request.mock.calls.filter(([path]) => path === '/devices')
  expect(registrations[0]?.[1]).toEqual(registrations[1]?.[1])
  const reads = host.invoke.mock.calls.filter(([method]) => method === 'replicaPage').length
  await vi.advanceTimersByTimeAsync(15000)
  expect(host.invoke.mock.calls.filter(([method]) => method === 'replicaPage')).toHaveLength(reads)
})

it('never re-executes a claimed operation when its receipt is lost', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-commands-')); homes.push(home)
  let claimed = false
  let lost = false
  const receipts: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) { if (claimed) return []; claimed = true; return [{ id: 'request-1', nativeId: 'native', kind: 'prompt', payload: { text: 'Continue', mode: 'queue' } }] }
    if (path.endsWith('/commands/request-1')) { if (body !== undefined) receipts.push(body); if (!lost) { lost = true; throw new Error('Acknowledgement lost') }; return {} }
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => method === 'replicaList' || method === 'replicaState' ? {} : { accepted: true })
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(2)
  expect(receipts[0]).toEqual(receipts[1])
  expect(invoke.mock.calls.filter(([method]) => method === 'prompt')).toHaveLength(1)
  expect(invoke.mock.calls.filter(([method]) => method === 'replicaFlush')).toHaveLength(1)
})

it.each([['rejected', new HistoryRemoteError('Retired turn')], ['uncertain', new Error('Carrier lost')]])('reports %s without replaying a failed native call', async (outcome, failure) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-commands-')); homes.push(home)
  let claimed = false
  const receipts: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) { if (claimed) return []; claimed = true; return [{ id: 'request-1', nativeId: 'native', kind: 'cancel', payload: { expectedTurn: 1 } }] }
    if (path.endsWith('/commands/request-1') && body !== undefined) receipts.push(body)
    return {}
  } }
  const host = { invoke: vi.fn(async (method: string): Promise<unknown> => { if (method === 'cancel') throw failure; return {} }) }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  expect(receipts[0]).toMatchObject({ status: outcome })
  await vi.advanceTimersByTimeAsync(4000)
  expect(host.invoke.mock.calls.filter(([method]) => method === 'cancel')).toHaveLength(1)
})

it('awaits in-flight work during shutdown and admits no further operations', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-shutdown-')); homes.push(home)
  let release: (value: unknown) => void = () => { throw new Error('Read not started') }
  const reading = new Promise<unknown>((resolve) => { release = resolve })
  const invoke = vi.fn(async (): Promise<unknown> => reading)
  const dispose = vi.fn()
  const syncRequest = vi.fn(async (): Promise<unknown> => ({}))
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  const host = { invoke, dispose }
  const stop = await startHistorySync(account, home, () => host, vi.fn())
  await vi.waitFor(() => { expect(invoke).toHaveBeenCalledOnce() })
  let done = false
  const stopping = stop().then(() => { done = true })
  await Promise.resolve()
  expect(done).toBe(false)
  expect(dispose).not.toHaveBeenCalled()
  release({})
  await stopping
  expect(done).toBe(true)
  expect(dispose).toHaveBeenCalledOnce()
  expect(invoke).toHaveBeenCalledOnce()
  expect(syncRequest).toHaveBeenCalledOnce()
})

it('executes the whole claimed batch before retrying a failed receipt', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-batch-')); homes.push(home)
  let claimed = false
  let firstAck = true
  const receipts: string[] = []
  const syncRequest = vi.fn(async (path: string): Promise<unknown> => {
    if (path.endsWith('/state')) {
      if (claimed) return []
      claimed = true
      return ['one', 'two'].map(id => ({ id, nativeId: 'native', kind: 'prompt', payload: { text: id, mode: 'queue' } }))
    }
    if (path.includes('/commands/')) {
      receipts.push(path)
      if (path.endsWith('/one') && firstAck) { firstAck = false; throw new Error('Receipt lost') }
    }
    return {}
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  const invoke = vi.fn(async (_method: string, _args: object): Promise<unknown> => ({}))
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(2) })
  expect(invoke.mock.calls.filter(([method]) => method === 'prompt')).toHaveLength(2)
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(3)
  expect(invoke.mock.calls.filter(([method]) => method === 'prompt')).toHaveLength(2)
})

it('publishes the restored runtime before retrying a pending receipt', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-restored-')); homes.push(home)
  let published = false
  let claimed = false
  let paused = false
  let firstAck = true
  const receipts: string[] = []
  const syncRequest = vi.fn(async (path: string): Promise<unknown> => {
    if (path.endsWith('/state')) {
      if (paused) throw new HistorySyncHttpError(404)
      published = true
      if (claimed) return []
      claimed = true
      return [{ id: 'one', nativeId: 'native', kind: 'prompt', payload: { text: 'one', mode: 'queue' } }]
    }
    if (path.includes('/commands/')) {
      receipts.push(path)
      if (firstAck) { firstAck = false; published = false; paused = true; throw new Error('Receipt lost') }
      if (!published) throw new HistorySyncHttpError(409)
    }
    return {}
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  const invoke = vi.fn(async (_method: string, _args: object): Promise<unknown> => ({}))
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(1)
  paused = false
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(2)
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(2)
  expect(invoke.mock.calls.filter(([method]) => method === 'prompt')).toHaveLength(1)
})

it('retires a permanently fenced receipt without blocking subsequent commands', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-fenced-')); homes.push(home)
  let publications = 0
  const receipts: string[] = []
  const syncRequest = vi.fn(async (path: string): Promise<unknown> => {
    if (path.endsWith('/state')) {
      publications++
      return publications <= 2 ? [{ id: `request-${publications}`, nativeId: 'native', kind: 'prompt', payload: { text: 'one', mode: 'queue' } }] : []
    }
    if (path.includes('/commands/')) { receipts.push(path); if (path.endsWith('/request-1')) throw new HistorySyncHttpError(409) }
    return {}
  })
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest }
  const invoke = vi.fn(async (_method: string, _args: object): Promise<unknown> => ({}))
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(4000)
  expect(receipts).toHaveLength(2)
  expect(invoke.mock.calls.filter(([method]) => method === 'prompt')).toHaveLength(2)
})

it('creates once in a published Workspace and preserves the archive receipt across acknowledgement loss', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-create-')); homes.push(home)
  let claimed = false
  let failed = false
  const receipts: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) { if (claimed) return []; claimed = true; return [{ id: 'create-1', nativeId: '', kind: 'create', payload: { workspaceId: 'work-1' } }] }
    if (path === '/sessions') return { id: 'cloud-new', nextSeq: 0 }
    if (path.endsWith('/commands/create-1')) { receipts.push(body!); if (!failed) { failed = true; throw new Error('Lost receipt') } }
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'create') return { sessionId: 'mobile-create-1' }
    if (method === 'replicaPage') return { header: { id: 'mobile-create-1' }, inheritedEventCount: 0 }
    return {}
  })
  const host = { invoke, workspaces: () => [{ id: 'work-1', title: 'Work' }] }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(2000)
  expect(invoke.mock.calls.filter(([method]) => method === 'create')).toHaveLength(1)
  expect(invoke).toHaveBeenCalledWith('create', { workspaceId: 'work-1', sessionId: 'mobile-create-1' })
  expect(receipts).toHaveLength(2)
  expect(receipts[0]).toEqual(receipts[1])
  expect(receipts[0]).toMatchObject({ status: 'accepted', result: { sessionId: 'cloud-new' } })
})

it('discards stale Workspace generations and never publishes local paths', () => {
  const catalog = new HistoryWorkspaceCatalog()
  expect(catalog.list()).toBeUndefined()
  catalog.accept({ type: 'baseline', value: { items: [{ workspaceId: 'one', title: 'Work', path: '/private', sessionIds: ['secret'] }] } })
  expect(catalog.list()).toEqual([{ id: 'one', title: 'Work' }])
  catalog.accept({ type: 'upsert', workspace: { workspaceId: 'two', title: 'Other' } })
  catalog.accept({ type: 'remove', workspaceId: 'one' })
  expect(catalog.list()).toEqual([{ id: 'two', title: 'Other' }])
  catalog.clear()
  catalog.accept({ type: 'upsert', workspace: { workspaceId: 'stale', title: 'Stale' } })
  expect(catalog.list()).toBeUndefined()
})

it.each([false, true])('does not replay creation after Workspace removal or partial native failure: %s', async (partial) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-create-failure-')); homes.push(home)
  let claimed = false
  const receipts: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) { if (claimed) return []; claimed = true; return [{ id: 'create-failure', nativeId: '', kind: 'create', payload: { workspaceId: 'work' } }] }
    if (path.endsWith('/commands/create-failure')) receipts.push(body!)
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'create') throw new HistoryRemoteError('Workspace attachment failed after creation')
    return {}
  })
  const host = { invoke, workspaces: () => partial ? [{ id: 'work', title: 'Work' }] : [] }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(4000)
  expect(receipts[0]).toMatchObject({ status: partial ? 'uncertain' : 'rejected' })
  expect(invoke.mock.calls.filter(([method]) => method === 'create')).toHaveLength(partial ? 1 : 0)
})

it('bounds the published Workspace catalog by encoded bytes', () => {
  const catalog = new HistoryWorkspaceCatalog()
  catalog.accept({ type: 'baseline', value: { items: Array.from({ length: 1000 }, (_, i) => ({ workspaceId: `work-${i}`, title: '你'.repeat(200) })) } })
  const list = catalog.list()
  expect(list?.length).toBeGreaterThan(0)
  expect(Buffer.byteLength(JSON.stringify(list))).toBeLessThanOrEqual(200000)
})

it.each([false, true])('imports verified phone files before prompt and rejects corruption=%s', async (corrupt) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-phone-files-')); homes.push(home)
  const data = Buffer.from('phone file')
  const { createHash } = await import('node:crypto')
  const hash = createHash('sha256').update(data).digest('hex')
  let claimed = false
  const receipts: object[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) {
      expect(body).toMatchObject({ attachmentUploads: true })
      if (claimed) return []
      claimed = true
      return [{ id: 'files', sessionId: 'cloud', nativeId: 'native', kind: 'prompt', payload: { text: '', mode: 'queue', attachments: [{ hash, name: 'phone.txt', bytes: data.length, mediaType: 'text/plain' }] } }]
    }
    if (path.includes('/attachments/')) return { data: (corrupt ? Buffer.from('wrong file') : data).toString('base64'), nextOffset: data.length, complete: true }
    if (path.endsWith('/commands/files') && body !== undefined) receipts.push(body)
    return {}
  } }
  const upload = vi.fn(async () => ({ receiptId: 'receipt-file', attachmentId: `sha256:${hash}`, bytes: data.length }))
  const invoke = vi.fn(async (): Promise<unknown> => ({}))
  const host = { invoke, upload }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  if (corrupt) {
    expect(receipts[0]).toMatchObject({ status: 'rejected' })
    expect(upload).not.toHaveBeenCalled()
    expect(invoke).not.toHaveBeenCalledWith('prompt', expect.anything())
  } else {
    expect(upload).toHaveBeenCalledWith('native', 'phone.txt', data)
    expect(invoke).toHaveBeenCalledWith('prompt', { sessionId: 'native', requestId: 'files', mode: 'queue', content: [{ type: 'file', receiptId: 'receipt-file' }] })
    expect(receipts[0]).toMatchObject({ status: 'accepted' })
  }
})


it.each(['accepted', 'wrong-prefix', 'native-rejection', 'invalid-cut', 'long-identity'])('fork delivery retains its exact child and cut: %s', async (mode) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-fork-')); homes.push(home)
  let claimed = false
  let lostReceipt = false
  const commandId = mode === 'long-identity' ? 'f'.repeat(65) : 'fork-1'
  const invalid = mode === 'invalid-cut' || mode === 'long-identity'
  const receipts: object[] = []
  const archive = vi.fn()
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) {
      if (claimed) return []
      claimed = true
      return [{ id: commandId, nativeId: 'source', kind: 'fork', payload: { atSeq: mode === 'invalid-cut' ? -1 : 2 } }]
    }
    if (path === '/sessions') { archive(body); return { id: 'cloud-fork', nextSeq: 0 } }
    if (path.endsWith(`/commands/${commandId}`)) {
      receipts.push(body!)
      if (!lostReceipt) { lostReceipt = true; throw new Error('Lost receipt') }
    }
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'fork') {
      if (mode === 'native-rejection') throw new HistoryRemoteError('Fork failed after child creation')
      return { sessionId: 'mobile-fork-1' }
    }
    if (method === 'replicaPage') return { header: { id: 'mobile-fork-1', parentSession: 'source', isSeeded: true }, inheritedEventCount: mode === 'wrong-prefix' ? 6 : 3 }
    return {}
  })
  const host = { invoke }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  await vi.advanceTimersByTimeAsync(2000)
  expect(receipts).toHaveLength(2); expect(receipts[0]).toEqual(receipts[1])
  expect(invoke.mock.calls.filter(([method]) => method === 'fork')).toHaveLength(invalid ? 0 : 1)
  if (mode === 'accepted') {
    expect(invoke).toHaveBeenCalledWith('fork', { sessionId: 'source', childSessionId: 'mobile-fork-1', atSeq: 2 })
    expect(archive).toHaveBeenCalledTimes(1)
    expect(receipts[0]).toMatchObject({ status: 'accepted', result: { sessionId: 'cloud-fork' } })
  } else {
    expect(archive).not.toHaveBeenCalled()
    expect(receipts[0]).toMatchObject({ status: invalid ? 'rejected' : 'uncertain', result: {} })
  }
})


it.each([true, false])('revalidates mobile model choices and keeps desktop defaults: %s', async (available) => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-model-create-')); homes.push(home)
  let claimed = false
  const receipts: object[] = []
  const model = { provider: 'enterprise', model: 'qwen' }
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined,
    policyRequest: async () => undefined, syncRequest: async (path, body) => {
      if (path.endsWith('/state')) { if (claimed) return []; claimed = true; return [{ id: 'choice-1', nativeId: '', kind: 'create', payload: { workspaceId: 'work', model, agentPreset: 'enterprise-helper-v1' } }] }
      if (path === '/sessions') return { id: 'cloud-choice', nextSeq: 0 }
      if (path.endsWith('/commands/choice-1')) receipts.push(body!)
      return {}
    } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'create') return { sessionId: 'mobile-choice-1' }
    if (method === 'replicaPage') return { header: { id: 'mobile-choice-1' }, inheritedEventCount: 0 }
    return {}
  })
  const host = { invoke, workspaces: () => [{ id: 'work', title: 'Work' }], choices: async () => ({ models: available ? [{ ...model, name: 'Qwen' }] : [], presets: [{ id: 'enterprise-helper-v1', name: 'Helper' }] }) }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  expect(receipts[0]).toMatchObject({ status: available ? 'accepted' : 'rejected' })
  if (available) {
    expect(invoke).toHaveBeenCalledWith('create', { sessionId: 'mobile-choice-1', workspaceId: 'work', agentPreset: 'enterprise-helper-v1' })
    expect(invoke).toHaveBeenCalledWith('selectModel', { sessionId: 'mobile-choice-1', ...model, saveDefault: false })
  } else expect(invoke.mock.calls.some(([method]) => method === 'create')).toBe(false)
})


it('keeps 512 managed presets plus standard and normalizes labels without poisoning valid choices', () => {
  const presets = [{ id: 'standard', name: 'Standard' }, ...Array.from({ length: 512 }, (_, id) => ({
    id: `enterprise-${id}-v1`, name: id === 0 ? '企业\n助手' : '助'.repeat(100),
  })), { id: 'bad\nid', name: 'Invalid' }, { id: 'broken', name: 'Broken', broken: 'error' }]
  const value = mobileChoices({ groups: [{ id: 'enterprise', models: [
    { id: 'qwen', name: '企业\r\n千问' }, { id: '', name: 'invalid' }, { id: 'deepseek', name: '' },
  ] }] }, { presets })
  expect(value.models).toEqual([{ provider: 'enterprise', model: 'qwen', name: '企业 千问' },
    { provider: 'enterprise', model: 'deepseek', name: 'deepseek' }])
  expect(value.presets).toHaveLength(513)
  expect((value.presets as { name: string }[])[1]?.name).toBe('企业 助手')
  expect(Buffer.byteLength(JSON.stringify(value))).toBeLessThanOrEqual(1_048_576)
})

it('publishes one catalog for many workspaces instead of discarding selection', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-large-catalog-')); homes.push(home)
  const choices = mobileChoices({ groups: [{ id: 'enterprise', models: [{ id: 'qwen', name: 'Qwen' }] }] }, {
    presets: Array.from({ length: 512 }, (_, id) => ({ id: `enterprise-${id}-v1`, name: '助'.repeat(100) })),
  })
  const workspaces = Array.from({ length: 100 }, (_, id) => ({ id: `work-${id}`, title: 'Work' }))
  const bodies: Record<string, unknown>[] = []
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined,
    policyRequest: async () => undefined, syncRequest: async (path, body) => {
      if (path.endsWith('/state')) { bodies.push(body as Record<string, unknown>); return [] }
      return {}
    } }
  const host = { invoke: async () => ({}), workspaces: () => workspaces, choices: async () => choices }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(bodies.length).toBeGreaterThan(0) })
  expect(bodies[0]?.choices).toEqual(choices)
  expect(bodies[0]?.workspaces).toEqual(workspaces)
  expect(Buffer.byteLength(JSON.stringify(bodies[0]))).toBeLessThan(250_000)
})

it('keeps state synchronization live during slow workspace commands without claiming or replaying them', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'fw-workspace-heartbeat-')); homes.push(home)
  let claimed = false
  let release: (() => void) | undefined
  const blocked = new Promise<void>((resolve) => { release = resolve })
  const receipts: unknown[] = []
  const heartbeats: unknown[] = []
  let running = true
  const account: FunnyWorkerAccount = { username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: async (path, body) => {
    if (path.endsWith('/state')) {
      expect(body).toMatchObject({ choices: { workspaceOperations: true } })
      if (claimed) return []
      claimed = true
      return [{ id: 'workspace-command', nativeId: '', kind: 'workspace', payload: { action: 'read', workspaceId: 'work', path: 'file' } }]
    }
    if (path.endsWith('/heartbeat')) heartbeats.push(body)
    if (path.endsWith('/commands/workspace-command')) receipts.push(body)
    return {}
  } }
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'replicaState') return { native: { seq: 0, running } }
    if (method === 'workspaceOperation') { await blocked; return { text: 'file content', revision: 'hash' } }
    if (method === 'replicaList') return []
    return {}
  })
  const approval = { agentId: 'native', eventId: 'approval-one', event: 'approval/request', request: { callId: 'call-one' } }
  const host = { invoke, workspaceOperations: true, pending: () => [approval] }
  stops.push(await startHistorySync(account, home, () => host, vi.fn()))
  await vi.waitFor(() => { expect(invoke).toHaveBeenCalledWith('workspaceOperation', expect.objectContaining({ commandId: 'workspace-command' })) })
  running = false
  await vi.advanceTimersByTimeAsync(4000)
  expect(heartbeats.length).toBeGreaterThan(0)
  const lastHeartbeat = heartbeats.at(-1)
  release?.()
  await vi.waitFor(() => { expect(receipts).toHaveLength(1) })
  expect(lastHeartbeat).toMatchObject({ sessions: { native: { running: false, pending: [approval] } } })
  await vi.advanceTimersByTimeAsync(4000)
  expect(invoke.mock.calls.filter(([method]) => method === 'workspaceOperation')).toHaveLength(1)
  expect(receipts[0]).toMatchObject({ status: 'accepted', result: { text: 'file content' } })
})
