import { beforeEach as configureRegionalEntry, afterEach as restoreRegionalEntry } from 'vitest'
import { routeFixture } from './funnyworker-route-fixture.ts'
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import { HistorySyncHttpError } from '../src/funnyworker-sync-error.ts'
import { ModelEntryHttpError } from '../src/funnyworker-inference-error.ts'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const native = vi.hoisted(() => ({
  showMessageBox: vi.fn<(options: Electron.MessageBoxOptions) => Promise<Electron.MessageBoxReturnValue>>(),
  isEncryptionAvailable: vi.fn(() => true),
  decryptStringAsync: vi.fn(async (bytes: Buffer) => ({ result: bytes.toString(), shouldReEncrypt: false })),
  getSelectedStorageBackend: vi.fn<() => ReturnType<typeof import('electron').safeStorage.getSelectedStorageBackend>>(() => 'gnome_libsecret'),
}))
vi.mock('electron', () => ({ app: { getLocale: () => 'en' }, dialog: { showMessageBox: native.showMessageBox }, safeStorage: {
  decryptStringAsync: native.decryptStringAsync,
  isEncryptionAvailable: native.isEncryptionAvailable, getSelectedStorageBackend: native.getSelectedStorageBackend,
  decryptString: (bytes: Buffer) => bytes.toString(), encryptString: (text: string) => Buffer.from(text),
} }))
vi.mock('../src/welcome-window.ts', () => ({ openWelcomeWindow: () => { throw new Error('Unexpected login window') } }))
import { authenticateFunnyWorker, FunnyWorkerAccountProtocolError, FunnyWorkerLoginCancelled, parseModelBudget } from '../src/funnyworker-account.ts'
const homes: string[] = []
afterEach(async () => { vi.unstubAllGlobals(); await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true }))) })
beforeEach(() => {
  native.showMessageBox.mockReset().mockResolvedValue({ response: 1, checkboxChecked: false })
  native.isEncryptionAvailable.mockReset().mockReturnValue(true)
  native.decryptStringAsync.mockReset().mockImplementation(async (bytes: Buffer) => ({ result: bytes.toString(), shouldReEncrypt: false }))
  native.getSelectedStorageBackend.mockReset().mockReturnValue('gnome_libsecret')
})

it.each(['gnome_libsecret', 'kwallet', 'kwallet5', 'kwallet6'] as const)('uses the secure Linux %s backend for a saved account', async (backend) => {
  vi.stubGlobal('process', { ...process, platform: 'linux' })
  native.getSelectedStorageBackend.mockReturnValue(backend)
  const home = await mkdtemp(join(tmpdir(), 'fw-secure-account-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  expect((await authenticateFunnyWorker(home)).username).toBe('name')
})

it.each(['basic_text', 'unknown', 'locked'] as const)('rejects Linux %s storage before login or saved credentials are used', async (backend) => {
  vi.stubGlobal('process', { ...process, platform: 'linux' })
  if (backend === 'locked') native.isEncryptionAvailable.mockReturnValue(false)
  else native.getSelectedStorageBackend.mockReturnValue(backend)
  const home = await mkdtemp(join(tmpdir(), 'fw-unavailable-account-')); homes.push(home)
  const saved = JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' })
  await writeFile(join(home, 'funnyworker-account.enc'), saved)
  const request = vi.fn(); vi.stubGlobal('fetch', routeFixture(request))
  await expect(authenticateFunnyWorker(home)).rejects.toThrow('secure account storage is unavailable')
  expect(request).not.toHaveBeenCalled()
  expect(await readFile(join(home, 'funnyworker-account.enc'), 'utf8')).toBe(saved)
})

it.each(['darwin', 'win32'] as const)('uses native secure storage on %s without querying a Linux backend', async (platform) => {
  vi.stubGlobal('process', { ...process, platform })
  const home = await mkdtemp(join(tmpdir(), 'fw-native-account-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  expect((await authenticateFunnyWorker(home)).username).toBe('name')
  expect(native.getSelectedStorageBackend).not.toHaveBeenCalled()
})

it('keeps the existing directory after an account rename and refuses identity reassignment', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'test-session' }))
  let username = 'old-name'
  let principalId = 'owner'
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username, tenant: 'tenant', principalId }))))
  const original = await authenticateFunnyWorker(home)
  username = 'new-name'
  const renamed = await authenticateFunnyWorker(home)
  expect(renamed.username).toBe('new-name')
  expect(renamed.dataKey).toBe(original.dataKey)
  principalId = 'someone-else'
  expect(await renamed.check()).toBe(false)
})

