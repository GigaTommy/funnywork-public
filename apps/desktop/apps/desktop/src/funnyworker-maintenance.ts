/** The original FunnyWork desktop executes repository assignments through its account task channel. */
import { spawn } from 'node:child_process'
import { readFile, writeFile, mkdir, lstat, rename, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { StringDecoder } from 'node:string_decoder'
import { HistorySyncHttpError } from './funnyworker-sync-error.ts'
import { maintenanceLease } from './funnyworker-maintenance-lease.ts'
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import { MaintenanceAgentTerminationUnconfirmed } from './funnyworker-maintenance-agent.ts'

interface Check {
  id: string
  argv: string[]
  timeoutSeconds: number
}
interface SpecBase {
  version: number
  problemId: string
  repository: string
  baseBranch: string
  baseSha: string
  platform: string
  branch: string
  title: string
  requirement: string
  acceptance: string
  checks: Check[]
  allowedPaths?: string[]
}
type DeliverySpec = {
  mode: 'VERIFY' | 'DELIVER'
  jobId: string
  candidateSha: string
  prUrl: string
  requiredCi: string[]
  issues: string[]
}
type Spec = SpecBase & (DeliverySpec | { mode?: undefined; jobId?: undefined })
export interface ModelUsage {
  calls: number
  inputTokens: number | null
  outputTokens: number | null
  cachedInputTokens: number | null
}
interface Report {
  modelUsage?: ModelUsage
  epoch: number
  specHash: string
  outcome: 'CANDIDATE_READY' | 'VERIFIED' | 'DELIVERED' | 'CHECKS_FAILED' | 'NEEDS_ENVIRONMENT' | 'RUNTIME_FAILED'
  resultSha: string | null
  prUrl: string | null
  summary: string
  checks: { id: string; exitCode: number; output: string }[]
}
interface Configuration {
  workspaceRoot: string
  pollIntervalMs: number
  commandTimeoutMs: number
  maxIterations: number
  maxOutputBytes: number
}
interface Journal {
  phase: 'RUNNING' | 'READY' | 'SENT' | 'STALE'
  epoch: number
  specHash: string
  runtimeEpoch?: string
  report?: Report
}
/** Native command exit status and bounded combined output. */
export interface MaintenanceCommandResult {
  exitCode: number
  output: string
  stdout?: string
}
/** Executor used by the embedded worker for native tools, checks and cancellation. */
export interface MaintenanceAgentRequest {
  sessionId: string
  cwd: string
  prompt: string
  title: string
  iteration: number
  signal: AbortSignal
  timeoutMs: number
  maxOutputBytes: number
}
/** Existing FunnyWork Agent, sharing native UI and model policy. */
export interface MaintenanceAgent {
  /** Confirm an available native model without invoking it. */
  ready(): Promise<void>
  /** Run one native session turn and wait for confirmed completion or cancellation. */
  run(request: MaintenanceAgentRequest): Promise<{ sessionId: string; modelUsage: ModelUsage }>
}
export interface MaintenanceRuntime {
  agent?: MaintenanceAgent
  /** Executes one argv vector and waits for child teardown.
   * @param argv Native executable and exact arguments, without shell interpretation.
   * @param cwd Isolated repository or private workspace directory.
   * @param signal Cancellation owned by the attempt or application.
   * @param timeoutMs Finite deadline for this command.
   * @param maxOutputBytes Combined stdout and stderr byte limit.
   * @param environment Original desktop Host environment, scrubbed before launching tools.
   * @returns Exit code, bounded output and optional separate protocol stdout.
   */
  run(
    argv: readonly string[],
    cwd: string,
    signal: AbortSignal,
    timeoutMs: number,
    maxOutputBytes: number,
    environment?: NodeJS.ProcessEnv,
  ): Promise<MaintenanceCommandResult>
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
function text(value: unknown, max = 10000): string {
  if (typeof value !== 'string' || value.length > max) throw new Error('Invalid maintenance text')
  return value
}
function positive(value: unknown): number {
  if (!Number.isSafeInteger(value) || Number(value) < 1) throw new Error('Invalid maintenance limit')
  return Number(value)
}
function parseSpec(value: unknown): Spec {
  if (!record(value) || !Array.isArray(value.checks) || value.checks.length < 1 || value.checks.length > 20)
    throw new Error('Invalid maintenance specification')
  const repository = text(value.repository, 200),
    baseSha = text(value.baseSha, 64),
    problemId = text(value.problemId, 64),
    branch = text(value.branch, 120)
  if (
    !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) ||
    !/^[a-f0-9]{40}$|^[a-f0-9]{64}$/.test(baseSha) ||
    !/^[A-Za-z0-9-]+$/.test(problemId) ||
    branch !== `funnywork/maintenance-${problemId}`
  )
    throw new Error('Invalid maintenance repository or revision')
  const checks = value.checks.map((check: unknown): Check => {
    if (!record(check) || !Array.isArray(check.argv) || check.argv.length < 1 || check.argv.length > 32)
      throw new Error('Invalid maintenance check')
    return {
      id: text(check.id, 64),
      argv: check.argv.map((arg: unknown) => text(arg, 1000)),
      timeoutSeconds: positive(check.timeoutSeconds),
    }
  })
  let allowedPaths: string[] | undefined
  if (value.allowedPaths !== undefined) {
    if (!Array.isArray(value.allowedPaths) || value.allowedPaths.length < 1 || value.allowedPaths.length > 20
      || !value.allowedPaths.every(path => typeof path === 'string' && /^[A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*$/.test(path) && !path.includes('..')))
      throw new Error('Invalid maintenance change scope')
    allowedPaths = value.allowedPaths
  }
  let delivery: DeliverySpec | undefined
  if (value.mode !== undefined) {
    if (value.mode !== 'VERIFY' && value.mode !== 'DELIVER') throw new Error('Unknown maintenance mode')
    const candidateSha = text(value.candidateSha, 64), jobId = text(value.jobId, 64), prUrl = text(value.prUrl, 300)
    if (!/^[a-f0-9]{40}$|^[a-f0-9]{64}$/.test(candidateSha) || !/^[A-Za-z0-9-]+$/.test(jobId)
      || !prUrl.startsWith(`https://github.com/${repository}/pull/`) || !/\/pull\/[1-9][0-9]*$/.test(prUrl)
      || !Array.isArray(value.requiredCi) || value.requiredCi.length < 1 || value.requiredCi.length > 30
      || !value.requiredCi.every((c: unknown): c is string => typeof c === 'string' && c.length > 0 && c.length <= 160)
      || !Array.isArray(value.issues) || value.issues.length > 100
      || !value.issues.every((u: unknown): u is string => typeof u === 'string' && u.startsWith(`https://github.com/${repository}/issues/`) && /\/issues\/[1-9][0-9]*$/.test(u)))
      throw new Error('Invalid pinned delivery assignment')
    delivery = { mode: value.mode, candidateSha, jobId, prUrl,
      requiredCi: value.requiredCi, issues: value.issues }
  }
  const base: SpecBase = {
    version: positive(value.version),
    problemId,
    repository,
    baseBranch: text(value.baseBranch, 120),
    baseSha,
    platform: text(value.platform, 16),
    branch,
    title: text(value.title, 160),
    requirement: text(value.requirement, 50000),
    acceptance: text(value.acceptance),
    checks,
    ...(allowedPaths === undefined ? {} : { allowedPaths }),
  }
  return delivery === undefined ? base : { ...base, ...delivery }
}
function parseJournal(value: unknown): Journal {
  if (!record(value) || !['RUNNING', 'READY', 'SENT', 'STALE'].includes(text(value.phase, 16)))
    throw new Error('Invalid maintenance journal')
  const phase = value.phase
  if (phase !== 'RUNNING' && phase !== 'READY' && phase !== 'SENT' && phase !== 'STALE')
    throw new Error('Invalid maintenance phase')
  const epoch = positive(value.epoch),
    specHash = text(value.specHash, 64)
  if (!/^[a-f0-9]{64}$/.test(specHash)) throw new Error('Invalid maintenance journal hash')
  if (value.report === undefined) {
    if (phase === 'READY') throw new Error('Missing maintenance receipt')
    return { phase, epoch, specHash, ...(typeof value.runtimeEpoch === 'string' ? { runtimeEpoch: value.runtimeEpoch } : {}) }
  }
  const report = value.report
  if (
    !record(report) ||
    report.epoch !== epoch ||
    report.specHash !== specHash ||
    !Array.isArray(report.checks) ||
    report.checks.length > 20
  )
    throw new Error('Invalid maintenance receipt')
  const outcome = report.outcome
  if (
    outcome !== 'CANDIDATE_READY' && outcome !== 'VERIFIED' && outcome !== 'DELIVERED' &&
    outcome !== 'CHECKS_FAILED' &&
    outcome !== 'NEEDS_ENVIRONMENT' &&
    outcome !== 'RUNTIME_FAILED'
  )
    throw new Error('Invalid maintenance outcome')
  const checks = report.checks.map((check: unknown) => {
    if (!record(check) || !Number.isSafeInteger(check.exitCode)) throw new Error('Invalid maintenance check receipt')
    return {
      id: text(check.id, 64),
      exitCode: Number(check.exitCode),
      output: text(check.output, 20000),
    }
  })
  let modelUsage: ModelUsage | undefined
  if (report.modelUsage !== undefined) {
    if (
      !record(report.modelUsage) ||
      !Number.isSafeInteger(report.modelUsage.calls) ||
      Number(report.modelUsage.calls) < 0 ||
      Number(report.modelUsage.calls) > 100000
    )
      throw new Error('Invalid maintenance usage')
    const count = (value: unknown): number | null => {
      if (value === null) return null
      if (!Number.isSafeInteger(value) || Number(value) < 0) throw new Error('Invalid maintenance token count')
      return Number(value)
    }
    modelUsage = {
      calls: Number(report.modelUsage.calls),
      inputTokens: count(report.modelUsage.inputTokens),
      outputTokens: count(report.modelUsage.outputTokens),
      cachedInputTokens: count(report.modelUsage.cachedInputTokens),
    }
  }
  return {
    phase,
    epoch,
    specHash,
    ...(typeof value.runtimeEpoch === 'string' ? { runtimeEpoch: value.runtimeEpoch } : {}),
    report: {
      ...(modelUsage === undefined ? {} : { modelUsage }),
      epoch,
      specHash,
      outcome,
      resultSha: report.resultSha === null ? null : text(report.resultSha, 64),
      prUrl: report.prUrl === null ? null : text(report.prUrl, 300),
      summary: text(report.summary, 20000),
      checks,
    },
  }
}
async function save(path: string, value: object): Promise<void> {
  const temporary = `${path}.${randomUUID()}.tmp`
  await writeFile(temporary, JSON.stringify(value), {
    mode: 0o600,
    flag: 'wx',
  })
  await rename(temporary, path)
}
function scrubbedEnvironment(environment: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  return Object.fromEntries(
    Object.entries(environment).filter(([key]) => !/KEY|SECRET|TOKEN|PASSWORD/i.test(key) && !/^DSH_/i.test(key)),
  )
}
/** Native process execution preserves argument boundaries and waits for the owned process group on cancellation. */
export const maintenanceRuntime: MaintenanceRuntime = {
  run(argv, cwd, signal, timeoutMs, maxOutputBytes, environment = process.env) {
    if (signal.aborted) return Promise.reject(new Error('Maintenance cancelled'))
    return new Promise((resolve, reject) => {
      const executable = argv[0]
      if (executable === undefined) { reject(new Error('Maintenance command is empty')); return }
      const child = spawn(executable, argv.slice(1), {
        cwd,
        env: scrubbedEnvironment(environment),
        shell: false,
        detached: process.platform !== 'win32',
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      const stdoutDecoder = new StringDecoder('utf8'),
        stderrDecoder = new StringDecoder('utf8')
      let stdout = ''
      let output = '',
        bytes = 0,
        failure: Error | undefined,
        stopping: Promise<void> | undefined
      const terminate = (): void => {
        if (stopping !== undefined || child.pid === undefined) return
        if (process.platform === 'win32') {
          stopping = new Promise((done) => {
            const killer = spawn('taskkill', ['/PID', String(child.pid), '/T', '/F'], {
              stdio: 'ignore',
              windowsHide: true,
            })
            killer.once('error', () => {
              child.kill()
              done()
            })
            killer.once('close', () => {
              done()
            })
          })
        } else {
          try {
            process.kill(-child.pid, 'SIGKILL')
          } catch (error) {
            if (!record(error) || error.code !== 'ESRCH') child.kill('SIGKILL')
          }
          stopping = Promise.resolve()
        }
      }
      const abort = (): void => {
        failure = new Error('Maintenance cancelled')
        terminate()
      }
      signal.addEventListener('abort', abort, { once: true })
      const timer = setTimeout(() => {
        failure = new Error('Maintenance command timed out')
        terminate()
      }, timeoutMs)
      const data = (chunk: Buffer, stream: 'stdout' | 'stderr'): void => {
        bytes += chunk.length
        if (bytes > maxOutputBytes) {
          failure = new Error('Maintenance output limit exceeded')
          terminate()
          return
        }
        const decoded = stream === 'stdout' ? stdoutDecoder.write(chunk) : stderrDecoder.write(chunk)
        output += decoded
        if (stream === 'stdout') stdout += decoded
      }
      child.stdout.on('data', (chunk: Buffer) => { data(chunk, 'stdout') })
      child.stderr.on('data', (chunk: Buffer) => { data(chunk, 'stderr') })
      child.once('exit', () => {
        terminate()
      })
      child.once('error', (error) => {
        failure = error
      })
      child.once('close', (code) => {
        terminate()
        clearTimeout(timer)
        signal.removeEventListener('abort', abort)
        void (stopping ?? Promise.resolve()).then(() => {
          if (failure !== undefined) reject(failure)
          else {
            const tail = stdoutDecoder.end()
            stdout += tail
            output += tail + stderrDecoder.end()
            resolve({ exitCode: code ?? -1, output, stdout })
          }
        })
      })
    })
  },
}

/** Runs one candidate workflow; checks provide evidence independently of the model's final message.
 * @param specValue Immutable assignment received from the authenticated backend.
 * @param epoch Current attempt epoch, included in the result receipt.
 * @param specHash Backend hash identifying the exact assignment.
 * @param config Native tools, isolated workspaces and finite execution budgets.
 * @param signal Cancellation propagated from lease loss and app teardown.
 * @param runtime Native argv executor, replaceable for keyless protocol tests.
 * @param assertLease Revalidates the active lease before each remote write.
 * @returns Repair, verification or explicitly authorized delivery evidence; only DELIVER assignments may merge and close sources.
 */
export async function executeMaintenance(
  specValue: unknown,
  epoch: number,
  specHash: string,
  config: Configuration,
  signal: AbortSignal,
  runtime: MaintenanceRuntime = maintenanceRuntime,
  assertLease: () => Promise<void> = async () => {},
): Promise<Report> {
  const spec = parseSpec(specValue),
    expectedPlatform = process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'linux'
  const report: Report & { modelUsage: ModelUsage } = {
    modelUsage: { calls: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0 },
    epoch,
    specHash,
    outcome: 'RUNTIME_FAILED',
    resultSha: null,
    prUrl: null,
    summary: '',
    checks: [],
  }
  if (spec.platform !== expectedPlatform)
    return {
      ...report,
      outcome: 'NEEDS_ENVIRONMENT',
      summary: 'Assigned platform does not match this terminal.',
    }
  const cwd = join(config.workspaceRoot, spec.jobId ?? spec.problemId)
  const run = (argv: readonly string[], timeoutMs = config.commandTimeoutMs): Promise<MaintenanceCommandResult> =>
    runtime.run(argv, cwd, signal, timeoutMs, config.maxOutputBytes)
  const checked = async (argv: readonly string[]): Promise<string> => {
    const result = await run(argv)
    if (result.exitCode !== 0) throw new Error(result.output.slice(-4000))
    return result.output.trim()
  }
  try {
    await mkdir(config.workspaceRoot, { recursive: true, mode: 0o700 })
    let exists = true
    try {
      if ((await lstat(cwd)).isSymbolicLink()) throw new Error('Workspace must not be a link')
    } catch (error) {
      if (record(error) && error.code === 'ENOENT') exists = false
      else throw error
    }
    if (!exists) {
      const cloned = await runtime.run(
        ['git', 'clone', '--', `https://github.com/${spec.repository}.git`, cwd],
        config.workspaceRoot,
        signal,
        config.commandTimeoutMs,
        config.maxOutputBytes,
      )
      if (cloned.exitCode !== 0) throw new Error(cloned.output.slice(-4000))
    }
    if ((await checked(['git', 'remote', 'get-url', 'origin'])) !== `https://github.com/${spec.repository}.git`)
      throw new Error('Workspace repository does not match assignment')
    await checked(['git', 'fetch', 'origin'])
    if (spec.mode !== undefined) {
      await checked(['git', 'fetch', 'origin', spec.candidateSha])
      await checked(['git', 'checkout', '--detach', spec.candidateSha])
      if (await checked(['git', 'rev-parse', 'HEAD']) !== spec.candidateSha || await checked(['git', 'status', '--porcelain']) !== '')
        throw new Error('Validation must start from the clean pinned candidate')
      await checked(['git', 'merge-base', '--is-ancestor', spec.baseSha, spec.candidateSha])
      report.resultSha = spec.candidateSha
      report.prUrl = spec.prUrl
      if (spec.mode === 'VERIFY') {
        const tree = await checked(['git', 'rev-parse', 'HEAD^{tree}'])
        for (const check of spec.checks) {
          const result = await run(check.argv, Math.min(config.commandTimeoutMs, check.timeoutSeconds * 1000))
          report.checks.push({ id: check.id, exitCode: result.exitCode, output: Buffer.from(result.output).subarray(-8000).toString('utf8') })
        }
        if (await checked(['git', 'rev-parse', 'HEAD']) !== spec.candidateSha || await checked(['git', 'rev-parse', 'HEAD^{tree}']) !== tree
          || await checked(['git', 'status', '--porcelain']) !== '') throw new Error('Verification checks changed the candidate')
        return { ...report, outcome: report.checks.every(c => c.exitCode === 0) ? 'VERIFIED' : 'CHECKS_FAILED',
          summary: `Original FunnyWork ${expectedPlatform} client checked pinned candidate ${spec.candidateSha}; no model was invoked.` }
      }
      const prNumber = spec.prUrl.slice(spec.prUrl.lastIndexOf('/') + 1)
      const prState = async (): Promise<Record<string, unknown>> => {
        const result: unknown = JSON.parse(await checked(['gh', 'pr', 'view', spec.prUrl, '--json', 'headRefOid,baseRefName,state,mergedAt,mergeCommit,statusCheckRollup']))
        if (!record(result) || result.headRefOid !== spec.candidateSha || result.baseRefName !== spec.baseBranch)
          throw new Error('Remote candidate or target branch changed')
        return result
      }
      let pr = await prState()
      if (pr.state !== 'MERGED') {
        if (pr.state !== 'OPEN' || !Array.isArray(pr.statusCheckRollup) || pr.statusCheckRollup.length === 0)
          throw new Error('Candidate is not open with completed CI evidence')
        const passed = new Set<string>()
        for (const c of pr.statusCheckRollup) {
          if (!record(c)) throw new Error('Invalid CI evidence')
          if (c.__typename === 'CheckRun' && c.status === 'COMPLETED' && c.conclusion === 'SUCCESS') passed.add(text(c.name, 160))
          else if (c.__typename === 'StatusContext' && c.state === 'SUCCESS') passed.add(text(c.context, 160))
          else throw new Error('CI is pending or failed; merge blocked')
        }
        if (!spec.requiredCi.every(name => passed.has(name))) throw new Error('Required CI checks missing')
        await assertLease(); signal.throwIfAborted()
        // GitHub atomically rejects a head change between inspection and the merge request.
        await checked(['gh', 'api', '--method', 'PUT', `repos/${spec.repository}/pulls/${prNumber}/merge`, '-f', `sha=${spec.candidateSha}`, '-f', 'merge_method=squash'])
        pr = await prState()
      }
      if (pr.state !== 'MERGED' || !record(pr.mergeCommit) || !/^[a-f0-9]{40}$/.test(text(pr.mergeCommit.oid, 64)))
        throw new Error('Merge not confirmed; issue closure blocked')
      const mergeSha = text(pr.mergeCommit.oid, 64)
      const issueEvidence: { issue: string; commentId: number; closed: boolean }[] = []
      for (const issue of spec.issues) {
        const number = issue.slice(issue.lastIndexOf('/') + 1), api = `repos/${spec.repository}/issues/${number}`
        const marker = `<!-- funnywork-delivery:${spec.jobId}:${number} -->`
        const pages: unknown = JSON.parse(await checked(['gh', 'api', '--paginate', '--slurp', `${api}/comments?per_page=100`]))
        if (!Array.isArray(pages) || !pages.every(Array.isArray)) throw new Error('Invalid issue comment evidence')
        let comment: unknown = pages.flat().find((c: unknown) => record(c) && typeof c.body === 'string' && c.body.includes(marker))
        if (comment === undefined) {
          await assertLease(); signal.throwIfAborted()
          comment = JSON.parse(await checked(['gh', 'api', '--method', 'POST', `${api}/comments`, '-f',
            `body=${marker}\nFunnyWork 管理端已审核并合并修复：${spec.prUrl}\n候选提交：${spec.candidateSha}\n合并提交：${mergeSha}\n已完成配置的客户端验证与必需 CI 检查。此记录表示代码已合并，不表示已发布安装包。`]))
        }
        if (!record(comment) || !Number.isSafeInteger(comment.id)) throw new Error('Comment delivery not confirmed')
        await assertLease(); signal.throwIfAborted()
        const closed: unknown = JSON.parse(await checked(['gh', 'api', '--method', 'PATCH', api, '-f', 'state=closed', '-f', 'state_reason=completed']))
        if (!record(closed) || closed.state !== 'closed') throw new Error('Issue closure not confirmed')
        issueEvidence.push({ issue, commentId: Number(comment.id), closed: true })
      }
      const proof = JSON.stringify({ candidateSha: spec.candidateSha, mergeSha, prUrl: spec.prUrl, issues: issueEvidence })
      await save(join(config.workspaceRoot, `delivery-${spec.jobId}.json`),
        { candidateSha: spec.candidateSha, mergeSha, prUrl: spec.prUrl, issues: issueEvidence })
      return { ...report, outcome: 'DELIVERED', checks: [{ id: 'delivery', exitCode: 0, output: proof }], summary: proof }
    }
    const local = await run(['git', 'show-ref', '--verify', `refs/heads/${spec.branch}`])
    if (local.exitCode === 0) await checked(['git', 'checkout', spec.branch])
    else {
      const remote = await run(['git', 'show-ref', '--verify', `refs/remotes/origin/${spec.branch}`])
      await checked(
        remote.exitCode === 0
          ? ['git', 'checkout', '-b', spec.branch, `origin/${spec.branch}`]
          : ['git', 'checkout', '-b', spec.branch, spec.baseSha],
      )
    }
    await checked(['git', 'merge-base', '--is-ancestor', spec.baseSha, 'HEAD'])
    let failures = '', checkedTree = ''
    const sessionId = `maintenance-${spec.problemId}-v${spec.version}-e${epoch}`
    for (let iteration = 0; iteration < config.maxIterations; iteration++) {
      const initialPrompt = `Work only in this isolated repository. Read AGENTS.md, README.md and applicable design rules. Use built-in FunnyWork tools only; do not launch Codex, Claude Code or another coding CLI. Implement this finite requirement: ${spec.requirement}\nAcceptance: ${spec.acceptance}\nPreserve existing work and stay on the assigned branch. Do not commit, push, create a PR, merge, deploy, or close an issue. The FunnyWork executor handles delivery. Fix only this requirement. ${failures}`
      const prompt =
        iteration === 0
          ? initialPrompt
          : `Continue this same requirement in the assigned repository and branch. Preserve scope and delivery restrictions. Fix only these independent check failures:\n${failures}`
      const logRoot = join(config.workspaceRoot, 'model-logs', spec.problemId, `${spec.version}-${epoch}`)
      await mkdir(logRoot, { recursive: true, mode: 0o700 })
      await save(join(logRoot, `request-${iteration}.json`), {
        epoch,
        specHash,
        prompt,
      })
      const priorUsage = report.modelUsage
      report.modelUsage = {
        calls: priorUsage.calls + 1,
        inputTokens: null,
        outputTokens: null,
        cachedInputTokens: null,
      }
      if (runtime.agent === undefined) throw new Error('FunnyWork native Agent adapter is unavailable')
      const model = await runtime.agent.run({ sessionId, cwd, prompt, title: spec.title, iteration, signal,
        timeoutMs: config.commandTimeoutMs, maxOutputBytes: config.maxOutputBytes })
      await save(join(logRoot, `response-${iteration}.json`), model)
      const add = (previous: number | null, value: number | null): number | null =>
        previous !== null && value !== null ? previous + value : null
      report.modelUsage = { calls: priorUsage.calls + model.modelUsage.calls,
        inputTokens: add(priorUsage.inputTokens, model.modelUsage.inputTokens),
        outputTokens: add(priorUsage.outputTokens, model.modelUsage.outputTokens),
        cachedInputTokens: add(priorUsage.cachedInputTokens, model.modelUsage.cachedInputTokens) }
      await checked(['git', 'add', '--all'])
      const allowedPaths = spec.allowedPaths
      if (allowedPaths !== undefined) {
        const changed = (await checked(['git', 'diff', '--cached', '--name-only', '-z', spec.baseSha])).split('\0').filter(Boolean)
        if (changed.some(path => !allowedPaths.some(allowed => path === allowed || path.startsWith(`${allowed}/`))))
          throw new Error('Candidate changed files outside the admitted repair scope')
      }
      checkedTree = await checked(['git', 'write-tree'])
      report.checks = []
      for (const check of spec.checks) {
        const result = await run(check.argv, Math.min(config.commandTimeoutMs, check.timeoutSeconds * 1000))
        report.checks.push({
          id: check.id,
          exitCode: result.exitCode,
          output: Buffer.from(result.output).subarray(-8000).toString('utf8'),
        })
      }
      if (report.checks.every(check => check.exitCode === 0)) break
      failures = `Previous independent checks failed:\n${report.checks
        .filter(check => check.exitCode !== 0)
        .map(check => `${check.id}: ${check.output}`)
        .join('\n')}`
    }
    if (report.checks.some(check => check.exitCode !== 0))
      return {
        ...report,
        outcome: 'CHECKS_FAILED',
        summary: 'Required checks failed; review the attached check output.',
      }
    if ((await checked(['git', 'branch', '--show-current'])) !== spec.branch)
      throw new Error('FunnyWork Agent changed the assigned branch')
    await checked(['git', 'merge-base', '--is-ancestor', spec.baseSha, 'HEAD'])
    if ((await checked(['git', 'remote', 'get-url', 'origin'])) !== `https://github.com/${spec.repository}.git`)
      throw new Error('FunnyWork Agent changed the repository remote')
    await checked(['git', 'add', '--all'])
    if ((await checked(['git', 'write-tree'])) !== checkedTree)
      throw new Error('Checks modified candidate files; a new verification is required')
    if ((await checked(['git', 'status', '--porcelain'])) !== '') {
      await checked(['git', 'add', '--all'])
      await checked(['git', 'commit', '-m', spec.title])
    }
    if (
      (await checked(['git', 'rev-parse', 'HEAD^{tree}'])) !== checkedTree ||
      (await checked(['git', 'status', '--porcelain'])) !== ''
    )
      throw new Error('Commit hooks changed the checked candidate')
    report.resultSha = await checked(['git', 'rev-parse', 'HEAD'])
    if (report.resultSha === spec.baseSha) throw new Error('No candidate change was produced')
    await assertLease()
    signal.throwIfAborted()
    await checked(['git', 'push', 'origin', `HEAD:refs/heads/${spec.branch}`])
    const existing = await checked([
      'gh',
      'pr',
      'list',
      '--repo',
      spec.repository,
      '--head',
      spec.branch,
      '--state',
      'open',
      '--json',
      'url',
    ])
    const rows: unknown = JSON.parse(existing)
    if (!Array.isArray(rows)) throw new Error('Invalid pull request lookup')
    if (rows.length > 0 && record(rows[0])) report.prUrl = text(rows[0].url, 300)
    else {
      await assertLease()
      signal.throwIfAborted()
      report.prUrl = await checked([
        'gh',
        'pr',
        'create',
        '--repo',
        spec.repository,
        '--base',
        spec.baseBranch,
        '--head',
        spec.branch,
        '--title',
        spec.title,
        '--body',
        `FunnyWork maintenance ${spec.problemId}\n\n${spec.requirement}\n\nAcceptance: ${spec.acceptance}\n\nChecks: ${report.checks.map(check => check.id).join(', ')}\nCandidate: ${report.resultSha}\nAwaiting management acceptance.`,
      ])
    }
    const remoteHead = await checked(['gh', 'pr', 'view', report.prUrl, '--json', 'headRefOid', '--jq', '.headRefOid'])
    if (remoteHead !== report.resultSha) throw new Error('PR candidate does not match verified local commit')
    return {
      ...report,
      outcome: 'CANDIDATE_READY',
      summary: `FunnyWork native session maintenance-${spec.problemId}-v${spec.version}-e${epoch} submitted the candidate with required checks; management acceptance is pending.`,
    }
  } catch (error) {
    if (error instanceof MaintenanceAgentTerminationUnconfirmed) throw error
    return {
      ...report,
      summary: signal.aborted && signal.reason instanceof Error ? signal.reason.message.slice(-4000)
        : error instanceof Error ? error.message.slice(-4000) : 'Maintenance execution failed.',
    }
  }
}

/** Discover native readiness without changing the original app's tool configuration.
 * @param home Existing account home containing execution journals.
 * @param runtime Original native Agent and local tools.
 * @returns Available executor metadata, or undefined when recovery or tools are required.
 */
export async function desktopMaintenanceReady(
  home: string, runtime: MaintenanceRuntime,
): Promise<{ executor: string; gitVersion: string } | undefined> {
  if (runtime.agent === undefined) return undefined
  if ((await readdir(home)).some(name => /^maintenance-attempt-[A-Za-z0-9-]+\.json$/.test(name))) {
    for (const name of await readdir(home)) if (/^maintenance-attempt-[A-Za-z0-9-]+\.json$/.test(name)) {
      if (parseJournal(JSON.parse(await readFile(join(home, name), 'utf8'))).phase === 'RUNNING') return undefined
    }
  }
  await runtime.agent.ready()
  const git = await runtime.run(['git', '--version'], home, new AbortController().signal, 10000, 10000)
  const gh = await runtime.run(['gh', '--version'], home, new AbortController().signal, 10000, 10000)
  if (git.exitCode !== 0 || gh.exitCode !== 0) return undefined
  return { executor: 'funnywork-native-v1', gitVersion: git.output.trim().slice(0, 120) }
}
/**
 * Execute a claimed assignment from the original desktop synchronization channel.
 * @param home Existing account home; journals and clones never enter the user's active repository.
 * @param account Original desktop account transport, without any extra node credential.
 * @param deviceId Original synchronization device identity.
 * @param attempt Claimed backend attempt identity.
 * @param payload Immutable backend assignment and exact runtime epoch.
 * @param signal Original app lifecycle cancellation.
 * @param runtime Existing native Host Agent plus independent local tools.
 */
export async function runDesktopMaintenance(home: string, account: Pick<FunnyWorkerAccount, 'syncRequest'>, deviceId: string,
  attempt: string, payload: unknown, signal: AbortSignal, runtime: MaintenanceRuntime): Promise<void> {
  if (!/^[A-Za-z0-9-]{1,64}$/.test(attempt) || !record(payload)) throw new Error('Invalid native maintenance assignment')
  const epoch = positive(payload.epoch), specHash = text(payload.specHash, 64), runtimeEpoch = text(payload.runtimeEpoch, 64)
  if (!/^[a-f0-9]{64}$/.test(specHash) || !/^[A-Za-z0-9_-]{1,64}$/.test(runtimeEpoch)) throw new Error('Invalid native maintenance identity')
  parseSpec(payload.spec)
  const path = join(home, `maintenance-attempt-${attempt}.json`)
  let journal: Journal | undefined
  try { journal = parseJournal(JSON.parse(await readFile(path, 'utf8'))) }
  catch (error) { if (!record(error) || error.code !== 'ENOENT') throw error }
  if (journal !== undefined && (journal.epoch !== epoch || journal.specHash !== specHash))
    throw new MaintenanceAgentTerminationUnconfirmed('Native maintenance journal does not match this assignment')
  if (journal?.phase === 'SENT' || journal?.phase === 'STALE') return
  if (journal?.phase === 'RUNNING') throw new MaintenanceAgentTerminationUnconfirmed('Inspect interrupted native maintenance before accepting new work')
  const route = `/devices/${encodeURIComponent(deviceId)}/maintenance/${attempt}`
  let lease: Awaited<ReturnType<typeof maintenanceLease>> | undefined
  if (journal === undefined) {
    try {
      lease = await maintenanceLease(positive(payload.leaseDurationMs),
        async () => await account.syncRequest(route + '/renew', { runtimeEpoch, epoch }), signal)
    } catch (error) {
      if (error instanceof HistorySyncHttpError && [403, 404, 409, 410].includes(error.status)) {
        await save(path, { phase: 'STALE', epoch, specHash, runtimeEpoch })
        return
      }
      throw new MaintenanceDeliveryPending('Native execution awaits lease confirmation', { cause: error })
    }
  }
  try {
    if (journal === undefined) {
      if (lease === undefined) throw new Error('Maintenance lease is unavailable')
      const confirmed = lease
      await save(path, { phase: 'RUNNING', epoch, specHash, runtimeEpoch })
      const report = await executeMaintenance(payload.spec, epoch, specHash,
        { workspaceRoot: join(home, 'maintenance-workspaces'), pollIntervalMs: 2000, commandTimeoutMs: 600000,
          maxIterations: 2, maxOutputBytes: 2000000 }, confirmed.signal, runtime, () => confirmed.confirm())
      journal = { phase: 'READY', epoch, specHash, runtimeEpoch, report }
      await save(path, journal)
    }
    if (journal.report === undefined) throw new Error('Native maintenance result missing')
    try { await account.syncRequest(route + '/result', { runtimeEpoch, report: journal.report }) }
    catch (error) { throw new MaintenanceDeliveryPending('Native result saved; delivery will retry', { cause: error }) }
    journal.phase = 'SENT'
    await save(path, journal)
  } finally { await lease?.dispose() }
}

/** Execution is complete and its durable receipt can be retried without another model call. */
export class MaintenanceDeliveryPending extends Error {}

/** Retry saved receipts without executing any task again.
 * @param home Original account directory.
 * @param account Authenticated original account transport.
 * @param deviceId Original synchronization identity.
 * @param runtimeEpoch Current Host generation; earlier receipts remain available for review.
 */
export async function flushDesktopMaintenanceReceipts(home: string, account: Pick<FunnyWorkerAccount, 'syncRequest'>,
  deviceId: string, runtimeEpoch: string): Promise<void> {
  for (const name of await readdir(home)) {
    if (!/^maintenance-attempt-[A-Za-z0-9-]+\.json$/.test(name)) continue
    const path = join(home, name)
    const journal = parseJournal(JSON.parse(await readFile(path, 'utf8')))
    if (journal.phase !== 'READY') continue
    if (journal.runtimeEpoch !== runtimeEpoch) { journal.phase = 'STALE'; await save(path, journal); continue }
    const attempt = name.slice('maintenance-attempt-'.length, -5)
    try {
      await account.syncRequest(`/devices/${encodeURIComponent(deviceId)}/maintenance/${attempt}/result`,
        { runtimeEpoch, report: journal.report })
      journal.phase = 'SENT'
    } catch (error) {
      if (!(error instanceof HistorySyncHttpError && error.status === 409)) throw error
      journal.phase = 'STALE'
    }
    await save(path, journal)
  }
}
