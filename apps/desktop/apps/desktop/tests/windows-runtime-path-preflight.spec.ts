import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, win32 } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { validateWindowsRuntimePaths } from '../scripts/windows-runtime-path-preflight.ts'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true, maxRetries: 3 }) })

function payload() {
  const root = mkdtempSync(join(tmpdir(), 'runtime-path-'))
  roots.push(root)
  const relative = ['primary-runtime', 'dependencies', 'python', 'Lib', 'site-packages', 'numpy.libs', 'libscipy_openblas64_-9e3e5a4229c1ca39f10dc82bba9e2b2b.dll']
  mkdirSync(join(root, ...relative.slice(0, -1)), { recursive: true })
  writeFileSync(join(root, ...relative), 'synthetic path fixture, never executed')
  return { root, relative }
}

it('accepts a short destination for the actual native payload', () => {
  const { root } = payload()
  expect(() => { validateWindowsRuntimePaths(root, 'C:\\fw\\win-unpacked') }).not.toThrow()
})

it('rejects the deep unsigned output before a native module reaches the loader', () => {
  const { root } = payload()
  const output = 'C:\\Users\\GinoChow\\AppData\\Local\\Temp\\fw-win-d82-20261015\\apps\\desktop\\apps\\desktop\\.desktop-build\\targets\\win-x64\\unsigned-artifacts\\win-unpacked'
  expect(() => { validateWindowsRuntimePaths(root, output) }).toThrow(/native payload path.*libscipy_openblas.*shorter isolated checkout/)
})

it('counts the NUL terminator at the Windows loader limit', () => {
  const { root, relative } = payload()
  const suffix = win32.join('resources', 'runtime', ...relative)
  const destination = 'C:\\' + 'a'.repeat(259 - 4 - suffix.length)
  expect(win32.join(destination, suffix)).toHaveLength(259)
  expect(() => { validateWindowsRuntimePaths(root, destination) }).not.toThrow()
  expect(() => { validateWindowsRuntimePaths(root, destination + 'a') }).toThrow(/maximum 259/)
})

it('rejects missing prepared runtime and relative destinations', () => {
  const { root } = payload()
  expect(() => { validateWindowsRuntimePaths(join(root, 'missing'), 'C:\\fw') }).toThrow(/ENOENT/)
  expect(() => { validateWindowsRuntimePaths(root, 'relative') }).toThrow(/absolute application directory/)
})
