/** Desktop IPC paths and ownership admit only a real owner-private lease. */
import { chmod, lstat, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { expect, it, onTestFinished } from 'vitest'
import { acquireDesktopProcessLease, desktopProcessLeasePath } from '../src/process-lease.ts'

async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), 'desktop-lease-path-'))
  onTestFinished(() => rm(directory, { recursive: true, force: true }))
  return { directory, path: join(directory, 'desktop.lease'), signal: new AbortController().signal }
}

it('uses the native macOS OS-user IPC location despite task temporary-directory configuration', () => {
  if (process.platform === 'darwin') expect(desktopProcessLeasePath()).toBe(`/tmp/dsh-cua-${process.getuid!()}/desktop-turn.lease`)
  expect(() => desktopProcessLeasePath('relative.lease')).toThrow('absolute')
})

it('holds the existing owner-private writer record until explicit release', async () => {
  const f = await fixture()
  const lease = await acquireDesktopProcessLease({ path: f.path, waitMs: 100 }, f.signal)
  expect(await readFile(`${f.path}.lock`, 'utf8')).toBe(`${process.pid}\n`)
  if (process.platform !== 'win32') expect((await lstat(`${f.path}.lock`)).mode & 0o777).toBe(0o600)
  await lease.release()
  await expect(lstat(`${f.path}.lock`)).rejects.toMatchObject({ code: 'ENOENT' })
})

it('rejects broad directory permissions and symbolic-link lock files', async () => {
  const f = await fixture()
  if (process.platform !== 'win32') {
    await chmod(f.directory, 0o755)
    await expect(acquireDesktopProcessLease({ path: f.path, waitMs: 100 }, f.signal)).rejects.toThrow('owner-only directory')
    await chmod(f.directory, 0o700)
  }
  const target = join(f.directory, 'target')
  await writeFile(target, 'preserved', { mode: 0o600 })
  await symlink(target, `${f.path}.lock`)
  await expect(acquireDesktopProcessLease({ path: f.path, waitMs: 100 }, f.signal)).rejects.toThrow('regular owner-only file')
  expect(await readFile(target, 'utf8')).toBe('preserved')
})
