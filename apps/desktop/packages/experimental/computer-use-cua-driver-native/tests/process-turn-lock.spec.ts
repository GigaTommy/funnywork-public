/** Separate Hosts compete for one OS-user desktop lease, including cleanup and crash recovery. */
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { once } from 'node:events'
import { expect, it, onTestFinished } from 'vitest'

interface Event { event: string; content?: unknown }

async function setup() {
  const directory = await mkdtemp(join(tmpdir(), 'desktop-process-turn-'))
  onTestFinished(() => rm(directory, { recursive: true, force: true }))
  const path = join(directory, 'desktop.lease')
  const fixture = fileURLToPath(new URL('./fixtures/desktop-lock-host.ts', import.meta.url))
  const desktopRoot = resolve(dirname(fixture), '../../../../..')
  function host(gated: boolean | 'failing' = false) {
    const child = spawn(process.execPath, ['--import', 'tsx/esm', fixture, path, gated === 'failing' ? 'failing' : gated ? 'gated' : 'plain'], {
      cwd: desktopRoot, stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, TSX_TSCONFIG_PATH: join(desktopRoot, 'tsconfig.json') },
    })
    let output = ''
    let errors = ''
    const events: Event[] = []
    const pending: Array<{ event: string; resolve: (event: Event) => void; reject: (error: Error) => void }> = []
    child.stderr.on('data', (chunk) => { errors += String(chunk) })
    child.stdout.on('data', (chunk) => {
      output += String(chunk)
      for (;;) {
        const newline = output.indexOf('\n')
        if (newline < 0) break
        const line = output.slice(0, newline)
        output = output.slice(newline + 1)
        if (!line.startsWith('{')) continue
        const event = JSON.parse(line) as Event
        const index = pending.findIndex(item => item.event === event.event)
        if (index < 0) events.push(event)
        else pending.splice(index, 1)[0]!.resolve(event)
      }
    })
    child.on('exit', (code, signal) => {
      for (const item of pending.splice(0)) item.reject(new Error(`Fixture exited ${code ?? signal}: ${errors}`))
    })
    const exited = once(child, 'exit')
    onTestFinished(async () => { if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL'); await exited })
    function next(event: string): Promise<Event> {
      const index = events.findIndex(item => item.event === event)
      if (index >= 0) return Promise.resolve(events.splice(index, 1)[0]!)
      return new Promise((resolve, reject) => { pending.push({ event, resolve, reject }) })
    }
    return { child, next, exited, send: (command: string) => { child.stdin.write(`${command}\n`) } }
  }
  return { host }
}

it('holds complete turns across Host processes and retains ownership through canceled native cleanup', async () => {
  const f = await setup()
  const first = f.host(true)
  const second = f.host()
  await Promise.all([first.next('ready'), second.next('ready')])
  first.send('observe')
  await first.next('observed')
  // The first observe returned, but its durable turn continues.
  second.send('observe')
  expect(JSON.stringify((await second.next('blocked')).content)).toContain('COMPUTER_BUSY')
  first.send('observe')
  await first.next('observed')
  first.send('cancel')
  await first.next('cleanup-started')
  second.send('observe')
  expect(JSON.stringify((await second.next('blocked')).content)).toContain('COMPUTER_BUSY')
  first.send('cleanup')
  await first.next('released')
  second.send('observe')
  await second.next('observed')
  second.send('end')
  await second.next('released')
}, 20_000)

it('recovers the existing writer lease only after its Host process actually exits', async () => {
  const f = await setup()
  const first = f.host()
  const second = f.host()
  await Promise.all([first.next('ready'), second.next('ready')])
  first.send('observe')
  await first.next('observed')
  first.child.kill('SIGKILL')
  await first.exited
  second.send('observe')
  await second.next('observed')
  second.send('end')
  await second.next('released')
}, 20_000)

it('retains a failed native-cleanup lease against other Hosts until the failed Host exits', async () => {
  const f = await setup()
  const first = f.host('failing')
  const second = f.host()
  await Promise.all([first.next('ready'), second.next('ready')])
  first.send('observe')
  await first.next('observed')
  first.send('end')
  await first.next('released')
  second.send('observe')
  expect(JSON.stringify((await second.next('blocked')).content)).toContain('COMPUTER_BUSY')
  first.child.kill('SIGKILL')
  await first.exited
  second.send('observe')
  await second.next('observed')
  second.send('end')
  await second.next('released')
}, 20_000)
