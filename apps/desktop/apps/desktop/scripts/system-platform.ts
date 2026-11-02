/** Target-specific POSIX native payloads that are mandatory in a Desktop release. */

/** Resolve the independent system package; Windows uses the existing semaphore implementation. */
export function desktopSystemPlatformPackage(target: { platform: string; arch: string }): string | undefined {
  if (target.platform === 'win32') return undefined
  if (!['darwin', 'linux'].includes(target.platform) || !['arm64', 'x64'].includes(target.arch)) {
    throw new Error(`desktop system: unsupported target ${target.platform}-${target.arch}`)
  }
  return `@deepseek-ai/node-addon-system-${target.platform}-${target.arch}`
}

/** Complete native package contract, including both Linux libc variants and its confinement launcher. */
export function desktopSystemPlatformFiles(target: { platform: string; arch: string }): readonly string[] {
  if (desktopSystemPlatformPackage(target) === undefined) return []
  return ['package.json', 'prebuilds.json', ...(target.platform === 'darwin'
    ? ['bin/system.node'] : ['bin/landlock-run', 'bin/glibc/system.node', 'bin/musl/system.node'])]
}