it('adopts the mainland account mapping without moving history or requiring a new login', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-official-alias-')); homes.push(home)
  const mainland = 'https://regional.example.invalid'
  const canonical = 'https://funnywork.funnypixelsapp.com'
  const key = (origin: string) => createHash('sha256').update(JSON.stringify([origin, 'tenant', 'owner'])).digest('hex')
  const legacy = key(mainland)
  await mkdir(join(home, 'accounts', legacy), { recursive: true })
  await writeFile(join(home, 'accounts', legacy, 'history-marker'), 'existing history')
  await writeFile(join(home, 'funnyworker-backend.json'), JSON.stringify({ url: mainland }))
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: mainland, token: 'fixture-only' }))
  await writeFile(join(home, 'funnyworker-account-directories.json'), JSON.stringify({ [legacy]: legacy }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  const account = await authenticateFunnyWorker(home)
  expect(account.dataKey).toBe(legacy)
  expect(account.serverOrigin).toBe(canonical)
  expect(await readFile(join(home, 'accounts', legacy, 'history-marker'), 'utf8')).toBe('existing history')
  expect(JSON.parse(await readFile(join(home, 'funnyworker-account-directories.json'), 'utf8'))).toEqual({ [key(canonical)]: legacy })
})

it('retains saved credentials when every official entry is temporarily unreachable', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-official-offline-')); homes.push(home)
  const saved = JSON.stringify({ origin: 'https://regional.example.invalid', token: 'fixture-only' })
  await writeFile(join(home, 'funnyworker-account.enc'), saved)
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Offline') }))
  await expect(authenticateFunnyWorker(home)).rejects.toBeInstanceOf(AggregateError)
  expect(await readFile(join(home, 'funnyworker-account.enc'), 'utf8')).toBe(saved)
})

it('refuses to merge distinct official histories and leaves their mappings unchanged', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-history-conflict-')); homes.push(home)
  const key = (origin: string) => createHash('sha256').update(JSON.stringify([origin, 'tenant', 'owner'])).digest('hex')
  const canonical = 'https://funnywork.funnypixelsapp.com'
  const alias = 'https://regional.example.invalid'
  const mapping = JSON.stringify({ [key(canonical)]: key(canonical), [key(alias)]: key(alias) })
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: canonical, token: 'fixture-only' }))
  await writeFile(join(home, 'funnyworker-account-directories.json'), mapping)
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  await expect(authenticateFunnyWorker(home)).rejects.toThrow('两份独立本地历史')
  expect(await readFile(join(home, 'funnyworker-account-directories.json'), 'utf8')).toBe(mapping)
})

it('offers ownership-confirmed recovery for a mainland username-era history directory', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-mainland-legacy-')); homes.push(home)
  const legacy = createHash('sha256').update(JSON.stringify(['https://regional.example.invalid', 'tenant', 'old-name'])).digest('hex')
  await mkdir(join(home, 'accounts', legacy), { recursive: true })
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://regional.example.invalid', token: 'fixture-only' }))
  native.showMessageBox.mockResolvedValue({ response: 0, checkboxChecked: true })
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'old-name', tenant: 'tenant', principalId: 'owner' }))))
  expect((await authenticateFunnyWorker(home)).dataKey).toBe(legacy)
  expect(native.showMessageBox).toHaveBeenCalledOnce()
})

