import { readFile } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'

/**
 * Recover the account registry root inherited by an update restart.
 * @param home Configured data root, possibly already scoped by the previous process.
 * @returns The owning registry root; unrelated paths remain unchanged.
 */
export async function resolveFunnyWorkerDataRoot(home: string): Promise<string> {
  let current = home
  while (/^[a-f0-9]{64}$/.test(basename(current)) && basename(dirname(current)) === 'accounts') {
    const root = dirname(dirname(current))
    const mapping = await readFile(join(root, 'funnyworker-account-directories.json'), 'utf8').catch((error: unknown) => {
      if (error instanceof Error && 'code' in error && error.code === 'ENOENT') return undefined
      throw error
    })
    if (mapping === undefined) return current
    const entries: unknown = JSON.parse(mapping)
    if (typeof entries !== 'object' || entries === null || Array.isArray(entries)) throw new Error('Invalid account directory mapping')
    if (!Object.values(entries).includes(basename(current))) return current
    current = root
  }
  return current
}
