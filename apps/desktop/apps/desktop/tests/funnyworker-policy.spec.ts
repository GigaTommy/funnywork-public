/** Organization defaults remain below personal preferences and receipts follow runtime application. */
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { afterEach, expect, it, vi } from 'vitest'
import { appliedFunnyWorkerPreferences, funnyWorkerPolicyPatch, parseFunnyWorkerPolicy, prepareFunnyWorkerPolicy, type FunnyWorkerPolicyDocument } from '../src/funnyworker-policy.ts'

const homes: string[] = []
afterEach(async () => { vi.useRealTimers(); await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true }))) })
function policy(version = 1): FunnyWorkerPolicyDocument {
  return { schema: 1, version, digest: String(version).padStart(64, '0'), policy: { constraints: { crossDeviceAccess: true, remoteControl: false }, defaults: { theme: 'dark', locale: 'zh-CN' } } }
}
function settings(theme = 'dark', locale = 'zh-CN', userTheme?: string, userLocale?: string) {
  return { namespaces: [{ ns: 'ui-theme', value: { preference: theme }, user: userTheme === undefined ? {} : { preference: userTheme } }, { ns: 'locale', value: { preference: locale }, user: userLocale === undefined ? {} : { preference: userLocale } }] }
}
async function fixture() {
  const home = await mkdtemp(join(tmpdir(), 'fw-policy-')); homes.push(home)
  await mkdir(join(home, 'model-publication'))
  await writeFile(join(home, 'model-publication/node.id'), 'a'.repeat(32))
  await writeFile(join(home, 'cordis.defaults.yml'), JSON.stringify(funnyWorkerPolicyPatch([], policy())))
  let response: unknown = policy()
  const policyRequest = vi.fn(async (_path: string, body?: object): Promise<unknown> => body === undefined ? response : { state: 'APPLIED', version: response !== undefined ? parseFunnyWorkerPolicy(response).version : 0 })
  vi.useFakeTimers(); vi.setSystemTime(100000)
  return { home, policyRequest, response: (next: unknown) => { response = next } }
}
it('rejects unsupported schemas, unknown constraints and malformed publication metadata', () => {
  expect(parseFunnyWorkerPolicy(policy())).toEqual(policy())
  for (const value of [{ ...policy(), schema: 2 }, { ...policy(), version: -1 }, { ...policy(), digest: 'bad' }, { ...policy(), policy: { ...policy().policy, forcedTheme: 'dark' } }]) expect(() => parseFunnyWorkerPolicy(value)).toThrow()
})
it('keeps other home fields and does not accumulate policy entries on repeated refreshes', async () => {
  const rows = [{ id: 'ui-theme', config: { fontSize: 16, preference: 'light' } }, { id: 'other', config: { enabled: true } }]
  const next = funnyWorkerPolicyPatch(rows, policy())
  expect(funnyWorkerPolicyPatch(next, policy())).toEqual(next)
  await expect(next).toMatchFileSnapshot('./expected/funnyworker-policy-home.json')
  expect(rows[0]?.config.preference).toBe('light')
})
it('acknowledges personal overrides, including custom languages, only when the resolved runtime agrees', () => {
  expect(appliedFunnyWorkerPreferences(policy(), settings())).toEqual({ theme: null, locale: null })
  expect(appliedFunnyWorkerPreferences(policy(), settings('light', 'fr-FR', 'light', 'fr-FR'))).toEqual({ theme: 'light', locale: 'fr-FR' })
  expect(appliedFunnyWorkerPreferences(policy(), settings('dark', 'zh'))).toEqual({ theme: null, locale: null })
  expect(appliedFunnyWorkerPreferences(policy(), settings('light'))).toBeUndefined()
  expect(() => appliedFunnyWorkerPreferences(policy(), { namespaces: [] })).toThrow()
})
it('waits for Host application, then sends the exact issued device and policy receipt', async () => {
  const f = await fixture(), client = await prepareFunnyWorkerPolicy(f.home, f, '0.2.1-alpha.1')
  expect(await client.confirm(async () => settings('light'))).toBe(false)
  expect(f.policyRequest).toHaveBeenCalledTimes(1)
  expect(await client.confirm(async () => settings())).toBe(true)
  await expect(f.policyRequest.mock.calls[1]).toMatchFileSnapshot('./expected/funnyworker-policy-receipt.json')
})
it('serializes refreshes, applies a newer policy atomically and rejects regressions', async () => {
  const f = await fixture(), client = await prepareFunnyWorkerPolicy(f.home, f, '1')
  const next = policy(2); next.policy.defaults.theme = 'light'; f.response(next)
  const inspect = vi.fn(async () => settings('light'))
  expect(await Promise.all([client.refresh(inspect), client.refresh(inspect)])).toEqual([true, true])
  expect(inspect).toHaveBeenCalledTimes(1)
  expect(JSON.parse(await readFile(join(f.home, 'cordis.defaults.yml'), 'utf8'))).toEqual(funnyWorkerPolicyPatch([], next))
  f.response(policy()); vi.setSystemTime(200000)
  await expect(client.refresh(inspect)).rejects.toThrow('version conflict')
  expect(client.document?.version).toBe(2)
})
it('leaves the last policy intact during an outage and rejects missing current policy documents', async () => {
  const f = await fixture(), client = await prepareFunnyWorkerPolicy(f.home, f, '1')
  f.policyRequest.mockRejectedValueOnce(new Error('offline'))
  await expect(client.refresh(async () => settings())).rejects.toThrow('offline')
  expect(client.document?.version).toBe(1)
  f.response(undefined); vi.setSystemTime(200000)
  await expect(client.refresh(async () => settings())).rejects.toThrow('Unsupported organization policy')
  await expect(prepareFunnyWorkerPolicy(f.home, f, '1')).rejects.toThrow('Unsupported organization policy')
})