it.each([
  '/sessions/../../logout', '/sessions/device/heartbeat', '/devices/device/heartbeats',
  '/devices/device/heartbeat/extra', '/devices/../heartbeat', '/devices/device%2Fother/heartbeat',
  '/devices/device/heartbeat?extra=true', `/devices/${'a'.repeat(65)}/heartbeat`,
])('does not send an arbitrary path through authenticated synchronization: %s', async (path) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'test-session' }))
  const send = vi.fn(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  await expect(account.syncRequest(path, {})).rejects.toThrow('Invalid sync path')
  expect(send).not.toHaveBeenCalled()
})

it('posts device heartbeats with the signed-in account and preserves server ownership rejection', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-heartbeat-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  const send = vi.fn<typeof fetch>(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  await expect(account.syncRequest('/devices/device/heartbeat')).rejects.toThrow('Invalid sync path')
  expect(send).not.toHaveBeenCalled()
  const body = { epoch: 'runtime-epoch', sessions: {}, workspaces: [], choices: { models: [], presets: [] } }
  send.mockResolvedValueOnce(Response.json({ ok: true }))
  expect(await account.syncRequest('/devices/device/heartbeat', body)).toEqual({ ok: true })
  const signal = send.mock.calls[0]?.[1]?.signal
  expect(signal).toBeInstanceOf(AbortSignal)
  expect(send).toHaveBeenCalledExactlyOnceWith(`${origin}/api/account/sync/devices/device/heartbeat`, {
    method: 'POST', redirect: 'error', signal,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer fixture-only' }, body: JSON.stringify(body),
  })
  send.mockClear().mockResolvedValueOnce(new Response('', { status: 403 }))
  await expect(account.syncRequest('/devices/other-device/heartbeat', body)).rejects.toEqual(new HistorySyncHttpError(403))
  expect(send).toHaveBeenCalledTimes(1)
})

it.each([false, true])('isolates reused usernames regardless of existing username directories=%s', async (existing) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-reuse-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  const legacyKey = createHash('sha256').update(JSON.stringify([origin, 'tenant', 'same-name'])).digest('hex')
  if (existing) await mkdir(join(home, 'accounts', legacyKey), { recursive: true })
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  let principalId = 'original-owner'
  let username = 'same-name'
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username, tenant: 'tenant', principalId }))))
  if (existing) {
    await expect(authenticateFunnyWorker(home)).rejects.toBeInstanceOf(FunnyWorkerLoginCancelled)
    await expect(readFile(join(home, 'funnyworker-account-directories.json'))).rejects.toMatchObject({ code: 'ENOENT' })
    native.showMessageBox.mockResolvedValue({ response: 0, checkboxChecked: true })
  }
  const original = await authenticateFunnyWorker(home)
  expect(original.dataKey).toBe(existing ? legacyKey : createHash('sha256').update(JSON.stringify([origin, 'tenant', principalId])).digest('hex'))
  username = 'renamed'
  expect((await authenticateFunnyWorker(home)).dataKey).toBe(original.dataKey)
  principalId = 'new-owner'; username = 'same-name'
  const confirmations = native.showMessageBox.mock.calls.length
  const next = await authenticateFunnyWorker(home)
  expect(native.showMessageBox).toHaveBeenCalledTimes(confirmations)
  expect(next.dataKey).not.toBe(original.dataKey)
  expect(next.dataKey).toBe(createHash('sha256').update(JSON.stringify([origin, 'tenant', principalId])).digest('hex'))
})

it('restores existing history and settings in place only once after confirmed ownership', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-restore-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  const priorKey = createHash('sha256').update(JSON.stringify([origin, 'tenant', 'name'])).digest('hex')
  const directory = join(home, 'accounts', priorKey)
  await mkdir(join(directory, 'storages'), { recursive: true })
  await writeFile(join(directory, 'storages', 'workspace.json'), '{"history":"retained"}')
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  native.showMessageBox.mockResolvedValue({ response: 0, checkboxChecked: true })
  const account = await authenticateFunnyWorker(home)
  expect(account.dataKey).toBe(priorKey)
  expect(await readFile(join(directory, 'storages', 'workspace.json'), 'utf8')).toBe('{"history":"retained"}')
  expect(await authenticateFunnyWorker(home)).toHaveProperty('dataKey', priorKey)
  expect(native.showMessageBox).toHaveBeenCalledOnce()
  await expect(JSON.stringify(native.showMessageBox.mock.calls[0]![0], null, 2) + '\n').toMatchFileSnapshot('./expected/account-workspace-recovery.json')
})

