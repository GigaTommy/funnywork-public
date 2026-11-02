import { mkdtemp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, expect, it } from 'vitest'
import { FunnyWorkerStartupTimings } from '../src/funnyworker-startup-timing.ts'
const roots: string[] = []
afterEach(async () => { await Promise.all(roots.splice(0).map(root => rm(root, { recursive: true, force: true }))) })
async function location() { const root = await mkdtemp(join(tmpdir(), 'fw-timing-')); roots.push(root); return join(root, 'timings.jsonl') }
it('records stages and failures without persisting results or exception details', async () => {
  const path = await location(), timing = new FunnyWorkerStartupTimings(path)
  expect(await timing.measure('login-request', async () => ({ token: 'private-token', username: 'private-name' }))).toHaveProperty('token')
  await expect(timing.measure('startup-config', async () => { throw new Error('private-password') })).rejects.toThrow('private-password')
  await timing.flush()
  const text = await readFile(path, 'utf8'), rows = text.trim().split('\n').map((line) => { const value: unknown = JSON.parse(line); return value })
  expect(text).not.toContain('private-')
  expect(rows).toMatchObject([{ outcome: 'ok' }, { outcome: 'error' }])
  expect(rows[0]).toMatchObject({ schema: 1, stage: 'login-request' })
  expect(text).toMatch(/"durationMs":\d+/)
  if (process.platform !== 'win32') expect((await stat(path)).mode & 0o777).toBe(0o600)
})
it('rotates bounded diagnostics and ignores unavailable diagnostic storage', async () => {
  const path = await location(); await writeFile(path, 'x'.repeat(1048576))
  const timing = new FunnyWorkerStartupTimings(path)
  await timing.measure('session-check', async () => Response.json({}, { status: 401 })); await timing.flush()
  expect((await stat(path + '.previous')).size).toBe(1048576)
  expect(await readFile(path, 'utf8')).toContain('"outcome":"error"')
  const directory = await location(); await mkdir(directory)
  const unavailable = new FunnyWorkerStartupTimings(directory)
  expect(await unavailable.measure('workspace-runtime', async () => 42)).toBe(42)
  await unavailable.flush()
})
