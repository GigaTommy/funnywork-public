/** Resolve exact cached dependencies before allowing registry access. */

/**
 * Run offline first and permit a registry request only for missing cache entries.
 * @param {(offline: boolean) => Promise<void>} run - One supervised package-manager attempt.
 * @returns {Promise<void>} After dependency verification succeeds; other failures remain errors.
 */
export async function installCachedPnpm(run) {
  try { await run(true) }
  catch (error) {
    if (!(error instanceof Error) || !/\bERR_PNPM_(?:NO_OFFLINE_META|NO_OFFLINE_TARBALL|CANNOT_DOWNLOAD_BINARY_OFFLINE)\b/u.test(error.message)) throw error
    process.stdout.write('desktop dependencies: required cache entry missing; resolving from the configured registry\n')
    await run(false)
  }
}
