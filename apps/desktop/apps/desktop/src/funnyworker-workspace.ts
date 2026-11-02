/** Explicit remote operations confined to one registered Workspace. */
import { createHash } from 'node:crypto'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { realpath, readdir, readFile, writeFile, rename, unlink, mkdir, stat } from 'node:fs/promises'
import { resolve, relative, isAbsolute, dirname, join } from 'node:path'

export class WorkspaceOperationRejected extends Error {}
const exec = promisify(execFile)
const digest = (value: string): string => createHash('sha256').update(value).digest('hex')
const text = (value: unknown, max = 4096): string => {
  if (typeof value !== 'string' || value.length > max || value.includes('\0')) throw new WorkspaceOperationRejected('Invalid workspace input')
  return value
}
const branch = (value: unknown): string => {
  const name = text(value, 200)
  if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(name) || name.includes('..') || name.includes('//') || name.endsWith('/') || name.endsWith('.lock')) throw new WorkspaceOperationRejected('Invalid branch name')
  return name
}
const inside = (root: string, path: string): boolean => { const part = relative(root, path); return part === '' || (!part.startsWith('..') && !isAbsolute(part)) }

export class RemoteWorkspaceOperations {
  private readonly lanes = new Map<string, Promise<unknown>>()
  constructor(private readonly locate: (id: string) => string | undefined) {}
  /** Serialize mutations and reads per Workspace so remote edits cannot lose updates. */
  run(payload: Record<string, unknown>): Promise<unknown> {
    const id = text(payload.workspaceId, 64)
    const preceding = this.lanes.get(id) ?? Promise.resolve()
    const work = preceding.catch(() => undefined).then(async () => {
      const result = await this.execute(id, payload)
      if (JSON.stringify(result).length > 400_000) throw new WorkspaceOperationRejected('Workspace result exceeds limit')
      return result
    })
    this.lanes.set(id, work)
    void work.finally(() => { if (this.lanes.get(id) === work) this.lanes.delete(id) }).catch(() => undefined)
    return work
  }
  private async execute(id: string, p: Record<string, unknown>): Promise<unknown> {
    const registered = this.locate(id)
    if (registered === undefined) throw new WorkspaceOperationRejected('Workspace unavailable')
    const root = await realpath(registered)
    const scoped = async (value: unknown, existing = true): Promise<string> => {
      const name = text(value)
      if (isAbsolute(name) || name.split(/[\\/]/).includes('..') || name.split(/[\\/]/).includes('.git')) throw new WorkspaceOperationRejected('Path outside workspace')
      const candidate = resolve(root, name)
      if (!inside(root, candidate)) throw new WorkspaceOperationRejected('Path outside workspace')
      const canonical = existing ? await realpath(candidate)
        : join(await realpath(dirname(candidate)), candidate.slice(dirname(candidate).length + 1))
      if (!inside(root, canonical) || canonical.split(/[\\/]/).some(part => part.toLowerCase() === '.git')) throw new WorkspaceOperationRejected('Path outside workspace')
      return canonical
    }
    const git = async (args: string[]): Promise<string> => {
      const { stdout } = await exec('git', ['-c', `core.hooksPath=${process.platform === 'win32' ? 'NUL' : '/dev/null'}`, ...args], { cwd: root, timeout: 15_000, maxBuffer: 128 * 1024, env: { ...process.env, GIT_TERMINAL_PROMPT: '0' } })
      return stdout
    }
    switch (p.action) {
      case 'files': {
        const directory = await scoped(p.path ?? '')
        const entries = (await readdir(directory, { withFileTypes: true })).filter(row => row.name !== '.git').sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name))
        const after = text(p.after ?? '', 512)
        const start = after === '' ? 0 : entries.findIndex(row => row.name === after) + 1
        if (after !== '' && start === 0) throw new WorkspaceOperationRejected('Directory changed; refresh')
        const page = entries.slice(start, start + 64)
        return { entries: page.map(row => ({ name: row.name, directory: row.isDirectory(), symbolic: row.isSymbolicLink() })), after: start + page.length < entries.length ? page.at(-1)?.name : '' }
      }
      case 'read': {
        const file = await scoped(p.path)
        if ((await stat(file)).size > 64_000) throw new WorkspaceOperationRejected('File exceeds editor limit')
        const bytes = await readFile(file)
        const content = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
        if (content.includes('\0')) throw new WorkspaceOperationRejected('Binary file cannot be edited')
        return { text: content, revision: digest(content) }
      }
      case 'write': {
        const file = await scoped(p.path, p.revision !== 'new')
        if (file === root || relative(root, file).split(/[\\/]/).includes('.git')) throw new WorkspaceOperationRejected('Protected path')
        const content = text(p.text, 64_000)
        if (Buffer.byteLength(content) > 64_000) throw new WorkspaceOperationRejected('File exceeds editor limit')
        if (p.revision === 'new') { await writeFile(file, content, { flag: 'wx' }); return { revision: digest(content) } }
        const current = await readFile(file, 'utf8')
        if (digest(current) !== p.revision) throw new WorkspaceOperationRejected('File changed; reload before saving')
        const temporary = join(dirname(file), `.funnywork-${createHash('sha256').update(String(p.commandId)).digest('hex').slice(0, 20)}.tmp`)
        try {
          await writeFile(temporary, content, { flag: 'wx', mode: (await stat(file)).mode })
          if (digest(await readFile(file, 'utf8')) !== p.revision) throw new WorkspaceOperationRejected('File changed; reload before saving')
          await rename(temporary, file)
        } finally { await unlink(temporary).catch(() => undefined) }
        return { revision: digest(content) }
      }
      case 'mkdir': { await mkdir(await scoped(p.path, false)); return { ok: true } }
      case 'git': {
        const current = (await git(['branch', '--show-current'])).trim()
        const branches = (await git(['for-each-ref', '--format=%(refname:short)', 'refs/heads/'])).trim().split('\n').filter(Boolean).slice(0, 128)
        const status = await git(['status', '--short'])
        const worktrees = (await git(['worktree', 'list', '--porcelain'])).split('\n\n').filter(Boolean).slice(0, 20).map(block => ({ path: resolve(block.split('\n').find(line => line.startsWith('worktree '))?.slice(9) ?? root), branch: block.split('\n').find(line => line.startsWith('branch '))?.slice(7).replace('refs/heads/', '') ?? '' }))
        const base = join(dirname(root), '.funnywork-worktrees')
        return { current, branches, status: status.slice(0, 2000), worktrees: worktrees.map(tree => ({ ...tree, removable: inside(base, tree.path) && tree.path.startsWith(join(base, `${id}-`)) && tree.path !== root })) }
      }
      case 'branchCreate': await git(['branch', branch(p.branch)]); return { ok: true }
      case 'branchDelete': await git(['branch', '-d', '--', branch(p.branch)]); return { ok: true }
      case 'branchSwitch': await git(['switch', '--', branch(p.branch)]); return { ok: true }
      case 'worktreeAdd': {
        const name = branch(p.branch)
        const base = join(dirname(root), '.funnywork-worktrees')
        await mkdir(base, { recursive: true })
        if (await realpath(base) !== base) throw new WorkspaceOperationRejected('Worktree directory must not be a symbolic link')
        const destination = join(base, `${id}-${name.replaceAll('/', '-')}`)
        await git(p.newBranch === false ? ['worktree', 'add', destination, name] : ['worktree', 'add', '-b', name, destination, 'HEAD'])
        return { path: destination }
      }
      case 'worktreeRemove': {
        const target = text(p.path)
        const base = join(dirname(root), '.funnywork-worktrees')
        const canonical = await realpath(target)
        if (!inside(base, canonical) || !canonical.startsWith(join(base, `${id}-`))) throw new WorkspaceOperationRejected('Only managed worktrees can be removed')
        await git(['worktree', 'remove', '--', canonical]); return { ok: true }
      }
      default: throw new WorkspaceOperationRejected('Unsupported workspace operation')
    }
  }
}
