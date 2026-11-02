import { expect, it, vi } from 'vitest'
import type { AppUpdater } from 'electron-updater'
import { ElectronHttpExecutor } from 'electron-updater/out/electronHttpExecutor.js'
import type { GenericProvider } from 'electron-updater/out/providers/GenericProvider.js'
import type { ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js'
import { configureSignedUpdateProvider } from '../src/signed-update-provider.ts'

it.each([
  ['mac-arm64', 'latest', 'latest-mac.yml.signed.json'],
  ['mac-x64', 'nightly', 'nightly-mac.yml.signed.json'],
  ['win-x64', 'latest', 'latest.yml.signed.json'],
] as const)('uses the signed %s/%s feed and never retries unsigned metadata', async (target, channel, filename) => {
  let options: Parameters<AppUpdater['setFeedURL']>[0] | undefined
  configureSignedUpdateProvider({ setFeedURL: (value) => { options = value } }, {
    target, channel, url: `https://updates.example.com/feeds/${target}/`,
  })
  if (!options || typeof options === 'string' || options.provider !== 'custom'
    || !('updateProvider' in options) || typeof options.updateProvider !== 'function') throw new Error('Expected custom provider')
  const executor = new ElectronHttpExecutor()
  const request = vi.spyOn(executor, 'request').mockResolvedValue(JSON.stringify({ version: '9.9.9', files: [] }))
  // The dependency's custom-provider constructor intentionally accepts its runtime adapter structurally.
  const UpdateProvider = options.updateProvider as new (
    configuration: object, owner: AppUpdater, runtime: ProviderRuntimeOptions,
  ) => GenericProvider
  const provider = new UpdateProvider(options, {} as AppUpdater, {
    platform: target.startsWith('mac-') ? 'darwin' : 'win32',
    executor, isUseMultipleRangeRequest: false,
  })
  await expect(provider.getLatestVersion()).rejects.toThrow(/signed envelope/u)
  expect(request).toHaveBeenCalledTimes(1)
  expect(request.mock.calls[0]?.[0]).toMatchObject({ hostname: 'updates.example.com', path: `/feeds/${target}/${filename}` })
})
