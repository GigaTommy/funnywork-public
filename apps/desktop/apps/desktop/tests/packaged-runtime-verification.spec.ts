import { describe, expect, it, vi } from 'vitest'

const { verifyDesktopRuntime, normalizeLinuxArtifactPermissions, materializeLinuxPythonAliases } = vi.hoisted(() => ({
  verifyDesktopRuntime: vi.fn<(root: string, expected: string) => Promise<void>>(async () => undefined),
  normalizeLinuxArtifactPermissions: vi.fn<(root: string) => void>(),
  materializeLinuxPythonAliases: vi.fn<(root: string) => void>(),
}))
vi.mock('../scripts/linux-python-aliases.mjs', () => ({ materializeLinuxPythonAliases }))
vi.mock('../scripts/linux-artifact-permissions.mjs', async importOriginal => ({
  ...await importOriginal<typeof import('../scripts/linux-artifact-permissions.mjs')>(), normalizeLinuxArtifactPermissions,
}))
// The hook imports the built tree, which a clean checkout has not produced; this is the path it resolves.
vi.mock('/apps/desktop/lib/types/runtime-tree.js', () => ({ verifyDesktopRuntime }))
vi.mock('../scripts/windows-asar-unpack.mjs', async importOriginal => ({
  ...await importOriginal<typeof import('../scripts/windows-asar-unpack.mjs')>(),
  verifyWindowsAsarUnpack: async () => undefined,
}))

const ENVIRONMENT = {
  DSH_DESKTOP_APP_ID: 'com.example.installer',
  DSH_DESKTOP_MANDATORY_UPDATE_TEST_ORIGIN: 'https://policy.example.com',
  DSH_DESKTOP_MANDATORY_UPDATE_CONFIG: JSON.stringify({ allowedAuthOrigins: ['https://login.example.com'] }),
  DSH_DESKTOP_TARGET_PLATFORM: 'win32',
  DSH_DESKTOP_TARGET_ARCH: 'x64',
  DSH_DESKTOP_UNSIGNED: '1',
  DOWNLOAD_TEST_ORIGIN: 'https://desktop-updates.example.com',
  DOWNLOAD_TEST_RELEASE_ID: '0123456789abcdef0123456789abcdef',
}

const CONTEXT = { appOutDir: 'out', packager: { getResourcesDir: () => 'out/resources' } }

/**
 * Run the packaging hook that verifies the bundled runtime.
 * @returns The version that hook required the runtime to declare.
 */
async function requiredRuntimeVersion(preparedRuntime?: string, preparedRuntimeVersion?: string): Promise<unknown> {
  verifyDesktopRuntime.mockClear()
  const { createElectronBuilderConfig } = await import('../scripts/electron-builder-config.mjs')
  const config = createElectronBuilderConfig(ENVIRONMENT, 'win32', 'x64', preparedRuntime, preparedRuntimeVersion)
  await config.afterPack(CONTEXT as never)
  return verifyDesktopRuntime.mock.calls[0]?.[1]
}

describe('packaged runtime verification', () => {
  it('normalizes only the completed Linux application directory before creating distributables', async () => {
    const { createElectronBuilderConfig } = await import('../scripts/electron-builder-config.mjs')
    normalizeLinuxArtifactPermissions.mockClear()
    materializeLinuxPythonAliases.mockClear()
    const linux = createElectronBuilderConfig({ DSH_DESKTOP_APP_ID: 'com.example.installer', DSH_DESKTOP_UNSIGNED: '1' }, 'linux', 'x64')
    await linux.afterPack(CONTEXT as never)
    expect(normalizeLinuxArtifactPermissions).toHaveBeenCalledExactlyOnceWith(CONTEXT.appOutDir)
    expect(materializeLinuxPythonAliases).toHaveBeenCalledExactlyOnceWith(CONTEXT.appOutDir)
    expect(materializeLinuxPythonAliases.mock.invocationCallOrder[0])
      .toBeLessThan(normalizeLinuxArtifactPermissions.mock.invocationCallOrder[0]!)
    await createElectronBuilderConfig(ENVIRONMENT, 'win32', 'x64').afterPack(CONTEXT as never)
    await createElectronBuilderConfig({ DSH_DESKTOP_APP_ID: 'com.example.installer', DSH_DESKTOP_UNSIGNED: '1' }, 'darwin', 'x64').afterPack(CONTEXT as never)
    expect(normalizeLinuxArtifactPermissions).toHaveBeenCalledTimes(1)
    expect(materializeLinuxPythonAliases).toHaveBeenCalledTimes(1)
  })

  it('stops Linux packaging before normalization when Python alias validation fails', async () => {
    const { createElectronBuilderConfig } = await import('../scripts/electron-builder-config.mjs')
    normalizeLinuxArtifactPermissions.mockClear()
    materializeLinuxPythonAliases.mockImplementationOnce(() => { throw new Error('invalid Python alias fixture') })
    const linux = createElectronBuilderConfig({ DSH_DESKTOP_APP_ID: 'com.example.installer', DSH_DESKTOP_UNSIGNED: '1' }, 'linux', 'x64')
    await expect(linux.afterPack(CONTEXT as never)).rejects.toThrow('invalid Python alias fixture')
    expect(normalizeLinuxArtifactPermissions).not.toHaveBeenCalled()
  })

  it('requires the product version when the target tree supplies the runtime', async () => {
    const productVersion = (JSON.parse(
      await import('node:fs/promises').then(async fs => fs.readFile(new URL('../package.json', import.meta.url), 'utf8')),
    ) as { version: string }).version
    expect(await requiredRuntimeVersion()).toBe(productVersion)
  })

  it('requires the version installed-update qualification wrote into its private runtime', async () => {
    // Qualification rewrites the runtime's own version, so comparing against the product version would always fail.
    expect(await requiredRuntimeVersion('/qualification/dsh', '0.1.6-alpha.2.20260921.1')).toBe('0.1.6-alpha.2.20260921.1')
  })

  it('does not let a build version change what the bundled runtime must declare', async () => {
    const productVersion = (JSON.parse(
      await import('node:fs/promises').then(async fs => fs.readFile(new URL('../package.json', import.meta.url), 'utf8')),
    ) as { version: string }).version
    const { createElectronBuilderConfig } = await import('../scripts/electron-builder-config.mjs')
    verifyDesktopRuntime.mockClear()
    const config = createElectronBuilderConfig(
      { ...ENVIRONMENT, DSH_DESKTOP_BUILD_VERSION: `${productVersion}.20260921.1` }, 'win32', 'x64')
    expect(config.extraMetadata).toMatchObject({ version: `${productVersion}.20260921.1` })
    await config.afterPack(CONTEXT as never)
    expect(verifyDesktopRuntime.mock.calls[0]?.[1]).toBe(productVersion)
  })
})
