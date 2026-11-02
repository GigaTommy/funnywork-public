import { EventEmitter } from 'node:events'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import { resolveDesktopLocale } from '../src/locale.ts'
import type { FunnyWorkerAccount } from '../src/funnyworker-account.ts'

const windows = vi.hoisted(() => [] as WindowStub[])
class WindowStub extends EventEmitter {
  readonly webContents = Object.assign(new EventEmitter(), { setWindowOpenHandler: vi.fn() })
  pages: string[] = []
  destroyed = false
  options: object
  constructor(options: object) { super(); this.options = options; windows.push(this) }
  setMenu() {}
  show() {}
  focus() {}
  isDestroyed() { return this.destroyed }
  async loadURL(url: string) { this.pages.push(decodeURIComponent(url.replace('data:text/html;charset=utf-8,', ''))) }
  close() { this.destroyed = true; this.emit('closed') }
}
vi.mock('electron', () => ({ BrowserWindow: WindowStub }))
const { openDeviceLinkWindow } = await import('../src/funnyworker-device-link-window.ts')
const homes: string[] = []
afterEach(async () => {
  for (const window of windows.splice(0)) { if (!window.destroyed) window.close() }
  vi.useRealTimers()
  await Promise.all(homes.splice(0).map(home => rm(home, { recursive: true, force: true })))
})
async function home() { const value = await mkdtemp(join(tmpdir(), 'fw-link-')); homes.push(value); await writeFile(join(value, 'history-device-id'), 'desktop'); return value }
function account(request: FunnyWorkerAccount['syncRequest']): FunnyWorkerAccount {
  return { username: 'user', dataKey: 'key', serverOrigin: 'https://example.com', budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: request }
}
it('expires the image, intercepts refresh, denies external navigation, and cancels on close', async () => {
  vi.useFakeTimers()
  const request = vi.fn(async (path: string) => path.endsWith('/link') ? { id: 'code', secret: 'a'.repeat(64), expiresIn: 120000 } : { ok: true })
  await openDeviceLinkWindow(account(request), await home(), resolveDesktopLocale('zh').messages)
  const window = windows[0]!
  expect(window.options).toMatchObject({ webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } })
  expect(window.pages.at(-1)).toContain('data:image/png;base64,')
  expect(window.pages.at(-1)).not.toContain('a'.repeat(64))
  expect(window.pages.at(-1)).not.toContain('<script')
  const preventDefault = vi.fn()
  window.webContents.emit('will-navigate', { preventDefault }, 'https://example.org/')
  expect(preventDefault).toHaveBeenCalled(); expect(request).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(120000)
  expect(window.pages.at(-1)).not.toContain('<img')
  expect(window.pages.at(-1)).toContain('二维码已过期')
  window.webContents.emit('will-navigate', { preventDefault }, 'https://connect.funnywork.invalid/refresh')
  await vi.waitFor(() => { expect(request.mock.calls.filter(([path]) => path.endsWith('/link'))).toHaveLength(2) })
  await vi.waitFor(() => { expect(window.pages.at(-1)).toContain('<img') })
  window.close()
  await vi.waitFor(() => { expect(request.mock.calls.filter(([path]) => path.endsWith('/cancel'))).toHaveLength(2) })
  const redacted = window.pages.at(-1)!.replace(/data:image\/png;base64,[A-Za-z0-9+/=]+/g, '<local-qr>')
  expect(redacted).toMatchSnapshot()
})
it('cancels a code created after its window has closed and never displays it', async () => {
  let resolve: (value: unknown) => void = () => undefined
  const request = vi.fn(async (path: string) => path.endsWith('/link') ? await new Promise((res) => { resolve = res }) : { ok: true })
  const opening = openDeviceLinkWindow(account(request), await home(), resolveDesktopLocale('en').messages)
  await vi.waitFor(() => { expect(request).toHaveBeenCalledWith('/devices/desktop/link', {}) })
  const window = windows[0]!; window.close()
  resolve({ id: 'late', secret: 'b'.repeat(64), expiresIn: 120000 })
  await opening
  expect(request).toHaveBeenCalledWith('/device-links/late/cancel', { secret: 'b'.repeat(64) })
  expect(window.pages).toHaveLength(1); expect(window.pages[0]).not.toContain('<img')
})

it('explains the HTTPS requirement without creating a phone code on a local HTTP server', async () => {
  const request = vi.fn(async () => ({}))
  await openDeviceLinkWindow({ ...account(request), serverOrigin: 'http://127.0.0.1:8765' }, await home(), resolveDesktopLocale('zh').messages)
  expect(request).not.toHaveBeenCalled()
  expect(windows[0]!.pages.at(-1)).toContain('连接手机需要 HTTPS 服务地址')
  expect(windows[0]!.pages.at(-1)).toMatchSnapshot()
})