it.each(['unchecked', 'changed-identity', 'changed-mapping'])('does not bind an existing directory after %s recovery', async (failure) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-restore-refused-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  const priorKey = createHash('sha256').update(JSON.stringify([origin, 'tenant', 'name'])).digest('hex')
  await mkdir(join(home, 'accounts', priorKey), { recursive: true })
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  let principalId = 'owner'
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId }))))
  native.showMessageBox.mockImplementation(async () => {
    if (failure === 'changed-identity') principalId = 'different-owner'
    if (failure === 'changed-mapping') await writeFile(join(home, 'funnyworker-account-directories.json'), JSON.stringify({ preserved: 'b'.repeat(64) }))
    return { response: 0, checkboxChecked: failure !== 'unchecked' }
  })
  if (failure === 'changed-mapping') {
    await expect(authenticateFunnyWorker(home)).rejects.toThrow('mapping changed')
    expect(JSON.parse(await readFile(join(home, 'funnyworker-account-directories.json'), 'utf8'))).toEqual({ preserved: 'b'.repeat(64) })
  } else {
    await expect(authenticateFunnyWorker(home)).rejects.toBeInstanceOf(failure === 'unchecked' ? FunnyWorkerLoginCancelled : FunnyWorkerAccountProtocolError)
    await expect(readFile(join(home, 'funnyworker-account-directories.json'))).rejects.toMatchObject({ code: 'ENOENT' })
  }
})

it.each([404, 410, 'invalid-json'])('classifies a broken session response as a protocol error: %s', async (response) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-broken-session-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => typeof response === 'number' ? new Response('', { status: response }) : new Response('not-json'))))
  await expect(authenticateFunnyWorker(home)).rejects.toBeInstanceOf(FunnyWorkerAccountProtocolError)
  expect(native.showMessageBox).not.toHaveBeenCalled()
})

it('refuses a previously ambiguous directory mapping', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-ambiguous-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  const first = createHash('sha256').update(JSON.stringify([origin, 'tenant', 'owner'])).digest('hex')
  const second = createHash('sha256').update(JSON.stringify([origin, 'tenant', 'other'])).digest('hex')
  await writeFile(join(home, 'funnyworker-account-directories.json'), JSON.stringify({ [first]: 'a'.repeat(64), [second]: 'a'.repeat(64) }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  await expect(authenticateFunnyWorker(home)).rejects.toThrow('multiple identities')
})

it('requires the current principal field even when the login username is available', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-current-principal-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  const account = await authenticateFunnyWorker(home)
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant' }))))
  await expect(account.check()).rejects.toThrow('principal is invalid')
})

it('preserves the HTTP status of a rejected synchronization request', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-http-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  const account = await authenticateFunnyWorker(home)
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => new Response('', { status: 409 }))))
  await expect(account.syncRequest('/devices/device/commands/request', {})).rejects.toEqual(new HistorySyncHttpError(409))
})

