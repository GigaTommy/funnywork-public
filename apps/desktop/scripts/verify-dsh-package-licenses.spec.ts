import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { inspectDshPackageLicenses } from './verify-dsh-package-licenses.ts'

const roots: string[] = []

afterEach(() => {
  for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true })
})

function writeManifest(root: string, file: string, manifest: Record<string, unknown>): void {
  const path = join(root, file)
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`)
}

function createWorkspace(): string {
  const temporary = mkdtempSync(join(tmpdir(), 'dsh-package-licenses-'))
  roots.push(temporary)
  const root = join(temporary, 'apps/desktop')
  writeFileSync(join(temporary, 'LICENSE'), 'project license fixture\n')
  writeManifest(root, 'package.json', {
    name: '@deepseek-ai/dsh-root',
    license: 'MIT',
    workspaces: ['apps/*', 'packages/*/*', 'vendor/*'],
  })
  return root
}

describe('DSH package license gate', () => {
  it('checks root, unhyphenated CLI, and dsh-prefixed package names while ignoring other families', () => {
    const root = createWorkspace()
    writeManifest(root, 'apps/cli/package.json', { name: '@deepseek-ai/dsh', license: 'MIT' })
    writeManifest(root, 'packages/core/agent/package.json', {
      name: '@deepseek-ai/dsh-agent',
      license: 'BSD-3-Clause',
    })
    writeManifest(root, 'vendor/cordis/package.json', {
      name: '@deepseek-ai/cordis',
      license: 'BSD-3-Clause',
    })

    expect(inspectDshPackageLicenses(root)).toEqual({
      packageCount: 3,
      failures: [
        'packages/core/agent/package.json: @deepseek-ai/dsh-agent must declare "license": "MIT"; found "BSD-3-Clause".',
      ],
    })
  })

  it('rejects a missing license declaration', () => {
    const root = createWorkspace()
    writeManifest(root, 'packages/core/agent/package.json', { name: '@deepseek-ai/dsh-agent' })

    expect(inspectDshPackageLicenses(root).failures).toEqual([
      'packages/core/agent/package.json: @deepseek-ai/dsh-agent must declare "license": "MIT"; found undefined.',
    ])
  })
})


describe('FunnyWork package license scope', () => {
  it('accepts only the named owned package with its complete matching project license', () => {
    const root = createWorkspace()
    const file = 'packages/extensions/funnywork-desktop-controls/package.json'
    writeManifest(root, file, { name: '@deepseek-ai/dsh-funnywork-desktop-controls', license: 'SEE LICENSE IN LICENSE', private: true })
    expect(inspectDshPackageLicenses(root).failures).toHaveLength(1)
    writeFileSync(join(root, 'packages/extensions/funnywork-desktop-controls/LICENSE'), 'project license fixture\n')
    expect(inspectDshPackageLicenses(root).failures).toEqual([])
    writeFileSync(join(root, 'packages/extensions/funnywork-desktop-controls/LICENSE'), 'different license')
    expect(inspectDshPackageLicenses(root).failures).toHaveLength(1)
  })
  it('rejects extending the owned license exception to an upstream package or a different path', () => {
    const root = createWorkspace()
    writeManifest(root, 'packages/core/agent/package.json', { name: '@deepseek-ai/dsh-agent', license: 'SEE LICENSE IN LICENSE', private: true })
    writeManifest(root, 'packages/extensions/impostor/package.json', { name: '@deepseek-ai/dsh-funnywork-desktop-controls', license: 'SEE LICENSE IN LICENSE', private: true })
    expect(inspectDshPackageLicenses(root).failures).toHaveLength(2)
  })
})
