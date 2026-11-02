/** Organization defaults occupy the installation defaults layer; profile-owned user settings keep precedence. */
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { join } from 'node:path'

/** Authenticated policy metadata, never provider credentials. */
export interface FunnyWorkerPolicyDocument {
  schema: 1
  version: number
  digest: string
  policy: {
    constraints: { crossDeviceAccess: boolean; remoteControl: boolean }
    defaults: { theme: 'system' | 'light' | 'dark'; locale: 'system' | 'zh-CN' | 'en-US' }
  }
}
/** Main-process transport for the current policy protocol. */
export interface FunnyWorkerPolicyTransport {
  /** @param path - Account-relative policy path. @param body - Optional receipt. @returns Parsed current-protocol response. */
  policyRequest(path: string, body?: object): Promise<unknown>
}
function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function keys(value: Record<string, unknown>, expected: string[]): boolean {
  return Object.keys(value).length === expected.length && expected.every(key => key in value)
}
/** Validate every policy field before changing a profile or acknowledging it.
 * @param value - Authenticated server response.
 * @returns The supported policy document.
 */
export function parseFunnyWorkerPolicy(value: unknown): FunnyWorkerPolicyDocument {
  if (!record(value) || !keys(value, ['schema', 'version', 'digest', 'policy']) || value.schema !== 1
    || typeof value.version !== 'number' || !Number.isSafeInteger(value.version) || value.version < 0
    || typeof value.digest !== 'string' || !/^[a-f0-9]{64}$/u.test(value.digest) || !record(value.policy)
    || !keys(value.policy, ['constraints', 'defaults'])) throw new Error('Unsupported organization policy')
  const { constraints, defaults } = value.policy
  if (!record(constraints) || !keys(constraints, ['crossDeviceAccess', 'remoteControl'])
    || typeof constraints.crossDeviceAccess !== 'boolean' || typeof constraints.remoteControl !== 'boolean'
    || !record(defaults) || !keys(defaults, ['theme', 'locale'])
    || (defaults.theme !== 'system' && defaults.theme !== 'light' && defaults.theme !== 'dark')
    || (defaults.locale !== 'system' && defaults.locale !== 'zh-CN' && defaults.locale !== 'en-US')) throw new Error('Unsupported organization policy')
  return { schema: 1, version: value.version, digest: value.digest, policy: {
    constraints: { crossDeviceAccess: constraints.crossDeviceAccess, remoteControl: constraints.remoteControl },
    defaults: { theme: defaults.theme, locale: defaults.locale },
  } }
}
/** Replace only organization-owned preference fields, retaining other installation defaults.
 * @param rows - Existing installation defaults, below the profile's user layer.
 * @param document - Supported organization policy.
 * @returns An installation defaults patch with organization preferences.
 */
export function funnyWorkerPolicyPatch(rows: readonly unknown[], document: FunnyWorkerPolicyDocument): unknown[] {
  const cleaned = rows.flatMap((row) => {
    if (!record(row) || (row.id !== 'ui-theme' && row.id !== 'locale')) return [row]
    if (row.config !== undefined && !record(row.config)) throw new Error('Invalid home preference configuration')
    const config = { ...(record(row.config) ? row.config : {}) }
    delete config.preference
    return Object.keys(config).length === 0 && Object.keys(row).every(key => key === 'id' || key === 'config') ? [] : [{ ...row, config }]
  })
  return [...cleaned, { id: 'ui-theme', config: { preference: document.policy.defaults.theme } },
    { id: 'locale', config: document.policy.defaults.locale === 'system' ? {} : { preference: document.policy.defaults.locale } }]
}
function locale(value: unknown): string | null {
  if (value === undefined) return null
  if (typeof value !== 'string' || value.length > 80 || !/^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/u.test(value)) throw new Error('Invalid runtime locale')
  return value === 'zh' ? 'zh-CN' : value === 'en' ? 'en-US' : value
}
function theme(value: unknown): 'system' | 'light' | 'dark' {
  if (value !== 'system' && value !== 'light' && value !== 'dark') throw new Error('Invalid runtime theme')
  return value
}
/** Compare applied Host values with defaults and explicit personal overrides.
 * @param document - The fetched policy.
 * @param description - Redacted settings/describe response from the running Host.
 * @returns Receipt preferences only when both settings match their effective values.
 */
