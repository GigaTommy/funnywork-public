import { afterEach, expect, it } from 'vitest'
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { EnterpriseAdmissionStore, readEntryCredential } from '../src/enterprise-store.ts'
const directories: string[] = []
afterEach(async () => {
  for (const dir of directories.splice(0)) await rm(dir, { recursive: true, force: true })
})
async function directory() {
  const dir = await mkdtemp(join(tmpdir(), 'fw-admissions-'))
  directories.push(dir)
  return dir
}
it('concurrent owners recover one complete record and preserve it across Host generations', async () => {
  const dir = await directory(),
    first = new EnterpriseAdmissionStore(dir),
    second = new EnterpriseAdmissionStore(dir)
  const records = await Promise.all(
    Array.from({ length: 16 }, (_, index) => (index % 2 === 0 ? first : second).acquire('fingerprint')),
  )
  expect(new Set(records.map(record => record.callId)).size).toBe(1)
  await first.save('fingerprint', { ...records[0]!, ticketId: 'ticket-1' })
  expect((await new EnterpriseAdmissionStore(dir).acquire('fingerprint')).ticketId).toBe('ticket-1')
  const files = await readdir(dir)
  expect(files).toHaveLength(1)
  expect(await readFile(join(dir, files[0]!), 'utf8')).not.toContain('fingerprint')
  if (process.platform !== 'win32') expect((await stat(join(dir, files[0]!))).mode & 0o777).toBe(0o600)
  await second.remove('fingerprint', records[0]!)
  expect(await readdir(dir)).toEqual([])
})
it('refuses corrupted recovery records instead of minting a second call', async () => {
  const dir = await directory(),
    store = new EnterpriseAdmissionStore(dir)
  await store.acquire('fingerprint')
  const files = await readdir(dir)
  await writeFile(join(dir, files[0]!), '{}')
  await expect(store.acquire('fingerprint')).rejects.toThrow('Invalid admission recovery')
})
it('late cleanup of a completed call cannot delete its replacement', async () => {
  const store = new EnterpriseAdmissionStore(await directory()),
    old = await store.acquire('same')
  await store.remove('same', old)
  const replacement = await store.acquire('same')
  await store.remove('same', old)
  expect((await store.acquire('same')).callId).toBe(replacement.callId)
  await expect(store.save('same', { ...old, ticketId: 'old-ticket' })).rejects.toThrow('identity changed')
})
it('refuses expired and cross-origin credential files', async () => {
  const file = join(await directory(), 'credential.json'),
    value = {
      origin: 'https://enterprise.example',
      token: 'mwi.' + 'a'.repeat(24),
      expires: Date.now() + 60000,
      device: 'desktop-1',
    }
  await writeFile(file, JSON.stringify(value))
  await expect(readEntryCredential(file, value.origin)).resolves.toMatchObject({ device: 'desktop-1' })
  await expect(readEntryCredential(file, 'https://another.example')).rejects.toThrow('invalid')
  await writeFile(file, JSON.stringify({ ...value, expires: 0 }))
  await expect(readEntryCredential(file, value.origin)).rejects.toThrow('expired')
})
