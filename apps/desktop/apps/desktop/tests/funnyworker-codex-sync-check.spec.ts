/** Source-only synchronization checks use fake Host and Git transports, never paid models. */
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runCodexSyncCheck, flushCodexSyncChecks, desktopProbeBuild } from '../src/funnyworker-codex-sync-check.ts'
import type { HistoryHost } from '../src/funnyworker-history-sync.ts'
import { MaintenanceDeliveryPending } from '../src/funnyworker-maintenance.ts'
import { HistorySyncHttpError } from '../src/funnyworker-sync-error.ts'
import { MaintenanceAgentTerminationUnconfirmed } from '../src/funnyworker-maintenance-agent.ts'
let home: string
beforeEach(async () => { home = await mkdtemp(join(tmpdir(), 'codex-sync-')) })
afterEach(async () => { vi.useRealTimers(); await rm(home, { recursive: true, force: true }) })
function fixture() {
  const calls: string[][] = [], posts: { route: string; body: unknown }[] = []
  const data = { taskId: 'task', executor: 'funnywork-codex-v1', protocolVersion: 1, runtimeEpoch: 'epoch', repository: 'owner/repository', baseSha: 'a'.repeat(40), expectedVersion: '1.2.3', requiredCapabilities: ['sync-check'], platform: process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'linux', timeoutMs: 60000, maxOutputBytes: 16384, leaseDurationMs: 15000 }
  const payload = { ...data, specHash: createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.keys(data).sort().map(k => [k, data[k as keyof typeof data]])))).digest('hex') }
  const build = { version: '1.2.3', commit: 'a'.repeat(40), tree: 'b'.repeat(40), dirty: false }
  let starts = 0
  const receipt = { runId: 'target', runtimeEpoch: 'epoch', state: 'succeeded', processStopped: true }
  const host: HistoryHost = { invoke: async (method) => {
    if (method === 'workspaceCreate') return { workspace: { workspaceId: 'workspace' } }
    if (method === 'codexCapabilities') return { available: true, processStopped: true, executor: 'funnywork-codex-v1', protocolVersion: 1, executorVersion: '1', pluginVersion: '0.153.4', capabilities: ['sync-check'] }
    if (method === 'codexStart') { starts++; return { ...receipt, state: 'running', processStopped: false } }
    if (method === 'codexEvents') return { ...receipt, nextSeq: 3, events: [{ seq: 1, kind: 'started' }, { seq: 2, kind: 'capability', pluginVersion: '0.153.4', protocolVersion: 1 }, { seq: 3, kind: 'terminal', state: 'succeeded' }] }
    return receipt
  } }
  const account = { syncRequest: async (route: string, body?: unknown): Promise<unknown> => { posts.push({ route, body }); return route.endsWith('/renew') ? { expiresAt: Date.now() + 15000, leaseDurationMs: 15000 } : {} } }
  const run = () => runCodexSyncCheck(home, account, 'device', 'target', payload, new AbortController().signal, () => host, '1.2.3', build)
  return { payload, build, host, account, posts, calls, run, starts: () => starts }
}
test('reports installed commit/tree/app version with safe probe evidence and never checks out or modifies source', async () => {
  const f = fixture(); await f.run(); await f.run()
  expect(f.starts()).toBe(1)
  expect(f.posts.at(-1)).toMatchObject({ route: '/devices/device/maintenance-sync-checks/target/result', body: { outcome: 'CHECKED', clientVersion: '1.2.3', pluginVersion: '0.153.4', observedSha: 'a'.repeat(40), processStopped: true } })
  expect(f.calls.every(argv => argv[0] === 'git')).toBe(true)
  expect(f.calls.flat()).not.toContain('checkout')
  expect(f.calls.flat()).not.toContain('push')
})
test('rejects changed hashes and arbitrary commands before process launch', async () => {
  const f = fixture()
  await expect(runCodexSyncCheck(home, f.account, 'device', 'target', { ...f.payload, prompt: 'run arbitrary code' }, new AbortController().signal, () => f.host, '1.2.3', f.build)).rejects.toThrow('Invalid')
  await expect(runCodexSyncCheck(home, f.account, 'device', 'target', { ...f.payload, baseSha: 'b'.repeat(40) }, new AbortController().signal, () => f.host, '1.2.3', f.build)).rejects.toThrow('hash')
  expect(f.calls).toEqual([])
})
test('receipt retry does not repeat the probe', async () => {
  const f = fixture(), original = f.account.syncRequest.bind(f.account)
  let failing = true
  f.account.syncRequest = async (route, body) => { if (route.endsWith('/result') && failing) throw new Error('offline'); return original(route, body) }
  await expect(f.run()).rejects.toBeInstanceOf(MaintenanceDeliveryPending)
  failing = false; await flushCodexSyncChecks(home, f.account, 'device', 'epoch'); await f.run()
  expect(f.starts()).toBe(1)
})
test('unconfirmed cancellation persists UNKNOWN instead of granting retry', async () => {
  const f = fixture(), original = f.host.invoke.bind(f.host)
  f.host.invoke = async (method, args) => {
    if (method === 'codexEvents') throw new Error('disconnected')
    if (method === 'codexCancel' || method === 'codexReconcile') return { runId: 'target', runtimeEpoch: 'epoch', state: 'unknown', processStopped: false }
    return original(method, args)
  }
  await expect(f.run()).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
  const journal: unknown = JSON.parse(await readFile(join(home, 'codex-sync-target.json'), 'utf8'))
  expect(journal).toMatchObject({ receipt: { outcome: 'UNKNOWN', terminalState: 'unknown', processStopped: false } })
  await f.run(); expect(f.starts()).toBe(1)
})

