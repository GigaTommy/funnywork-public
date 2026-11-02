/** Check final native-library paths before Windows packaging copies the runtime. */
import { readdirSync } from 'node:fs'
import { join, win32 } from 'node:path'

/**
 * Reject runtime paths that Windows native loaders cannot open without long-path support.
 * This checks the prepared payload, so wheel filename and interpreter changes need no path estimates.
 * @param runtimeDirectory - Prepared runtime tree copied into resources/runtime.
 * @param applicationDirectory - Absolute destination win-unpacked directory.
 * @returns Returns when every native payload path fits the legacy 260-character limit including NUL.
 */
export function validateWindowsRuntimePaths(runtimeDirectory: string, applicationDirectory: string): void {
  if (!win32.isAbsolute(applicationDirectory)) throw new Error('Windows runtime path preflight requires an absolute application directory')
  const destination = win32.join(applicationDirectory, 'resources', 'runtime')
  function visit(directory: string, segments: string[]): void {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const relative = [...segments, entry.name]
      if (entry.isSymbolicLink()) throw new Error(`Windows runtime path preflight refuses linked payload: ${relative.join('/')}`)
      if (entry.isDirectory()) visit(join(directory, entry.name), relative)
      else if (/\.(?:dll|exe|pyd)$/iu.test(entry.name)) {
        const path = win32.join(destination, ...relative)
        if (path.length >= 260) throw new Error(
          `Windows runtime path preflight: native payload path is ${path.length} UTF-16 code units (maximum 259): ${path}. `
          + 'Use a shorter isolated checkout path before packaging. Do not change Windows long-path or security policy. No artifact was packaged.',
        )
      }
    }
  }
  visit(runtimeDirectory, [])
}
