/** Sign a prepared updater document without sending private keys to the Web host. */
import { createPrivateKey, createPublicKey, sign } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'
import { parseArgs } from 'node:util'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { verifySignedUpdate, type SignedUpdatePolicy } from '../src/signed-update.ts'

/**
 * Sign normalized metadata and confirm it matches the installed trust anchor.
 * @param metadata Final published YAML with the immutable artifact URL.
 * @param policy Published platform, channel and feed identity.
 * @param privateKey Restricted local PEM contents.
 * @param expires Explicit expiration time for this publication.
 * @returns Authenticated envelope containing no private key material.
 */
export function signUpdateMetadata(metadata: string, policy: SignedUpdatePolicy, privateKey: Buffer, expires: string): string {
  const payload = Buffer.from(JSON.stringify({ schema: 1, target: policy.target, channel: policy.channel,
    issued: new Date().toISOString(), expires, metadata }))
  const key = createPrivateKey(privateKey)
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Expected Ed25519 signing key')
  const envelope = JSON.stringify({ payload: payload.toString('base64'), signature: sign(null, payload, key).toString('base64') })
  verifySignedUpdate(envelope, policy)
  verifySignedUpdate(envelope, policy, createPublicKey(key).export({ format: 'pem', type: 'spki' }).toString())
  return envelope
}

async function main(): Promise<void> {
  const { values } = parseArgs({ options: {
    metadata: { type: 'string' }, key: { type: 'string' }, output: { type: 'string' },
    target: { type: 'string' }, channel: { type: 'string' }, url: { type: 'string' }, expires: { type: 'string' },
  } })
  if (!values.metadata || !values.key || !values.output || !values.url || !values.expires
    || !['mac-arm64', 'mac-x64', 'win-x64'].includes(values.target ?? '')
    || (values.channel !== 'latest' && values.channel !== 'nightly')) throw new Error('Expected metadata, key, output, target, channel, url and expires')
  const target = values.target
  if (target !== 'mac-arm64' && target !== 'mac-x64' && target !== 'win-x64') throw new Error('Invalid target')
  const policy: SignedUpdatePolicy = { target, channel: values.channel, url: values.url }
  const envelope = signUpdateMetadata(await readFile(values.metadata, 'utf8'), policy, await readFile(values.key), values.expires)
  const trusted = verifySignedUpdate(envelope, policy)
  await writeFile(values.output, envelope + '\n')
  console.log(`Signed ${policy.target} ${policy.channel} ${trusted.version}`)
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error: unknown) => { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1 })
}
