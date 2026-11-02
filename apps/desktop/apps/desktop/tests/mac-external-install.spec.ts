import { EventEmitter } from 'node:events'
import { beforeEach, describe, expect, it, vi } from 'vitest'

type Callback = (error: Error | null, stdout: string, stderr: string) => void
const f = vi.hoisted(() => ({
  run: vi.fn<(bin: string, args: string[], callback: Callback) => void>(),
  spawn: vi.fn(), write: vi.fn<(path: string, content: string, options: { mode: number }) => Promise<void>>(), dev: 2,
  app: { isPackaged: true, getPath: () => '/Volumes/Work Drive/funnywork.app/Contents/MacOS/funnywork', getVersion: () => '1.0.0' } }))
vi.mock('electron', () => ({ app: f.app }))
vi.mock('node:fs/promises', () => ({
  mkdir: vi.fn(), mkdtemp: vi.fn(async () => '/Volumes/Work Drive/.funnywork-updates/transaction-test'),
  realpath: vi.fn(async (p: string) => p), stat: vi.fn(async (p: string) => ({ dev: p === '/' ? 1 : f.dev })), writeFile: f.write,
}))
vi.mock('node:child_process', async () => {
  const { promisify } = await import('node:util')
  const execFile = Object.assign(() => undefined, {
    [promisify.custom]: (bin: string, args: string[]) => new Promise<{ stdout: string; stderr: string }>((resolve, reject) => {
      f.run(bin, args, (error, stdout, stderr) => {
        if (error) reject(error)
        else resolve({ stdout, stderr })
      })
    }),
  })
  return { execFile, spawn: f.spawn }
})
import { MacExternalInstall } from '../src/mac-external-install.ts'

beforeEach(() => {
  vi.clearAllMocks()
  f.run.mockImplementation((bin: string, args: string[], callback: (e: Error | null, result: string, stderr: string) => void) => {
    if (bin.endsWith('launchctl') && args[0] === 'list') { callback(new Error('not loaded'), '', ''); return }
    callback(null, bin.endsWith('plutil') ? (args.at(-1)?.includes('rollback.app') ? '1.0.0' : '1.1.0') : '', '')
  })
})

describe('external Mac installation', () => {
  it('refuses a handoff before a verified backup exists', async () => {
    await expect(new MacExternalInstall().launch()).rejects.toThrow('not prepared')
    expect(f.spawn).not.toHaveBeenCalled()
  })
  it('does not create an installer for a system-volume app', async () => {
    f.dev = 1
    expect(await MacExternalInstall.create()).toBeUndefined()
    f.dev = 2
  })
  it('rejects an archive whose signed bundle has a different version without spawning', async () => {
    await expect(new MacExternalInstall().prepare(['/verified.zip'], '2.0.0')).rejects.toThrow('version mismatch')
    expect(f.spawn).not.toHaveBeenCalled()
  })
  it('preserves paths with spaces and backs up before creating a handoff', async () => {
    const installer = new MacExternalInstall()
    await installer.prepare(['/verified.zip'], '1.1.0')
    const request = JSON.parse(f.write.mock.calls.find(call => call[0].endsWith('request.json'))![1]) as { targetBundleURL: string }
    expect(request.targetBundleURL).toContain('Work%20Drive')
    expect(f.run.mock.calls.some(call => call[0].endsWith('ditto') && call[1].at(-1)?.endsWith('rollback.app'))).toBe(true)
    const child = Object.assign(new EventEmitter(), { unref: vi.fn() })
    f.spawn.mockImplementation(() => { queueMicrotask(() => child.emit('spawn')); return child })
    await installer.launch()
    expect(f.spawn).toHaveBeenCalledWith('/bin/sh', expect.arrayContaining(['/Volumes/Work Drive/.funnywork-updates/transaction-test/install.sh']), { detached: true, stdio: 'ignore' })
  })
})
