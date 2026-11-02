/** Use the checksum shipped in the integrity-locked official Electron npm package. */

/**
 * Select one archive's trusted digest without disabling @electron/get verification.
 * @param version - Installed Electron version.
 * @param platform - Target platform.
 * @param arch - Target architecture.
 * @param metadata - Official electron/checksums.json contents from the same npm package.
 * @returns The single archive digest accepted by @electron/get's checksums option.
 */
export function desktopElectronChecksums(
  version: string, platform: string, arch: string, metadata: unknown,
): Record<string, string> {
  const filename = `electron-v${version}-${platform}-${arch}.zip`
  const digest = metadata !== null && typeof metadata === 'object'
    ? (metadata as Record<string, unknown>)[filename] : undefined
  if (typeof digest !== 'string' || !/^[a-f0-9]{64}$/u.test(digest)) {
    throw new Error(`desktop runtime: official Electron package has no valid SHA-256 for ${filename}`)
  }
  return { [filename]: digest }
}