it.each([new TypeError('offline'), new DOMException('timeout', 'TimeoutError')])('clears local credentials after remote logout fails: %s', async (failure) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-logout-')); homes.push(home)
  const storage = join(home, 'funnyworker-account.enc')
  await writeFile(storage, JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  const send = vi.fn(async (_url: string, _options: RequestInit = {}) => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockRejectedValueOnce(failure)
  await expect(account.logout()).rejects.toBe(failure)
  await expect(readFile(storage)).rejects.toMatchObject({ code: 'ENOENT' })
  await account.check()
  expect(send.mock.lastCall?.[1]?.headers).not.toHaveProperty('Authorization')
})

it('limits policy transport to the device fetch and receipt and rejects missing APIs', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-policy-account-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'test-session' }))
  const send = vi.fn(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  await expect(account.policyRequest('policy/../../logout')).rejects.toThrow('Invalid policy path')
  await expect(account.policyRequest('policy/receipt')).rejects.toThrow('Invalid policy path')
  expect(send).not.toHaveBeenCalled()
  send.mockResolvedValueOnce(new Response('', { status: 404 }))
  await expect(account.policyRequest(`policy?device=${'a'.repeat(32)}`)).rejects.toThrow('unavailable')
  send.mockResolvedValueOnce(new Response('', { status: 503 })).mockResolvedValueOnce(new Response('', { status: 503 }))
  await expect(account.policyRequest(`policy?device=${'a'.repeat(32)}`)).rejects.toThrow('unavailable')
  send.mockResolvedValueOnce(new Response('', { status: 404 }))
  await expect(account.inferenceRequest!('bootstrap')).rejects.toEqual(new ModelEntryHttpError(404))
})

const walletWire = { configured: true, enabled: true, remaining_micros: 1000000, held: 200000, daily_limit: 1000000, daily_spent: 300000, expires_at: 1800000000000, promotion: { mode: 'DAILY', available_micros: 1000000 } }
it('reads and claims only fixed account wallet routes with main-process authentication', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-wallet-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'private-session' }))
  vi.stubGlobal('fetch', routeFixture(vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))))
  const account = await authenticateFunnyWorker(home)
  const send = vi.fn(async (url: string) => Response.json(url.endsWith('/claim') ? { credited_micros: 1000000, budget: walletWire } : walletWire))
  vi.stubGlobal('fetch', routeFixture(send))
  expect(await account.budget(false)).toMatchObject({ remainingMicros: 1000000, dailyRemainingMicros: 500000 })
  const claimed = await account.budget(true)
  expect(claimed.creditedMicros).toBe(1000000)
  expect(JSON.stringify(claimed)).not.toContain('private-session')
  expect(send.mock.calls.map(call => call[0])).toEqual(['https://funnywork.funnypixelsapp.com/api/account/inference/budget', 'https://funnywork.funnypixelsapp.com/api/account/inference/budget/claim'])
})
it.each([-1, 0.5, Number.MAX_SAFE_INTEGER + 1, '1000000'])('rejects malformed allowance money %s', (amount) => {
  expect(() => parseModelBudget({ ...walletWire, remaining_micros: amount }, false)).toThrow('Invalid model budget amount')
})

it('uses the original account transport for exact native maintenance routes only', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-native-channel-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'test-session' }))
  const send = vi.fn(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  await account.syncRequest('/devices/original/annual-report/attempt-id/candidate', { runtimeEpoch: 'runtime', epoch: 1, candidateJson: '{}' })
  await account.syncRequest('/devices/original/annual-report/attempt-id/failure', { runtimeEpoch: 'runtime', epoch: 1 })
  await account.syncRequest('/maintenance/features')
  await account.syncRequest('/feedback/repositories')
  await account.syncRequest('/feedback', { requestId: 'feedback-id' })
  await account.syncRequest('/devices/original/maintenance-intake/intake-id/renew', { runtimeEpoch: 'runtime', revision: 1 })
  await account.syncRequest('/devices/original/maintenance-intake/intake-id/result', { runtimeEpoch: 'runtime', revision: 1, analysis: {} })
  await account.syncRequest('/devices/original/maintenance/attempt-id/renew', { runtimeEpoch: 'runtime', epoch: 1 })
  await account.syncRequest('/devices/original/maintenance/attempt-id/result', { runtimeEpoch: 'runtime', report: {} })
  expect(send.mock.calls).toHaveLength(9)
  for (const path of ['/devices/original/annual-report/attempt-id/publish', '/devices/original/annual-report/../candidate', '/maintenance/../../logout', '/devices/original/maintenance/attempt-id/other', '/devices/other/maintenance/../result'])
    await expect(account.syncRequest(path)).rejects.toThrow('Invalid sync path')
  expect(send.mock.calls).toHaveLength(9)
})