export function appliedFunnyWorkerPreferences(
  document: FunnyWorkerPolicyDocument, description: unknown,
): { theme: string | null; locale: string | null } | undefined {
  if (!record(description) || !Array.isArray(description.namespaces)) throw new Error('Invalid runtime settings')
  const namespaces: unknown[] = description.namespaces
  const find = (id: string): Record<string, unknown> => {
    const matches = namespaces.filter(item => record(item) && item.ns === id)
    if (matches.length !== 1 || !record(matches[0]) || !record(matches[0].value)) throw new Error('Runtime preferences are unavailable')
    return matches[0]
  }
  const themeRow = find('ui-theme'), localeRow = find('locale')
  if (!record(themeRow.value) || !record(localeRow.value)) throw new Error('Invalid runtime preferences')
  if ((themeRow.user !== undefined && !record(themeRow.user)) || (localeRow.user !== undefined && !record(localeRow.user))) throw new Error('Invalid user preferences')
  const userTheme = record(themeRow.user) && Object.hasOwn(themeRow.user, 'preference') ? theme(themeRow.user.preference) : null
  const userLocale = locale(record(localeRow.user) ? localeRow.user.preference : undefined)
  const expectedTheme = userTheme ?? document.policy.defaults.theme
  const expectedLocale = userLocale ?? document.policy.defaults.locale
  if (theme(themeRow.value.preference) !== expectedTheme || (locale(localeRow.value.preference) ?? 'system') !== expectedLocale) return undefined
  return { theme: userTheme, locale: userLocale }
}
/** Policy acquisition and acknowledgement owned by the serialized account renewal loop. */
export interface FunnyWorkerPolicy {
  readonly document: FunnyWorkerPolicyDocument
  /** @param inspect - Read the running Host's redacted settings. @returns Whether the policy document changed. */
  refresh(inspect: () => Promise<unknown>): Promise<boolean>
  /** @param inspect - Read the running Host's redacted settings. @returns Whether matching loaded settings were acknowledged. */
  confirm(inspect: () => Promise<unknown>): Promise<boolean>
}
/**
 * Prepare the stable policy identity before requesting startup configuration.
 * @param home - Account-isolated Harness home.
 * @returns A persistent policy device identifier.
 */
export async function prepareFunnyWorkerPolicyDevice(home: string): Promise<string> {
  const directory=join(home,'model-publication'),path=join(directory,'node.id')
  let device:string
  try{device=await readFile(path,'utf8')}
  catch(error){
    if(!(error instanceof Error&&'code' in error&&error.code==='ENOENT'))throw error
    await mkdir(directory,{ recursive:true,mode:0o700 })
    try{await writeFile(path,randomBytes(16).toString('hex'),{ mode:0o600,flag:'wx' })}
    catch(createError){if(!(createError instanceof Error&&'code' in createError&&createError.code==='EEXIST'))throw createError}
    device=await readFile(path,'utf8')
  }
  if (!/^[a-f0-9]{32}$/u.test(device)) throw new Error('Invalid policy device')
  return device
}
/** Fetch defaults before Host startup; acknowledge only after inspecting the running Host.
 * @param home - Account-isolated home created by model provisioning.
 * @param account - Main-process authenticated transport.
 * @param clientVersion - Packaged desktop version for the receipt.
 * @param initial - Untrusted combined policy response, validated before use.
 * @returns The current document and serialized renewal operations.
 */
export async function prepareFunnyWorkerPolicy(
  home: string, account: FunnyWorkerPolicyTransport, clientVersion: string,
  initial?: { readonly value: unknown },
): Promise<FunnyWorkerPolicy> {
  const device = await prepareFunnyWorkerPolicyDevice(home)
  const fetch = async (): Promise<FunnyWorkerPolicyDocument> => {
    const value = await account.policyRequest(`policy?device=${device}`)
    return parseFunnyWorkerPolicy(value)
  }
  let document = initial === undefined ? await fetch() : parseFunnyWorkerPolicy(initial.value)
  let next = 0
  let pending: Promise<boolean> | undefined
  const confirm = async (inspect: () => Promise<unknown>): Promise<boolean> => {
    const current = document
    const preferences = appliedFunnyWorkerPreferences(current, await inspect())
    if (preferences === undefined || current !== document) return false
    const result = await account.policyRequest('policy/receipt', { device, version: current.version, digest: current.digest, schema: 1, clientVersion, preferences })
    if (!record(result) || result.state !== 'APPLIED' || result.version !== current.version) throw new Error('Organization policy receipt was not confirmed')
    return true
  }
  const refresh = async (inspect: () => Promise<unknown>): Promise<boolean> => {
    if (Date.now() < next) return false
    next = Date.now() + 60000
    const received = await fetch()
    if (received.version < document.version || (received.version === document.version && received.digest !== document.digest)) throw new Error('Organization policy version conflict')
    const changed = document.version !== received.version || document.digest !== received.digest
    if (changed) {
      const path = join(home, 'cordis.defaults.yml'), temporary = `${path}.${randomBytes(8).toString('hex')}.tmp`
      const rows: unknown = JSON.parse(await readFile(path, 'utf8'))
      if (!Array.isArray(rows)) throw new Error('Invalid account home configuration')
      const entries: readonly unknown[] = rows
      try { await writeFile(temporary, JSON.stringify(funnyWorkerPolicyPatch(entries, received)), { mode: 0o600, flag: 'wx' }); await rename(temporary, path) }
      finally { await rm(temporary, { force: true }) }
      document = received
    }
    await confirm(inspect)
    return changed
  }
  return { get document() { return document }, confirm, refresh(inspect) {
    if (pending !== undefined) return pending
    pending = refresh(inspect).finally(() => { pending = undefined })
    return pending
  } }
}
