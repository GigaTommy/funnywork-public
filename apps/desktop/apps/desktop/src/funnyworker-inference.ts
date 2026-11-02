/** Main-process renewal of scoped model credentials. Upstream secrets never reach the Host. */
import { mkdir, readFile, writeFile, rename, rm } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { join } from 'node:path'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import { ModelEntryHttpError } from './funnyworker-inference-error.ts'

/** Enterprise Host composition and main-process credential lifecycle. */
export interface FunnyWorkerInference {
  readonly plugin: { origin: string; credentialFile: string; stateDirectory: string }
  readonly defaultModel?: { provider: string; model: string }
  refresh(): Promise<void>
  dispose(): Promise<void>
}

async function privateWrite(path: string, text: string): Promise<void> {
  const temporary = `${path}.${randomUUID()}.tmp`
  try {
    await writeFile(temporary, text, { flag: 'wx', mode: 0o600 })
    await rename(temporary, path)
  } finally {
    await rm(temporary, { force: true })
  }
}

/**
 * Prepare the stable device before a combined startup request.
 * @param home - Account-isolated Harness home.
 * @returns The existing or newly created scoped-credential device.
 */
export async function prepareFunnyWorkerInferenceDevice(home: string): Promise<string> {
  const directory = join(home, 'model-entry')
  await mkdir(directory, { recursive: true, mode: 0o700 })
  let device: string
  try {
    device = (await readFile(join(directory, 'device.id'), 'utf8')).trim()
  } catch (error) {
    if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
    device = randomUUID()
    await privateWrite(join(directory, 'device.id'), device)
  }
  if (!/^[a-zA-Z0-9_-]{1,64}$/.test(device)) throw new Error('Invalid inference device identity')
  return device
}

/**
 * Enter enterprise mode only after an explicit authenticated bootstrap.
 * @param home - the account-isolated Harness home.
 * @param account - the Electron account transport.
 * @param initial - Untrusted combined startup fields validated identically to individual responses.
 * @returns the current metered credential lifecycle.
 */
export async function prepareFunnyWorkerInference(
  home: string,
  account: FunnyWorkerAccount,
  initial?: { readonly bootstrap: unknown; readonly credential: unknown; readonly catalog: unknown },
): Promise<FunnyWorkerInference> {
  const transport = account.inferenceRequest?.bind(account)
  const bootstrap = initial === undefined ? (transport === undefined ? undefined : await transport('bootstrap')) : initial.bootstrap
  if (
    (typeof bootstrap === 'object' && bootstrap !== null && 'enabled' in bootstrap && bootstrap.enabled === false)
  ) {
    throw new Error('Metered model entry must be enabled')
  }
  if (
    typeof bootstrap !== 'object' ||
    bootstrap === null ||
    !('enabled' in bootstrap) ||
    bootstrap.enabled !== true ||
    transport === undefined
  )
    throw new Error('Invalid enterprise model bootstrap')
  const inferenceRequest = transport
  if (account.serverOrigin === undefined) throw new Error('Enterprise model origin is unavailable')
  const origin = new URL(account.serverOrigin)
  if (
    origin.origin !== account.serverOrigin ||
    (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && origin.hostname === '127.0.0.1'))
  )
    throw new Error('Invalid enterprise model origin')
  const directory = join(home, 'model-entry')
  const credentialFile = join(directory, 'credential.json')
  const device = await prepareFunnyWorkerInferenceDevice(home)
  let expires = 0,
    nextBootstrap = 0,
    disposed = false
  let pending: Promise<void> | undefined
  async function acquire(seed?: { value: unknown }): Promise<void> {
    const received = seed === undefined ? await inferenceRequest('credential', { deviceId: device }) : seed.value
    if (
      typeof received !== 'object' ||
      received === null ||
      !('token' in received) ||
      typeof received.token !== 'string' ||
      !/^mwi\.[A-Za-z0-9_-]{20,512}$/.test(received.token) ||
      !('expires' in received) ||
      typeof received.expires !== 'number' ||
      !Number.isSafeInteger(received.expires) ||
      received.expires <= Date.now() ||
      received.expires > Date.now() + 900000
    )
      throw new Error('Invalid scoped model credential')
    await privateWrite(
      credentialFile,
      JSON.stringify({ origin: origin.origin, token: received.token, expires: received.expires, device }),
    )
    expires = received.expires
  }
  try {
    await acquire(initial === undefined ? undefined : { value: initial.credential })
  } catch (error) {
    await rm(credentialFile, { force: true })
    throw error
  }
  let defaultModel: FunnyWorkerInference['defaultModel']
  try {
    const catalog = initial === undefined ? await inferenceRequest('catalog') : initial.catalog
    if (
      typeof catalog !== 'object' ||
      catalog === null ||
      !('items' in catalog) ||
      !Array.isArray(catalog.items) ||
      catalog.items.length > 512
    )
      throw new Error('Invalid enterprise model directory')
    const items: readonly unknown[] = catalog.items
    for (const item of items) {
      if (
        typeof item !== 'object' ||
        item === null ||
        !('id' in item) ||
        typeof item.id !== 'string' ||
        !/^[A-Za-z0-9_-]{1,64}$/.test(item.id) ||
        !('model' in item) ||
        typeof item.model !== 'string' ||
        item.model.length === 0 ||
        item.model.length > 120
      )
        throw new Error('Invalid enterprise model entry')
      if ('provider_id' in item) {
        if (typeof item.provider_id !== 'string' || !/^enterprise-[a-zA-Z0-9_-]{1,64}$/.test(item.provider_id))
          throw new Error('Invalid enterprise provider')
        defaultModel ??= { provider: item.provider_id, model: item.id }
      } else defaultModel ??= { provider: `enterprise-${item.id}`, model: item.model }
    }
  } catch (error) {
    try {
      await inferenceRequest('credentials/revoke', { deviceId: device })
    } catch (_revocationUnavailable) {
      /* Token expiry bounds failed startup access. */
    }
    await rm(credentialFile, { force: true })
    throw error
  }
  async function refresh(): Promise<void> {
    if (disposed) return
    if (Date.now() >= nextBootstrap) {
      let mode: unknown
      try {
        mode = await inferenceRequest('bootstrap')
      } catch (error) {
        if ((error instanceof ModelEntryHttpError && [401, 403].includes(error.status)) || Date.now() >= expires)
          throw error
        return
      }
      if (typeof mode !== 'object' || mode === null || !('enabled' in mode) || mode.enabled !== true)
        throw new Error('Enterprise model entry was disabled')
      nextBootstrap = Date.now() + 60000
    }
    if (Date.now() + 120000 < expires) return
    try {
      await acquire()
    } catch (error) {
      if ((error instanceof ModelEntryHttpError && [401, 403, 409].includes(error.status)) || Date.now() >= expires)
        throw error
    }
  }
  return {
    plugin: { origin: origin.origin, credentialFile, stateDirectory: join(directory, 'calls') },
    ...(defaultModel === undefined ? {} : { defaultModel }),
    async refresh() {
      if (pending !== undefined) {
        await pending
        return
      }
      pending = refresh()
      try {
        await pending
      } finally {
        pending = undefined
      }
    },
    async dispose() {
      if (disposed) return
      disposed = true
      try {
        await pending
        await inferenceRequest('credentials/revoke', { deviceId: device })
      } catch (_revocationUnavailable) {
        /* Local removal and the server expiry still bound access. */
      } finally {
        await rm(credentialFile, { force: true })
      }
    },
  }
}
