/** Desktop-specific selection of workspace and runtime-only dependency patches. */

/** A patch shared with the workspace, excluded from runtime installation, or supplied only to Desktop. */
export type DesktopRuntimePatch =
  | { scope: 'shared'; reason: string }
  | { scope: 'workspace-only'; reason: string }
  | { scope: 'runtime-only'; path: string; reason: string }

/** Every workspace patch requires an explicit Desktop decision; runtime-only entries may add or replace a patch. */
export const DESKTOP_RUNTIME_PATCHES: Readonly<Record<string, DesktopRuntimePatch>> = {
  '@earendil-works/pi-ai@1.1.0': { scope: 'shared', reason: 'Runtime providers need the streamed argument parsing fix.' },
  '@electron/osx-sign@1.3.3': { scope: 'workspace-only', reason: 'Signing executes on the build host.' },
  '@fortune-sheet/core@1.0.4': { scope: 'shared', reason: 'Host workbook parsing and the bundled preview share patched spreadsheet formatting.' },
  '@fortune-sheet/react@1.0.4': { scope: 'workspace-only', reason: 'Patched spreadsheet code is embedded in the client bundle.' },
  '@trycua/cua-driver@0.28.0': { scope: 'runtime-only', path: 'patches/cua-driver-desktop-asar.patch', reason: 'Native FFI must open unpacked libraries from the packaged Electron application.' },
  '@yao-pkg/pkg@6.21.0': { scope: 'workspace-only', reason: 'The executable packer executes on the build host.' },
  'app-builder-lib@26.17.0': { scope: 'workspace-only', reason: 'DMG format validation executes on the build host.' },
  'exceljs@4.4.0': { scope: 'shared', reason: 'Host workbook parsing and the bundled preview share patched OOXML parsing.' },
  'extract-zip@2.0.1': { scope: 'workspace-only', reason: 'Archive extraction executes on the build host.' },
  'node-addon-require-builtin@0.1.9': { scope: 'shared', reason: 'Electron Hosts use the existing exposed builtin loader instead of a V8 fingerprint probe.' },
  'node-pty@1.2.0-beta.15': { scope: 'shared', reason: 'Runtime terminals need helper resolution and Windows worker cleanup.' },
}

/** Reviewed Desktop dependency constraints, independent of patch selection. */
export const DESKTOP_RUNTIME_DEPENDENCY_OVERRIDES: Readonly<Record<string, string>> = {
  // The provider declares ^1.1.0; its patch is qualified against 1.1.0.
  '@earendil-works/pi-ai@^1.1.0': '1.1.0',
  // Preserve the reviewed security constraints in the independent runtime install.
  '@hono/node-server': '2.1.5',
  'dompurify': '3.4.17',
  'fflate': '0.8.3',
  'hono': '4.13.14',
  '@modelcontextprotocol/sdk@^1': '1.32.2',
  'body-parser@^2': '2.4.0',
  'fast-uri@^3': '3.1.9',
  'follow-redirects': '1.16.2',
  'glob@^13': '14.0.3',
  'ip-address@^10': '10.7.5',
  'katex@^0.16': '0.18.7',
  'minimatch@>=9 <11': '11.0.3',
  'node-forge': '1.4.2',
  'qs@^6': '6.17.1',
  'undici@^6': '6.29.1',
  'undici@^7': '7.30.1',
  'undici@^8': '8.12.3',
  'uuid@^8': '11.1.1',
  'zod@^4': '4.6.7',
  'node-addon-require-builtin': '0.1.9',
}
