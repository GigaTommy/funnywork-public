/** Downloads verified message attachments before invoking the local upload provider. */
import { createHash } from 'node:crypto'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * Resolve at most four files, bounded to 32 MiB in total, without local path input.
 * @param account - authenticated cloud request capability.
 * @param sessionId - cloud Session owning these command attachments.
 * @param value - attachment descriptors from the claimed command.
 * @param active - throws when this Host generation is no longer current.
 * @returns exact bytes verified against each descriptor's length and SHA-256.
 */
export async function downloadMobileAttachments(
  account: FunnyWorkerAccount, sessionId: unknown, value: unknown, active: () => void,
): Promise<{ hash: string; name: string; data: Buffer }[]> {
  if (typeof sessionId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(sessionId) || !Array.isArray(value) || value.length < 1 || value.length > 4) throw new Error('Invalid attachment command')
  const files: { hash: string; name: string; bytes: number }[] = []
  let total = 0
  const seen = new Set<string>()
  for (const file of value) {
    if (!record(file) || typeof file.hash !== 'string' || !/^[a-f0-9]{64}$/.test(file.hash) || seen.has(file.hash) || typeof file.name !== 'string' || file.name.length === 0 || file.name.length > 255 || /[/\\\x00-\x1f\x7f]/.test(file.name) || !Number.isSafeInteger(file.bytes) || Number(file.bytes) < 0 || Number(file.bytes) > 33_554_432) throw new Error('Invalid attachment descriptor')
    total += Number(file.bytes)
    if (total > 33_554_432) throw new Error('Attachment command exceeds capacity')
    seen.add(file.hash); files.push({ hash: file.hash, name: file.name, bytes: Number(file.bytes) })
  }
  const result: { hash: string; name: string; data: Buffer }[] = []
  for (const file of files) {
    const chunks: Buffer[] = []
    let offset = 0
    do {
      active()
      const chunk = await account.syncRequest(`/sessions/${sessionId}/attachments/${file.hash}?offset=${offset}`)
      active()
      if (!record(chunk) || typeof chunk.data !== 'string' || chunk.data.length > 512_000 || typeof chunk.complete !== 'boolean') throw new Error('Invalid attachment chunk')
      const data = Buffer.from(chunk.data, 'base64')
      const next = offset + data.length
      if (data.toString('base64') !== chunk.data || chunk.nextOffset !== next || next > file.bytes || (next === offset && file.bytes !== 0) || chunk.complete !== (next === file.bytes)) throw new Error('Invalid attachment cursor')
      chunks.push(data); offset = next
      if (chunk.complete) break
    } while (offset < file.bytes)
    const data = Buffer.concat(chunks)
    if (data.length !== file.bytes || createHash('sha256').update(data).digest('hex') !== file.hash) throw new Error('Attachment checksum mismatch')
    result.push({ hash: file.hash, name: file.name, data })
  }
  return result
}
