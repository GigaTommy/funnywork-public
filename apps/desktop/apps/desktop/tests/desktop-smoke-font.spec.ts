/** Package-time Office qualification requires a real installed font on every platform. */
import { join } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ available: new Set<string>() }))
vi.mock('node:fs', () => ({ existsSync: (path: string) => state.available.has(path) }))
import { desktopSmokeFont } from '../scripts/desktop-smoke-font.ts'
afterEach(() => { state.available.clear() })

it.each([
  '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
  '/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf',
  '/usr/share/fonts/dejavu/DejaVuSans.ttf',
])('selects the installed Linux qualification font at %s', (path) => {
  state.available.add(path)
  expect(desktopSmokeFont('linux', {})).toBe(path)
})

it('requires a Linux font instead of falling through to a Windows path', () => {
  expect(() => desktopSmokeFont('linux', {})).toThrow(/install the distribution DejaVu Sans/u)
  expect(() => desktopSmokeFont('freebsd', {})).toThrow(/unsupported smoke platform/u)
})

it('preserves the macOS and Windows font locations', () => {
  expect(desktopSmokeFont('darwin', {})).toBe('/System/Library/Fonts/Supplemental/Arial.ttf')
  expect(desktopSmokeFont('win32', {})).toBe(join('C:\\Windows', 'Fonts', 'arial.ttf'))
  expect(desktopSmokeFont('win32', { SystemRoot: 'D:\\Windows' })).toBe(join('D:\\Windows', 'Fonts', 'arial.ttf'))
})
