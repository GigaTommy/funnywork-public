/** Resolve packaged Office engine manifests from their complete, unpacked resource directories. */
import { registerHooks, type ModuleHooks } from 'node:module'
import { realpathSync } from 'node:fs'
import { basename, dirname, join, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

/**
 * Locate the archive containing a packaged runtime.
 * @param runtimeDir - Prepared or ASAR-contained runtime directory.
 * @returns Parent archive path, or undefined for a prepared directory.
 */
export function runtimeArchivePath(runtimeDir: string): string | undefined {
  const parent = dirname(runtimeDir)
  return basename(parent) === 'app.asar' ? parent : undefined
}

/**
 * Keep engine executable and resource paths usable by native child processes outside Electron.
 * Hooks apply only to this thread; worker threads must install their own resolver.
 * @param runtimeDir - Prepared or ASAR-contained dsh runtime directory.
 * @param platform - Platform selecting the complete physical Linux Office closure.
 * @returns Installed resolver for the Host lifetime, or undefined for a non-ASAR runtime.
 */
export function installOfficeEngineResolution(runtimeDir: string, platform: NodeJS.Platform = process.platform): ModuleHooks | undefined {
  if (runtimeArchivePath(runtimeDir) === undefined) return undefined
  const root = realpathSync(runtimeDir)
  const archive = dirname(root)
  const source = pathToFileURL(join(root, 'node_modules', '@deepseek-ai', 'libreoffice-kit')).href
  const destination = pathToFileURL(join(`${archive}.unpacked`, relative(archive, root), 'node_modules', '@deepseek-ai', 'libreoffice-kit')).href
  return registerHooks({
    resolve(specifier, context, nextResolve) {
      const resolved = nextResolve(specifier, context)
      if (!resolved.url.startsWith('file:')) return resolved
      // Electron ASAR returns null for a missing optional native package's lstat probe.
      // Keep Linux discovery and WASM workers in the already-unpacked dependency closure.
      const linuxOffice = platform === 'linux'
      const engineRequest = /^@deepseek-ai\/libreoffice-kit-(?:darwin|win32|linux)-/u.test(specifier)
        || linuxOffice && /^@deepseek-ai\/libreoffice-kit(?:-wasm)?(?:\/|$)/u.test(specifier)
      const engineTarget = /\/node_modules\/@deepseek-ai\/libreoffice-kit-(?:darwin|win32|linux)-[^/]+\//u
        .test(new URL(resolved.url).pathname)
        || linuxOffice && /\/node_modules\/@deepseek-ai\/libreoffice-kit(?:-wasm)?\//u.test(new URL(resolved.url).pathname)
      if (!engineRequest && !engineTarget) return resolved
      const canonical = pathToFileURL(realpathSync(fileURLToPath(resolved.url))).href
      if (!canonical.startsWith(`${source}-`) && !(linuxOffice && canonical.startsWith(`${source}/`))) {
        if (canonical.startsWith(pathToFileURL(archive + '/').href)) {
          throw new Error(`desktop Office engine resolved outside the runtime package directory: ${resolved.url}`)
        }
        return resolved
      }
      const physical = realpathSync(fileURLToPath(destination + canonical.slice(source.length)))
      return { ...resolved, url: pathToFileURL(physical).href }
    },
  })
}
