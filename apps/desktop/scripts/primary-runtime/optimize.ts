/** Remove duplicate interpreter entry points and non-target native code before signing. */
import { lstat, readFile, readdir, readlink, rm, symlink, unlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

/**
 * Select one architecture from an unsigned universal Mach-O image.
 * @param bytes - Original native library bytes.
 * @param arch - Release architecture, independent of the build host.
 * @returns The selected image, or the unchanged non-universal input.
 */
export function thinMachO(bytes: Buffer, arch: 'arm64' | 'x64'): Buffer {
  if (bytes.length < 8 || bytes.readUInt32BE(0) !== 0xcafebabe) return bytes
  const count = bytes.readUInt32BE(4), cpu = arch === 'arm64' ? 0x0100000c : 0x01000007
  if (count === 0 || count > 32 || bytes.length < 8 + count * 20) throw new Error('primary runtime: invalid universal image')
  for (let i = 0; i < count; i++) {
    const entry = 8 + i * 20
    if (bytes.readUInt32BE(entry) !== cpu) continue
    const offset = bytes.readUInt32BE(entry + 8), size = bytes.readUInt32BE(entry + 12)
    if (size < 8 || offset < 8 + count * 20 || offset + size > bytes.length
      || bytes.readUInt32LE(offset) !== 0xfeedfacf || bytes.readUInt32LE(offset + 4) !== cpu) {
      throw new Error('primary runtime: invalid architecture slice')
    }
    return bytes.subarray(offset, offset + size)
  }
  throw new Error(`primary runtime: missing ${arch} architecture`)
}

async function optimizeNativeTree(root: string, arch: 'arm64' | 'x64'): Promise<void> {
  for (const entry of await readdir(root, { withFileTypes: true })) {
    const path = join(root, entry.name)
    if (entry.isSymbolicLink()) continue
    if (entry.isDirectory()) await optimizeNativeTree(path, arch)
    else if (/\.(?:so|dylib)$/u.test(entry.name)) {
      const bytes = await readFile(path), selected = thinMachO(bytes, arch)
      if (selected !== bytes) await writeFile(path, selected)
    }
  }
}

/**
 * Keep a complete payload with one Python binary and only target pnpm add-ons.
 * @param root - Unsigned payload containing dependencies/.
 * @param target - Locked release target.
 * @param pythonVersion - Locked interpreter version used by executable aliases.
 * @returns Resolves after architecture selection and relocatable alias replacement.
 */
export async function optimizePrimaryRuntime(root: string, target: string, pythonVersion: string): Promise<void> {
  const dependencies = join(root, 'dependencies'), python = join(dependencies, 'python')
  const platform = target.startsWith('mac-') ? 'darwin' : target.startsWith('win-') ? 'win32' : 'linux'
  const arch = target.endsWith('-arm64') ? 'arm64' : 'x64'
  if (platform !== 'win32') {
    const binary = `python${pythonVersion.split('.').slice(0, 2).join('.')}`
    const canonicalPath = join(python, 'bin', binary)
    if (!(await lstat(canonicalPath)).isFile()) throw new Error('primary runtime: versioned Python must be a regular file')
    const canonical = await readFile(canonicalPath)
    for (const alias of ['python', 'python3']) {
      const path = join(python, 'bin', alias)
      const metadata = await lstat(path)
      if (!metadata.isFile() && !metadata.isSymbolicLink()) throw new Error('primary runtime: invalid Python alias')
      if (metadata.isSymbolicLink() && await readlink(path) === binary) continue
      const wrapper = `#!/bin/sh\nexec "\${0%/*}/${binary}" "$@"\n`
      const bytes = await readFile(path)
      if (bytes.toString() !== wrapper && !bytes.equals(canonical)) throw new Error(`primary runtime: unexpected Python alias ${alias}`)
      await unlink(path)
      await symlink(binary, path)
    }
  }
  if (platform === 'darwin') await optimizeNativeTree(python, arch)
  const pnpm = join(dependencies, 'pnpm', 'dist')
  for (const [directory, remove] of [
    [join(pnpm, 'node_modules', '@reflink'), (name: string) => name.startsWith('reflink-') && !name.startsWith(`reflink-${platform}-${arch}`)],
    [join(pnpm, 'vendor'), (name: string) => platform !== 'win32' && /^fastlist-.*\.exe$/u.test(name)],
  ] as const) {
    let entries: string[]
    try { entries = await readdir(directory) } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT') continue
      throw error
    }
    for (const name of entries.filter(remove)) await rm(join(directory, name), { recursive: true, force: true })
  }
}
