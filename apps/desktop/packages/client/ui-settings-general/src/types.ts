/** Type-only Electron update declarations shared by the settings UI and Desktop compatibility checks. */

/** Classified failure copy selected by the Web locale without exposing raw updater diagnostics. */
export type DesktopUpdateFailureKind =
  | 'check'
  | 'check-network'
  | 'download'
  | 'download-network'
  | 'install'
  | 'install-network'
  | 'stop-failed'
  | 'tasks-changed'
  | 'tasks-unavailable'

/** Public semantic preload fields consumed by the settings row. */
export interface DesktopUpdatePresentation {
  readonly phase: 'idle' | 'checking' | 'available' | 'downloading' | 'verifying' | 'installing' | 'ready' | 'error'
  readonly version?: string
  readonly percent?: number
  readonly failure?: DesktopUpdateFailureKind
}

/** Optional carrier API; it cannot select artifacts or authorize installation. */
export interface DesktopUpdateBridge {
  status(): Promise<DesktopUpdatePresentation>
  open(): Promise<void>
  subscribe(listener: (state: DesktopUpdatePresentation) => void): () => void
}

/** Shared carrier status for the account row and collapsed sidebar badge. */
export interface DesktopUpdateView {
  readonly presentation?: DesktopUpdatePresentation
  readonly failed: boolean
  readonly opening: boolean
}

/** Display-only account metadata; credentials never enter the renderer. */
export interface DesktopAccountProfile {
  readonly username: string
  readonly avatarUrl?: string
  readonly nickname?: string
  readonly roles: readonly string[]
  readonly department?: string
}

/** Server-owned wallet presentation; contains no account or provider credential. */
export interface DesktopModelBudget {
  readonly configured: boolean
  readonly enabled: boolean
  readonly remainingMicros: number
  readonly heldMicros: number
  readonly dailyRemainingMicros: number
  readonly expiresAt: number
  readonly promotionMode: 'DISABLED' | 'WELCOME' | 'DAILY'
  readonly claimableMicros: number
  readonly creditedMicros: number
}

/** Main-process account commands available only to the product document. */
export interface DesktopAccountBridge {
  updateAvatar?(): Promise<DesktopAccountProfile | undefined>
  profile(): Promise<DesktopAccountProfile | undefined>
  budget(claim: boolean): Promise<DesktopModelBudget>
  signOut(): Promise<void>
}
