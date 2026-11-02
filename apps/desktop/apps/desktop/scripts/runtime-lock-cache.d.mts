/**
 * Identify the runtime lock from integrity-pinned package inputs, configuration, referenced patch bytes and registry.
 * @param project - Temporary runtime project.
 * @param cache - Persistent target cache.
 * @param registry - Configured package registry.
 * @returns Cached lock path.
 */
export function runtimeLockCachePath(project: string, cache: string, registry: string): string
/**
 * Restore the exact cached lock.
 * @param project - Temporary runtime project.
 * @param cached - Cached lock path.
 * @returns Whether a matching lock exists.
 */
export function restoreRuntimeLock(project: string, cached: string): boolean
/**
 * Retain the verified runtime lock atomically.
 * @param project - Temporary runtime project.
 * @param cached - Cached lock path.
 * @returns After the file is saved.
 */
export function cacheRuntimeLock(project: string, cached: string): void
