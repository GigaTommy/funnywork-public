/** Non-model probes retain process ownership across cancellation and carrier failures. */
import { expect, test } from 'vitest'
import { runCodexProbe, codexCapabilities } from '../src/funnyworker-codex-executor.ts'
import { MaintenanceAgentTerminationUnconfirmed } from '../src/funnyworker-maintenance-agent.ts'
import type { HistoryHost } from '../src/funnyworker-history-sync.ts'
const request = { requestId: 'target', runtimeEpoch: 'epoch', workspaceId: 'workspace', baselineSha: 'a'.repeat(40), timeoutMs: 1000, maxOutputBytes: 16384 }
function fixture() {
  const calls: string[] = []
  const receipt = { runId: 'target', runtimeEpoch: 'epoch', state: 'succeeded', processStopped: true }
  const host: HistoryHost = { invoke: async (method) => {
    calls.push(method)
    if (method === 'codexCapabilities') return { available: true, processStopped: true, executor: 'funnywork-codex-v1', protocolVersion: 1, executorVersion: '1', pluginVersion: '0.153.4', capabilities: ['sync-check'] }
    if (method === 'codexStart') return { ...receipt, state: 'running', processStopped: false }
    if (method === 'codexEvents') return { ...receipt, nextSeq: 3, events: [
      { seq: 1, kind: 'started' }, { seq: 2, kind: 'capability', pluginVersion: '0.153.4', protocolVersion: 1 }, { seq: 3, kind: 'terminal', state: 'succeeded' },
    ] }
    return receipt
  } }
  return { host, calls, receipt }
}
test('collects contiguous structured events and only returns after confirmed process stop', async () => {
  const f = fixture()
  const result = await runCodexProbe(() => f.host, request, new AbortController().signal)
  expect(result).toMatchObject({ terminalState: 'completed', processStopped: true, eventCount: 3, pluginVersion: '0.153.4' })
  expect(result.evidenceDigest).toMatch(/^[a-f0-9]{64}$/)
  expect(f.calls).toEqual(['codexStart', 'codexEvents'])
})
test('does not infer availability from a version without explicit capability', async () => {
  const f = fixture(); f.host.invoke = async () => ({ pluginVersion: '0.153.4', available: false })
  await expect(codexCapabilities(f.host)).rejects.toThrow('unavailable')
})
test('an anonymous readiness probe with unconfirmed exit blocks maintenance ownership', async () => {
  const f = fixture()
  f.host.invoke = async () => ({ available: false, processStopped: false })
  await expect(codexCapabilities(f.host)).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
})
test('cancel acknowledgement alone never becomes a stopped result', async () => {
  const f = fixture(), original = f.host.invoke.bind(f.host), abort = new AbortController()
  f.host.invoke = async (method, args) => {
    if (method === 'codexStart') { abort.abort(); return { ...f.receipt, state: 'running', processStopped: false } }
    if (method === 'codexCancel' || method === 'codexReconcile') return { ...f.receipt, state: 'running', processStopped: false }
    return original(method, args)
  }
  await expect(runCodexProbe(() => f.host, request, abort.signal)).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
})
test('start carrier loss cancels by deterministic target id and confirms exit', async () => {
  const f = fixture(), original = f.host.invoke.bind(f.host)
  f.host.invoke = async (method, args) => { if (method === 'codexStart') throw new Error('transport'); return original(method, args) }
  await expect(runCodexProbe(() => f.host, request, new AbortController().signal)).rejects.toThrow('transport')
  expect(f.calls).toContain('codexCancel')
})
test('Host replacement keeps ownership unknown without cancelling an unrelated generation', async () => {
  const f = fixture(), original = f.host.invoke.bind(f.host)
  let current: HistoryHost | undefined = f.host
  f.host.invoke = async (method, args) => {
    const result = await original(method, args)
    if (method === 'codexStart') current = undefined
    return result
  }
  const pending = runCodexProbe(() => current, request, new AbortController().signal)
  await expect(pending).rejects.toBeInstanceOf(MaintenanceAgentTerminationUnconfirmed)
  expect(f.calls).not.toContain('codexCancel')
})
test.each(['gap', 'raw-output', 'budget'])('rejects unsafe or incomplete evidence: %s', async (kind) => {
  const f = fixture(), original = f.host.invoke.bind(f.host)
  f.host.invoke = async (method, args) => {
    if (method === 'codexEvents' && kind !== 'budget') return { ...f.receipt, nextSeq: 3, events: [{ seq: kind === 'gap' ? 2 : 1, kind: 'started', ...(kind === 'raw-output' ? { stderr: 'secret' } : {}) }] }
    return original(method, args)
  }
  await expect(runCodexProbe(() => f.host, { ...request, maxOutputBytes: kind === 'budget' ? 5 : request.maxOutputBytes }, new AbortController().signal)).rejects.toThrow()
  expect(f.calls).toContain('codexCancel')
})
