import { beforeEach, expect, it, vi } from 'vitest'
import { join } from 'node:path'

const files = vi.hoisted(() => new Map<string, string>())
vi.mock('node:fs/promises', () => ({
  readFile: vi.fn(async (path: string) => {
    const text = files.get(path)
    if (text === undefined) throw Object.assign(new Error('missing'), { code: 'ENOENT' })
    return text
  }),
}))
import { resolveFunnyWorkerDataRoot } from '../src/funnyworker-data-root.ts'

const root = join('/Volumes', 'Work Drive', '.dsh'), key = 'a'.repeat(64)
const account = join(root, 'accounts', key)
beforeEach(() => { files.clear() })

it('keeps an unscoped data root', async () => {
  expect(await resolveFunnyWorkerDataRoot(root)).toBe(root)
})
it('uses the original workspace registry when an updater inherits the account home', async () => {
  files.set(join(root, 'funnyworker-account-directories.json'), JSON.stringify({ principal: key }))
  expect(await resolveFunnyWorkerDataRoot(account)).toBe(root)
})
it('recovers repeated account nesting without moving session data', async () => {
  files.set(join(root, 'funnyworker-account-directories.json'), JSON.stringify({ principal: key }))
  files.set(join(account, 'funnyworker-account-directories.json'), JSON.stringify({ principal: key }))
  expect(await resolveFunnyWorkerDataRoot(join(account, 'accounts', key))).toBe(root)
})
it('leaves a custom account-like path without an owning registry alone', async () => {
  expect(await resolveFunnyWorkerDataRoot(account)).toBe(account)
  files.set(join(root, 'funnyworker-account-directories.json'), JSON.stringify({ another: 'b'.repeat(64) }))
  expect(await resolveFunnyWorkerDataRoot(account)).toBe(account)
})
it('rejects a malformed owning registry instead of opening an empty workspace', async () => {
  for (const text of ['null', '[]', '{']) {
    files.set(join(root, 'funnyworker-account-directories.json'), text)
    await expect(resolveFunnyWorkerDataRoot(account)).rejects.toThrow()
  }
})
