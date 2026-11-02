import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  assertDesktopHostPackageFiles,
  assertComputerUsePackageFiles,
  assertDesktopBundlePackageFiles,
  assertSystemPlatformPackageFiles,
  selectDesktopPackageClosure,
  type PackedDesktopPackage,
} from '../scripts/prepare-package-set.ts'

function packed(name: string, manifest: Record<string, unknown> = {}): PackedDesktopPackage {
  return { tarball: `${name}.tgz`, manifest: { name, version: '1.0.0', ...manifest } }
}

describe('desktop package-set selection', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('rejects a macOS Computer Use package without its matching precompiled helper', () => {
    const files = ['package/native/bin/darwin-arm64/dsh-cua-native']
    expect(() =>{  assertComputerUsePackageFiles(files, { platform: 'darwin', arch: 'arm64' }) }).not.toThrow()
    expect(() =>{  assertComputerUsePackageFiles(files, { platform: 'darwin', arch: 'x64' }) }).toThrow('precompiled helper')
    expect(() =>{  assertComputerUsePackageFiles([], { platform: 'win32', arch: 'x64' }) }).not.toThrow()
  })

  it('rejects a published bundle that omits its declared configuration patch', () => {
    const manifest = { name: 'desktop-controls', dsh: { bundle: { patch: './cordis.patch.yml' } } }
    expect(() =>{  assertDesktopBundlePackageFiles(manifest, ['package/lib/index.js']) }).toThrow('omits declared bundle patch')
    expect(() =>{  assertDesktopBundlePackageFiles(manifest, ['package/cordis.patch.yml']) }).not.toThrow()
    expect(() =>{  assertDesktopBundlePackageFiles({ name: 'ordinary-plugin' }, []) }).not.toThrow()
  })

  it('does not select a packaging target when imported as a library', async () => {
    vi.stubEnv('DSH_DESKTOP_TARGET_PLATFORM', 'linux')
    vi.stubEnv('DSH_DESKTOP_TARGET_ARCH', 'x64')
    vi.resetModules()
    await expect(import('../scripts/prepare-package-set.ts')).resolves.toHaveProperty('prepareDesktopPackageSet')
  })

  it.each([
    ['darwin', 'arm64'], ['darwin', 'x64'], ['linux', 'x64'],
  ])('requires the locally packed system platform for %s/%s even though the entry calls it optional', (platform, arch) => {
    const name = `@deepseek-ai/node-addon-system-${platform}-${arch}`
    const available = new Map<string, PackedDesktopPackage>([
      ['@deepseek-ai/dsh', packed('@deepseek-ai/dsh', { dependencies: { '@deepseek-ai/node-addon-system': '1.0.0' } })],
      ['@deepseek-ai/dsh-desktop-host', packed('@deepseek-ai/dsh-desktop-host')],
      ['@deepseek-ai/node-addon-system', packed('@deepseek-ai/node-addon-system', { optionalDependencies: { [name]: '~1.0.0' } })],
    ])
    expect(() => selectDesktopPackageClosure(available, { platform, arch })).toThrow(`omit required package ${name}`)
    available.set(name, packed(name, { version: '0.9.0' }))
    expect(() => selectDesktopPackageClosure(available, { platform, arch })).toThrow('must match system entry version')
    available.set(name, packed(name))
    expect(selectDesktopPackageClosure(available, { platform, arch }).map(entry => entry.manifest.name)).toContain(name)
    expect(selectDesktopPackageClosure(new Map([...available].filter(([key]) => key !== name)), {
      platform: 'win32', arch: 'x64',
    }).map(entry => entry.manifest.name)).not.toContain(name)
  })

  it('requires native metadata and the complete target-specific system payload', () => {
    const target = { platform: 'darwin', arch: 'arm64' }
    const manifest = { name: '@deepseek-ai/node-addon-system-darwin-arm64', os: ['darwin'], cpu: ['arm64'] }
    const files = ['package/package.json', 'package/prebuilds.json', 'package/bin/system.node']
    expect(() =>{  assertSystemPlatformPackageFiles(manifest, files, target) }).not.toThrow()
    for (const file of files) {
      expect(() =>{  assertSystemPlatformPackageFiles(manifest, files.filter(entry => entry !== file), target) }).toThrow('omits required native')
    }
    expect(() =>{  assertSystemPlatformPackageFiles({ ...manifest, cpu: ['x64'] }, files, target) }).toThrow('metadata does not match')
    const linux = { platform: 'linux', arch: 'x64' }
    const linuxManifest = { name: '@deepseek-ai/node-addon-system-linux-x64', os: ['linux'], cpu: ['x64'] }
    const linuxFiles = ['package/package.json', 'package/prebuilds.json', 'package/bin/landlock-run',
      'package/bin/glibc/system.node', 'package/bin/musl/system.node']
    expect(() =>{  assertSystemPlatformPackageFiles(linuxManifest, linuxFiles, linux) }).not.toThrow()
    expect(() =>{  assertSystemPlatformPackageFiles(linuxManifest, linuxFiles.slice(0, -1), linux) }).toThrow('bin/musl/system.node')
    expect(() =>{  assertSystemPlatformPackageFiles({}, [], { platform: 'win32', arch: 'x64' }) }).not.toThrow()
  })

  it('includes only the available internal production closure', () => {
    const available = new Map<string, PackedDesktopPackage>([
      ['@deepseek-ai/dsh', packed('@deepseek-ai/dsh', {
        dependencies: { '@deepseek-ai/dsh-base': '^1.0.0', external: '^2.0.0' },
        optionalDependencies: { '@deepseek-ai/platform-package': '1.0.0', '@deepseek-ai/missing-platform': '1.0.0' },
      })],
      ['@deepseek-ai/dsh-desktop-host', packed('@deepseek-ai/dsh-desktop-host', {
        dependencies: { '@deepseek-ai/dsh': '^1.0.0' },
      })],
      ['@deepseek-ai/dsh-base', packed('@deepseek-ai/dsh-base', {
        peerDependencies: { '@deepseek-ai/cordis': '^1.0.0' },
      })],
      ['@deepseek-ai/cordis', packed('@deepseek-ai/cordis')],
      ['@deepseek-ai/platform-package', packed('@deepseek-ai/platform-package')],
      ['@deepseek-ai/unused', packed('@deepseek-ai/unused')],
    ])
    expect(selectDesktopPackageClosure(available).map(entry => entry.manifest.name)).toEqual([
      '@deepseek-ai/cordis',
      '@deepseek-ai/dsh',
      '@deepseek-ai/dsh-base',
      '@deepseek-ai/dsh-desktop-host',
      '@deepseek-ai/platform-package',
    ])
  })

  it.each([
    '@deepseek-ai/dsh-base', '@deepseek-ai/cordis', '@deepseek-ai/node-addon-system',
  ])('rejects required prepared package %s absent from the packed release inputs', (dependency) => {
    const available = new Map<string, PackedDesktopPackage>([
      ['@deepseek-ai/dsh', packed('@deepseek-ai/dsh', {
        dependencies: { [dependency]: '^1.0.0' },
      })],
      ['@deepseek-ai/dsh-desktop-host', packed('@deepseek-ai/dsh-desktop-host', {
        dependencies: { '@deepseek-ai/dsh': '^1.0.0' },
      })],
    ])
    expect(() => selectDesktopPackageClosure(available)).toThrow(/unpacked package/u)
    expect(() => selectDesktopPackageClosure(new Map([
      ['@deepseek-ai/dsh', packed('@deepseek-ai/dsh')],
    ]))).toThrow(/omit @deepseek-ai\/dsh-desktop-host/u)
  })

  it('leaves independently published Office packages to npm resolution', () => {
    const available = new Map<string, PackedDesktopPackage>([
      ['@deepseek-ai/dsh', packed('@deepseek-ai/dsh', {
        dependencies: {
          '@deepseek-ai/libreoffice-kit': '0.0.1',
          '@deepseek-ai/libreoffice-kit-wasm': '0.0.1',
        },
      })],
      ['@deepseek-ai/dsh-desktop-host', packed('@deepseek-ai/dsh-desktop-host')],
    ])
    expect(selectDesktopPackageClosure(available).map(entry => entry.manifest.name)).toEqual([
      '@deepseek-ai/dsh', '@deepseek-ai/dsh-desktop-host',
    ])
  })

  it('requires both Desktop Host and public CLI entries', () => {
    const files = [
      'package/lib/index.js',
      'package/lib/cli.js',
    ]
    expect(() => {
      assertDesktopHostPackageFiles(files)
    }).not.toThrow()
    expect(() => {
      assertDesktopHostPackageFiles(files.slice(1))
    }).toThrow(/lib\/index\.js/u)
    expect(() => { assertDesktopHostPackageFiles(files.slice(0, 1)) }).toThrow(/lib\/cli\.js/u)
  })
})
