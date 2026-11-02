import { afterEach, expect, it, vi } from 'vitest'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { prepareFunnyWorkerInference } from '../src/funnyworker-inference.ts'
import { prepareFunnyWorkerPolicy } from '../src/funnyworker-policy.ts'
import { ModelEntryHttpError } from '../src/funnyworker-inference-error.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'

const homes: string[] = []
afterEach(async () => {
  vi.useRealTimers()
  for (const home of homes.splice(0)) await rm(home, { recursive: true, force: true })
})
async function home() {
  const result = await mkdtemp(join(tmpdir(), 'fw-inference-'))
  homes.push(result)
  return result
}
function account() {
  let enabled = true,
    issued = 0,
    offline = false,
    grouped = false
  const calls: Array<{ path: string; body?: object }> = []
  const api: FunnyWorkerAccount = {
    username: 'member',
    dataKey: 'account',
    serverOrigin: 'https://enterprise.example',
    budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true,
    logout: async () => {},
    policyRequest: async () => ({ schema: 1, version: 1, digest: 'a'.repeat(64), policy: {
      constraints: { crossDeviceAccess: true, remoteControl: true }, defaults: { theme: 'system', locale: 'system' },
    } }),
    syncRequest: async () => undefined,
    inferenceRequest: async (path, body) => {
      calls.push({ path, ...(body === undefined ? {} : { body }) })
      if (offline) throw new ModelEntryHttpError(503)
      if (path === 'bootstrap') return { enabled }
      if (path === 'credential') {
        issued++
        return { token: 'mwi.' + String(issued).repeat(24), expires: Date.now() + 600000 }
      }
      if (path === 'catalog') return { items: [{ id: 'private-qwen', model: 'qwen', ...(grouped ? { provider_id: 'enterprise-alibaba' } : {}) }] }
      return { updated: true }
    },
  }
  return {
    api,
    calls,
    setGrouped() { grouped = true },
    setEnabled(value: boolean) {
      enabled = value
    },
    setOffline(value: boolean) {
      offline = value
    },
    issued: () => issued,
  }
}
it('provisions scoped credentials and policy identity without acquiring a public key', async () => {
  const root = await home(),
    fixture = account(),
    entry = await prepareFunnyWorkerInference(root, fixture.api)
  expect(entry?.defaultModel).toEqual({ provider: 'enterprise-private-qwen', model: 'qwen' })
  const raw: unknown = JSON.parse(await readFile(entry.plugin.credentialFile, 'utf8'))
  expect(raw).toMatchObject({ origin: 'https://enterprise.example', token: 'mwi.' + '1'.repeat(24) })
  if (process.platform !== 'win32') expect((await stat(entry.plugin.credentialFile)).mode & 0o777).toBe(0o600)
  await prepareFunnyWorkerPolicy(root, fixture.api, 'test')
  expect(await readFile(join(root, 'model-publication', 'node.id'), 'utf8')).toMatch(/^[a-f0-9]{32}$/)
  await entry.dispose()
  expect(fixture.calls.at(-1)?.path).toBe('credentials/revoke')
  await expect(readFile(entry.plugin.credentialFile)).rejects.toMatchObject({ code: 'ENOENT' })
})
it('rejects disabled metered inference without selecting another model protocol', async () => {
  const fixture = account()
  fixture.setEnabled(false)
  await expect(prepareFunnyWorkerInference(await home(), fixture.api)).rejects.toThrow('Metered model entry must be enabled')
  expect(fixture.issued()).toBe(0)
})
it('never falls back to public models after enterprise mode has been entered', async () => {
  const root = await home(),
    fixture = account(),
    entry = await prepareFunnyWorkerInference(root, fixture.api)
  await entry.dispose()
  fixture.setEnabled(false)
  await expect(prepareFunnyWorkerInference(root, fixture.api)).rejects.toThrow('Metered model entry must be enabled')
})
it('serializes renewal and preserves the device identity across token rotation', async () => {
  vi.useFakeTimers()
  const root = await home(),
    fixture = account(),
    entry = await prepareFunnyWorkerInference(root, fixture.api)
  vi.setSystemTime(Date.now() + 480001)
  await Promise.all([entry.refresh(), entry.refresh()])
  expect(fixture.issued()).toBe(2)
  const bodies = fixture.calls.filter(call => call.path === 'credential').map(call => call.body)
  expect(bodies[0]).toEqual(bodies[1])
  expect(await readFile(entry.plugin.credentialFile, 'utf8')).toContain('mwi.' + '2'.repeat(24))
  await entry.dispose()
})
it('tolerates a transient outage until the current credential expires', async () => {
  vi.useFakeTimers()
  const fixture = account(),
    entry = await prepareFunnyWorkerInference(await home(), fixture.api)
  fixture.setOffline(true)
  await expect(entry.refresh()).resolves.toBeUndefined()
  vi.setSystemTime(Date.now() + 600001)
  await expect(entry.refresh()).rejects.toBeInstanceOf(ModelEntryHttpError)
  await entry.dispose()
})
it('stops using the entry when its authenticated bootstrap is disabled', async () => {
  const fixture = account(),
    entry = await prepareFunnyWorkerInference(await home(), fixture.api)
  fixture.setEnabled(false)
  await expect(entry.refresh()).rejects.toThrow('was disabled')
  await entry.dispose()
})
it('rejects an absent bootstrap instead of selecting the signed publication path', async () => {
  const fixture = account()
  fixture.api.inferenceRequest = async () => undefined
  await expect(prepareFunnyWorkerInference(await home(), fixture.api)).rejects.toThrow('Invalid enterprise model bootstrap')
  expect(fixture.issued()).toBe(0)
})

it('selects the provider and deployment identity from a maker-grouped catalog', async () => {
  const fixture = account();fixture.setGrouped()
  const entry = await prepareFunnyWorkerInference(await home(), fixture.api)
  expect(entry.defaultModel).toEqual({ provider: 'enterprise-alibaba', model: 'private-qwen' })
  await entry.dispose()
})
