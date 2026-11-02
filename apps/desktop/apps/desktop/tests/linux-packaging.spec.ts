/** Linux packaging is an explicit local artifact, without an update channel. */
import { expect, it } from 'vitest'
import { createElectronBuilderConfig } from '../scripts/electron-builder-config.mjs'
import { resolveDesktopAutoUpdateTarget } from '../scripts/desktop-auto-update-environment.mjs'

it('packages Linux artifacts with the normal sandbox and no release-policy metadata', () => {
  const env = { DSH_DESKTOP_APP_ID: 'com.funnyworker.desktop', DSH_DESKTOP_UNSIGNED: '1' }
  const config = createElectronBuilderConfig(env, 'linux', 'x64')
  expect(config.linux).toMatchObject({ executableName: 'funnywork', target: ['AppImage', 'tar.gz'] })
  expect(config.linux.maintainer).toBe('FunnyWork <support@funnypixels.cn>')
  expect(config.extraMetadata.desktopName).toBe(`${env.DSH_DESKTOP_APP_ID}.desktop`)
  expect(config.linux.syncDesktopName).toBe(true)
  expect(config.linux.desktop.entry.StartupWMClass).toBe(env.DSH_DESKTOP_APP_ID)
  expect(config.extraMetadata).toHaveProperty('homepage', 'https://funnywork.funnypixelsapp.com')
  expect(config.deb.packageName).toBe('funnywork')
  expect(config.deb.depends).toEqual(expect.arrayContaining([
    'libc6 (>= 2.32)', 'libstdc++6 (>= 11)', 'bubblewrap', 'libsecret-1-0', 'libasound2t64 | libasound2',
    'libcrypt1', 'zlib1g',
  ]))
  expect(config.deb).not.toHaveProperty('afterInstall')
  expect(config.deb).not.toHaveProperty('appArmorProfile')
  expect(config.publish).toBeNull()
  expect(config.extraMetadata).not.toHaveProperty('dshMandatoryUpdatePolicy')
  expect(config.artifactName).toContain('-unsigned')
  expect(config.directories.output).toMatch(/linux-x64[/\\]unsigned-artifacts$/u)
  expect(config.electronFuses).toEqual({ runAsNode: true })
  expect(config.appImage.executableArgs).toEqual([])
  expect(config.extraFiles).toHaveLength(1)
  expect(config.extraFiles[0]).toHaveProperty('to', 'AppRun')
  expect(config.extraFiles[0]?.from).toMatch(/cli[/\\]AppRun-linux$/u)
  expect(config.linux).not.toHaveProperty('executableArgs')
  expect(() => createElectronBuilderConfig({ DSH_DESKTOP_APP_ID: env.DSH_DESKTOP_APP_ID }, 'linux', 'x64')).toThrow(/local prerelease/u)
  expect(() => createElectronBuilderConfig({ ...env, DSH_DESKTOP_MANDATORY_UPDATE_PROD_ORIGIN: 'https://updates.example.com' }, 'linux', 'x64')).toThrow(/do not support a mandatory-update/u)
  expect(() => resolveDesktopAutoUpdateTarget('linux', 'x64')).toThrow(/unsupported target/u)
})

it.each(['darwin', 'win32'] as const)('does not add Linux window identity to %s metadata', (platform) => {
  const config = createElectronBuilderConfig({
    DSH_DESKTOP_APP_ID: 'com.funnyworker.desktop', DSH_DESKTOP_UNSIGNED: '1',
    DSH_DESKTOP_AUTO_UPDATE_ENV: 'production', DSH_DESKTOP_MANDATORY_UPDATE_PROD_ORIGIN: 'https://updates.example.com',
  }, platform, 'x64')
  expect(config.extraMetadata).not.toHaveProperty('desktopName')
  expect(config.extraFiles).toEqual([])
})
