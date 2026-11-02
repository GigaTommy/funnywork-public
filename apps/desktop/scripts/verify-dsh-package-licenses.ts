/**
 * Check upstream MIT declarations and explicitly scoped FunnyWork package licenses.
 * @module scripts/verify-dsh-package-licenses
 */

import { existsSync, globSync, readFileSync } from 'node:fs'
import { resolve, sep } from 'node:path'
import { FUNNYWORK_PACKAGE_NAMES } from './funnywork-package-policy.ts'

const ROOT = resolve(import.meta.dirname, '..')
const FUNNYWORK_PACKAGES = new Map(Object.entries(FUNNYWORK_PACKAGE_NAMES).map(([dir, name]) => [`${dir}/package.json`, name]))
const DSH_PACKAGE_NAME = /^@deepseek-ai\/dsh(?:-|$)/

/** Result of checking every DSH package reachable through the root workspace list. */
export interface DshPackageLicenseReport {
  /** Number of DSH package manifests checked. */
  packageCount: number
  /** Repository-relative diagnostics for incorrect license declarations. */
  failures: string[]
}

function readManifest(root: string, file: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(readFileSync(resolve(root, file), 'utf8'))
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error(`verify-dsh-package-licenses: ${file} must contain a JSON object.`)
  }
  return parsed as Record<string, unknown>
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry: unknown) => typeof entry === 'string')
}

function workspaceManifestPaths(root: string): string[] {
  const rootManifest = readManifest(root, 'package.json')
  const workspaces = rootManifest.workspaces
  if (!isStringArray(workspaces)) {
    throw new Error('verify-dsh-package-licenses: package.json workspaces must be a string array.')
  }

  const files = new Set(['package.json'])
  for (const pattern of workspaces) {
    for (const file of globSync(`${pattern}/package.json`, { cwd: root })) {
      files.add(file)
    }
  }
  return [...files].sort()
}

function printable(value: unknown): string {
  return value === undefined ? 'undefined' : JSON.stringify(value)
}

/**
 * Check every DSH npm package declared by the repository workspace.
 * @param root - absolute repository root containing the workspace package.json.
 * @returns the checked package count and every incorrect license declaration.
 */
export function inspectDshPackageLicenses(root: string): DshPackageLicenseReport {
  let packageCount = 0
  const failures: string[] = []

  for (const file of workspaceManifestPaths(root)) {
    const manifest = readManifest(root, file)
    const name = manifest.name
    if (typeof name !== 'string' || !DSH_PACKAGE_NAME.test(name)) continue

    packageCount++
    const normalized = file.split(sep).join('/')
    if (FUNNYWORK_PACKAGES.get(normalized) === name) {
      const licensePath = resolve(root, file, '../LICENSE')
      const projectLicense = resolve(root, '../../LICENSE')
      if (manifest.license !== 'SEE LICENSE IN LICENSE' || manifest.private !== true
        || !existsSync(licensePath) || !existsSync(projectLicense)
        || readFileSync(licensePath, 'utf8') !== readFileSync(projectLicense, 'utf8')) {
        failures.push(`${normalized}: FunnyWork package requires a matching project LICENSE, private: true, and SEE LICENSE IN LICENSE.`)
      }
      continue
    }
    if (manifest.license !== 'MIT') {
      const normalizedFile = file.split(sep).join('/')
      failures.push(
        `${normalizedFile}: ${name} must declare "license": "MIT"; found ${printable(manifest.license)}.`,
      )
    }
  }

  return { packageCount, failures }
}

if (process.argv[1] && import.meta.filename === resolve(process.argv[1])) {
  const report = inspectDshPackageLicenses(ROOT)
  if (report.failures.length > 0) {
    process.stderr.write('verify-dsh-package-licenses: incorrect DSH package license declarations found:\n')
    for (const failure of report.failures) process.stderr.write(`  ${failure}\n`)
    process.exitCode = 1
  } else {
    process.stdout.write(
      `verify-dsh-package-licenses: ${String(report.packageCount)} DSH package(s) checked; upstream MIT and FunnyWork license scopes verified.\n`,
    )
  }
}
