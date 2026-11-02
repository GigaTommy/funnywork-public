/** Cached Electron bytes still require the official npm package's archive digest. */
import { createHash } from 'node:crypto'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { downloadArtifact } from '@electron/get'
import { expect, it, vi } from 'vitest'
import { desktopElectronChecksums } from '../scripts/desktop-electron-checksums.ts'

it.each([['linux', 'x64'], ['darwin', 'arm64'], ['darwin', 'x64'], ['win32', 'x64']])
('selects only the official %s-%s checksum', (platform, arch) => {
  const filename = `electron-v44.0.0-${platform}-${arch}.zip`
  expect(desktopElectronChecksums('44.0.0', platform, arch, { [filename]: 'a'.repeat(64), other: 'ignored' }))
    .toEqual({ [filename]: 'a'.repeat(64) })
})

it.each([undefined, null, {}, { 'electron-v44.0.0-linux-x64.zip': 'invalid' }])
('rejects missing or malformed trusted checksum metadata %j', (metadata) => {
  expect(() => desktopElectronChecksums('44.0.0', 'linux', 'x64', metadata)).toThrow(/no valid SHA-256/u)
})

it('verifies first download and cache hits without requesting a remote checksum file', async () => {
  const cacheRoot = await mkdtemp(join(tmpdir(), 'desktop-electron-checksums-'))
  const bytes = Buffer.from('inert archive fixture')
  const digest = createHash('sha256').update(bytes).digest('hex')
  const checksums = desktopElectronChecksums('44.0.0', 'linux', 'x64', { 'electron-v44.0.0-linux-x64.zip': digest })
  const downloader = { download: vi.fn(async (_url: string, destination: string) => { await writeFile(destination, bytes) }) }
  try {
    const options = { version: '44.0.0', platform: 'linux', arch: 'x64', artifactName: 'electron', cacheRoot, checksums, downloader }
    const archive = await downloadArtifact(options)
    expect(downloader.download).toHaveBeenCalledTimes(1)
    expect(downloader.download.mock.calls[0]![0]).toContain('/electron-v44.0.0-linux-x64.zip')
    await expect(downloadArtifact(options)).resolves.toBe(archive)
    expect(downloader.download).toHaveBeenCalledTimes(1)
    await writeFile(archive, 'corrupt cached bytes')
    downloader.download.mockImplementationOnce(async (_url, destination) => { await writeFile(destination, 'corrupt download') })
    await expect(downloadArtifact(options)).rejects.toThrow(/checksum/u)
  } finally { await rm(cacheRoot, { recursive: true, force: true }) }
})
