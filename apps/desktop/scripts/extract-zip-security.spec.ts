/** Archive extraction retains in-root links and rejects writes through symlinks. */
import { mkdtemp, mkdir, readFile, readdir, readlink, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import extract from 'extract-zip'
import { strToU8, zipSync } from 'fflate'
import { expect, it } from 'vitest'

it.skipIf(process.platform === 'win32')('contains relative link targets and preserves framework-style internal links', async () => {
  // Windows symlink creation requires privileges not present in the standard test lane.
  const root = await mkdtemp(join(tmpdir(), 'zip-link-target-'))
  try {
    const archive = join(root, 'input.zip'), output = join(root, 'output')
    await writeFile(archive, zipSync({
      'Versions/A/content': strToU8('framework'),
      'Versions/Current': [strToU8('A'), { os: 3, attrs: 0o120777 << 16 }],
    }))
    await extract(archive, { dir: output })
    expect(await readlink(join(output, 'Versions/Current'))).toBe('A')
    expect(await readFile(join(output, 'Versions/Current/content'), 'utf8')).toBe('framework')
    await writeFile(archive, zipSync({ escape: [strToU8('../outside'), { os: 3, attrs: 0o120777 << 16 }] }))
    await expect(extract(archive, { dir: output })).rejects.toThrow('Out of bound path')
    expect(await readdir(root)).not.toContain('outside')
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

it.skipIf(process.platform === 'win32')('rejects final-component symlinks without modifying their targets', async () => {
  const root = await mkdtemp(join(tmpdir(), 'zip-final-link-'))
  try {
    const output = join(root, 'output'), outside = join(root, 'outside.txt'), archive = join(root, 'input.zip')
    await mkdir(output)
    await writeFile(outside, 'original')
    await symlink(outside, join(output, 'entry'))
    await writeFile(archive, zipSync({ entry: strToU8('replacement') }))
    await expect(extract(archive, { dir: output })).rejects.toThrow('Refusing to overwrite symlink')
    expect(await readFile(outside, 'utf8')).toBe('original')
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

it.skipIf(process.platform === 'win32')('rejects an escaped parent before creating directories outside the extraction root', async () => {
  const root = await mkdtemp(join(tmpdir(), 'zip-parent-link-'))
  try {
    const output = join(root, 'output'), outside = join(root, 'outside'), archive = join(root, 'input.zip')
    await mkdir(output)
    await mkdir(outside)
    await symlink(outside, join(output, 'parent'))
    await writeFile(archive, zipSync({ 'parent/new-directory/file.txt': strToU8('replacement') }))
    await expect(extract(archive, { dir: output })).rejects.toThrow('Out of bound path')
    expect(await readdir(outside)).toEqual([])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
