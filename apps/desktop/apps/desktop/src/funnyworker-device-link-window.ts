/** Isolated, script-free mobile connection window; credentials stay in the main process. */
import { BrowserWindow } from 'electron'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import type { DesktopMessages } from './locale.ts'
import { createDeviceLink, deviceLinkImage, DeviceLinkHttpsRequiredError, type DeviceLink } from './funnyworker-device-link.ts'

const escape = (text: string): string => text.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] ?? char)
let active: BrowserWindow | undefined

/**
 * Opens one connection window. Refresh replaces the code; closing cancels it, including an in-flight creation.
 * @param account Account that owns the current desktop.
 * @param home Current account's history directory.
 * @param messages Localized product copy.
 * @returns After the initial page has loaded; request failures are displayed in the window.
 */
export async function openDeviceLinkWindow(account: FunnyWorkerAccount, home: string, messages: DesktopMessages): Promise<void> {
  if (active !== undefined && !active.isDestroyed()) { active.show(); active.focus(); return }
  const window = new BrowserWindow({ width: 440, height: 570, minWidth: 360, minHeight: 510, title: messages.deviceLinkTitle, backgroundColor: '#F8F6F1', webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false } })
  active = window
  window.setMenu(null)
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.on('will-redirect', (event) => { event.preventDefault() })
  let closed = false
  const isClosed = (): boolean => closed
  let busy = false
  let link: DeviceLink | undefined
  let expiry: ReturnType<typeof setTimeout> | undefined
  const cancel = async (value: DeviceLink): Promise<void> => {
    try { await account.syncRequest(`/device-links/${value.id}/cancel`, { secret: value.secret }) }
    catch (_error: unknown) { /* Codes also expire server-side when cancellation cannot reach the service. */ }
  }
  const render = async (description: string, image?: string): Promise<void> => {
    if (closed || window.isDestroyed()) return
    const html = `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>body{margin:0;background:#F8F6F1;color:#292724;font:15px system-ui;text-align:center}main{padding:24px}h1{font-size:22px;font-weight:600}img{width:320px;max-width:100%;border-radius:12px}p{line-height:1.5;color:#69645c}a{display:inline-block;padding:12px 22px;color:inherit;border:1px solid #DDD9D2;border-radius:24px;text-decoration:none}a:focus-visible{outline:2px solid currentColor}@media(prefers-color-scheme:dark){body{background:#201F1D;color:#EEEAE2}p{color:#b7b0a5}a{border-color:#45413C}}</style></head><body><main><h1>${escape(messages.deviceLinkTitle)}</h1>${image === undefined ? '' : `<img alt="${escape(messages.deviceLinkTitle)}" src="${image}">`}<p>${escape(description)}</p><a href="https://connect.funnywork.invalid/refresh">${escape(messages.deviceLinkRefresh)}</a></main></body></html>`
    await window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`)
  }
  const refresh = async (): Promise<void> => {
    if (busy || closed) return
    busy = true
    clearTimeout(expiry)
    const old = link; link = undefined
    try {
      await render(messages.deviceLinkLoading)
      if (old !== undefined) await cancel(old)
      const deviceId = (await readFile(join(home, 'history-device-id'), 'utf8')).trim()
      if (isClosed()) return
      const created = await createDeviceLink(account, deviceId)
      if (isClosed()) { await cancel(created); return }
      link = created
      const image = await deviceLinkImage(created)
      const remaining = created.expiresAt - Date.now()
      if (isClosed()) return
      if (remaining <= 0) { await render(messages.deviceLinkExpired); return }
      await render(messages.deviceLinkScan, image)
      if (isClosed()) return
      expiry = setTimeout(() => {
        void render(messages.deviceLinkExpired).catch((_error: unknown) => { /* A closing window can reject its final load. */ })
      }, Math.max(0, created.expiresAt - Date.now()))
    } catch (error: unknown) {
      if (!isClosed())
        await render(error instanceof DeviceLinkHttpsRequiredError ? messages.deviceLinkHttpsRequired : messages.deviceLinkError)
    } finally { busy = false }
  }
  window.webContents.on('will-navigate', (event, target) => {
    event.preventDefault()
    if (target === 'https://connect.funnywork.invalid/refresh') void refresh().catch((_error: unknown) => { /* A closing window can reject its final load. */ })
  })
  window.on('closed', () => {
    closed = true; clearTimeout(expiry)
    if (active === window) active = undefined
    if (link !== undefined) void cancel(link)
  })
  await refresh()
}
