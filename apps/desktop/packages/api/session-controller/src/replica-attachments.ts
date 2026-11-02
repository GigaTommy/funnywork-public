/** Bounded reads of provider-owned attachments proven reachable from a native log. */
import { open } from 'node:fs/promises'
import type { SessionPersistence } from '@deepseek-ai/dsh-session-persistence'
import type { Context } from '@deepseek-ai/cordis'
import { AttachmentId, type FileAttachmentRef, type ImageAttachmentRef } from '@deepseek-ai/dsh-attachment'
import { RemoteError } from '@deepseek-ai/dsh-typert-protocol'
import type {
  SessionReplicaAttachmentChunk, SessionReplicaAttachmentChunkRequest,
} from './types.ts'

const grants = new WeakMap<SessionPersistence, Map<string, {
  revision: string
  until: number
  ref: FileAttachmentRef | ImageAttachmentRef
}>>()

function record(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value) }

function reference(value: unknown, id: string): FileAttachmentRef | ImageAttachmentRef | undefined {
  const stack: unknown[] = [value]
  while (stack.length > 0) {
    const item = stack.pop()
    if (Array.isArray(item)) { for (const child of item) stack.push(child); continue }
    if (!record(item)) continue
    const ref = item.attachment
    if ((item.type === 'file' || item.type === 'image') && record(ref) && ref.attachmentId === id && Number.isSafeInteger(ref.bytes) && Number(ref.bytes) >= 0) {
      if (item.type === 'file' && typeof ref.name === 'string') return { attachmentId: AttachmentId(id), bytes: Number(ref.bytes), name: ref.name }
      if (item.type === 'image' && (ref.mediaType === 'image/png' || ref.mediaType === 'image/jpeg' || ref.mediaType === 'image/webp' || ref.mediaType === 'image/gif') && Number.isSafeInteger(ref.width) && Number.isSafeInteger(ref.height)) return { attachmentId: AttachmentId(id), bytes: Number(ref.bytes), mediaType: ref.mediaType, width: Number(ref.width), height: Number(ref.height) }
    }
    for (const child of Object.values(item)) stack.push(child)
  }
}

/**
 * Read one fixed chunk without exposing or accepting a filesystem path.
 * @param ctx - Host carrying native persistence and its attachment provider.
 * @param request - owning Session, content identity, and byte offset.
 * @param signal - cancellation for authorization and file access.
 * @returns at most 384000 bytes encoded as base64 with the next byte offset.
 */
export async function replicaAttachmentChunk(
  ctx: Context, request: SessionReplicaAttachmentChunkRequest, signal: AbortSignal,
): Promise<SessionReplicaAttachmentChunk> {
  if (!/^sha256:[a-f0-9]{64}$/.test(request.attachmentId) || !Number.isSafeInteger(request.offset) || request.offset < 0) throw new RemoteError('gateway/bad-request', 'Invalid attachment cursor', {})
  const persistence = ctx.get('sessionPersistence')
  if (persistence === undefined) throw new RemoteError('session/persistence-unavailable', 'Session persistence is unavailable', {})
  const before = await persistence.stat(request.sessionId, { signal })
  await using reader = await persistence.open(request.sessionId, 'read', { signal })
  let cache = grants.get(persistence)
  if (cache === undefined) { cache = new Map(); grants.set(persistence, cache) }
  const key = `${request.sessionId}:${request.attachmentId}`
  const prior = cache.get(key)
  // Positive proofs belong to one backend revision; file checks still run for every chunk.
  let ref = prior !== undefined && prior.revision === before?.revision && prior.until > Date.now() ? prior.ref : undefined
  const reused = ref !== undefined
  for (let offset = 0; ref === undefined;) {
    const page = await reader.read(offset, 100, { signal })
    for (const event of page.events) { ref = reference(event.data, request.attachmentId); if (ref !== undefined) break }
    if (page.events.length < 100) break
    offset += page.events.length
  }
  if (!reused && ref !== undefined && before !== undefined) {
    const after = await persistence.stat(request.sessionId, { signal })
    if (after?.revision === before.revision) {
      if (cache.size >= 128) { const oldest = cache.keys().next().value; if (oldest !== undefined) cache.delete(oldest) }
      cache.set(key, { revision: before.revision, until: Date.now() + 60_000, ref })
    }
  }
  if (ref === undefined || request.offset > ref.bytes) throw new RemoteError('gateway/bad-request', 'Attachment is not referenced by the Session', {})
  const path = 'mediaType' in ref ? ctx.attachments.imageHostPath(ref) : ctx.attachments.fileHostPath(ref)
  if (path === undefined) throw new RemoteError('gateway/bad-request', 'Attachment provider does not support ranged native reads', {})
  signal.throwIfAborted()
  const file = await open(path, 'r')
  try {
    const stat = await file.stat()
    if (!stat.isFile() || stat.size !== ref.bytes) throw new Error('Attachment size differs from its durable reference')
    const bytes = Buffer.alloc(Math.min(384000, ref.bytes - request.offset))
    const result = await file.read(bytes, 0, bytes.length, request.offset)
    signal.throwIfAborted()
    if (result.bytesRead !== bytes.length) throw new Error('Attachment changed during its read')
    return { data: bytes.toString('base64'), nextOffset: request.offset + bytes.length }
  } finally { await file.close() }
}
