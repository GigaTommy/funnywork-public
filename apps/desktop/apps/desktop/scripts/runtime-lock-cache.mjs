/** Preserve the resolved runtime lockfile for unchanged, integrity-pinned package inputs. */
import { createHash, randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import yaml from 'js-yaml'

/**
 * Identify one resolved lock by package descriptors, install configuration and referenced patch bytes.
 * @param {string} project - Disposable runtime project.
 * @param {string} cache - Persistent target metadata cache.
 * @param {string} registry - Registry whose resolutions the lock contains.
 * @returns {string} Content-addressed cached lockfile path.
 */
export function runtimeLockCachePath(project, cache, registry) {
  const hash = createHash('sha256').update(registry)
  for (const name of ['package.json', 'pnpm-workspace.yaml', 'desktop-packages.json']) {
    hash.update(name).update('\0').update(readFileSync(join(project, name))).update('\0')
  }
  const workspace = yaml.load(readFileSync(join(project, 'pnpm-workspace.yaml'), 'utf8'))
  for (const [spec, path] of Object.entries(workspace.patchedDependencies ?? {}).sort(([left], [right]) => left.localeCompare(right))) {
    hash.update(spec).update('\0').update(readFileSync(join(project, path))).update('\0')
  }
  return join(cache, 'runtime-locks', `${hash.digest('hex')}.yaml`)
}

/**
 * Restore a matching lock without consulting registry metadata.
 * @param {string} project - Disposable runtime project.
 * @param {string} cached - Input-addressed lock path.
 * @returns {boolean} Whether an existing lock was restored.
 */
export function restoreRuntimeLock(project, cached) {
  let bytes
  try { bytes = readFileSync(cached) }
  catch (error) { if (error instanceof Error && 'code' in error && error.code === 'ENOENT') return false; throw error }
  writeFileSync(join(project, 'pnpm-lock.yaml'), bytes)
  return true
}

/**
 * Atomically retain the resolved lock; temporary assembly cleanup cannot remove it.
 * @param {string} project - Verified runtime project.
 * @param {string} cached - Input-addressed lock path.
 * @returns {void} After the cached file is complete.
 */
export function cacheRuntimeLock(project, cached) {
  mkdirSync(dirname(cached), { recursive: true })
  const temporary = `${cached}.${randomUUID()}.tmp`
  try {
    writeFileSync(temporary, readFileSync(join(project, 'pnpm-lock.yaml')), { flag: 'wx', mode: 0o600 })
    renameSync(temporary, cached)
  } finally { if (existsSync(temporary)) unlinkSync(temporary) }
}
