/** Private admission records. Only identifiers and request fingerprints reach disk. */
import { mkdir, readFile, writeFile, rename, rm, link } from 'node:fs/promises'
import { createHash, randomUUID } from 'node:crypto'
import { join } from 'node:path'
import type { AdmissionRecord } from './admission.ts'

/**
 * Parse the file owned by Electron; reject expired or cross-origin credentials.
 * @param path - absolute account-private credential file.
 * @param origin - the configured enterprise origin to match.
 * @returns a valid scoped token and its device identifier.
 */
export async function readEntryCredential(path: string, origin: string): Promise<{ token: string; device: string }> {
  const text = await readFile(path, 'utf8')
  if (Buffer.byteLength(text) > 4096) throw new Error('Invalid entry credential file')
  const value: unknown = JSON.parse(text)
  if (
    typeof value !== 'object' ||
    value === null ||
    !('origin' in value) ||
    value.origin !== origin ||
    !('token' in value) ||
    typeof value.token !== 'string' ||
    !/^mwi\.[A-Za-z0-9_-]{20,512}$/.test(value.token) ||
    !('expires' in value) ||
    typeof value.expires !== 'number' ||
    !Number.isSafeInteger(value.expires) ||
    value.expires <= Date.now() ||
    !('device' in value) ||
    typeof value.device !== 'string' ||
    !/^[A-Za-z0-9_-]{1,64}$/.test(value.device)
  )
    throw new Error('Entry credential is expired or invalid')
  return { token: value.token, device: value.device }
}

/** Atomic durable admission storage shared by Host generations of one account. */
export class EnterpriseAdmissionStore {
  private readonly locks = new Map<string, Promise<void>>()
  constructor(private readonly directory: string) {}
  private async locked<T>(key: string, action: () => Promise<T>): Promise<T> {
    const previous = this.locks.get(key),
      completion = Promise.withResolvers<void>()
    this.locks.set(key, completion.promise)
    try {
      await previous
      return await action()
    } finally {
      completion.resolve()
      if (this.locks.get(key) === completion.promise) this.locks.delete(key)
    }
  }
  private path(fingerprint: string): string {
    return join(this.directory, createHash('sha256').update(fingerprint).digest('hex') + '.json')
  }
  private async read(path: string): Promise<AdmissionRecord> {
    const text = await readFile(path, 'utf8')
    if (Buffer.byteLength(text) > 4096) throw new Error('Invalid admission record')
    const value: unknown = JSON.parse(text)
    if (
      typeof value !== 'object' ||
      value === null ||
      !('callId' in value) ||
      typeof value.callId !== 'string' ||
      !/^[A-Za-z0-9_.:-]{1,128}$/.test(value.callId) ||
      !('logicalCallId' in value) ||
      typeof value.logicalCallId !== 'string' ||
      !/^[A-Za-z0-9_.:-]{1,128}$/.test(value.logicalCallId) ||
      ('ticketId' in value && (typeof value.ticketId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(value.ticketId)))
    )
      throw new Error('Invalid admission recovery record')
    return {
      callId: value.callId,
      logicalCallId: value.logicalCallId,
      ...('ticketId' in value ? { ticketId: value.ticketId as string } : {}),
    }
  }
  /**
   * Create a complete record exclusively, or read the existing call after a reconnect.
   * @param fingerprint - stable request identity hashed for the file name.
   * @returns the existing or exclusively created call identifiers.
   */
  async acquire(fingerprint: string): Promise<AdmissionRecord> {
    return await this.locked(fingerprint, () => this.acquireRecord(fingerprint))
  }
  private async acquireRecord(fingerprint: string): Promise<AdmissionRecord> {
    await mkdir(this.directory, { recursive: true, mode: 0o700 })
    const path = this.path(fingerprint)
    try {
      return await this.read(path)
    } catch (error) {
      if (!(error instanceof Error && 'code' in error && error.code === 'ENOENT')) throw error
    }
    const record = { callId: randomUUID(), logicalCallId: randomUUID() },
      temporary = path + '.' + randomUUID() + '.tmp'
    try {
      await writeFile(temporary, JSON.stringify(record), { mode: 0o600, flag: 'wx' })
      try {
        await link(temporary, path)
      } catch (error) {
        if (!(error instanceof Error && 'code' in error && error.code === 'EEXIST')) throw error
      }
      return await this.read(path)
    } finally {
      await rm(temporary, { force: true })
    }
  }
  /**
   * Save only recovery metadata, preserving the original admission identity.
   * @param fingerprint - stable request identity.
   * @param record - matching call identifiers and server ticket.
   */
  async save(fingerprint: string, record: AdmissionRecord): Promise<void> {
    await this.locked(fingerprint, () => this.saveRecord(fingerprint, record))
  }
  private async saveRecord(fingerprint: string, record: AdmissionRecord): Promise<void> {
    const path = this.path(fingerprint),
      previous = await this.read(path)
    if (
      previous.callId !== record.callId ||
      previous.logicalCallId !== record.logicalCallId ||
      (previous.ticketId !== undefined && previous.ticketId !== record.ticketId)
    )
      throw new Error('Admission identity changed')
    const temporary = path + '.' + randomUUID() + '.tmp'
    try {
      await writeFile(temporary, JSON.stringify(record), { mode: 0o600, flag: 'wx' })
      await rename(temporary, path)
    } finally {
      await rm(temporary, { force: true })
    }
  }
  /**
   * Remove a terminal call only if its stored identity still matches.
   * @param fingerprint - stable request identity.
   * @param record - completed call identifiers; a replacement is preserved.
   */
  async remove(fingerprint: string, record: AdmissionRecord): Promise<void> {
    await this.locked(fingerprint, async () => {
      let current: AdmissionRecord
      try {
        current = await this.read(this.path(fingerprint))
      } catch (error) {
        if (error instanceof Error && 'code' in error && error.code === 'ENOENT') return
        throw error
      }
      if (current.callId === record.callId && current.logicalCallId === record.logicalCallId)
        await rm(this.path(fingerprint), { force: true })
    })
  }
}
