import { lstat, mkdtemp, mkdir, readFile, readlink, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { optimizePrimaryRuntime, thinMachO } from './optimize.ts'
import { copyPrimaryRuntime } from '@deepseek-ai/dsh-tool-workspace-dependencies'
const roots: string[] = []
afterEach(async () => { await Promise.all(roots.splice(0).map(root => rm(root, { recursive: true, force: true }))) })
function image() {
  const bytes = Buffer.alloc(80)
  bytes.writeUInt32BE(0xcafebabe); bytes.writeUInt32BE(2, 4)
  for (const [i, cpu] of [0x01000007, 0x0100000c].entries()) {
    const entry = 8 + i * 20, offset = 48 + i * 16
    bytes.writeUInt32BE(cpu, entry); bytes.writeUInt32BE(offset, entry + 8); bytes.writeUInt32BE(16, entry + 12)
    bytes.writeUInt32LE(0xfeedfacf, offset); bytes.writeUInt32LE(cpu, offset + 4)
  }
  return bytes
}
it('selects the target architecture independently of the host and rejects broken slices', () => {
  const universal = image()
  expect(thinMachO(universal, 'arm64').readUInt32LE(4)).toBe(0x0100000c)
  expect(thinMachO(universal, 'x64').readUInt32LE(4)).toBe(0x01000007)
  const ordinary = Buffer.from('ordinary resource')
  expect(thinMachO(ordinary, 'arm64')).toBe(ordinary)
  const missing = image(); missing.writeUInt32BE(1, 4)
  expect(() => thinMachO(missing, 'arm64')).toThrow('missing arm64')
  const truncated = image(); truncated.writeUInt32BE(100, 40)
  expect(() => thinMachO(truncated, 'arm64')).toThrow('invalid architecture slice')
  const invalid = image(); invalid.writeUInt32BE(100, 4)
  expect(() => thinMachO(invalid, 'arm64')).toThrow('invalid universal image')
})
// POSIX file aliases cannot be created by unprivileged Windows test processes.
it.skipIf(process.platform === 'win32')('retains complete libraries and relocatable interpreter aliases through payload copying', async () => {
  const root = await mkdtemp(join(tmpdir(), 'runtime-optimize-')); roots.push(root)
  const bin = join(root, 'dependencies/python/bin'), packages = join(root, 'dependencies/python/lib/site-packages')
  await mkdir(bin, { recursive: true }); await mkdir(packages, { recursive: true })
  await writeFile(join(bin, 'python3.12'), 'interpreter')
  await writeFile(join(bin, 'python'), 'interpreter')
  await symlink('python3.12', join(bin, 'python3'))
  await writeFile(join(packages, 'library.so'), image())
  await writeFile(join(packages, 'complete-library.py'), 'library with tests retained')
  const pnpm = join(root, 'dependencies/pnpm/dist/node_modules/@reflink')
  for (const name of ['reflink-darwin-arm64', 'reflink-darwin-x64', 'reflink-win32-x64-msvc']) await mkdir(join(pnpm, name), { recursive: true })
  await optimizePrimaryRuntime(root, 'mac-arm64', '3.12.14')
  await optimizePrimaryRuntime(root, 'mac-arm64', '3.12.14')
  for (const alias of ['python', 'python3']) expect(await readlink(join(bin, alias))).toBe('python3.12')
  expect(await readFile(join(packages, 'complete-library.py'), 'utf8')).toContain('tests retained')
  expect((await readFile(join(packages, 'library.so'))).length).toBe(16)
  expect((await stat(join(pnpm, 'reflink-darwin-arm64'))).isDirectory()).toBe(true)
  await expect(stat(join(pnpm, 'reflink-darwin-x64'))).rejects.toMatchObject({ code: 'ENOENT' })
  await expect(stat(join(pnpm, 'reflink-win32-x64-msvc'))).rejects.toMatchObject({ code: 'ENOENT' })
  const relocated = await mkdtemp(join(tmpdir(), 'runtime-relocated-')); roots.push(relocated)
  await copyPrimaryRuntime(root, relocated, { desktopVersion: 'test', platform: 'darwin', arch: 'arm64', python: '3.12.14', pythonPackages: {} })
  await rm(root, { recursive: true, force: true })
  for (const alias of ['python', 'python3']) {
    const path = join(relocated, 'dependencies/python/bin', alias)
    expect((await lstat(path)).isSymbolicLink()).toBe(true)
    expect(await readlink(path)).toBe('python3.12')
    expect(await readFile(path, 'utf8')).toBe('interpreter')
  }
  expect((await lstat(join(relocated, 'dependencies/python/bin/python3.12'))).isFile()).toBe(true)
})
// A previously prepared FunnyWork payload used a shell wrapper at each alias.
it.skipIf(process.platform === 'win32')('normalizes prior shell wrappers into direct relative aliases', async () => {
  const root = await mkdtemp(join(tmpdir(), 'runtime-wrapper-aliases-')); roots.push(root)
  const bin = join(root, 'dependencies/python/bin'); await mkdir(bin, { recursive: true })
  await writeFile(join(bin, 'python3.12'), 'interpreter')
  for (const alias of ['python', 'python3']) await writeFile(join(bin, alias), '#!/bin/sh\nexec "${0%/*}/python3.12" "$@"\n')
  await optimizePrimaryRuntime(root, 'linux-x64', '3.12.14')
  for (const alias of ['python', 'python3']) expect(await readlink(join(bin, alias))).toBe('python3.12')
})
it('does not overwrite a non-equivalent interpreter alias', async () => {
  const root = await mkdtemp(join(tmpdir(), 'runtime-alias-')); roots.push(root)
  const bin = join(root, 'dependencies/python/bin'); await mkdir(bin, { recursive: true })
  await writeFile(join(bin, 'python3.12'), 'interpreter'); await writeFile(join(bin, 'python'), 'different tool')
  await expect(optimizePrimaryRuntime(root, 'linux-x64', '3.12.14')).rejects.toThrow('unexpected Python alias')
  expect(await readFile(join(bin, 'python'), 'utf8')).toBe('different tool')
})
