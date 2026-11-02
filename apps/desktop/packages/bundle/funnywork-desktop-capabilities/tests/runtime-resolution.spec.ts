/** Keep the Desktop bundle's plugin visible through normal profile package admission. */
import { mkdir, mkdtemp, readFile, rm, symlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { composeEntries, createRuntimeResolution, initProfile, loadProfileDirectory } from '@deepseek-ai/dsh-app-boot'
import { expect, it } from 'vitest'

it('includes its declared composition patch in the production package', async () => {
  const manifest = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8')) as {
    dsh: { bundle: { patch: string } }
    files: string[]
    exports: Record<string, unknown>
  }
  expect(manifest.files).toContain(manifest.dsh.bundle.patch.replace('./', ''))
  expect(manifest.exports[manifest.dsh.bundle.patch]).toBe(manifest.dsh.bundle.patch)
  expect(await readFile(new URL(manifest.dsh.bundle.patch, new URL('../', import.meta.url)), 'utf8')).toContain('@deepseek-ai/dsh-funnywork-desktop-controls')
})

it('maps the plugin carried by the bundle dependency instead of importing the bundle container itself', async () => {
  const home = await mkdtemp(join(tmpdir(), 'funnywork-desktop-profile-'))
  try {
    const profileDir = join(home, 'profiles', 'desktop')
    initProfile(profileDir, ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', '@deepseek-ai/dsh-funnywork-desktop-capabilities'])
    const scope = join(profileDir, 'node_modules', '@deepseek-ai')
    await mkdir(scope, { recursive: true })
    await symlink(fileURLToPath(new URL('../', import.meta.url)), join(scope, 'dsh-funnywork-desktop-capabilities'), process.platform === 'win32' ? 'junction' : 'dir')
    const installAnchor = fileURLToPath(new URL('../../../../apps/cli/package.json', import.meta.url))
    const profile = loadProfileDirectory('dsh', profileDir, installAnchor)
    expect(profile.skippedBundles).toEqual([])
    const row = composeEntries(profile.layers.map(layer => layer.patches)).find(entry => entry.id === 'funnywork-desktop-capabilities')
    expect(row?.name).toBe('@deepseek-ai/dsh-funnywork-desktop-controls')
    const resolution = await createRuntimeResolution({ installAnchor, profile, home })
    expect(resolution.entries.some(entry => entry.name === row?.name)).toBe(true)
    expect(resolution.entries.some(entry => entry.name === '@deepseek-ai/dsh-experimental-browser-use-playwright-mcp')).toBe(true)
    expect(resolution.entries.some(entry => entry.name === '@deepseek-ai/dsh-experimental-cua-macos')).toBe(true)
  } finally {
    await rm(home, { recursive: true, force: true })
  }
})
