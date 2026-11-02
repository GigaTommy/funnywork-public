/** Native maintenance exercises Host RPCs, never an external coding CLI. */
import { expect, test } from 'vitest'
import { nativeMaintenanceAgent, MaintenanceAgentTerminationUnconfirmed } from '../src/funnyworker-maintenance-agent.ts'
import type { HistoryHost } from '../src/funnyworker-history-sync.ts'
const sessionId = 'maintenance-problem-v1-e1'
function fixture(reason = 'completed', prior = 0, code?: string) {
  const calls: { method: string; args: object }[] = []
  let cancelled = false
  const host: HistoryHost = { async invoke(method, args) {
    calls.push({ method, args })
    if (method === 'modelCatalog') return { default: { provider: 'enterprise-native', model: 'deepseek-flash' }, routableProviders: ['enterprise-native'] }
    if (method === 'workspaceCreate') return { created: true, workspace: { workspaceId: 'workspace-native' } }
    if (method === 'replicaState') return { [sessionId]: { seq: prior, running: !cancelled && reason !== 'completed', admissionWaiting: 0, queue: [] } }
    if (method === 'replicaPage') return { nextSeq: prior + 2, events: [
      { type: 'assistant/message', data: { usage: { inputTokens: 20, outputTokens: 5, cacheReadTokens: 8 } } },
      { type: 'turn/end', data: { reason: { kind: reason, error: { code, message: 'private bearer and provider URL' } } } },
    ] }
    if (method === 'cancel') cancelled = true
    return {}
  } }
  const request = { sessionId, cwd: '/private/repo', title: '修复菜单', prompt: 'Fix only the reported problem', iteration: prior > 0 ? 1 : 0,
    signal: new AbortController().signal, timeoutMs: 1000, maxOutputBytes: 10000 }
  return { host, calls, request }
}
test('native execution creates a visible session using the configured DeepSeek model', async () => {
  const f = fixture(), agent = nativeMaintenanceAgent(() => f.host)
  await agent.ready()
  expect(await agent.run(f.request)).toEqual({
    sessionId, modelUsage: { calls: 1, inputTokens: 20, outputTokens: 5, cachedInputTokens: 8 },
  })
  expect(f.calls.find(c => c.method === 'create')?.args).toEqual({ sessionId, workspaceId: 'workspace-native' })
  expect(f.calls.find(c => c.method === 'selectModel')?.args).toEqual({ sessionId, provider: 'enterprise-native', model: 'deepseek-flash', saveDefault: false })
  expect(f.calls.find(c => c.method === 'rename')?.args).toEqual({ sessionId, title: '修复菜单' })
  expect(f.calls.some(c => c.method === 'replicaFlush')).toBe(true)
})
test('a non-completed native turn cannot produce a verified candidate', async () => {
  const f = fixture('blocked')
  await expect(nativeMaintenanceAgent(() => f.host).run(f.request)).rejects.toThrow('ended without completing')
  expect(f.calls.find(c => c.method === 'cancel')?.args).toEqual({ sessionId })
})
test('host-generation loss cannot be declared a confirmed stop', async () => {
  const f = fixture()
  let current: HistoryHost | undefined = f.host
  const original = f.host.invoke.bind(f.host)
  f.host.invoke = async (method, args) => { const result = await original(method, args); if (method === 'prompt') current = undefined; return result }
  await expect(nativeMaintenanceAgent(() => current).run(f.request)).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
})
test('continuation reads only the new native turn prefix', async () => {
  const f = fixture('completed', 10)
  await nativeMaintenanceAgent(() => f.host).run(f.request)
  expect(f.calls.find(c => c.method === 'replicaPage')?.args).toEqual({ id: sessionId, offset: 10 })
})
test('missing native model prevents readiness without prompting', async () => {
  const f = fixture()
  f.host.invoke = async () => ({ default: { provider: 'unavailable', model: 'unavailable' }, routableProviders: [] })
  await expect(nativeMaintenanceAgent(() => f.host).ready()).rejects.toThrow('management backend')
  expect(f.calls).toEqual([])
})

test('annual retries use distinct workspace names required by the original workspace controller', async () => {
  const titles: string[] = []
  for (const id of ['annual-attempt-1', 'annual-attempt-2']) {
    const f = fixture(), original = f.host.invoke.bind(f.host)
    f.host.invoke = async (method,args) => {
      if (method === 'replicaState') return { [id]: { seq: 2, running: false, admissionWaiting: 0, queue: [] } }
      return original(method,args)
    }
    await nativeMaintenanceAgent(() => f.host).run({ ...f.request, sessionId: id })
    const renamed = f.calls.find(c => c.method === 'workspaceRename')?.args
    if (renamed !== undefined && 'title' in renamed && typeof renamed.title === 'string') titles.push(renamed.title)
  }
  expect(new Set(titles).size).toBe(2)
})


test('returns safe admission classification in the backend receipt without copying provider messages', async () => {
  const f = fixture('error', 0, 'ADMISSION_CONTROL_CONNECT_TIMEOUT')
  await expect(nativeMaintenanceAgent(() => f.host).run(f.request)).rejects.toThrow('FunnyWork Agent ended without completing maintenance: error [ADMISSION_CONTROL_CONNECT_TIMEOUT]')
})
