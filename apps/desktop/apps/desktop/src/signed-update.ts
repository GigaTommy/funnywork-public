/** Verify the publisher's exact updater document before electron-updater consumes it. */
import { createPublicKey, verify } from 'node:crypto'
import { valid } from 'semver'
import { parseUpdateInfo } from 'electron-updater/out/providers/Provider.js'

/** Trust anchor sealed into the signed application; never fetched from the update server. */
export const DESKTOP_UPDATE_PUBLIC_KEY = '-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEALHp07EoIaRpXgZLdNi2l0ruzfKWAsyRfXGVmAkelMZ8=\n-----END PUBLIC KEY-----\n'

/** Installed platform, channel and server identity. */
export interface SignedUpdatePolicy {
  readonly target: 'mac-arm64' | 'mac-x64' | 'win-x64'
  readonly channel: 'nightly' | 'latest'
  readonly url: string
}

/**
 * Resolve the sealed feed for this running architecture, rejecting missing or foreign settings.
 * @param manifest Installed application manifest.
 * @param platform Running operating system.
 * @param arch Running CPU architecture.
 * @returns The installed update identity.
 */
export function signedUpdatePolicy(manifest: unknown, platform: NodeJS.Platform, arch: string): SignedUpdatePolicy {
  const target = `${platform === 'darwin' ? 'mac' : platform === 'win32' ? 'win' : platform}-${arch}`
  if (target !== 'mac-arm64' && target !== 'mac-x64' && target !== 'win-x64') throw new Error('desktop update: unsupported signed target')
  if (!record(manifest) || typeof manifest.dshDesktopUpdateFeed !== 'string'
    || (manifest.dshDesktopUpdateChannel !== 'nightly' && manifest.dshDesktopUpdateChannel !== 'latest')) {
    throw new Error('desktop update: missing installed signed feed')
  }
  const url = new URL(manifest.dshDesktopUpdateFeed)
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !url.pathname.endsWith('/')) {
    throw new Error('desktop update: invalid installed update origin')
  }
  return { target, url: url.href, channel: manifest.dshDesktopUpdateChannel }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Authenticate scoped, expiring metadata without an unsigned fallback.
 * @param raw Signed envelope returned by the update service.
 * @param policy Identity sealed into the installed App.
 * @param publicKey Publisher key; production callers use the compiled trust anchor.
 * @param now Wall-clock time used for expiration checks.
 * @returns Verified upstream update information, retaining its exact SHA-512 digests.
 */
export function verifySignedUpdate(
  raw: string, policy: SignedUpdatePolicy, publicKey = DESKTOP_UPDATE_PUBLIC_KEY, now = Date.now(),
): ReturnType<typeof parseUpdateInfo> {
  if (raw.length > 100_000) throw new Error('desktop update: oversized signed metadata')
  const envelope: unknown = JSON.parse(raw)
  if (!record(envelope) || typeof envelope.payload !== 'string' || typeof envelope.signature !== 'string'
    || !/^[A-Za-z0-9+/]+={0,2}$/u.test(envelope.payload) || !/^[A-Za-z0-9+/]+={0,2}$/u.test(envelope.signature)) {
    throw new Error('desktop update: invalid signed envelope')
  }
  const bytes = Buffer.from(envelope.payload, 'base64')
  const signature = Buffer.from(envelope.signature, 'base64')
  const key = createPublicKey(publicKey)
  if (bytes.length > 65_536 || key.asymmetricKeyType !== 'ed25519' || signature.length !== 64 || !verify(null, bytes, key, signature)) {
    throw new Error('desktop update: publisher signature verification failed')
  }
  const data: unknown = JSON.parse(bytes.toString('utf8'))
  if (!record(data) || data.schema !== 1 || data.target !== policy.target || data.channel !== policy.channel
    || typeof data.issued !== 'string' || typeof data.expires !== 'string' || typeof data.metadata !== 'string'
    || !Number.isFinite(Date.parse(data.issued)) || !Number.isFinite(Date.parse(data.expires))
    || Date.parse(data.issued) > now + 300_000 || Date.parse(data.expires) <= now
    || Date.parse(data.expires) <= Date.parse(data.issued)
    || Date.parse(data.expires) - Date.parse(data.issued) > 90 * 86_400_000) {
    throw new Error('desktop update: invalid, expired or mismatched release')
  }
  const base = new URL(policy.url)
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || !base.pathname.endsWith('/')) {
    throw new Error('desktop update: invalid installed update origin')
  }
  const info = parseUpdateInfo(data.metadata, 'signed metadata', base)
  if (valid(info.version) === null || (policy.channel === 'latest' && info.version.includes('-'))
    || !Array.isArray(info.files) || info.files.length === 0 || info.files.length > 10 || 'packages' in info) {
    throw new Error('desktop update: invalid release identity')
  }
  for (const file of info.files) {
    const url = new URL(file.url, base)
    if (url.origin !== base.origin || url.username || url.password || url.search || url.hash
      || !(policy.target.startsWith('mac-') ? ['.zip', '.dmg'] : ['.exe']).some(extension => url.pathname.endsWith(extension))
      || typeof file.sha512 !== 'string' || !/^[A-Za-z0-9+/]{86}==$/u.test(file.sha512)
      || !Number.isSafeInteger(file.size) || (file.size ?? 0) <= 0) {
      throw new Error('desktop update: invalid signed artifact')
    }
  }
  return info
}
