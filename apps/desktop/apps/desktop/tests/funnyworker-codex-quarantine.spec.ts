/** An uncertain readiness probe withdraws every task advertisement in the same state exchange. */
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, test, vi } from 'vitest'
import { startHistorySync, type HistorySyncDisposer } from '../src/funnyworker-history-sync.ts'
import { maintenanceRuntime } from '../src/funnyworker-maintenance.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'

test('unconfirmed Codex readiness retracts native and annual advertisements before dispatch', async () => {
  vi.useFakeTimers()
  const home = await mkdtemp(join(tmpdir(), 'codex-quarantine-'))
  const commands = vi.spyOn(maintenanceRuntime, 'run').mockResolvedValue({ exitCode: 0, output: 'git version 2.50.0' })
  const publications: Record<string, unknown>[] = []
  const invoke = vi.fn(async (method: string): Promise<unknown> => {
    if (method === 'modelCatalog') return { default: { provider: 'native', model: 'deepseek' }, routableProviders: ['native'] }
    if (method === 'codexCapabilities') return { available: false, processStopped: false }
    return {}
  })
  const host = { invoke }
  const account: FunnyWorkerAccount = {
    username: 'user', dataKey: 'key', budget: async () => { throw new Error('Unused') }, check: async () => true,
    logout: async () => undefined, policyRequest: async () => undefined,
    syncRequest: async (path, body) => {
      if (path === '/maintenance/features') return { executor: 'funnywork-native-v1', annualReport: 'annual-report.v1', syncCheck: { executor: 'funnywork-codex-v1', protocolVersion: 1 } }
      if (path.endsWith('/state') && body !== undefined) {
        const state = body as Record<string, unknown>
        publications.push(state)
        return [{ id: 'native-task', nativeId: '', kind: 'maintenance', payload: { runtimeEpoch: state.epoch } }]
      }
      return {}
    },
  }
  let stop: HistorySyncDisposer | undefined
  try {
    stop = await startHistorySync(account, home, () => host, () => {}, {}, '1.2.3')
    await vi.waitFor(() => { expect(publications.length).toBeGreaterThan(0) })
    expect(publications[0]?.choices).not.toHaveProperty('maintenance')
    expect(publications[0]?.choices).not.toHaveProperty('annualReport')
    expect(publications[0]?.choices).not.toHaveProperty('maintenanceExecutors')
    expect(invoke.mock.calls.filter(([method]) => method === 'codexCapabilities')).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(32000)
    expect(invoke.mock.calls.filter(([method]) => method === 'codexCapabilities')).toHaveLength(1)
    expect(invoke.mock.calls.some(([method]) => method === 'prompt')).toBe(false)
  } finally {
    await stop?.(); commands.mockRestore(); vi.useRealTimers(); await rm(home, { recursive: true, force: true })
  }
})
