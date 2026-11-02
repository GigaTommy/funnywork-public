/** Restrictive build shells must not create root-only installed Linux application trees. */
import { spawnSync } from 'node:child_process'
import { chmodSync, linkSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, realpathSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import { rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, onTestFinished } from 'vitest'
import { materializeLinuxPythonAliases } from '../scripts/linux-python-aliases.mjs'
import { normalizeLinuxArtifactPermissions } from '../scripts/linux-artifact-permissions.mjs'

function fixture() {
  const parent = realpathSync(mkdtempSync(join(tmpdir(), 'linux-artifact-permissions-')))
  onTestFinished(async () => { await rm(parent, { recursive: true, force: true }) })
  const root = join(parent, 'linux-unpacked')
  mkdirSync(join(root, 'resources'), { recursive: true, mode: 0o700 })
  for (const name of ['funnywork', 'AppRun', 'resources/app.asar']) {
    writeFileSync(join(root, name), `unchanged ${name}`, { mode: name.endsWith('.asar') ? 0o600 : 0o700 })
  }
  return { parent, root }
}

describe.skipIf(process.platform === 'win32')('Linux artifact permissions', () => {
  it('adds public read/traversal while preserving contents and executable distinction', () => {
    const { root } = fixture()
    const paths = ['funnywork', 'AppRun', 'resources/app.asar']
    const contents = paths.map(name => readFileSync(join(root, name)))
    normalizeLinuxArtifactPermissions(root)
    expect(statSync(root).mode & 0o7777).toBe(0o755)
    expect(statSync(join(root, 'resources')).mode & 0o7777).toBe(0o755)
    expect(paths.map(name => statSync(join(root, name)).mode & 0o7777)).toEqual([0o755, 0o755, 0o644])
    expect(paths.map(name => readFileSync(join(root, name)))).toEqual(contents)
    normalizeLinuxArtifactPermissions(root)
  })

  it('does not add owner write to read-only resources', () => {
    const { root } = fixture()
    chmodSync(join(root, 'resources/app.asar'), 0o400)
    normalizeLinuxArtifactPermissions(root)
    expect(statSync(join(root, 'resources/app.asar')).mode & 0o7777).toBe(0o444)
  })

  it.each(['symlink', 'hardlink', 'special bits', 'other-user write'] as const)('rejects %s before changing any application mode', (kind) => {
    const { parent, root } = fixture()
    const outside = join(parent, 'private-fixture')
    writeFileSync(outside, 'not an application file', { mode: 0o600 })
    const entry = join(root, 'resources/unsafe')
    if (kind === 'symlink') symlinkSync(outside, entry)
    else if (kind === 'hardlink') linkSync(outside, entry)
    else {
      writeFileSync(entry, 'fixture')
      chmodSync(entry, kind === 'special bits' ? 0o1600 : 0o666)
    }
    expect(() => { normalizeLinuxArtifactPermissions(root) }).toThrow(/unsafe Linux artifact entry/u)
    expect(statSync(join(root, 'resources/app.asar')).mode & 0o7777).toBe(0o600)
    expect(statSync(outside).mode & 0o7777).toBe(0o600)
  })

  it('rejects linked roots, unrelated directories and incomplete output', () => {
    const { parent, root } = fixture()
    const linkedParent = join(parent, 'linked-parent')
    symlinkSync(parent, linkedParent)
    expect(() => { normalizeLinuxArtifactPermissions(join(linkedParent, 'linux-unpacked')) }).toThrow(/physical linux-unpacked/u)
    expect(() => { normalizeLinuxArtifactPermissions(parent) }).toThrow(/physical linux-unpacked/u)
    chmodSync(join(root, 'AppRun'), 0o600)
    expect(() => { normalizeLinuxArtifactPermissions(root) }).toThrow(/incomplete Linux artifact/u)
    expect(statSync(join(root, 'resources/app.asar')).mode & 0o7777).toBe(0o600)
  })

  it.each(['linux', 'darwin', 'win32'])('limits the inherited umask correction to %s build processes', (platform) => {
    const { parent } = fixture()
    const helper = new URL('../scripts/linux-artifact-permissions.mjs', import.meta.url).href
    const script = `
      import { configureLinuxArtifactUmask } from ${JSON.stringify(helper)};
      import { mkdirSync, writeFileSync, statSync } from 'node:fs';
      process.umask(0o077);
      const previous = configureLinuxArtifactUmask(${JSON.stringify(platform)});
      mkdirSync(process.argv[1] + '/output');
      writeFileSync(process.argv[1] + '/output/entry.desktop', 'public');
      console.log(JSON.stringify([previous ?? null, process.umask(),
        statSync(process.argv[1] + '/output').mode & 0o777,
        statSync(process.argv[1] + '/output/entry.desktop').mode & 0o777]));
    `
    const result = spawnSync(process.execPath, ['--input-type=module', '--eval', script, parent], { encoding: 'utf8' })
    expect(result.status, result.stderr).toBe(0)
    expect(JSON.parse(result.stdout)).toEqual(platform === 'linux'
      ? [0o077, 0o022, 0o755, 0o644] : [null, 0o077, 0o700, 0o600])
  })

  it('sets the public artifact mask through the real Linux electron-builder configuration entry', () => {
    const entry = new URL('../electron-builder.config.mjs', import.meta.url).href
    const result = spawnSync(process.execPath, ['--input-type=module', '--eval',
      `process.umask(0o077); await import(${JSON.stringify(entry)}); console.log(process.umask());`], {
      encoding: 'utf8', env: { ...process.env, DSH_DESKTOP_APP_ID: 'com.example.permissions',
        DSH_DESKTOP_UNSIGNED: '1', DSH_DESKTOP_TARGET_PLATFORM: 'linux', DSH_DESKTOP_TARGET_ARCH: 'x64' },
    })
    expect(result.status, result.stderr).toBe(0)
    expect(result.stdout.trim()).toBe(String(0o022))
  })
})

// POSIX aliases and permission bits are not available on native Windows fixtures.
describe.skipIf(process.platform === 'win32')('Linux packaged Python aliases', () => {
  function pythonFixture() {
    const { parent, root } = fixture()
    const bin = join(root, 'resources/runtime/primary-runtime/dependencies/python/bin')
    mkdirSync(bin, { recursive: true, mode: 0o700 })
    const source = join(bin, 'python3.12')
    writeFileSync(source, 'versioned interpreter bytes', { mode: 0o700 })
    for (const name of ['python', 'python3']) symlinkSync('python3.12', join(bin, name))
    return { parent, root, bin, source }
  }

  it('copies only the two aliases into independent regular executables before permission normalization', () => {
    const { root, bin, source } = pythonFixture()
    chmodSync(source, 0o750)
    const original = statSync(source), bytes = readFileSync(source)
    expect(() => { normalizeLinuxArtifactPermissions(root) }).toThrow('unsafe Linux artifact entry')
    materializeLinuxPythonAliases(root)
    const paths = ['python', 'python3', 'python3.12'].map(name => join(bin, name))
    const metadata = paths.map(path => lstatSync(path))
    expect(metadata.every(entry => entry.isFile() && entry.nlink === 1)).toBe(true)
    expect(new Set(metadata.map(entry => entry.ino)).size).toBe(3)
    expect(metadata.map(entry => entry.mode & 0o777)).toEqual([0o750, 0o750, 0o750])
    expect(paths.map(path => readFileSync(path))).toEqual([bytes, bytes, bytes])
    expect(statSync(source).ino).toBe(original.ino)
    expect(readdirSync(bin).sort()).toEqual(['python', 'python3', 'python3.12'])
    normalizeLinuxArtifactPermissions(root)
    expect(paths.map(path => statSync(path).mode & 0o777)).toEqual([0o755, 0o755, 0o755])
  })

  it.each(['absolute', 'relative escape', 'alias loop', 'versioned loop', 'versioned escape', 'dangling', 'directory',
    'shared inode', 'special bits', 'other-user write', 'non-executable'] as const)(
    'rejects %s without replacing either alias or changing outside files', (kind) => {
      const { parent, root, bin, source } = pythonFixture()
      const outside = join(parent, 'private-interpreter')
      writeFileSync(outside, 'private bytes', { mode: 0o700 })
      if (kind === 'absolute' || kind === 'relative escape' || kind === 'alias loop') {
        rmSync(join(bin, 'python3'))
        symlinkSync(kind === 'absolute' ? outside : kind === 'relative escape' ? '../../../../../../private-interpreter' : 'python', join(bin, 'python3'))
        if (kind === 'alias loop') {
          rmSync(join(bin, 'python'))
          symlinkSync('python3', join(bin, 'python'))
        }
      } else if (kind === 'versioned loop' || kind === 'versioned escape' || kind === 'dangling' || kind === 'directory' || kind === 'shared inode') {
        rmSync(source)
        if (kind === 'versioned loop') symlinkSync('python3.12', source)
        else if (kind === 'versioned escape') symlinkSync(outside, source)
        else if (kind === 'directory') mkdirSync(source)
        else if (kind === 'shared inode') linkSync(outside, source)
      } else chmodSync(source, kind === 'special bits' ? 0o1700 : kind === 'other-user write' ? 0o777 : 0o600)
      const links = ['python', 'python3'].map(name => readlinkSync(join(bin, name)))
      expect(() => { materializeLinuxPythonAliases(root) }).toThrow()
      expect(['python', 'python3'].map(name => readlinkSync(join(bin, name)))).toEqual(links)
      expect(readFileSync(outside, 'utf8')).toBe('private bytes')
      expect(statSync(outside).mode & 0o7777).toBe(0o700)
      expect(readdirSync(bin).some(name => name.startsWith('.dsh-python-aliases-'))).toBe(false)
    },
  )

  it('rejects linked application ancestors and a Python directory outside the physical application', () => {
    const { parent, root, bin } = pythonFixture()
    const linkedParent = join(parent, 'linked-parent')
    symlinkSync(parent, linkedParent)
    expect(() => { materializeLinuxPythonAliases(join(linkedParent, 'linux-unpacked')) }).toThrow('physical linux-unpacked')
    const outside = join(parent, 'bin-outside')
    renameSync(bin, outside)
    symlinkSync(outside, bin)
    expect(() => { materializeLinuxPythonAliases(root) }).toThrow('physical Python bin')
    expect(readlinkSync(join(outside, 'python'))).toBe('python3.12')
    expect(readlinkSync(join(outside, 'python3'))).toBe('python3.12')
  })

  it('rejects aliases that name different interpreters before replacing either', () => {
    const { root, bin } = pythonFixture()
    writeFileSync(join(bin, 'python3.13'), 'another interpreter', { mode: 0o700 })
    rmSync(join(bin, 'python3'))
    symlinkSync('python3.13', join(bin, 'python3'))
    expect(() => { materializeLinuxPythonAliases(root) }).toThrow('one physical interpreter')
    expect(readlinkSync(join(bin, 'python'))).toBe('python3.12')
    expect(readlinkSync(join(bin, 'python3'))).toBe('python3.13')
  })

  it('does not admit unrelated symlinks into the permission-checked artifact', () => {
    const { root, bin } = pythonFixture()
    symlinkSync('python3.12', join(bin, 'unrecognized-alias'))
    materializeLinuxPythonAliases(root)
    expect(readlinkSync(join(bin, 'unrecognized-alias'))).toBe('python3.12')
    expect(() => { normalizeLinuxArtifactPermissions(root) }).toThrow('unsafe Linux artifact entry')
  })
})
