/** Build the macOS accessibility helper before packing the desktop's production dependencies. */

import { execFileSync } from 'node:child_process'
import { chmodSync, copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { isEntry } from '../../../scripts/release/process.ts'
import { desktopTargetPlatform, resolveDesktopBuildTarget, resolveDesktopTargetBuildPaths } from './desktop-build-paths.mjs'

/**
 * Compile one target's native helper into the package payload; installed applications need no compiler.
 * @param environment - Target and cache configuration inherited from the packaging supervisor.
 * @returns Prepared executable, or undefined for targets using a different desktop provider.
 */
export function prepareComputerUse(environment: NodeJS.ProcessEnv = process.env): string | undefined {
  const targetName = resolveDesktopBuildTarget(environment)
  const target = desktopTargetPlatform(targetName)
  if (target.platform !== 'darwin') return undefined
  if (process.platform !== 'darwin') throw new Error('macOS Computer Use must be prepared on a macOS build host')
  const paths = resolveDesktopTargetBuildPaths(environment)
  const repository = resolve(import.meta.dirname, '../../..')
  const native = join(repository, 'packages/experimental/cua-macos/native')
  const scratch = join(paths.root, 'computer-use', 'build')
  const cache = join(paths.root, 'computer-use', 'cache')
  const modules = join(cache, 'modules')
  const architecture = target.arch === 'x64' ? 'x86_64' : 'arm64'
  mkdirSync(modules, { recursive: true })
  execFileSync('/usr/bin/xcrun', [
    'swift', 'build', '--package-path', native, '--scratch-path', scratch,
    '--cache-path', cache, '--configuration', 'release', '--product', 'dsh-cua-native',
    '--arch', architecture, '-Xswiftc', '-module-cache-path', '-Xswiftc', modules,
  ], {
    cwd: repository, stdio: 'inherit', timeout: 300_000,
    env: { ...environment, CLANG_MODULE_CACHE_PATH: modules, SWIFTPM_MODULECACHE_OVERRIDE: modules },
  })
  const built = join(scratch, 'release', 'dsh-cua-native')
  const payload = join(native, 'bin', `darwin-${target.arch}`, 'dsh-cua-native')
  mkdirSync(dirname(payload), { recursive: true })
  copyFileSync(built, payload)
  chmodSync(payload, 0o755)
  execFileSync('/usr/bin/lipo', [payload, '-verify_arch', architecture], { stdio: 'inherit' })
  // Developer ID signing happens with the complete prepared runtime. Local builds retain an ad-hoc signature.
  execFileSync('/usr/bin/codesign', ['--force', '--sign', '-', payload], { stdio: 'inherit' })
  execFileSync('/usr/bin/codesign', ['--verify', '--strict', payload], { stdio: 'inherit' })
  return payload
}

if (isEntry(import.meta.url)) prepareComputerUse()