it.each(['renew', 'result', 'stopped'])('posts the fixed Codex %s endpoint through the authenticated transport', async (action) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-codex-')); homes.push(home)
  const origin = 'https://funnywork.funnypixelsapp.com'
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin, token: 'fixture-only' }))
  const send = vi.fn<typeof fetch>(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  const path = `/devices/Device_0-1/maintenance-sync-checks/Target_0-1/${action}`
  await expect(account.syncRequest(path)).rejects.toThrow('Invalid sync path')
  expect(send).not.toHaveBeenCalled()
  const body = { runtimeEpoch: 'epoch', specHash: 'a'.repeat(64), evidenceDigest: 'b'.repeat(64) }
  send.mockResolvedValueOnce(Response.json({ ok: true }))
  expect(await account.syncRequest(path, body)).toEqual({ ok: true })
  const signal = send.mock.calls[0]?.[1]?.signal
  expect(signal).toBeInstanceOf(AbortSignal)
  expect(send).toHaveBeenCalledExactlyOnceWith(`${origin}/api/account/sync${path}`, {
    method: 'POST', redirect: 'error', signal,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer fixture-only' }, body: JSON.stringify(body),
  })
  for (const status of [401, 403, 409]) {
    send.mockClear().mockResolvedValueOnce(new Response('', { status }))
    await expect(account.syncRequest(path, body)).rejects.toEqual(new HistorySyncHttpError(status))
    expect(send).toHaveBeenCalledTimes(1)
  }
  send.mockClear().mockResolvedValueOnce(Response.json({ ok: true }))
  await account.syncRequest(`/devices/${'D'.repeat(64)}/maintenance-sync-checks/${'T'.repeat(64)}/${action}`, body)
  expect(send).toHaveBeenCalledTimes(1)
})

