/** Project account gate; bearer sessions stay in the Electron main process. */
import { readFile, writeFile, rename, rm, mkdir, lstat } from 'node:fs/promises'
import { join } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { app, dialog, safeStorage } from 'electron'
import type { BrowserWindow } from 'electron'
import type { DesktopAccountProfile, DesktopModelBudget } from '@deepseek-ai/dsh-client-ui-settings-general/types'
import { openWelcomeWindow } from './welcome-window.ts'
import { formatDesktopMessage, resolveDesktopLocale } from './locale.ts'
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'
import type { FunnyWorkerStartupRecorder, FunnyWorkerStartupStage } from './funnyworker-startup-timing.ts'
import { ModelEntryHttpError } from './funnyworker-inference-error.ts'
import { funnyWorkOrigins, funnyWorkIdentity, funnyWorkTransport } from '@deepseek-ai/dsh-http-proxy'

/** Invalid current session protocol, distinct from a temporary transport failure. */
export class FunnyWorkerAccountProtocolError extends Error {}

export interface FunnyWorkerAccount {
  readonly username: string
  readonly dataKey: string
  /** Canonical configured origin; never includes credentials. */
  readonly serverOrigin?: string
  updateAvatar?(image: string): Promise<void>
  readonly profile?: DesktopAccountProfile
  /** Close the accepted login only after the workspace is visible. */
  finishLogin?(): void
  readonly principalId?: string
  readonly tenantId?: string
  budget(claim: boolean): Promise<DesktopModelBudget>
  check(): Promise<boolean>
  /** Clears local credentials even if remote revocation fails; callers must finish quitting on rejection. */
  logout(): Promise<void>
  /** Fetch the current organization policy or submit an applied-settings receipt. */
  policyRequest(path: string, body?: object): Promise<unknown>
  /** Current model bootstrap and scoped credential acquisition; missing APIs are errors. */
  inferenceRequest?(path: 'bootstrap' | 'credential' | 'catalog' | 'credentials/revoke', body?: object): Promise<unknown>
  /** Combined startup; undefined means the authenticated server does not implement this endpoint. */
  startupRequest?(body: { modelDevice: string; policyDevice: string }): Promise<unknown>
  syncRequest(path: string, body?: object): Promise<unknown>
}

/** Require native encrypted storage only when reading or submitting account credentials. */
function requireSecureAccountStorage(): void {
  if (!safeStorage.isEncryptionAvailable()
    || (process.platform === 'linux' && !['gnome_libsecret', 'kwallet', 'kwallet5', 'kwallet6'].includes(safeStorage.getSelectedStorageBackend()))) {
    throw new Error(process.platform === 'linux' ? resolveDesktopLocale(app.getLocale()).messages.linuxSecureStorageUnavailable
      : 'FunnyWorker secure account storage is unavailable')
  }
}

