import { routeFixture } from './funnyworker-route-fixture.ts'
import { createHash } from 'node:crypto'
import { EventEmitter } from 'node:events'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { WelcomeOperations as WelcomeHandlers } from '../src/welcome-api.ts'

const mocks = vi.hoisted(() => ({ open: vi.fn(), quit: vi.fn(), getPath: vi.fn(() => 'test-device'), isEncryptionAvailable: vi.fn(() => true),
  getSelectedStorageBackend: vi.fn<() => ReturnType<typeof import('electron').safeStorage.getSelectedStorageBackend>>(() => 'gnome_libsecret'),
  encryptString: vi.fn((text: string) => Buffer.from(text)), decryptString: vi.fn((bytes: Buffer) => bytes.toString()) }))
vi.mock('electron', () => ({ app: { getLocale: () => 'en', getPath: mocks.getPath, quit: mocks.quit },
  safeStorage: { isEncryptionAvailable: mocks.isEncryptionAvailable, getSelectedStorageBackend: mocks.getSelectedStorageBackend,
    encryptString: mocks.encryptString, decryptString: mocks.decryptString,
    decryptStringAsync: async (bytes: Buffer) => ({ result: mocks.decryptString(bytes), shouldReEncrypt: false }) } }))
vi.mock('../src/welcome-window.ts', () => ({ openWelcomeWindow: mocks.open }))
import { authenticateFunnyWorker, FunnyWorkerLoginCancelled } from '../src/funnyworker-account.ts'
const homes: string[] = []
beforeEach(() => {
  mocks.getPath.mockReset().mockReturnValue('test-device')
  mocks.isEncryptionAvailable.mockReset().mockReturnValue(true)
  mocks.getSelectedStorageBackend.mockReset().mockReturnValue('gnome_libsecret')
})
afterEach(async () => {
  vi.unstubAllGlobals(); vi.clearAllMocks(); mocks.getPath.mockReset().mockReturnValue('test-device')
  await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true })))
})

async function fixture(nickname?: string) {
  const home = await mkdtemp(join(tmpdir(), 'fw-first-login-')); homes.push(home)
  const window = Object.assign(new EventEmitter(), { isDestroyed: () => false, close: vi.fn(() => { window.emit('closed') }) })
  let handlers: WelcomeHandlers | undefined
  mocks.open.mockImplementation(async (_locale, value: WelcomeHandlers) => { handlers = value; return window })
  const send = vi.fn(async (url: string) => url.endsWith('/login')
    ? Response.json({ token: 'fixture-only' }) : Response.json({ username: 'tommy', ...(nickname === undefined ? {} : { nickname }), tenant: 'production', principalId: 'employee', roles: ['OPERATOR'] }))
  vi.stubGlobal('fetch', routeFixture(send))
  const account = authenticateFunnyWorker(home)
  await vi.waitFor(() => { expect(handlers).toBeDefined() })
  return { home, window, account, send, login: () => handlers!.projectLogin!('tommy', 'fixture-only') }
}

it('shows first login without touching the keychain when no encrypted session exists', async () => {
  mocks.isEncryptionAvailable.mockImplementation(() => { throw new Error('Keychain probe must wait for submitted credentials') })
  const f = await fixture()
  expect(mocks.isEncryptionAvailable).not.toHaveBeenCalled()
  expect(mocks.getSelectedStorageBackend).not.toHaveBeenCalled()
  expect(mocks.decryptString).not.toHaveBeenCalled()
  expect(f.send).not.toHaveBeenCalled()
  expect(await readdir(f.home)).toEqual([])
  const rejection = expect(f.account).rejects.toBeInstanceOf(FunnyWorkerLoginCancelled)
  f.window.close()
  await rejection
})

it.each([
  ['darwin', 'locked'], ['win32', 'locked'], ['linux', 'locked'], ['linux', 'basic_text'], ['linux', 'unknown'],
] as const)('refuses new credentials before sending them when %s secure storage is %s', async (platform, backend) => {
  vi.stubGlobal('process', { ...process, platform })
  if (backend === 'locked') mocks.isEncryptionAvailable.mockReturnValue(false)
  else mocks.getSelectedStorageBackend.mockReturnValue(backend)
  const f = await fixture()
  expect(mocks.isEncryptionAvailable).not.toHaveBeenCalled()
  expect(await f.login()).toEqual({ ok: false })
  expect(f.send).not.toHaveBeenCalled()
  expect(mocks.encryptString).not.toHaveBeenCalled()
  expect(await readdir(f.home)).toEqual([])
  const rejection = expect(f.account).rejects.toBeInstanceOf(FunnyWorkerLoginCancelled)
  f.window.close()
  await rejection
})

