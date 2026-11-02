/** Display-only observation of the optional desktop account carrier. */
import { createSnapshotStore } from '@deepseek-ai/dsh-client-store'
import type { DesktopAccountBridge, DesktopAccountProfile, DesktopModelBudget } from '../types.ts'

/** Owns the profile read for one settings shell registration. */
export class DesktopAccountSource {
  /** Profile snapshot observed by the registered settings shell. */
  readonly store = createSnapshotStore<DesktopAccountProfile | undefined>(undefined)
  private live = true
  private revision = 0
  private timer: ReturnType<typeof setInterval> | undefined
  constructor(private readonly bridge: DesktopAccountBridge | undefined) {
    const refresh = () => {
      const revision = ++this.revision
      void bridge?.profile().then((profile) => {
        if (this.live && this.revision === revision) this.store.set(profile)
      }).catch(() => undefined)
    }
    if (bridge !== undefined) this.timer = setInterval(refresh, 15000)
    refresh()
  }
  /** Read or claim the server-owned model allowance through main-process IPC.
   * @param claim Whether to request eligible promotional credit.
   * @returns Validated wallet display fields from the main process.
   */
  budget(claim: boolean): Promise<DesktopModelBudget> {
    if (this.bridge === undefined) return Promise.reject(new Error('Desktop account is unavailable'))
    return this.bridge.budget(claim)
  }
  /** Choose and save a profile picture through the authenticated desktop carrier. */
  async updateAvatar(): Promise<void> {
    if (this.bridge?.updateAvatar === undefined) throw new Error('Account picture is unavailable')
    const profile = await this.bridge.updateAvatar()
    if (this.live) { this.revision++; this.store.set(profile) }
  }
  /** Request the shell-owned confirmation and sign-out flow. */
  signOut(): Promise<void> { return this.bridge?.signOut() ?? Promise.resolve() }
  /** Ignore a profile read completing after plugin disposal. */
  dispose(): void { this.live = false; clearInterval(this.timer) }
}
