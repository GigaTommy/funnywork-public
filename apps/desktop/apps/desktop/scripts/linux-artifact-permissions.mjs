/** Linux distributables contain public application files, never private build logs or user data. */
import { constants, closeSync, fchmodSync, fstatSync, lstatSync, openSync, readdirSync, realpathSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'

/**
 * Set the mask of this build process only; children inherit readable public artifact creation.
 * @param {NodeJS.Platform} platform Explicit build target.
 * @returns {number | undefined} Previous mask for callers that restore it when their build settles.
 */
export function configureLinuxArtifactUmask(platform) {
  return platform === 'linux' ? process.umask(0o022) : undefined
}

/**
 * Normalize a completed, exclusively owned Linux application directory before archiving it.
 * @param {string} directory Electron-builder's concrete linux-unpacked output, not a source or user directory.
 * @returns {void} Rejects links, shared file inodes, special files/bits and writable-by-others entries before any chmod.
 */
export function normalizeLinuxArtifactPermissions(directory) {
  const root = resolve(directory)
  if (basename(root) !== 'linux-unpacked' || realpathSync(root) !== root || !lstatSync(root).isDirectory()) {
    throw new Error('desktop package: expected a physical linux-unpacked application directory')
  }
  const entries = []
  function visit(path) {
    const stat = lstatSync(path)
    if ((!stat.isDirectory() && !stat.isFile()) || (stat.isFile() && stat.nlink !== 1)
      || (stat.mode & 0o7022) !== 0) {
      throw new Error(`desktop package: unsafe Linux artifact entry: ${path}`)
    }
    entries.push({ path, stat })
    if (stat.isDirectory()) for (const name of readdirSync(path)) visit(join(path, name))
  }
  visit(root)
  for (const name of ['funnywork', 'AppRun', 'resources/app.asar']) {
    const stat = lstatSync(join(root, name))
    if (!stat.isFile() || (name !== 'resources/app.asar' && (stat.mode & 0o111) === 0)) {
      throw new Error(`desktop package: incomplete Linux artifact: ${name}`)
    }
  }
  for (const { path, stat } of entries) {
    const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK
      | (stat.isDirectory() ? constants.O_DIRECTORY : 0))
    try {
      const current = fstatSync(fd)
      if (current.dev !== stat.dev || current.ino !== stat.ino || current.mode !== stat.mode || current.nlink !== stat.nlink) {
        throw new Error(`desktop package: Linux artifact changed during permission validation: ${path}`)
      }
      // Add only public read/traversal bits. Keep owner write and the original executable/non-executable distinction.
      const mode = (stat.mode & 0o777) | 0o444 | (stat.isDirectory() || (stat.mode & 0o111) !== 0 ? 0o111 : 0)
      fchmodSync(fd, mode)
    } finally {
      closeSync(fd)
    }
  }
}
