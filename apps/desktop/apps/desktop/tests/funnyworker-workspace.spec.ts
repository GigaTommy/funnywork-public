import { mkdtemp, rm, writeFile, readFile, mkdir, symlink, realpath } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { afterEach, expect, it } from 'vitest'
import { RemoteWorkspaceOperations } from '../src/funnyworker-workspace.ts'
const exec = promisify(execFile)
const homes: string[] = []
afterEach(async () => { await Promise.all(homes.splice(0).map(path => rm(path, { recursive: true, force: true }))) })
async function fixture() {
  const home = await mkdtemp(join(tmpdir(), 'fw-workspace-')); homes.push(home)
  const root = join(home, 'project'); await mkdir(root)
  const api = new RemoteWorkspaceOperations(id => id === 'workspace' ? root : undefined)
  const run = (action: string, values: Record<string, unknown> = {}) => api.run({ action, workspaceId: 'workspace', commandId: 'test-command', ...values })
  return { home, root, api, run }
}
it('lists pages, edits with revision, rejects stale edits and creates files exclusively', async () => {
  const { root, run } = await fixture()
  await writeFile(join(root, 'file.txt'), 'original')
  const first = await run('read', { path: 'file.txt' }) as { text: string; revision: string }
  await run('write', { path: 'file.txt', text: 'changed', revision: first.revision })
  await expect(run('write', { path: 'file.txt', text: 'lost update', revision: first.revision })).rejects.toThrow('changed')
  expect(await readFile(join(root, 'file.txt'), 'utf8')).toBe('changed')
  await run('write', { path: 'new.txt', text: '', revision: 'new' })
  await expect(run('write', { path: 'new.txt', text: 'overwrite', revision: 'new' })).rejects.toThrow()
  await writeFile(join(root, 'bom.txt'), '\ufefftext')
  const bom = await run('read', { path: 'bom.txt' }) as { text: string; revision: string }
  expect(bom.text).toBe('\ufefftext')
  await run('write', { path: 'bom.txt', text: bom.text, revision: bom.revision })
  await run('mkdir', { path: 'folder' })
  for (let index = 0; index < 70; index++) await writeFile(join(root, `page${index}.txt`), '')
  const page = await run('files', { path: '' }) as { entries: object[]; after: string }
  expect(page.entries).toHaveLength(64)
  const tail = await run('files', { path: '', after: page.after }) as { entries: object[]; after: string }
  expect(tail.entries).toHaveLength(10); expect(tail.after).toBe('')
})
it('rejects traversal, external symlinks, Git metadata and oversized/binary files', async () => {
  const { home, root, api, run } = await fixture()
  await writeFile(join(home, 'secret'), 'private')
  const linkType = process.platform === 'win32' ? 'junction' : 'dir'
  await symlink(home, join(root, 'escape'), linkType)
  await mkdir(join(root, '.git')); await writeFile(join(root, '.git', 'config'), 'private'); await symlink(join(root, '.git'), join(root, 'git-alias'), linkType)
  for (const path of ['../secret', join(home, 'secret'), 'escape/secret', '.git/config', 'git-alias/config']) await expect(run('read', { path })).rejects.toThrow()
  await expect(run('write', { path: 'escape/new', text: 'x', revision: 'new' })).rejects.toThrow()
  await writeFile(join(root, 'large'), 'x'.repeat(64001)); await expect(run('read', { path: 'large' })).rejects.toThrow('limit')
  await writeFile(join(root, 'binary'), Buffer.from([0, 1])); await expect(run('read', { path: 'binary' })).rejects.toThrow('Binary')
  await expect(api.run({ workspaceId: 'missing', action: 'files' })).rejects.toThrow('unavailable')
})
it('serializes concurrent edits so only one matching revision succeeds', async () => {
  const { root, run } = await fixture(); await writeFile(join(root, 'file'), 'start')
  const first = await run('read', { path: 'file' }) as { revision: string }
  const results = await Promise.allSettled(['one', 'two'].map(text => run('write', { path: 'file', text, revision: first.revision })))
  expect(results.map(row => row.status)).toEqual(['fulfilled', 'rejected'])
})
it('operates real branches and worktrees without force-removing dirty files', async () => {
  const { root, run } = await fixture()
  const git = (args: string[]) => exec('git', args, { cwd: root })
  await git(['init', '-b', 'main']); await git(['config', 'user.email', 'test@example.invalid']); await git(['config', 'user.name', 'Test'])
  await writeFile(join(root, 'file'), 'initial'); await git(['add', 'file']); await git(['commit', '-m', 'initial'])
  await run('branchCreate', { branch: 'feature' }); await run('branchSwitch', { branch: 'feature' })
  expect(await run('git')).toMatchObject({ current: 'feature', branches: ['feature', 'main'] })
  await expect(run('branchCreate', { branch: '--evil' })).rejects.toThrow('Invalid')
  const added = await run('worktreeAdd', { branch: 'parallel' }) as { path: string }
  const canonicalRoot = await realpath(root)
  const state = await run('git') as { worktrees: { path: string; removable: boolean }[] }
  expect(state.worktrees.find(tree => tree.path === added.path)?.removable).toBe(true)
  expect(state.worktrees.find(tree => tree.path === canonicalRoot)?.removable).toBe(false)
  await writeFile(join(added.path, 'untracked'), 'keep')
  await expect(run('worktreeRemove', { path: added.path })).rejects.toThrow()
  expect(await readFile(join(added.path, 'untracked'), 'utf8')).toBe('keep')
  await rm(join(added.path, 'untracked')); await run('worktreeRemove', { path: added.path })
  await expect(run('worktreeRemove', { path: root })).rejects.toThrow('managed')
  await run('branchCreate', { branch: 'existing' })
  const existing = await run('worktreeAdd', { branch: 'existing', newBranch: false }) as { path: string }
  await run('worktreeRemove', { path: existing.path })
  await run('branchDelete', { branch: 'existing' })
  expect(await run('git')).toMatchObject({ branches: ['feature', 'main', 'parallel'] })
})
