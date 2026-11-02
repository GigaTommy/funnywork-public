/**
 * Probe the external tools a packaging run will shell out to, before it starts.
 *
 * The packaging sequence reaches its first archive step minutes in, and its
 * first installer step later still, so a tool that is absent or behaves
 * differently than the scripts assume wastes a whole run. Each probe here
 * exercises the specific behaviour the run depends on rather than merely
 * checking that the executable resolves.
 */

import { execFile } from 'node:child_process'
import { access, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { promisify } from 'node:util'
import { desktopSmokeFont } from './desktop-smoke-font.ts'

const run = promisify(execFile)

/** One tool the packaging run requires, and what went wrong with it. */
export interface DesktopToolchainProbeFailure {
  readonly tool: string
  readonly detail: string
}

async function probeTar(): Promise<string | undefined> {
  // GNU tar reads the colon in an absolute Windows path as a remote-host separator, which the
  // release scripts avoid by running beside the archive; this probe confirms the archive reader works there.
  const directory = await mkdtemp(join(tmpdir(), 'dsh-tar-probe-'))
  try {
    const member = join(directory, 'probe.txt')
    await writeFile(member, 'probe\n')
    const archive = join(directory, 'probe.tar.gz')
    await run('tar', ['-czf', basename(archive), basename(member)], { cwd: directory, windowsHide: true, timeout: 20_000 })
    const listed = await run('tar', ['-tzf', basename(archive)], { cwd: dirname(archive), windowsHide: true, timeout: 20_000 })
    if (!listed.stdout.includes('probe.txt')) return `tar listed ${JSON.stringify(listed.stdout)} instead of the archived member`
    return undefined
  }
  catch (error) {
    return error instanceof Error ? error.message : String(error)
  }
  finally {
    await rm(directory, { recursive: true, force: true })
  }
}

async function probeWindowsInstallerToolchain(environment: NodeJS.ProcessEnv): Promise<DesktopToolchainProbeFailure[]> {
  const failures: DesktopToolchainProbeFailure[] = []
  const programFiles = environment['ProgramFiles(x86)']
  const vswhere = programFiles === undefined
    ? undefined
    : join(programFiles, 'Microsoft Visual Studio/Installer/vswhere.exe')
  if (vswhere === undefined) {
    failures.push({ tool: 'vswhere', detail: 'ProgramFiles(x86) is not set, so Visual Studio cannot be located' })
  }
  else {
    try {
      const located = await run(vswhere, ['-latest', '-products', '*',
        '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'],
      { windowsHide: true, timeout: 30_000 })
      if (located.stdout.trim() === '') {
        failures.push({ tool: 'vswhere', detail: 'Visual Studio C++ build tools are not installed, so the installer helper cannot compile' })
      }
    }
    catch (error) {
      failures.push({ tool: 'vswhere', detail: error instanceof Error ? error.message : String(error) })
    }
  }
  return failures
}

/** Linux native helpers require both static executables and shared libraries from musl-gcc. */
async function probeMuslCompiler(): Promise<string | undefined> {
  const directory = await mkdtemp(join(tmpdir(), 'dsh-musl-probe-'))
  let stage = 'source preparation'
  try {
    const source = join(directory, 'probe.c')
    await writeFile(source, 'int main(void) { return 0; }\n')
    stage = 'static executable compilation'
    await run('musl-gcc', ['-static', source, '-o', join(directory, 'probe')], { timeout: 20_000 })
    stage = 'shared library compilation'
    await run('musl-gcc', ['-shared', '-fPIC', source, '-o', join(directory, 'probe.so')], { timeout: 20_000 })
    return undefined
  } catch (error) {
    return `Linux Desktop requires musl-gcc with static executable and shared library support; ${stage} failed: ${error instanceof Error ? error.message : String(error)}`
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

async function probeLinuxBuildToolchain(): Promise<DesktopToolchainProbeFailure[]> {
  const failures: DesktopToolchainProbeFailure[] = []
  const report = process.report.getReport() as { header: { glibcVersionRuntime?: string } }
  if (report.header.glibcVersionRuntime === undefined) {
    failures.push({ tool: 'glibc', detail: 'Linux Desktop requires a glibc-based build host; musl is not supported' })
  }
  const musl = await probeMuslCompiler()
  if (musl !== undefined) failures.push({ tool: 'musl-gcc', detail: musl })
  for (const tool of ['cc', 'make']) {
    try { await run(tool, ['--version'], { timeout: 20_000 }) }
    catch (error) { failures.push({ tool, detail: error instanceof Error ? error.message : String(error) }) }
  }
  try { desktopSmokeFont('linux') }
  catch (error) { failures.push({ tool: 'DejaVu Sans', detail: error instanceof Error ? error.message : String(error) }) }
  const headers = join(dirname(process.execPath), '../include/node/node_api.h')
  try { await access(headers) }
  catch (error) {
    failures.push({ tool: 'Node-API headers', detail: `Use a Node installation with development headers at ${headers}: ${error instanceof Error ? error.message : String(error)}` })
  }
  return failures
}

/**
 * Probe every external tool one packaging run needs.
 * @param platform - Target platform; a Windows target already requires a Windows build host.
 * @param environment - Packaging environment used to locate Windows tooling.
 * @returns Every probe that failed, empty when the host can run the packaging sequence.
 */
export async function probeDesktopToolchain(
  platform: 'darwin' | 'win32' | 'linux',
  environment: NodeJS.ProcessEnv = process.env,
): Promise<readonly DesktopToolchainProbeFailure[]> {
  const failures: DesktopToolchainProbeFailure[] = []
  const tar = await probeTar()
  if (tar !== undefined) failures.push({ tool: 'tar', detail: tar })
  if (platform === 'win32') failures.push(...await probeWindowsInstallerToolchain(environment))
  if (platform === 'linux') failures.push(...await probeLinuxBuildToolchain())
  return failures
}

/**
 * Probe the toolchain and fail with every problem the host has.
 * @param platform - Target platform; a Windows target already requires a Windows build host.
 * @param environment - Packaging environment used to locate Windows tooling.
 * @returns Resolves when every probe passes.
 */
export async function requireDesktopToolchain(
  platform: 'darwin' | 'win32' | 'linux',
  environment: NodeJS.ProcessEnv = process.env,
): Promise<void> {
  const failures = await probeDesktopToolchain(platform, environment)
  if (failures.length === 0) return
  throw new Error(`desktop package: the build host cannot run this packaging sequence:\n${
    failures.map(failure => `  ${failure.tool}: ${failure.detail}`).join('\n')}`)
}