it('rechecks secure storage before persisting a verified session and revokes it if storage becomes unavailable', async () => {
  const f = await fixture()
  f.send.mockImplementation(async (url: string) => {
    if (url.endsWith('/session')) mocks.isEncryptionAvailable.mockReturnValue(false)
    return url.endsWith('/login') ? Response.json({ token: 'fixture-only' })
      : Response.json({ username: 'tommy', tenant: 'production', principalId: 'employee' })
  })
  expect(await f.login()).toEqual({ ok: false })
  expect(mocks.isEncryptionAvailable).toHaveBeenCalledTimes(2)
  expect(f.send.mock.calls.map(([url]) => new URL(url).pathname)).toEqual([
    '/api/account/login', '/api/account/session', '/api/account/logout',
  ])
  expect(mocks.encryptString).not.toHaveBeenCalled()
  expect(await readdir(f.home)).toEqual([])
  const rejection = expect(f.account).rejects.toBeInstanceOf(FunnyWorkerLoginCancelled)
  f.window.close()
  await rejection
})
it('keeps the accepted login visible until the workspace explicitly completes the transition', async () => {
  const f = await fixture()
  expect(await f.login()).toEqual({ ok: true })
  const account = await f.account
  expect(mocks.isEncryptionAvailable).toHaveBeenCalledTimes(2)
  expect(mocks.encryptString).toHaveBeenCalledWith(JSON.stringify({ origin: 'https://funnywork.funnypixelsapp.com', token: 'fixture-only' }))
  expect(await readFile(join(f.home, 'funnyworker-account.enc'))).toEqual(mocks.encryptString.mock.results[0]!.value)
  expect(f.window.close).not.toHaveBeenCalled()
  expect(account.profile).toEqual({ username: 'tommy', roles: ['OPERATOR'] })
  account.finishLogin?.()
  expect(f.window.close).toHaveBeenCalledTimes(1)
  expect(mocks.quit).not.toHaveBeenCalled()
  account.finishLogin?.()
  expect(f.window.close).toHaveBeenCalledTimes(1)
})
it('classifies closing an unaccepted login as cancellation', async () => {
  const f = await fixture()
  const rejection = expect(f.account).rejects.toBeInstanceOf(FunnyWorkerLoginCancelled)
  f.window.close()
  await rejection
  expect(mocks.quit).toHaveBeenCalledTimes(1)
})

it('keeps the login identifier separate from the display nickname', async () => {
  const f = await fixture(' 小明 ')
  expect(await f.login()).toEqual({ ok: true })
  const account = await f.account
  expect(account.username).toBe('tommy')
  expect(account.profile).toEqual({ username: 'tommy', nickname: '小明', roles: ['OPERATOR'] })
  account.finishLogin?.()
})

it('falls back only when the authenticated startup endpoint is absent', async () => {
  const f = await fixture()
  await f.login(); const account = await f.account
  for (const status of [404, 401, 403, 503]) {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status })))
    const result = account.startupRequest!({ modelDevice: 'fixture', policyDevice: 'a'.repeat(32) })
    if (status === 404) expect(await result).toBeUndefined()
    else await expect(result).rejects.toMatchObject({ status })
  }
  account.finishLogin?.()
})

it.each(['test-device', 'x'.repeat(120), 'x'.repeat(121),
  '/Volumes/SSD/' + '企业工作目录/'.repeat(24),
  'C:\\Users\\' + 'employee-profile-'.repeat(12),
])('keeps login device identifiers within the API bound for %s', async (devicePath) => {
  mocks.getPath.mockReturnValue(devicePath)
  const f = await fixture()
  const devices: string[] = []
  f.send.mockImplementation(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/login')) {
      const raw = init?.body
      if (typeof raw !== 'string') throw new Error('Expected a JSON string body')
      const { device } = JSON.parse(raw) as { device: string }
      devices.push(device)
      return device.length > 120 ? Response.json({ error: 'device too long' }, { status: 400 })
        : Response.json({ token: 'fixture-only' })
    }
    return Response.json({ username: 'tommy', tenant: 'production', principalId: 'employee', roles: ['OPERATOR'] })
  })
  expect(await f.login()).toEqual({ ok: true })
  const account = await f.account
  account.finishLogin?.()
  expect(devices).toEqual([devicePath.length <= 120 ? devicePath
    : `desktop-${createHash('sha256').update(devicePath).digest('hex')}`])
  expect(devices[0]!.length).toBeLessThanOrEqual(120)
})
