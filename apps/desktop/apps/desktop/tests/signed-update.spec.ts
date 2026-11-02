import { generateKeyPairSync, sign } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { DESKTOP_UPDATE_PUBLIC_KEY, signedUpdatePolicy, verifySignedUpdate } from '../src/signed-update.ts'
import { signUpdateMetadata } from '../scripts/sign-update-metadata.ts'

const keys = generateKeyPairSync('ed25519')
const publicKey = keys.publicKey.export({ format: 'pem', type: 'spki' }).toString()
const now = Date.parse('2026-10-24T00:00:00Z')
const policy = { target: 'mac-arm64', channel: 'latest', url: 'https://updates.example.com/dsh-desk/feeds/mac-arm64/' } as const
const metadataValue = { version: '0.2.1', files: [{ url: '../../bin/mac-arm64/latest/funnywork-0.2.1-mac-arm64.zip',
  sha512: Buffer.alloc(64, 1).toString('base64'), size: 12345 }] }
const metadata = JSON.stringify(metadataValue)
const payload = { schema: 1, target: policy.target, channel: policy.channel, issued: new Date(now).toISOString(),
  expires: new Date(now + 86_400_000).toISOString(), metadata }
function envelope(value: object): string {
  const bytes = Buffer.from(JSON.stringify(value))
  return JSON.stringify({ payload: bytes.toString('base64'), signature: sign(null, bytes, keys.privateKey).toString('base64') })
}

describe('installed update trust', () => {
  it('keeps the independent public key consistent and refuses a foreign publication key', () => {
    expect(readFileSync(new URL('../../../../../deploy/client-update-public-key.pem', import.meta.url), 'utf8')).toBe(DESKTOP_UPDATE_PUBLIC_KEY)
    const foreign = keys.privateKey.export({ format: 'pem', type: 'pkcs8' })
    expect(() => signUpdateMetadata(metadata, policy, Buffer.from(foreign),
      new Date(Date.now() + 86_400_000).toISOString())).toThrow(/signature/u)
  })
  it('authenticates exact metadata while preserving its download digest', () => {
    expect(verifySignedUpdate(envelope(payload), policy, publicKey, now)).toEqual(metadataValue)
  })
  it('rejects raw feeds, tampering, a foreign key and oversized metadata', () => {
    expect(() => verifySignedUpdate(metadata, policy, publicKey, now)).toThrow(/envelope/u)
    const raw = JSON.parse(envelope(payload)) as { payload: string; signature: string }
    raw.payload = Buffer.from(JSON.stringify({ ...payload, metadata: '{}' })).toString('base64')
    expect(() => verifySignedUpdate(JSON.stringify(raw), policy, publicKey, now)).toThrow(/signature/u)
    expect(() => verifySignedUpdate(envelope(payload), policy, undefined, now)).toThrow(/signature/u)
    expect(() => verifySignedUpdate(' '.repeat(100_001), policy, publicKey, now)).toThrow(/oversized/u)
  })
  it.each([
    { target: 'mac-x64' }, { channel: 'nightly' }, { schema: 2 },
    { expires: new Date(now).toISOString() }, { issued: new Date(now + 600_000).toISOString() },
    { expires: new Date(now + 91 * 86_400_000).toISOString() },
  ])('rejects mismatched or expired metadata: %j', (changes) => {
    expect(() => verifySignedUpdate(envelope({ ...payload, ...changes }), policy, publicKey, now)).toThrow(/release/u)
  })
  it.each([
    { version: '0.2.1-rc.1' }, { version: 'bad' }, { files: [] }, { packages: {} },
    { files: [{ url: 'https://other.example.com/update.zip', sha512: Buffer.alloc(64).toString('base64'), size: 1 }] },
    { files: [{ url: 'update.exe', sha512: Buffer.alloc(64).toString('base64'), size: 1 }] },
    { files: [{ url: 'update.zip?redirect=1', sha512: Buffer.alloc(64).toString('base64'), size: 1 }] },
    { files: [{ url: 'update.zip', sha512: 'invalid', size: 1 }] },
  ])('rejects an unsafe signed upstream document: %j', (changes) => {
    const changed = envelope({ ...payload, metadata: JSON.stringify({ ...metadataValue, ...changes }) })
    expect(() => verifySignedUpdate(changed, policy, publicKey, now)).toThrow(/release identity|artifact/u)
  })
  it('uses the installed identity and rejects missing or non-HTTPS feeds', () => {
    const manifest = { dshDesktopUpdateFeed: policy.url, dshDesktopUpdateChannel: policy.channel }
    expect(signedUpdatePolicy(manifest, 'darwin', 'arm64')).toEqual(policy)
    expect(() => signedUpdatePolicy({}, 'darwin', 'arm64')).toThrow(/missing/u)
    expect(() => signedUpdatePolicy(manifest, 'linux', 'x64')).toThrow(/unsupported/u)
    expect(() => signedUpdatePolicy({ ...manifest, dshDesktopUpdateFeed: 'http://updates.example.com/' }, 'darwin', 'arm64')).toThrow(/origin/u)
  })
})
