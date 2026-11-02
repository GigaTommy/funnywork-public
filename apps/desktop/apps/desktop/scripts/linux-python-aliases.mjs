/** Materialize only prepared Python command aliases in the copied Linux application. */
import {
  chmodSync, closeSync, constants, fstatSync, lstatSync, mkdtempSync, openSync, readFileSync,
  readlinkSync, realpathSync, renameSync, rmSync, writeFileSync,
} from 'node:fs'
import { basename, join, resolve } from 'node:path'

/**
 * Replace the two validated Python aliases before Linux artifact permission checks and archiving.
 * @param {string} directory Exclusively owned, physical linux-unpacked application directory.
 * @returns {void} Copies preserve bytes and mode with independent inodes; failures stop packaging.
 * Invalid inputs remain unchanged; an I/O failure during replacement requires discarding this application output.
 */
export function materializeLinuxPythonAliases(directory) {
  const root = resolve(directory)
  if (basename(root) !== 'linux-unpacked' || realpathSync(root) !== root || !lstatSync(root).isDirectory()) {
    throw new Error('desktop package: expected a physical linux-unpacked application directory')
  }
  const bin = join(root, 'resources/runtime/primary-runtime/dependencies/python/bin')
  if (realpathSync(bin) !== bin || !lstatSync(bin).isDirectory()) {
    throw new Error(`desktop package: expected a physical Python bin directory: ${bin}`)
  }
  const aliases = ['python', 'python3'].map(name => {
    const path = join(bin, name), metadata = lstatSync(path)
    if (!metadata.isSymbolicLink()) throw new Error(`desktop package: expected a Python command alias: ${path}`)
    const target = readlinkSync(path)
    if (!/^python3\.\d+$/u.test(target)) throw new Error(`desktop package: invalid relative Python alias: ${path}`)
    return { name, path, target, metadata }
  })
  const source = join(bin, aliases[0].target)
  if (aliases.some(alias => alias.target !== aliases[0].target)
    || !lstatSync(source).isFile() || realpathSync(source) !== source) {
    throw new Error(`desktop package: Python aliases must target one physical interpreter inside ${bin}`)
  }
  const fd = openSync(source, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK)
  let bytes, mode
  try {
    const metadata = fstatSync(fd)
    if (!metadata.isFile() || metadata.nlink !== 1 || (metadata.mode & 0o7022) !== 0 || (metadata.mode & 0o111) === 0) {
      throw new Error(`desktop package: unsafe Python interpreter: ${source}`)
    }
    bytes = readFileSync(fd)
    mode = metadata.mode & 0o777
  } finally {
    closeSync(fd)
  }
  const temporary = mkdtempSync(join(bin, '.dsh-python-aliases-'))
  try {
    for (const alias of aliases) {
      const path = join(temporary, alias.name)
      writeFileSync(path, bytes, { flag: 'wx', mode })
      chmodSync(path, mode)
    }
    for (const alias of aliases) {
      const current = lstatSync(alias.path)
      if (!current.isSymbolicLink() || current.dev !== alias.metadata.dev || current.ino !== alias.metadata.ino
        || readlinkSync(alias.path) !== alias.target) {
        throw new Error(`desktop package: Python alias changed during materialization: ${alias.path}`)
      }
    }
    for (const alias of aliases) renameSync(join(temporary, alias.name), alias.path)
  } finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}