test('manifest identity uses actual package fields and refuses missing or mismatched evidence', () => {
  const manifest = { version: '1.2.3', dshBuildCommit: 'a'.repeat(40), dshBuildTree: 'b'.repeat(40), dshBuildDirty: false }
  expect(desktopProbeBuild(manifest, '1.2.3')).toEqual({ version: '1.2.3', commit: 'a'.repeat(40), tree: 'b'.repeat(40), dirty: false })
  expect(desktopProbeBuild({ ...manifest, dshBuildTree: undefined }, '1.2.3')).toBeUndefined()
  expect(desktopProbeBuild(manifest, '1.2.4')).toBeUndefined()
  expect(desktopProbeBuild({ ...manifest, dshBuildDirty: true }, '1.2.3')?.dirty).toBe(true)
})
test('dirty packages cannot report CHECKED or launch a probe', async () => {
  const f = fixture(); f.build.dirty = true; await f.run()
  expect(f.starts()).toBe(0)
  expect(f.posts.at(-1)?.body).toMatchObject({ outcome: 'UNAVAILABLE', buildDirty: true, processStopped: true })
})
test('the installed commit remains observed even when it differs from the requested baseline', async () => {
  const f = fixture(); f.build.commit = 'c'.repeat(40); await f.run()
  expect(f.posts.at(-1)?.body).toMatchObject({ outcome: 'CHECKED', observedSha: 'c'.repeat(40), buildTree: 'b'.repeat(40) })
})
test('lease revocation cancels a pending events request and sends stop evidence after confirmed exit', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance'] })
  const f = fixture(), invoke = f.host.invoke.bind(f.host), sync = f.account.syncRequest.bind(f.account)
  let renewals = 0, cancelled = false
  f.account.syncRequest = async (route, body) => {
    if (route.endsWith('/renew') && ++renewals > 1 || route.endsWith('/result')) throw new HistorySyncHttpError(403)
    return sync(route, body)
  }
  f.host.invoke = async (method, args) => {
    if (method === 'codexEvents') return await new Promise(() => {})
    if (method === 'codexCancel') cancelled = true
    return invoke(method, args)
  }
  const pending = f.run()
  // Drain filesystem setup without moving the lease clock.
  while (f.starts() === 0) await new Promise<void>((resolve) => { setImmediate(resolve) })
  await vi.advanceTimersByTimeAsync(5000)
  await pending
  expect(cancelled).toBe(true)
  expect(f.posts.at(-1)?.route).toBe('/devices/device/maintenance-sync-checks/target/stopped')
  const journal: unknown = JSON.parse(await readFile(join(home, 'codex-sync-target.json'), 'utf8'))
  expect(journal).toMatchObject({ receipt: { processStopped: true, outcome: 'FAILED' } })
})

test('transport failures never extend the last-confirmed lease or release an unconfirmed process', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date', 'performance'] })
  const f = fixture(), invoke = f.host.invoke.bind(f.host), sync = f.account.syncRequest.bind(f.account)
  let renewals = 0, cancelled = false
  f.account.syncRequest = async (route, body) => {
    if (route.endsWith('/renew') && ++renewals > 1) throw new Error('offline')
    if (route.endsWith('/result')) throw new HistorySyncHttpError(409)
    return sync(route, body)
  }
  f.host.invoke = async (method, args) => {
    if (method === 'codexEvents') return await new Promise(() => {})
    if (method === 'codexCancel' || method === 'codexReconcile') {
      cancelled = true
      return { runId: 'target', runtimeEpoch: 'epoch', state: 'unknown', processStopped: false }
    }
    return invoke(method, args)
  }
  const pending = f.run()
  const rejected = expect(pending).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
  while (f.starts() === 0) await new Promise<void>((resolve) => { setImmediate(resolve) })
  await vi.advanceTimersByTimeAsync(14999)
  expect(cancelled).toBe(false)
  await vi.advanceTimersByTimeAsync(1)
  await rejected
  expect(cancelled).toBe(true)
  expect(f.posts.some(p => p.route.endsWith('/stopped'))).toBe(false)
  const journal: unknown = JSON.parse(await readFile(join(home, 'codex-sync-target.json'), 'utf8'))
  expect(journal).toMatchObject({ receipt: { outcome: 'UNKNOWN', processStopped: false } })
})
