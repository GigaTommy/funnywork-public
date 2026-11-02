/** Signed Squirrel installation from a direct child, with an independent rollback copy.
 * launchd loses removable-volume attribution when Squirrel moves the running App.
 */
import { execFile, spawn } from 'node:child_process'
import { mkdir, mkdtemp, realpath, stat, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { promisify } from 'node:util'
import { app } from 'electron'

const run = promisify(execFile)
const requirement = '=identifier "com.funnyworker.desktop" and certificate leaf[subject.OU] = "VL3HPHK35K"'

/** Execute the verified, shipped installer without handing external-volume writes to launchd. */
export class MacExternalInstall {
  private prepared: { directory: string; target: string } | undefined

  /** Only external-volume installations need the direct handoff. */
  static async create(): Promise<MacExternalInstall | undefined> {
    if (process.platform !== 'darwin' || !app.isPackaged) return undefined
    const target = await realpath(join(dirname(app.getPath('exe')), '../..'))
    return (await stat(target)).dev === (await stat('/')).dev ? undefined : new MacExternalInstall()
  }

  private async verify(bundle: string, version: string): Promise<void> {
    await run('/usr/bin/codesign', ['--verify', '--deep', '--strict', '-R', requirement, bundle])
    const result = await run('/usr/bin/plutil', ['-extract', 'CFBundleShortVersionString', 'raw', '-o', '-', join(bundle, 'Contents/Info.plist')])
    if (result.stdout.trim() !== version) throw new Error('desktop update: prepared Mac version mismatch')
  }

  /** Complete every copy and signature check while the current App still works. */
  async prepare(files: string[], version: string): Promise<void> {
    this.prepared = undefined
    const archive = files.find(file => file.endsWith('.zip'))
    if (archive === undefined) throw new Error('desktop update: no verified Mac archive')
    const target = await realpath(join(dirname(app.getPath('exe')), '../..'))
    // Product update transactions live on the installation volume, never in system temp.
    const root = join(dirname(target), '.funnywork-updates')
    await mkdir(root, { recursive: true, mode: 0o700 })
    if ((await stat(root)).dev !== (await stat(target)).dev) throw new Error('desktop update: backup volume mismatch')
    const directory = await mkdtemp(join(root, 'transaction-'))
    const incoming = join(directory, 'incoming')
    await mkdir(incoming, { mode: 0o700 })
    await run('/usr/bin/ditto', ['-xk', archive, incoming])
    const candidate = join(incoming, 'funnywork.app')
    await this.verify(candidate, version)
    const backup = join(directory, 'rollback.app')
    await run('/usr/bin/ditto', [target, backup])
    await this.verify(backup, app.getVersion())
    const request = { launchAfterInstallation: false, updateBundleURL: `file://${candidate}/`,
      useUpdateBundleName: true, bundleIdentifier: 'com.funnyworker.desktop', targetBundleURL: `file://${target}/` }
    // URL construction must encode spaces and non-ASCII install paths.
    const { pathToFileURL } = await import('node:url')
    request.updateBundleURL = pathToFileURL(candidate + '/').href
    request.targetBundleURL = pathToFileURL(target + '/').href
    await writeFile(join(directory, 'request.json'), JSON.stringify(request), { mode: 0o600 })
    await writeFile(join(directory, 'state.json'), JSON.stringify({ target, oldVersion: app.getVersion(), version, backup }), { mode: 0o600 })
    await writeFile(join(directory, 'install.sh'), MAC_INSTALL_SCRIPT, { mode: 0o700 })
    this.prepared = { directory, target }
  }

  /** Spawn a stable installer copy; the caller quits only after successful process creation. */
  async launch(): Promise<void> {
    if (this.prepared === undefined) throw new Error('desktop update: external installation is not prepared')
    const { directory, target } = this.prepared
    // Do not race an already registered native installer. No other launchd jobs are touched.
    await run('/bin/launchctl', ['remove', 'com.funnyworker.desktop.ShipIt']).catch(() => undefined)
    const registered = await run('/bin/launchctl', ['list', 'com.funnyworker.desktop.ShipIt']).then(() => true, () => false)
    if (registered) throw new Error('desktop update: native installer could not be stopped')
    const child = spawn('/bin/sh', [join(directory, 'install.sh'), directory, target], { detached: true, stdio: 'ignore' })
    await new Promise<void>((resolve, reject) => { child.once('spawn', resolve); child.once('error', reject) })
    child.unref()
  }
}

/** Kept constant: paths arrive exclusively as quoted positional arguments. */
export const MAC_INSTALL_SCRIPT = `#!/bin/sh
set -eu
work=$1
target=$2
backup="$work/rollback.app"
helper="$backup/Contents/Frameworks/Squirrel.framework/Resources/ShipIt"
expected=$(/usr/bin/plutil -extract version raw -o - "$work/state.json")
restore() {
  /usr/bin/ditto "$backup" "$work/restore.app" || return 1
  /usr/bin/codesign --verify --deep --strict "$work/restore.app" || return 1
  if [ -e "$target" ]; then /bin/mv "$target" "$work/failed-install.app" || return 1; fi
  /bin/mv "$work/restore.app" "$target" || return 1
  /usr/bin/printf 'rolled-back\\n' > "$work/result"
  /usr/bin/open "$target"
}
"$helper" com.funnyworker.desktop.externalupdate "$work/request.json" > "$work/installer.log" 2>&1 &
installer=$!
(sleep 180; kill "$installer" 2>/dev/null || true) &
watchdog=$!
status=0
wait "$installer" || status=$?
kill "$watchdog" 2>/dev/null || true
if [ "$status" = 0 ]; then
  actual=$(/usr/bin/plutil -extract CFBundleShortVersionString raw -o - "$target/Contents/Info.plist") || actual=''
  if [ "$actual" = "$expected" ] && /usr/bin/codesign --verify --deep --strict "$target"; then
    if /usr/bin/open "$target"; then /usr/bin/printf 'installed\\n' > "$work/result"; exit 0; fi
  fi
fi
restore
`
