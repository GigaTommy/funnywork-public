/**
 * Verify cached dependencies offline, requesting registry data only for cache misses.
 * @param run - One supervised package-manager attempt.
 * @returns After a successful attempt; integrity, lockfile and execution failures reject.
 */
export function installCachedPnpm(run: (offline: boolean) => Promise<void>): Promise<void>
