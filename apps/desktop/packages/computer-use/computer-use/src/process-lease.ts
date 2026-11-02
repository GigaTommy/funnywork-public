/** OS-user desktop ownership shared by independent Harness host processes. */
import { lstat, mkdir } from 'node:fs/promises'
import { userInfo } from 'node:os'
import { dirname, isAbsolute, join } from 'node:path'
import { withFileLock } from '@deepseek-ai/dsh-atomic-write'

/** Host-owned acquisition settings; the path never comes from model arguments. */
export interface DesktopProcessLeaseOptions {
  /** Absolute lease filename; omission uses stable, OS-user IPC storage. */
  path?: string | undefined
  /** Acquisition budget; expiry reports busy without dispatching desktop input. */
  waitMs: number
}

/** A successful acquisition remains held until explicit native-quiescent release. */
export interface DesktopProcessLease {
  release(): Promise<void>
}

/** A typed acquisition timeout rather than interpreting another library's error messages. */
export class DesktopProcessBusyError extends Error {
  constructor() {
    super('Another host process owns the desktop; retry with a fresh observation after its turn finishes')
    this.name = 'DesktopProcessBusyError'
  }
}

/**
 * Stable IPC follows the native helper on macOS and the OS user's local profile on Windows.
 * @param override - optional absolute lease filename selected by the Host.
 * @returns the explicit filename or the current platform's OS-user lease filename.
 */
export function desktopProcessLeasePath(override?: string): string {
  if (override !== undefined) {
    if (!isAbsolute(override)) throw new Error('Desktop lease path must be absolute')
    return override
  }
  if (process.platform === 'darwin') {
    const uid = process.getuid?.()
    if (uid === undefined) throw new Error('Desktop lease requires the OS user identity')
    return `/tmp/dsh-cua-${uid}/desktop-turn.lease`
  }
  if (process.platform === 'win32') return join(userInfo().homedir, 'AppData', 'Local', 'FunnyWork', 'computer-use', 'desktop-turn.lease')
  throw new Error('Desktop process leases require macOS or Windows, or an explicit absolute lease path')
}

/** Reject symlinks, other-user IPC, and broad POSIX modes before entering the existing writer-lock protocol. */
async function prepareDirectory(filename: string): Promise<void> {
  const directory = dirname(filename)
  await mkdir(directory, { recursive: true, mode: 0o700 })
  const stat = await lstat(directory)
  const uid = process.getuid?.()
  if (!stat.isDirectory() || (uid !== undefined && (stat.uid !== uid || (stat.mode & 0o777) !== 0o700))) {
    throw new Error('Desktop lease directory must be a real owner-only directory (0700 on POSIX)')
  }
  // Windows inherits the OS user's LocalAppData ACL; POSIX owner/mode bits do not model its ACL.
  try {
    const existing = await lstat(`${filename}.lock`)
    if (!existing.isFile() || (uid !== undefined && (existing.uid !== uid || (existing.mode & 0o777) !== 0o600))) {
      throw new Error('Desktop lease lock must be a regular owner-only file (0600 on POSIX)')
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
}

/**
 * Hold the repository's audited writer lock across a desktop turn, including native cleanup.
 * Acquisition cancellation never releases an acquired lock; release is an explicit barrier.
 * Dead-process recovery and conservative PID-reuse handling belong to withFileLock.
 * @param options - fixed OS-user path and bounded acquisition wait.
 * @param signal - cancels only acquisition; an admitted operation owns its subsequent cancellation.
 * @returns a lease released only after admitted native work has settled.
 */
export async function acquireDesktopProcessLease(options: DesktopProcessLeaseOptions, signal: AbortSignal): Promise<DesktopProcessLease> {
  signal.throwIfAborted()
  const filename = desktopProcessLeasePath(options.path)
  await prepareDirectory(filename)
  signal.throwIfAborted()
  const acquired = Promise.withResolvers<DesktopProcessLease>()
  const released = Promise.withResolvers<void>()
  const budget = new AbortController()
  const timer = setTimeout(() => { budget.abort(new DesktopProcessBusyError()) }, options.waitMs)
  const acquisitionSignal = AbortSignal.any([signal, budget.signal])
  let entered = false
  const holding = withFileLock(filename, async () => {
    entered = true
    clearTimeout(timer)
    acquired.resolve({ release: async () => { released.resolve(); await holding } })
    await released.promise
  }, { signal: acquisitionSignal, waitMs: Number.POSITIVE_INFINITY })
  void holding.catch((error: unknown) => {
    if (!entered) acquired.reject(budget.signal.aborted && !signal.aborted ? budget.signal.reason : error)
  }).finally(() => { clearTimeout(timer) })
  return await acquired.promise
}