export async function authenticateFunnyWorker(home: string, timing?: FunnyWorkerStartupRecorder): Promise<FunnyWorkerAccount> {
  await mkdir(home, { recursive: true, mode: 0o700 })
  const connection: unknown = JSON.parse(await readFile(join(home, 'funnyworker-backend.json'), 'utf8').catch((error: unknown) => {
    if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
    return JSON.stringify({ url: 'https://funnywork.funnypixelsapp.com' })
  }))
  if (typeof connection !== 'object' || connection === null || !('url' in connection) || typeof connection.url !== 'string') throw new Error('FunnyWorker service is not configured')
  const url = new URL(connection.url)
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && url.hostname === '127.0.0.1')) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('FunnyWorker service address is invalid')
  const regionalOrigin = funnyWorkOrigins().find(entry => entry !== 'https://funnywork.funnypixelsapp.com')
  const origin = funnyWorkIdentity(url.origin)
  const transport = funnyWorkTransport(origin, (target, init) => fetch(target, init))
  const storage = join(home, 'funnyworker-account.enc')
  const saved = await lstat(storage).catch((error: unknown) => {
    if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
    return undefined
  })
  if (saved !== undefined && process.platform !== 'darwin') requireSecureAccountStorage()
  let token = ''
  let username = ''
  let nickname = ''
  let avatarUrl = ''
  let tenant = ''
  let principal = ''
  let roles: string[] = []
  let department = ''
  let loginWindow: BrowserWindow | undefined
  let expectedPrincipal = ''
  let expectedTenant = ''
  async function request(path: string, body?: object): Promise<Response> {
    const action = () => transport.fetch(`${origin}/api/account/${path}`, {
      method: body === undefined ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { 'Content-Type': 'application/json', ...(token === '' ? {} : { Authorization: `Bearer ${token}` }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    const stages: Readonly<Record<string, FunnyWorkerStartupStage>> = { login: 'login-request', session: 'session-check', startup: 'startup-config',
      'inference/bootstrap': 'model-bootstrap', 'inference/credential': 'model-credential', 'inference/catalog': 'model-catalog', policy: 'organization-policy' }
    const stage = stages[path.split('?')[0] ?? '']
    return timing === undefined || stage === undefined ? action() : timing.measure(stage, action)
  }
  async function check(): Promise<boolean> {
    const response = await request('session')
    if (response.status === 401 || response.status === 403) return false
    if (response.status === 404 || response.status === 410) throw new FunnyWorkerAccountProtocolError('FunnyWorker account session API is unavailable')
    if (!response.ok) throw new Error('FunnyWorker service is unavailable')
    const value: unknown = await response.json().catch((error: unknown) => {
      if (error instanceof SyntaxError) throw new FunnyWorkerAccountProtocolError('FunnyWorker account response is invalid')
      throw error
    })
    if (typeof value !== 'object' || value === null || !('username' in value) || typeof value.username !== 'string') throw new FunnyWorkerAccountProtocolError('FunnyWorker account response is invalid')
    if (!('tenant' in value) || typeof value.tenant !== 'string' || value.tenant === '') throw new FunnyWorkerAccountProtocolError('FunnyWorker account tenant is invalid')
    if (!('principalId' in value) || typeof value.principalId !== 'string' || value.principalId === '') throw new FunnyWorkerAccountProtocolError('FunnyWorker account principal is invalid')
    const incomingPrincipal = value.principalId
    if ((expectedTenant !== '' && value.tenant !== expectedTenant) || (expectedPrincipal !== '' && incomingPrincipal !== expectedPrincipal)) return false
    roles = 'roles' in value && Array.isArray(value.roles) ? value.roles.filter((role): role is string => typeof role === 'string') : []
    department = 'department' in value && typeof value.department === 'string' ? value.department : ''
    nickname = 'nickname' in value && typeof value.nickname === 'string' ? value.nickname.trim() : ''
    avatarUrl = 'avatarUrl' in value && typeof value.avatarUrl === 'string' && /^data:image\/(png|jpeg);base64,/.test(value.avatarUrl) ? value.avatarUrl : ''
    username = value.username
    tenant = value.tenant
    principal = incomingPrincipal
    return true
  }
  if (saved !== undefined) {
    // Keychain authorization must not block the macOS main thread. Keep the old
    // ciphertext unchanged, including when the provider recommends key rotation.
    let unlocked: string | undefined
    if (process.platform === 'darwin') {
      unlocked = (await safeStorage.decryptStringAsync(await readFile(storage))).result
      if (typeof unlocked !== 'string' || unlocked === '') throw new Error('FunnyWorker secure account storage is unavailable')
    }
    try {
      const stored: unknown = JSON.parse(unlocked === undefined ? safeStorage.decryptString(await readFile(storage)) : unlocked)
      if (typeof stored === 'object' && stored !== null && 'origin' in stored && typeof stored.origin === 'string' && funnyWorkIdentity(stored.origin) === origin && 'token' in stored && typeof stored.token === 'string') token = stored.token
    } catch { /* Unreadable saved sessions require fresh login. */ }
  }
  if (token !== '') {
    try {
      if (!await check()) { token = ''; await rm(storage, { force: true }) }
    } catch (error) {
      if (error instanceof FunnyWorkerAccountProtocolError) throw error
      // A transport outage must not turn a saved account into a password prompt.
      throw error
    }
  }
  if (token === '') {
    await new Promise<void>((resolve, reject) => {
      let accepted = false
      let cancelled = false
      const isCancelled = (): boolean => cancelled
      void openWelcomeWindow(resolveDesktopLocale('zh'), {
        analyticsEnabled: () => Promise.resolve(false),
        takeNotice: () => Promise.resolve(undefined),
        startSignIn: () => Promise.reject(new Error('Provider login is disabled')),
        cancelSignIn: () => Promise.reject(new Error('Provider login is disabled')),
        copySignInLink: () => Promise.reject(new Error('Provider login is disabled')),
        saveApiKey: () => Promise.resolve({ ok: false }),
        skip: () => Promise.reject(new Error('Account login is required')),
        projectLogin: async (user, password) => {
          if (accepted || isCancelled()) return { ok: false }
          try { requireSecureAccountStorage() }
          catch { return { ok: false } }
          const devicePath = app.getPath('userData')
          // Preserve existing identifiers within the account API's 120-character bound.
          const device = devicePath.length <= 120 ? devicePath : `desktop-${createHash('sha256').update(devicePath).digest('hex')}`
          const response = await request('login', { username: user, password, device })
          if (!response.ok || isCancelled()) return { ok: false }
          const value: unknown = await response.json()
          if (typeof value !== 'object' || value === null || !('token' in value) || typeof value.token !== 'string') return { ok: false }
          token = value.token
          try {
            if (!await check() || isCancelled()) return { ok: false }
            requireSecureAccountStorage()
            const temporary = `${storage}.${randomUUID()}.tmp`
            try {
              await writeFile(temporary, safeStorage.encryptString(JSON.stringify({ origin, token })), { mode: 0o600, flag: 'wx' })
              await rename(temporary, storage)
            } finally { await rm(temporary, { force: true }) }
            if (isCancelled()) { await request('logout', {}).catch(() => undefined); await rm(storage, { force: true }); token = ''; return { ok: false } }
            accepted = true
            resolve()
            return { ok: true }
          } catch {
            await request('logout', {}).catch(() => undefined)
            token = ''
            return { ok: false }
          }
        },
      }, origin).then((window) => {
        loginWindow = window
        window.once('closed', () => { if (!accepted) { cancelled = true; reject(new FunnyWorkerLoginCancelled()); app.quit() } })
      }).catch(reject)
    })
  }
  expectedPrincipal = principal
  expectedTenant = tenant
  let dataKey: string
  {
    const mappingFile = join(home, 'funnyworker-account-directories.json')
    const readMapping = () => readFile(mappingFile, 'utf8').catch((error: unknown) => {
      if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
      return '{}'
    })
    const mappingText = await readMapping()
    const mappings: unknown = JSON.parse(mappingText)
    if (typeof mappings !== 'object' || mappings === null || Array.isArray(mappings)) throw new Error('Invalid account directory mapping')
    const key = createHash('sha256').update(JSON.stringify([origin, tenant, principal])).digest('hex')
    const entries = mappings as Record<string, unknown>
    if (origin === 'https://funnywork.funnypixelsapp.com' && regionalOrigin !== undefined) {
      const alias = createHash('sha256').update(JSON.stringify([regionalOrigin, tenant, principal])).digest('hex')
      if (entries[key] !== undefined && entries[alias] !== undefined && entries[key] !== entries[alias]) {
        throw new Error(resolveDesktopLocale('zh').messages.accountHistoryConflict)
      }
    }
    if (entries[key] === undefined && origin === 'https://funnywork.funnypixelsapp.com' && regionalOrigin !== undefined) {
      const alias = createHash('sha256').update(JSON.stringify([regionalOrigin, tenant, principal])).digest('hex')
      const directory = entries[alias]
      if (directory !== undefined) {
        if (typeof directory !== 'string' || !/^[a-f0-9]{64}$/.test(directory) || Object.values(entries).filter(value => value === directory).length !== 1) throw new Error('Invalid official account directory mapping')
        const prior = await lstat(join(home, 'accounts', directory)).catch((error: unknown) => {
          if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
          return undefined
        })
        if (prior !== undefined && (!prior.isDirectory() || prior.isSymbolicLink())) throw new Error('Workspace recovery requires a real account directory')
        if (await readMapping() !== mappingText) throw new Error('Account directory mapping changed during workspace recovery')
        entries[key] = directory
        Reflect.deleteProperty(entries, alias)
        const temporary = `${mappingFile}.${randomUUID()}.tmp`
        try {
          await writeFile(temporary, JSON.stringify(entries), { mode: 0o600, flag: 'wx' })
          await rename(temporary, mappingFile)
        } finally { await rm(temporary, { force: true }) }
      }
    }
    if (entries[key] === undefined) {
      let directoryKey = key
      let priorKey = createHash('sha256').update(JSON.stringify([origin, tenant, username])).digest('hex')
      if (origin === 'https://funnywork.funnypixelsapp.com' && regionalOrigin !== undefined) {
        const alias = createHash('sha256').update(JSON.stringify([regionalOrigin, tenant, username])).digest('hex')
        if (!Object.values(entries).includes(alias)) {
          const old = await lstat(join(home, 'accounts', alias)).catch((error: unknown) => {
            if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
            return undefined
          })
          if (old !== undefined) {
            if (!old.isDirectory() || old.isSymbolicLink()) throw new Error('Workspace recovery requires a real account directory')
            const other = await lstat(join(home, 'accounts', priorKey)).catch((error: unknown) => {
              if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
              return undefined
            })
            if (other !== undefined) throw new Error(resolveDesktopLocale('zh').messages.accountHistoryConflict)
            priorKey = alias
          }
        }
      }
      if (priorKey !== key && !Object.values(entries).includes(priorKey)) {
        const prior = await lstat(join(home, 'accounts', priorKey)).catch((error: unknown) => {
          if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
          return undefined
        })
        if (prior !== undefined && (!prior.isDirectory() || prior.isSymbolicLink())) throw new Error('Workspace recovery requires a real account directory')
        if (prior !== undefined) {
          const messages = resolveDesktopLocale('zh').messages
          const options = {
            type: 'question' as const, title: messages.accountRestoreTitle,
            message: messages.accountRestoreMessage,
            detail: formatDesktopMessage(messages.accountRestoreDetail, { username, origin }),
            checkboxLabel: messages.accountRestoreOwnership, checkboxChecked: false,
            buttons: [messages.accountRestoreConfirm, messages.cancel], defaultId: 1, cancelId: 1,
          }
          const result = await (loginWindow === undefined ? dialog.showMessageBox(options) : dialog.showMessageBox(loginWindow, options))
          if (result.response !== 0 || !result.checkboxChecked) throw new FunnyWorkerLoginCancelled()
          if (!await check()) throw new FunnyWorkerAccountProtocolError('Account identity changed during workspace recovery')
          if (await readMapping() !== mappingText) throw new Error('Account directory mapping changed during workspace recovery')
          directoryKey = priorKey
        }
      }
      entries[key] = directoryKey
      const temporary = `${mappingFile}.${randomUUID()}.tmp`
      try {
        await writeFile(temporary, JSON.stringify(entries), { mode: 0o600, flag: 'wx' })
        await rename(temporary, mappingFile)
      } finally { await rm(temporary, { force: true }) }
    }
    if (typeof entries[key] !== 'string' || !/^[a-f0-9]{64}$/.test(entries[key])) throw new Error('Invalid account directory identity')
    if (Object.values(entries).filter(value => value === entries[key]).length !== 1) {
      throw new Error('Account directory is assigned to multiple identities')
    }
    dataKey = entries[key]
  }
  return {
    username,
    serverOrigin: origin,
    async updateAvatar(image) { const response = await request('avatar', { image }); if (!response.ok) throw new Error('Avatar update failed'); if (!await check()) throw new Error('Session expired') },
    get profile() {
      return { username, ...(avatarUrl ? { avatarUrl } : {}), ...(nickname ? { nickname } : {}),
        roles, ...(department ? { department } : {}) }
    },
    async startupRequest(body) {
      const response = await request('startup', body)
      if (response.status === 404) return undefined
      if (!response.ok) throw new ModelEntryHttpError(response.status)
      const value: unknown = await response.json()
      return value
    },
    finishLogin() { if (loginWindow !== undefined && !loginWindow.isDestroyed()) loginWindow.close(); loginWindow = undefined },
    principalId: principal,
    tenantId: tenant,
    budget: async (claim) => {
      const response = await request(claim ? 'inference/budget/claim' : 'inference/budget', claim ? {} : undefined)
      if (!response.ok) throw new ModelEntryHttpError(response.status)
      const content = await response.text()
      if (Buffer.byteLength(content) > 32768) throw new Error('Model budget response is too large')
      return parseModelBudget(JSON.parse(content), claim)
    },
    inferenceRequest: async (path, body) => {
      const response = await request(`inference/${path}`, body)
      if (!response.ok) throw new ModelEntryHttpError(response.status)
      const content = await response.text()
      if (Buffer.byteLength(content) > 262144) throw new Error('Enterprise model directory is too large')
      const value: unknown = JSON.parse(content)
      return value
    },
    policyRequest: async (path, body) => {
      if (!(body === undefined ? /^policy\?device=[a-f0-9]{32}$/u.test(path) : path === 'policy/receipt')) throw new Error('Invalid policy path')
      const response = await request(path, body)
      if (!response.ok) throw new Error('Organization policy is unavailable')
      const content = await response.text()
      if (Buffer.byteLength(content) > 32768) throw new Error('Organization policy response is too large')
      const value: unknown = JSON.parse(content)
      return value
    },
    dataKey,
    syncRequest: async (path, body) => {
      const feedback = body === undefined
        ? /^\/feedback(?:\/repositories|\/[A-Za-z0-9-]{1,64}|\?after=[A-Za-z0-9-]{0,64}&limit=(?:[1-9]|[1-9][0-9]|100))?$/.test(path)
        : path === '/feedback' || /^\/feedback\/[A-Za-z0-9-]{1,64}\/supplements$/.test(path)
      const heartbeat = body !== undefined && /^\/devices\/[A-Za-z0-9_-]{1,64}\/heartbeat$/.test(path)
      // Fixed POST-only Codex lifecycle endpoints; IDs follow the assignment validator.
      const codexSyncCheck = body !== undefined
        && /^\/devices\/[A-Za-z0-9_-]{1,64}\/maintenance-sync-checks\/[A-Za-z0-9_-]{1,64}\/(?:renew|result|stopped)(?![\s\S])/u.test(path)
      if (!heartbeat && !codexSyncCheck && path !== '/maintenance/features' && !/^\/devices\/[A-Za-z0-9_-]{1,64}\/annual-report\/[A-Za-z0-9-]{1,64}\/(candidate|failure)$/.test(path) && !feedback && !/^\/devices\/[A-Za-z0-9_-]{1,64}\/maintenance-intake\/[A-Za-z0-9-]{1,64}\/(renew|result)$/.test(path) && !/^\/devices\/[A-Za-z0-9_-]{1,64}\/maintenance\/[A-Za-z0-9-]{1,64}\/(renew|result)$/.test(path) && !/^\/devices\/[A-Za-z0-9_-]{1,64}\/link$/.test(path) && !/^\/device-links\/[A-Za-z0-9_-]{1,64}\/(inspect|confirm|cancel)$/.test(path) && !/^\/(devices|sessions)(?:\/[A-Za-z0-9_-]+\/(?:events|state|commands|revoke|restore|attachments)(?:\/[A-Za-z0-9_-]+)?)?(?:\?(?:offset|limit|after|epoch|hash)=[A-Za-z0-9_-]+(?:&(?:offset|limit|after|epoch|hash)=[A-Za-z0-9_-]+)*)?$/.test(path)) throw new Error('Invalid sync path')
      const response = await request(`sync${path}`, body)
      if (!response.ok) throw new HistorySyncHttpError(response.status)
      const value: unknown = await response.json()
      return value
    },
    check,
    logout: async () => { try { await request('logout', {}) } finally { token = ''; await rm(storage, { force: true }) } },
  }
}

/** Normal cancellation of the account gate, never a crash-recovery request. */
export class FunnyWorkerLoginCancelled extends Error {
  constructor() { super('FunnyWork sign-in cancelled') }
}

/** Validate wallet amounts before forwarding only display fields to the renderer. */
export function parseModelBudget(value: unknown, claim: boolean): DesktopModelBudget {
  if (typeof value !== 'object' || value === null) throw new Error('Invalid model budget')
  const envelope = value as Record<string, unknown>
  const data = claim ? envelope.budget : value
  if (typeof data !== 'object' || data === null) throw new Error('Invalid model budget')
  const row = data as Record<string, unknown>
  const amount = (key: string): number => {
    const n = row[key]
    if (typeof n !== 'number' || !Number.isSafeInteger(n) || n < 0) throw new Error('Invalid model budget amount')
    return n
  }
  if (typeof row.configured !== 'boolean' || typeof row.enabled !== 'boolean') throw new Error('Invalid model budget state')
  const promotion = row.promotion
  if (typeof promotion !== 'object' || promotion === null) throw new Error('Invalid promotion')
  const promo = promotion as Record<string, unknown>
  const mode = promo.mode
  if (mode !== 'DISABLED' && mode !== 'WELCOME' && mode !== 'DAILY') throw new Error('Invalid promotion mode')
  const available = promo.available_micros
  if (typeof available !== 'number' || !Number.isSafeInteger(available) || available < 0) throw new Error('Invalid promotion amount')
  const credited = claim ? envelope.credited_micros : 0
  if (typeof credited !== 'number' || !Number.isSafeInteger(credited) || credited < 0) throw new Error('Invalid credit amount')
  return { configured: row.configured, enabled: row.enabled, remainingMicros: amount('remaining_micros'), heldMicros: row.configured ? amount('held') : 0,
    dailyRemainingMicros: row.configured ? Math.max(0, amount('daily_limit') - amount('daily_spent') - amount('held')) : 0,
    expiresAt: row.configured ? amount('expires_at') : 0, promotionMode: mode, claimableMicros: available, creditedMicros: credited }
}
