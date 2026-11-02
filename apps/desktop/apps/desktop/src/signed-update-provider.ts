/** Signed metadata provider retaining electron-updater's downloads and native signature checks. */
import type { AppUpdater } from 'electron-updater'
import { GenericProvider } from 'electron-updater/out/providers/GenericProvider.js'
import type { ProviderRuntimeOptions } from 'electron-updater/out/providers/Provider.js'
import { verifySignedUpdate, type SignedUpdatePolicy } from './signed-update.ts'

/**
 * Configure a provider from the installed, code-signed manifest only.
 * @param updater Process-owned updater whose transport already has inactivity deadlines.
 * @param policy Installed channel, target architecture and HTTPS feed.
 */
export function configureSignedUpdateProvider(updater: Pick<AppUpdater, 'setFeedURL'>, policy: SignedUpdatePolicy): void {
  class SignedProvider extends GenericProvider {
    constructor(_options: object, owner: AppUpdater, runtime: ProviderRuntimeOptions) {
      super({ provider: 'generic', url: policy.url, channel: policy.channel }, owner, runtime)
    }

    override async getLatestVersion(): ReturnType<GenericProvider['getLatestVersion']> {
      const name = `${policy.channel}${policy.target.startsWith('mac-') ? '-mac' : ''}.yml.signed.json`
      const raw = await this.httpRequest(new URL(name, policy.url), { 'Cache-Control': 'no-cache' })
      if (raw === null) throw new Error('desktop update: signed metadata unavailable')
      return verifySignedUpdate(raw, policy)
    }
  }
  updater.setFeedURL({ provider: 'custom', updateProvider: SignedProvider })
}
