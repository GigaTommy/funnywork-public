/** Real loopback transport for the authenticated four-request first-login sequence. */
import { createServer } from 'node:http'
import { EventEmitter } from 'node:events'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, it, vi } from 'vitest'
import type { WelcomeOperations } from '../src/welcome-api.ts'
const mocks = vi.hoisted(() => ({ open: vi.fn() }))
vi.mock('electron', () => ({ app: { getPath: () => 'fixture-device', quit: vi.fn() }, safeStorage: {
  decryptStringAsync: async (bytes: Buffer) => ({ result: bytes.toString(), shouldReEncrypt: false }),
  isEncryptionAvailable: () => true, getSelectedStorageBackend: () => 'gnome_libsecret', encryptString: (text: string) => Buffer.from(text), decryptString: (bytes: Buffer) => bytes.toString(),
} }))
vi.mock('../src/welcome-window.ts', () => ({ openWelcomeWindow: mocks.open }))
import { authenticateFunnyWorker } from '../src/funnyworker-account.ts'
import { prepareFunnyWorkerStartup } from '../src/funnyworker-startup.ts'

it('reaches validated model and policy state in four authenticated HTTP requests including the final session check', async () => {
  const home = await mkdtemp(join(tmpdir(), 'startup-http-')), requests: string[] = []
  const server = createServer((request, response) => {
    const path = request.url ?? ''; requests.push(path)
    if (path !== '/api/account/login' && request.headers.authorization !== 'Bearer fixture-account') {
      response.writeHead(401).end(); return
    }
    response.setHeader('Content-Type', 'application/json')
    if (path.endsWith('/login')) response.end(JSON.stringify({ token: 'fixture-account' }))
    else if (path.endsWith('/session')) response.end(JSON.stringify({ username: 'fixture', tenant: 'fixture', principalId: 'fixture' }))
    else if (path.endsWith('/startup')) response.end(JSON.stringify({ schema: 1,
      inference: { bootstrap: { enabled: true }, credential: { token: 'mwi.' + 'x'.repeat(24), expires: Date.now() + 600000 },
        catalog: { items: [{ id: 'sol', model: 'gpt-sol', provider_id: 'enterprise-openai' }] } },
      policy: { schema: 1, version: 1, digest: 'a'.repeat(64), policy: {
        constraints: { crossDeviceAccess: true, remoteControl: true }, defaults: { theme: 'system', locale: 'system' },
      } },
    }))
    else if (path.endsWith('/credentials/revoke')) response.end('{}')
    else response.writeHead(404).end('{}')
  })
  let accountPromise: ReturnType<typeof authenticateFunnyWorker> | undefined
  let finish: (() => void) | undefined
  try {
    await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve) })
    const address = server.address()
    if (typeof address !== 'object' || address === null) throw new Error('Missing loopback address')
    await writeFile(join(home, 'funnyworker-backend.json'), JSON.stringify({ url: `http://127.0.0.1:${address.port}` }))
    const window = Object.assign(new EventEmitter(), { isDestroyed: () => false, close: () => { window.emit('closed') } })
    let operations: WelcomeOperations | undefined
    mocks.open.mockImplementation(async (_locale, value: WelcomeOperations) => { operations = value; return window })
    finish = () =>{  window.close() }
    accountPromise = authenticateFunnyWorker(home)
    void accountPromise.catch((_fixtureCancelled: unknown) => { /* The finalizer also awaits account cancellation. */ })
    await vi.waitFor(() => { expect(operations).toBeDefined() })
    expect(await operations?.projectLogin?.('fixture', 'fixture-password')).toEqual({ ok: true })
    const account = await accountPromise
    finish = () => account.finishLogin?.()
    const ready = await prepareFunnyWorkerStartup(home, account, 'fixture')
    try {
      expect(await account.check()).toBe(true)
      expect(requests).toEqual(['/api/account/login', '/api/account/session', '/api/account/startup', '/api/account/session'])
      expect(ready.inference.defaultModel).toEqual({ provider: 'enterprise-openai', model: 'sol' })
      expect(ready.policy.document.version).toBe(1)
    } finally { await ready.inference.dispose() }
  } finally {
    finish?.()
    await accountPromise?.catch((_fixtureCancelled: unknown) => { /* Closing an unfinished fixture cancels authentication. */ })
    mocks.open.mockReset()
    if (server.listening) await new Promise<void>((resolve, reject) => {
      server.close((error) => {
        if (error) { reject(error); return }
        resolve()
      })
    })
    await rm(home, { recursive: true, force: true })
  }
})
