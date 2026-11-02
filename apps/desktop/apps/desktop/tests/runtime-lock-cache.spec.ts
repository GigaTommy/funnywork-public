import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { cacheRuntimeLock, restoreRuntimeLock, runtimeLockCachePath } from '../scripts/runtime-lock-cache.mjs'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })
function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'dsh-runtime-lock-cache-')); roots.push(root)
  const project = join(root, 'project'); mkdirSync(project)
  for (const name of ['package.json', 'pnpm-workspace.yaml', 'desktop-packages.json']) writeFileSync(join(project, name), `${name}: original`)
  return { root, project, cache: join(root, 'cache') }
}
it('reuses a resolved lock after a disposable project is recreated with matching inputs', () => {
  const { project, cache } = fixture()
  const key = runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')
  expect(restoreRuntimeLock(project, key)).toBe(false)
  writeFileSync(join(project, 'pnpm-lock.yaml'), 'verified exact versions')
  cacheRuntimeLock(project, key)
  rmSync(join(project, 'pnpm-lock.yaml'))
  expect(restoreRuntimeLock(project, key)).toBe(true)
  expect(readFileSync(join(project, 'pnpm-lock.yaml'), 'utf8')).toBe('verified exact versions')
})
it.each(['package.json', 'pnpm-workspace.yaml', 'desktop-packages.json'])('invalidates a lock when %s changes, including package integrity', (name) => {
  const { project, cache } = fixture()
  const key = runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')
  writeFileSync(join(project, name), 'changed version or integrity')
  expect(runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')).not.toBe(key)
})
it('isolates resolutions from different registries', () => {
  const { project, cache } = fixture()
  expect(runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')).not.toBe(runtimeLockCachePath(project, cache, 'https://another.example'))
})
it('invalidates cached resolution when referenced patch bytes change at the same path', () => {
  const { project, cache } = fixture()
  mkdirSync(join(project, 'patches'))
  writeFileSync(join(project, 'pnpm-workspace.yaml'), 'patchedDependencies:\n  fixture@1.0.0: patches/fixture.patch\n')
  const patch = join(project, 'patches/fixture.patch')
  writeFileSync(patch, 'original patch bytes')
  const key = runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')
  writeFileSync(join(project, 'pnpm-lock.yaml'), 'lock for the original patch')
  cacheRuntimeLock(project, key)
  writeFileSync(patch, 'updated security patch bytes')
  const updated = runtimeLockCachePath(project, cache, 'https://registry.npmjs.org')
  expect(updated).not.toBe(key)
  expect(restoreRuntimeLock(project, updated)).toBe(false)
  expect(restoreRuntimeLock(project, key)).toBe(true)
})
