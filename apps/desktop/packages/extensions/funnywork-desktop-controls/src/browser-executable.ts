/** Local Chromium discovery without browser installation or access to personal browser profiles. @module */

import { accessSync, constants, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { posix, win32 } from 'node:path'

/** Operating-system paths used for read-only browser discovery. */
export interface BrowserExecutableOptions {
  /** Explicit executable; an unavailable selection never falls through to another browser. */
  executablePath?: string
  /** Additional complete executable paths checked before operating-system defaults. */
  searchPaths: readonly string[]
}

/**
 * Build candidate executable paths without starting processes or reading browser data.
 * @param platform Operating system used by the desktop Host.
 * @param environment Path variables, with no credentials read or forwarded.
 * @param userHome User's application directory root.
 * @returns Ordered Chromium-family executable candidates.
 */
export function browserExecutableCandidates(platform: NodeJS.Platform, environment: NodeJS.ProcessEnv, userHome: string): string[] {
  if (platform === 'win32') {
    const roots = [environment.LOCALAPPDATA, environment.ProgramFiles, environment['ProgramFiles(x86)'], environment.ProgramW6432]
      .filter((value): value is string => value !== undefined && value.length > 0)
    return [...new Set(roots.flatMap(root => [
      win32.join(root, 'Google', 'Chrome', 'Application', 'chrome.exe'),
      win32.join(root, 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
      win32.join(root, 'Chromium', 'Application', 'chrome.exe'),
    ]))]
  }
  const commands = ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser', 'microsoft-edge', 'microsoft-edge-stable']
  const onPath = (environment.PATH ?? '').split(':').filter(directory => directory.startsWith('/'))
    .flatMap(directory => commands.map(command => posix.join(directory, command)))
  if (platform !== 'darwin') return [...new Set(onPath)]
  const applications = [
    ['Google Chrome.app', 'Google Chrome'], ['Microsoft Edge.app', 'Microsoft Edge'],
    ['Chromium.app', 'Chromium'], ['Brave Browser.app', 'Brave Browser'],
  ] as const
  return [
    ...['/Applications', posix.join(userHome, 'Applications'), '/Volumes/SSD/work/Applications']
      .flatMap(root => applications.map(([application, executable]) => posix.join(root, application, 'Contents', 'MacOS', executable))),
    ...onPath,
  ]
}

function isExecutable(path: string): boolean {
  try {
    if (!statSync(path).isFile()) return false
    accessSync(path, constants.X_OK)
    return true
  } catch (_unavailableCandidate) {
    // Missing, inaccessible, and non-executable candidates cannot be launched.
    return false
  }
}

/**
 * Select an installed browser without downloading a runtime or changing the selected executable.
 * @param options Explicit selection and additional search candidates from the bundle configuration.
 * @returns A launchable executable, or undefined when the selected browser is unavailable.
 */
export function resolveBrowserExecutable(options: BrowserExecutableOptions): string | undefined {
  const candidates = options.executablePath === undefined
    ? [...options.searchPaths, ...browserExecutableCandidates(process.platform, process.env, homedir())]
    : [options.executablePath]
  return candidates.find(isExecutable)
}
