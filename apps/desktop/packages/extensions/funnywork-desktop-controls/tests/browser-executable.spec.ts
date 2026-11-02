import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { browserExecutableCandidates, resolveBrowserExecutable } from '../src/browser-executable.ts'

const roots: string[] = []
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }) })

it('discovers system applications without invoking a browser or reading its profile', () => {
  const windows = browserExecutableCandidates('win32', { LOCALAPPDATA: 'C:\\Users\\Employee\\AppData\\Local', ProgramFiles: 'C:\\Program Files', ProgramW6432: 'C:\\Program Files' }, 'unused')
  expect(windows).toContain('C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe')
  expect(windows.filter(path => path === 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')).toHaveLength(1)
  expect(browserExecutableCandidates('win32', {}, 'unused')).toEqual([])
  const mac = browserExecutableCandidates('darwin', {}, '/Users/employee')
  expect(mac).toContain('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
  expect(mac).toContain('/Users/employee/Applications/Chromium.app/Contents/MacOS/Chromium')
  expect(mac).toContain('/Volumes/SSD/work/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
  expect(browserExecutableCandidates('linux', { PATH: 'relative::/usr/bin:/usr/bin' }, '/home/employee'))
    .toEqual(['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser', 'microsoft-edge', 'microsoft-edge-stable'].map(name => `/usr/bin/${name}`))
  expect(browserExecutableCandidates('linux', {}, '/home/employee')).toEqual([])
})

it('honors the selected executable and refuses an unavailable explicit selection without fallback', () => {
  const root = mkdtempSync(join(tmpdir(), 'funnywork-browser-selection-'))
  roots.push(root)
  const executable = join(root, 'chromium')
  writeFileSync(executable, '#!/bin/sh\nexit 0\n', { mode: 0o755 })
  expect(resolveBrowserExecutable({ executablePath: executable, searchPaths: [] })).toBe(executable)
  expect(resolveBrowserExecutable({ executablePath: join(root, 'missing'), searchPaths: [executable] })).toBeUndefined()
  expect(resolveBrowserExecutable({ searchPaths: [join(root, 'missing'), executable] })).toBe(executable)
  const directory = join(root, 'directory')
  mkdirSync(directory)
  expect(resolveBrowserExecutable({ executablePath: directory, searchPaths: [] })).toBeUndefined()
  if (process.platform !== 'win32') {
    chmodSync(executable, 0o600)
    expect(resolveBrowserExecutable({ executablePath: executable, searchPaths: [] })).toBeUndefined()
  }
})
