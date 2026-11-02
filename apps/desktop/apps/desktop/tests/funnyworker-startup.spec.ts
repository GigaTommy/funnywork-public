import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'
import { prepareFunnyWorkerStartup } from '../src/funnyworker-startup.ts'
const homes: string[] = []
afterEach(async () => { await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true }))) })
const policy = { schema: 1, version: 1, digest: 'a'.repeat(64), policy: {
  constraints: { crossDeviceAccess: true, remoteControl: true }, defaults: { theme: 'system', locale: 'system' },
} }
function configuration() { return { schema: 1, policy, inference: { bootstrap: { enabled: true },
  credential: { token: 'mwi.' + '1'.repeat(24), expires: Date.now() + 600000 },
  catalog: { items: [{ id: 'sol', model: 'gpt-sol', provider_id: 'enterprise-openai' }] },
} } }
async function fixture() {
  const home = await mkdtemp(join(tmpdir(), 'fw-startup-')); homes.push(home)
  const config = configuration()
  const inferenceRequest = vi.fn(async (path: string, _body?: object): Promise<unknown> => {
    if (path === 'bootstrap') return config.inference.bootstrap
    if (path === 'catalog') return config.inference.catalog
    if (path === 'credential') return config.inference.credential
    return { updated: true }
  })
  const startupRequest = vi.fn(async (_body: { modelDevice: string; policyDevice: string }) => config)
  const policyRequest = vi.fn(async () => policy), budget = vi.fn<FunnyWorkerAccount['budget']>()
  const account: FunnyWorkerAccount = { username: 'fixture', dataKey: 'fixture', serverOrigin: 'https://enterprise.example',
    check: async () => true, logout: async () => {}, budget, syncRequest: vi.fn(),
    inferenceRequest, policyRequest, startupRequest,
  }
  return { home, account, config, inferenceRequest, startupRequest, policyRequest, budget }
}
it('uses one authenticated startup response while preserving provider grouping and credential renewal', async () => {
  const f = await fixture(), ready = await prepareFunnyWorkerStartup(f.home, f.account, 'fixture')
  expect(f.startupRequest).toHaveBeenCalledOnce()
  const body = f.startupRequest.mock.calls[0]?.[0]
  expect(body?.modelDevice).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
  expect(body?.policyDevice).toMatch(/^[a-f0-9]{32}$/)
  expect(f.inferenceRequest).not.toHaveBeenCalled()
  expect(f.policyRequest).not.toHaveBeenCalled()
  expect(ready.inference.defaultModel).toEqual({ provider: 'enterprise-openai', model: 'sol' })
  await ready.inference.refresh()
  expect(f.inferenceRequest).toHaveBeenCalledWith('bootstrap')
  await ready.inference.dispose()
  expect(f.budget).not.toHaveBeenCalled()
})
it('starts legacy model and policy requests concurrently and waits for both before returning', async () => {
  const f = await fixture(); f.account.startupRequest = async () => undefined
  let release: (() => void) | undefined
  const barrier = new Promise<void>((resolve) => { release = resolve })
  const original = f.inferenceRequest
  f.account.inferenceRequest = async (path, body) => { if (path === 'bootstrap') await barrier; return original(path, body) }
  let policyStarted = () => {}
  const started = new Promise<void>((resolve) => { policyStarted = resolve })
  f.account.policyRequest = async () => { policyStarted(); return policy }
  const initializing = prepareFunnyWorkerStartup(f.home, f.account, 'fixture')
  await started
  expect(f.inferenceRequest).not.toHaveBeenCalled()
  release?.()
  const ready = await initializing
  expect(ready.policy.document.version).toBe(1)
  await ready.inference.dispose()
})
it('revokes a completed credential when parallel policy validation fails', async () => {
  const f = await fixture(); f.config.policy.schema = 99
  await expect(prepareFunnyWorkerStartup(f.home, f.account, 'fixture')).rejects.toThrow()
  const deviceId = (await readFile(join(f.home, 'model-entry/device.id'), 'utf8')).trim()
  expect(f.inferenceRequest).toHaveBeenCalledWith('credentials/revoke', { deviceId })
  await expect(readFile(join(f.home, 'model-entry/credential.json'))).rejects.toMatchObject({ code: 'ENOENT' })
})
it('does not retry individual endpoints after an authentication or service failure', async () => {
  for (const status of [401, 403, 503]) {
    const f = await fixture(); f.account.startupRequest = async () => { throw new Error(`HTTP ${status}`) }
    await expect(prepareFunnyWorkerStartup(f.home, f.account, 'fixture')).rejects.toThrow(`HTTP ${status}`)
    expect(f.inferenceRequest).not.toHaveBeenCalled()
    expect(f.policyRequest).not.toHaveBeenCalled()
  }
})
it('rejects malformed combined responses and revokes any associated credential', async () => {
  const f = await fixture(); f.account.startupRequest = async () => ({ schema: 1, inference: {} })
  await expect(prepareFunnyWorkerStartup(f.home, f.account, 'fixture')).rejects.toThrow('Invalid combined')
  expect(f.inferenceRequest.mock.calls.map(([path]) => path)).toContain('credentials/revoke')
})

it('revokes a combined credential when its model bootstrap fails validation', async () => {
  const f = await fixture(); f.config.inference.bootstrap.enabled = false
  await expect(prepareFunnyWorkerStartup(f.home, f.account, 'fixture')).rejects.toThrow('Metered model entry')
  expect(f.inferenceRequest.mock.calls.map(([path]) => path)).toContain('credentials/revoke')
})
