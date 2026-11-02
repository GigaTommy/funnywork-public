/** Native intake qualification has no model charges or remote repository actions. */
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { afterEach, expect, test } from 'vitest'
import { runMaintenanceTriage, flushMaintenanceTriage } from '../src/funnyworker-maintenance-triage.ts'
import { MaintenanceAgentTerminationUnconfirmed } from '../src/funnyworker-maintenance-agent.ts'
import type { MaintenanceRuntime } from '../src/funnyworker-maintenance.ts'
const homes: string[] = []
afterEach(async () => { for (const home of homes.splice(0)) await rm(home, { recursive: true, force: true }) })
const payload = { runtimeEpoch: 'runtime', revision: 1, specHash: 'a'.repeat(64), spec: { intakeId: 'source', title: 'Missing example', body: 'Actual, expected and steps', allowedPaths: ['scripts'] } }
async function fixture() {
  const home = await mkdtemp(join(tmpdir(), 'funnywork-intake-')); homes.push(home)
  const calls: { path: string; body?: object }[] = []; let prompts = 0
  const account = { async syncRequest(path: string, body?: object) {
    calls.push({ path, ...(body === undefined ? {} : { body }) }); return { accepted: true }
  } }
  const runtime: MaintenanceRuntime = { async run() { throw new Error('Analysis must not invoke repository tools') }, agent: {
    async ready() {}, async run(request) { prompts++; expect(request.prompt).toContain('untrusted feedback snapshot'); expect(request.prompt).toContain('Do not open or modify a repository')
      await writeFile(join(request.cwd, 'analysis.json'), JSON.stringify({ outcome: 'READY', title: 'Add example', requirement: 'Add the described example', acceptance: 'Exact content', reason: 'Explicit source example', paths: ['scripts/example.json'], duplicateOf: null }))
      return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: 100, outputTokens: 50, cachedInputTokens: 0 } }
    } } }
  return { home, calls, account, runtime, prompts: () => prompts }
}
test('native analysis saves its receipt and repeated assignment runs no second model', async () => {
  const f = await fixture(); await runMaintenanceTriage(f.home, f.account, 'device', 'attempt', payload, new AbortController().signal, f.runtime)
  expect(f.prompts()).toBe(1); expect(f.calls.at(-1)?.path).toBe('/devices/device/maintenance-intake/attempt/result')
  expect(JSON.parse(await readFile(join(f.home, 'maintenance-intake-attempt.json'), 'utf8'))).toMatchObject({ phase: 'SENT' })
  await runMaintenanceTriage(f.home, f.account, 'device', 'attempt', payload, new AbortController().signal, f.runtime); expect(f.prompts()).toBe(1)
})
test('network failure retries only the saved analysis receipt', async () => {
  const f = await fixture(); let failing = true; const account = { async syncRequest(path: string, body?: object) { if (path.endsWith('/result') && failing) throw new Error('offline'); return f.account.syncRequest(path, body) } }
  await expect(runMaintenanceTriage(f.home, account, 'device', 'attempt', payload, new AbortController().signal, f.runtime)).rejects.toThrow('receipt delivery will retry')
  failing = false; await flushMaintenanceTriage(f.home, account, 'device', 'runtime'); expect(f.prompts()).toBe(1)
  expect(JSON.parse(await readFile(join(f.home, 'maintenance-intake-attempt.json'), 'utf8'))).toMatchObject({ phase: 'SENT' })
})
test('unconfirmed native stop retains RUNNING and submits no fabricated analysis', async () => {
  const f = await fixture(); f.runtime.agent!.run = async () => { throw new MaintenanceAgentTerminationUnconfirmed('lost Host') }
  await expect(runMaintenanceTriage(f.home, f.account, 'device', 'attempt', payload, new AbortController().signal, f.runtime)).rejects.toThrow('lost Host')
  expect(f.calls.some(call => call.path.endsWith('/result'))).toBe(false)
  expect(JSON.parse(await readFile(join(f.home, 'maintenance-intake-attempt.json'), 'utf8'))).toMatchObject({ phase: 'RUNNING' })
})
test('invalid native JSON becomes a failed analysis and cannot schedule code', async () => {
  const f = await fixture(); f.runtime.agent!.run = async (request) => { await writeFile(join(request.cwd, 'analysis.json'), '{bad'); return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: null, outputTokens: null, cachedInputTokens: null } } }
  await runMaintenanceTriage(f.home, f.account, 'device', 'attempt', payload, new AbortController().signal, f.runtime)
  expect(f.calls.at(-1)?.body).toMatchObject({ analysis: { outcome: 'RUNTIME_FAILED' } })
})
test('an earlier runtime receipt is kept for review without resubmission', async () => {
  const f = await fixture(); await writeFile(join(f.home, 'maintenance-intake-attempt.json'), JSON.stringify({ phase: 'READY', runtimeEpoch: 'old', receipt: {} }))
  await flushMaintenanceTriage(f.home, f.account, 'device', 'new'); expect(f.calls).toEqual([])
  expect(JSON.parse(await readFile(join(f.home, 'maintenance-intake-attempt.json'), 'utf8'))).toMatchObject({ phase: 'STALE' })
})
