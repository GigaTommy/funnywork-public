/** Launcher-owned profile locations and composition inputs. */
import { join } from 'node:path'
import { composeEntries, loadProfileDirectory, PROFILE_PATCH_FILENAME, type Profile } from './profile.ts'
import { loadOptionalPatches } from './index.ts'
import type { PatchOptions } from '@deepseek-ai/cordis-plugin-include'

/** Application-owned package manager executable; environment applies only to package operations. */
export interface ProfilePnpmInvocation {
  readonly command: string
  readonly args: readonly string[]
  readonly env: Readonly<Record<string, string>>
}

/** Current profile facts; scheduling and mutation belong to their callers. */
export interface ProfileContext {
  readonly name: string
  /** Packaged applications supply their bundled runtime instead of a PATH executable. */
  readonly packageManager?: ProfilePnpmInvocation
  readonly dir: string
  readonly patchPath: string
  readonly installAnchor: string
  readonly cwd: string
  readonly home: string
  /** Bundle packages used to start this process, before any persisted edits. */
  readonly startedBundles: readonly string[]
  /** Parsed command-line overlays, applied above profile and home patches. */
  readonly overlays: readonly PatchOptions[]
  /** Launch-time DSH_TELEMETRY_DISABLED value; any non-empty value opts out. */
  readonly telemetryDisabledEnv: string | undefined
}

declare module '@deepseek-ai/cordis' {
  interface Context {
    /** Present only in a profile launched by dsh. */
    profileContext: ProfileContext
  }
}

const TELEMETRY_ROW_ID = 'session-telemetry-otel'

/**
 * Resolve the telemetry opt-out switch into its boot patch. ANY non-empty
 * value (including `'0'`/`'false'`) disables: a privacy switch prefers
 * off-by-mistake over on-by-mistake. A composition without the telemetry row
 * exports nothing, so the switch is then trivially satisfied and no patch is
 * generated — custom profiles need not mount telemetry to run with the
 * switch set.
 * @param disabledEnv - the raw `DSH_TELEMETRY_DISABLED` value (`undefined` when unset).
 * @param hasRow - whether the composition carries the telemetry row.
 * @returns the disable patch, or `undefined` when no hard-disable patch is required.
 */
export function resolveTelemetryPatch(disabledEnv: string | undefined, hasRow: boolean): PatchOptions | undefined {
  if ((disabledEnv ?? '') === '' || !hasRow) return undefined
  return { id: TELEMETRY_ROW_ID, disabled: true }
}

/** Optional installation defaults, applied after bundles and before personal settings. */
export const PROFILE_DEFAULTS_FILENAME = 'cordis.defaults.yml'

/** Read installation defaults without changing profile-owned settings.
 * @param binName - Diagnostic prefix.
 * @param home - Installation home.
 * @param layers - Existing bundle patches whose config fields remain available.
 * @returns Defaults patches, or an empty list when none are configured.
 */
export function readProfileDefaults(binName: string, home: string, layers: readonly PatchOptions[][]): PatchOptions[] {
  const rows = loadOptionalPatches(binName, join(home, PROFILE_DEFAULTS_FILENAME)) ?? []
  if (rows.length === 0) return []
  const base = new Map(composeEntries(layers).map(row => [row.id, row.config]))
  return rows.map((row) => {
    const supplied: unknown = row.config
    if (typeof row.id !== 'string' || row.id.length === 0 || row.insert !== undefined || Object.keys(row).some(key => key !== 'id' && key !== 'config') || typeof supplied !== 'object' || supplied === null || Array.isArray(supplied)) throw new Error('Installation defaults require entry ids and object config fields')
    const original: unknown = base.get(row.id)
    if (original !== undefined && (typeof original !== 'object' || original === null || Array.isArray(original))) throw new Error('Installation defaults require object entry configuration')
    const config: Record<string, unknown> = { ...original, ...supplied }
    base.set(row.id, config)
    return { ...row, config }
  })
}

/** Read the selected bundles' patches, which apply before the profile patch file.
 * @param binName Diagnostic prefix for malformed or missing configuration.
 * @param context Data supplied by the profile launcher.
 * @param initialProfile Already loaded startup profile; omitted reads the current files.
 * @returns Bundle patches in application order.
 */
export function readBundlePatches(binName: string, context: ProfileContext, initialProfile?: Profile): PatchOptions[] {
  const profile = initialProfile ?? loadProfileDirectory(binName, context.dir, context.installAnchor, { userLayer: false })
  return profile.layers.flatMap(layer => layer.patches)
}

/** Read current bundle and user layers with the launch-time overlays.
 * @param binName Diagnostic prefix for malformed or missing configuration.
 * @param context Data supplied by the profile launcher.
 * @param initialProfile Already loaded startup profile; omitted reads the current files.
 * @returns Detached ordered patches; this function does not update the Loader.
 */
export function readProfilePatches(binName: string, context: ProfileContext, initialProfile?: Profile): PatchOptions[] {
  const profile = initialProfile ?? loadProfileDirectory(binName, context.dir, context.installAnchor, { userLayer: false })
  const defaults = readProfileDefaults(binName, context.home, profile.layers.map(layer => layer.patches))
  const user = initialProfile?.patches ?? loadOptionalPatches(binName, context.patchPath) ?? []
  const patches = structuredClone([
    ...readBundlePatches(binName, context, profile),
    ...defaults,
    ...user.map((row) => {
      const supplied: unknown = row.config
      const inherited: unknown = defaults.findLast(item => item.id === row.id)?.config
      if (typeof inherited !== 'object' || inherited === null || row.insert !== undefined || typeof supplied !== 'object' || supplied === null || Array.isArray(supplied)) return row
      return { ...row, config: { ...inherited, ...supplied } }
    }),
    ...(loadOptionalPatches(binName, join(context.home, PROFILE_PATCH_FILENAME)) ?? []),
    ...context.overlays,
  ])
  const telemetryPatch = resolveTelemetryPatch(context.telemetryDisabledEnv,
    composeEntries([patches]).some(row => row.id === TELEMETRY_ROW_ID))
  if (telemetryPatch !== undefined) patches.push(telemetryPatch)
  return patches
}