it.each([
  ...['', '.', '..', 'a/b', 'a\\b', '%2e%2e', 'a%2Fb', 'a%252Fb', 'a%5Cb', 'a?b', 'a#b', 'a b', 'é', 'a'.repeat(65)]
    .flatMap(id => [`/devices/${id}/maintenance-sync-checks/target/renew`, `/devices/device/maintenance-sync-checks/${id}/result`]),
  ...['renew', 'result', 'stopped'].flatMap(action => [
    `/devices/device/maintenance-sync-checks/target/${action}/extra`,
    `/devices/device/maintenance-sync-checks/target/${action}/`,
    `/devices/device/maintenance-sync-checks/target/${action}?epoch=valid`,
    `/devices/device/maintenance-sync-checks/target/${action}#fragment`,
    `/devices/device/maintenance-sync-checks/target/${action}\n`,
    `/devices/device/maintenance-sync-checks/target/${action}\r\n`,
  ]),
  '/devices/device/maintenance-sync-checks/target/other',
  '/devices/device/maintenance-sync-checks/target/RENEW',
  '/devices/device/maintenance-sync-checks/target/%72enew',
  '/devices/device/maintenance-sync-checks/target',
  '/maintenance-sync-checks', '/maintenance/sync-checks',
  '/maintenance/sync-checks/target', '/maintenance/sync-checks/devices/device/capabilities',
  '/devices/device/maintenance/target/stopped',
])('rejects malformed or unrelated Codex transport paths before fetch: %s', async (path) => {
  const home = await mkdtemp(join(tmpdir(), 'fw-account-codex-reject-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  const send = vi.fn(async () => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  await expect(account.syncRequest(path, {})).rejects.toThrow('Invalid sync path')
  expect(send).not.toHaveBeenCalled()
})


it('restores macOS ciphertext without synchronously blocking the main thread or rewriting it', async () => {
  vi.stubGlobal('process', { ...process, platform: 'darwin' })
  const home = await mkdtemp(join(tmpdir(), 'fw-keychain-async-')); homes.push(home)
  const saved = JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' })
  await writeFile(join(home, 'funnyworker-account.enc'), saved)
  native.isEncryptionAvailable.mockImplementation(() => { throw new Error('Synchronous keychain access blocks startup') })
  let unlock: (value: { result: string; shouldReEncrypt: boolean }) => void = () => { throw new Error('Unlock not requested') }
  native.decryptStringAsync.mockImplementation(() => new Promise((resolve) => { unlock = resolve }))
  const send = vi.fn(async () => Response.json({ username: 'name', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const restoring = authenticateFunnyWorker(home)
  void restoring.catch(() => undefined)
  await vi.waitFor(() => { expect(native.decryptStringAsync).toHaveBeenCalledOnce() })
  await new Promise<void>(resolve => setImmediate(resolve))
  expect(send).not.toHaveBeenCalled()
  expect(await readFile(join(home, 'funnyworker-account.enc'), 'utf8')).toBe(saved)
  unlock({ result: saved, shouldReEncrypt: true })
  expect((await restoring).principalId).toBe('owner')
  expect(native.isEncryptionAvailable).not.toHaveBeenCalled()
  expect(await readFile(join(home, 'funnyworker-account.enc'), 'utf8')).toBe(saved)
})

it('keeps saved macOS credentials when asynchronous keychain access is temporarily unavailable', async () => {
  vi.stubGlobal('process', { ...process, platform: 'darwin' })
  const home = await mkdtemp(join(tmpdir(), 'fw-keychain-unavailable-')); homes.push(home)
  const saved = JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' })
  await writeFile(join(home, 'funnyworker-account.enc'), saved)
  native.decryptStringAsync.mockRejectedValue(new Error('Keychain temporarily unavailable'))
  const send = vi.fn(); vi.stubGlobal('fetch', routeFixture(send))
  await expect(authenticateFunnyWorker(home)).rejects.toThrow('Keychain temporarily unavailable')
  expect(send).not.toHaveBeenCalled()
  expect(await readFile(join(home, 'funnyworker-account.enc'), 'utf8')).toBe(saved)
})


it('admits feedback pagination and POST supplements while rejecting forged feedback routes', async () => {
  const home = await mkdtemp(join(tmpdir(), 'fw-feedback-channel-')); homes.push(home)
  await writeFile(join(home, 'funnyworker-account.enc'), JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'test-session' }))
  const send = vi.fn(async (_url: string, _init?: RequestInit) => Response.json({ username: 'user', tenant: 'tenant', principalId: 'owner' }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = await authenticateFunnyWorker(home)
  send.mockClear()
  for (const path of ['/feedback?after=&limit=20', '/feedback?after=intake-2&limit=100', '/feedback/intake-2']) {
    await account.syncRequest(path)
    expect(send.mock.lastCall?.[0]).toBe('https://funnywork.funnypixelsapp.com/api/account/sync' + path)
    expect(send.mock.lastCall?.[1]?.method).toBe('GET')
  }
  const body = { requestId: 'supplement-id', content: 'Additional reproduction steps' }
  await account.syncRequest('/feedback/intake-2/supplements', body)
  expect(send.mock.lastCall?.[1]?.method).toBe('POST')
  expect(send.mock.lastCall?.[1]?.body).toBe(JSON.stringify(body))
  const admitted = send.mock.calls.length
  for (const path of ['/feedback?after=&limit=101', '/feedback?after=&limit=0', '/feedback?after=%2F&limit=20',
    '/feedback?after=&limit=20&admin=true', '/feedback/../logout', '/feedback/intake-2/supplements',
    '/feedback/intake-2/supplements/extra', '/feedback?after=&limit=20\n']) {
    await expect(account.syncRequest(path)).rejects.toThrow('Invalid sync path')
  }
  for (const path of ['/feedback/repositories', '/feedback/intake-2', '/feedback?after=&limit=20']) {
    await expect(account.syncRequest(path, {})).rejects.toThrow('Invalid sync path')
  }
  expect(send.mock.calls).toHaveLength(admitted)
})

configureRegionalEntry(() => { vi.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', 'https://regional.example.invalid') })
restoreRegionalEntry(() => { vi.unstubAllEnvs() })
