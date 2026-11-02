import { join, sep } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  desktopElectronExecutable,
  desktopPackagedApplicationPaths,
  desktopTargetBuildPaths,
  desktopTargetPlatform,
  developmentRuntimeDirectory,
  resolveDesktopBuildTarget,
} from '../scripts/desktop-build-paths.mjs'

describe('desktop build paths', () => {
  it('isolates every mutable build directory by complete target', () => {
    const arm64 = desktopTargetBuildPaths('mac-arm64')
    const x64 = desktopTargetBuildPaths('mac-x64')
    const windows = desktopTargetBuildPaths('win-x64')
    const linux = desktopTargetBuildPaths('linux-x64')
    const mutableKeys = [
      'root',
      'artifacts',
      'unsignedArtifacts',
      'unnotarizedArtifacts',
      'runtime',
      'packageSet',
      'dsh',
      'dshPnpm',
      'dependencyStore',
      'dependencyCache',
      'electron',
      'packedDsh',
      'packedVendor',
      'packedLandlock',
    ] as const

    for (const key of mutableKeys) {
      expect(new Set([arm64[key], x64[key], windows[key], linux[key]]).size).toBe(4)
    }
    expect(arm64.artifacts).toContain(join('targets', 'mac-arm64', 'artifacts'))
    expect(x64.dsh).toContain(join('targets', 'mac-x64', 'dsh'))
    expect(windows.runtime).toContain(join('targets', 'win-x64', 'runtime'))
  })

  it('shares only the immutable upstream download cache', () => {
    const arm64 = desktopTargetBuildPaths('mac-arm64')
    const x64 = desktopTargetBuildPaths('mac-x64')
    expect(arm64.downloads).toBe(x64.downloads)
    expect(arm64.downloads).not.toContain(`${sep}targets${sep}`)
  })

  it('keeps downloaded dependencies outside disposable runtime and pnpm state', () => {
    for (const target of ['mac-arm64', 'mac-x64', 'win-x64', 'linux-x64'] as const) {
      const paths = desktopTargetBuildPaths(target)
      expect(paths.dependencyStore).toBe(join(paths.root, 'dependency-store'))
      expect(paths.dependencyCache).toBe(join(paths.root, 'dependency-cache'))
      expect(paths.dependencyCache.startsWith(`${paths.dshPnpm}${sep}`)).toBe(false)
      expect(paths.dependencyCache.startsWith(`${paths.dsh}${sep}`)).toBe(false)
      expect(paths.dependencyStore.startsWith(paths.dsh + sep)).toBe(false)
      expect(paths.dependencyStore.startsWith(paths.runtime + sep)).toBe(false)
      expect(paths.dependencyStore.startsWith(paths.dshPnpm + sep)).toBe(false)
    }
  })

  it('resolves the development primary runtime from the build target rather than the host architecture', () => {
    expect(developmentRuntimeDirectory({}, 'darwin', 'arm64'))
      .toContain(join('targets', 'mac-arm64', 'runtime', 'primary-runtime'))
    expect(developmentRuntimeDirectory({}, 'darwin', 'x64'))
      .toContain(join('targets', 'mac-x64', 'runtime', 'primary-runtime'))
    expect(developmentRuntimeDirectory({}, 'win32', 'arm64'))
      .toContain(join('targets', 'win-x64', 'runtime', 'primary-runtime'))
  })

  it('maps every target to the platform and architecture of the payload it prepares', () => {
    expect(desktopTargetPlatform('mac-arm64')).toEqual({ platform: 'darwin', arch: 'arm64' })
    expect(desktopTargetPlatform('mac-x64')).toEqual({ platform: 'darwin', arch: 'x64' })
    expect(desktopTargetPlatform('win-x64')).toEqual({ platform: 'win32', arch: 'x64' })
    expect(desktopTargetPlatform('linux-x64')).toEqual({ platform: 'linux', arch: 'x64' })
    expect(() => desktopTargetPlatform('linux-arm64' as 'linux-x64')).toThrow(/unsupported target/u)
  })

  it('resolves environment overrides and rejects unsupported targets', () => {
    expect(resolveDesktopBuildTarget({
      DSH_DESKTOP_TARGET_PLATFORM: 'darwin',
      DSH_DESKTOP_TARGET_ARCH: 'x64',
    }, 'darwin', 'arm64')).toBe('mac-x64')
    expect(resolveDesktopBuildTarget({}, 'win32', 'x64')).toBe('win-x64')
    expect(resolveDesktopBuildTarget({}, 'linux', 'x64')).toBe('linux-x64')
    expect(developmentRuntimeDirectory({}, 'linux', 'x64')).toContain(join('targets', 'linux-x64', 'runtime', 'primary-runtime'))
    expect(() => resolveDesktopBuildTarget({}, 'linux', 'arm64')).toThrow(/unsupported target/u)
    expect(() => desktopTargetBuildPaths('linux-arm64' as 'linux-x64')).toThrow(/unsupported target/u)
  })
})

it('locates the native Linux Electron and packaged application without macOS bundle paths', () => {
  expect(desktopElectronExecutable('/distribution', 'linux')).toBe(join('/distribution', 'electron'))
  expect(desktopElectronExecutable('/distribution', 'darwin')).toBe(join('/distribution', 'Electron.app/Contents/MacOS/Electron'))
  expect(desktopElectronExecutable('/distribution', 'win32')).toBe(join('/distribution', 'electron.exe'))
  expect(desktopPackagedApplicationPaths('/artifacts', 'linux-x64')).toEqual({
    application: join('/artifacts', 'linux-unpacked'),
    resources: join('/artifacts', 'linux-unpacked/resources'),
    executable: join('/artifacts', 'linux-unpacked/funnywork'),
  })
})
