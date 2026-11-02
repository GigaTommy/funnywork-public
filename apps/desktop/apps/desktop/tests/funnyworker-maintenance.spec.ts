/** Keyless qualification uses native Git and subprocesses; no model or GitHub request is sent. */
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  executeMaintenance,
  runDesktopMaintenance,
  MaintenanceDeliveryPending,
  flushDesktopMaintenanceReceipts,
  maintenanceRuntime,
  type MaintenanceRuntime,
} from '../src/funnyworker-maintenance.ts'
let root: string
beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'funnywork-maintenance-'))
})
afterEach(async () => {
  vi.unstubAllEnvs()
  await rm(root, { recursive: true, force: true })
})
const signal = (): AbortSignal => new AbortController().signal
const platform = process.platform === 'darwin' ? 'macos' : process.platform === 'win32' ? 'windows' : 'linux'
async function command(argv: string[], cwd = root): Promise<string> {
  const result = await maintenanceRuntime.run(argv, cwd, signal(), 10000, 100000)
  expect(result.exitCode, result.output).toBe(0)
  return result.output.trim()
}
async function fixture(failCheck = false) {
  const source = join(root, 'source'),
    remote = join(root, 'remote.git'),
    workspaces = join(root, 'workspaces')
  await mkdir(source)
  await mkdir(workspaces)
  await command(['git', 'init', '-b', 'main'], source)
  await writeFile(join(source, 'menu.txt'), 'A long menu description\n')
  await command(['git', 'add', '.'], source)
  await command(
    ['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-m', 'Initial'],
    source,
  )
  const baseSha = await command(['git', 'rev-parse', 'HEAD'], source)
  await command(['git', 'clone', '--bare', source, remote])
  const config = {
    enabled: true,
    serverOrigin: 'http://127.0.0.1',
    workspaceRoot: workspaces,
    maxIterations: 2,
    commandTimeoutMs: 10000,
    pollIntervalMs: 1000,
    maxOutputBytes: 100000,
  }
  const spec = {
    version: 1,
    problemId: 'problem-1',
    repository: 'example/project',
    baseBranch: 'main',
    baseSha,
    platform,
    branch: 'funnywork/maintenance-problem-1',
    title: 'Fix menu',
    requirement: 'Use Tasks as the menu name',
    acceptance: 'Menu shows Tasks',
    checks: [
      {
        id: 'menu',
        argv: [
          process.execPath,
          '-e',
          failCheck
            ? "console.log('Menu rejected');process.exit(1)"
            : "if(require('node:fs').readFileSync('menu.txt','utf8')!=='Tasks\\n')process.exit(1)",
        ],
        timeoutSeconds: 5,
      },
    ],
  }
  const calls: string[][] = []
  const runtime: MaintenanceRuntime = {
    agent: { async ready() {}, async run(request) {
      calls.push(['native-agent', 'turn', ...(request.iteration > 0 ? ['resume'] : [])])
      await writeFile(join(request.cwd, 'menu.txt'), 'Tasks\n')
      return { sessionId: request.sessionId, modelUsage: { calls: 1, inputTokens: 20, outputTokens: 5, cachedInputTokens: null } }
    } },
    async run(argv, cwd, abort, timeout, outputLimit) {
      calls.push([...argv])
      if (argv[0] === 'git' && argv[1] === 'clone') {
        const result = await maintenanceRuntime.run(
          ['git', 'clone', remote, argv[argv.length - 1]!],
          cwd,
          abort,
          timeout,
          outputLimit,
        )
        await command(['git', 'config', 'user.name', 'Test'], argv[argv.length - 1])
        await command(['git', 'config', 'user.email', 'test@example.invalid'], argv[argv.length - 1])
        return result
      }
      if (argv[0] === 'git' && argv[1] === 'remote' && argv[2] === 'get-url')
        return {
          exitCode: 0,
          output: 'https://github.com/example/project.git',
        }
      if (argv[0] === 'gh') {
        if (argv[2] === 'list') return { exitCode: 0, output: '[]' }
        if (argv[2] === 'create')
          return {
            exitCode: 0,
            output: 'https://github.com/example/project/pull/42',
          }
        if (argv[2] === 'view')
          return {
            exitCode: 0,
            output: await command(['git', 'rev-parse', 'HEAD'], cwd),
          }
      }
      return maintenanceRuntime.run(argv, cwd, abort, timeout, outputLimit)
    },
  }
  return { spec, config, runtime, calls, remote }
}
test('native candidate pushes the checked commit and records a stable report', async () => {
  const f = await fixture()
  let leaseChecks = 0
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime, async () => {
    leaseChecks++
  })
  expect(report.outcome).toBe('CANDIDATE_READY')
  expect(leaseChecks).toBe(2)
  expect(await command(['git', '--git-dir', f.remote, 'rev-parse', `refs/heads/${f.spec.branch}`])).toBe(
    report.resultSha,
  )
  expect(await command(['git', '--git-dir', f.remote, 'show', `${report.resultSha}:menu.txt`])).toBe('Tasks')
  expect(
    JSON.parse(
      await readFile(join(f.config.workspaceRoot, 'model-logs', f.spec.problemId, '1-1', 'request-0.json'), 'utf8'),
    ),
  ).toHaveProperty('prompt', expect.stringContaining(f.spec.acceptance))
  expect({
    ...report,
    resultSha: '<checked-commit>',
    checks: report.checks.map(check => ({
      ...check,
      output: check.output.trim(),
    })),
  }).toMatchSnapshot()
})
test('bounded failed checks produce evidence without pushing or creating a PR', async () => {
  const f = await fixture(true)
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime)
  expect(report.outcome).toBe('CHECKS_FAILED')
  expect(f.calls.some(argv => argv.includes('resume'))).toBe(true)
  expect(report.modelUsage).toMatchObject({ calls: 2, inputTokens: 40, outputTokens: 10, cachedInputTokens: null })
  expect(f.calls.filter(argv => argv.includes('turn'))).toHaveLength(2)
  expect(f.calls.some(argv => argv[1] === 'push' || argv[0] === 'gh')).toBe(false)
  expect(
    JSON.parse(
      await readFile(join(f.config.workspaceRoot, 'model-logs', f.spec.problemId, '1-1', 'request-1.json'), 'utf8'),
    ),
  ).toHaveProperty('prompt', expect.stringContaining('Menu rejected'))
})
test('lease loss prevents remote delivery after local checks passed', async () => {
  const f = await fixture()
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime, async () => {
    throw new Error('Lease cancelled')
  })
  expect(report.outcome).toBe('RUNTIME_FAILED')
  expect(report.summary).toBe('Lease cancelled')
  expect(f.calls.some(argv => argv[1] === 'push')).toBe(false)
})
test('an unavailable native model returns a blocked report without delivery', async () => {
  const f = await fixture()
  const runtime: MaintenanceRuntime = { ...f.runtime, agent: { async ready() {}, async run() {
    throw new Error('FunnyWork native model is unavailable')
  } } }
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), runtime)
  expect(report.summary).toBe('FunnyWork native model is unavailable')
  expect(report.outcome).toBe('RUNTIME_FAILED')
  expect(f.calls.some(argv => argv[1] === 'push' || argv[0] === 'gh')).toBe(false)
})
test('rewriting native Agent history cannot deliver an unrelated candidate', async () => {
  const f = await fixture()
  const runtime: MaintenanceRuntime = { ...f.runtime, agent: { async ready() {}, async run(request) {
    const result = await f.runtime.agent!.run(request)
    await command(['git', 'checkout', '--orphan', 'unrelated'], request.cwd)
    await command(['git', 'add', '--all'], request.cwd)
    await command(['git', 'commit', '-m', 'Unrelated root'], request.cwd)
    await command(['git', 'branch', '-D', f.spec.branch], request.cwd)
    await command(['git', 'branch', '-m', f.spec.branch], request.cwd)
    return result
  } } }
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), runtime)
  expect(report.checks.every(check => check.exitCode === 0)).toBe(true)
  expect(report.outcome).toBe('RUNTIME_FAILED')
  expect(f.calls.some(argv => argv[1] === 'push' || argv[0] === 'gh')).toBe(false)
})
test('platform mismatch starts no repository or model process', async () => {
  const f = await fixture()
  const report = await executeMaintenance(
    { ...f.spec, platform: platform === 'windows' ? 'macos' : 'windows' },
    1,
    'a'.repeat(64),
    f.config,
    signal(),
    f.runtime,
  )
  expect(report.outcome).toBe('NEEDS_ENVIRONMENT')
  expect(f.calls).toEqual([])
})
test('native cancellation waits until the owned process stops', async () => {
  const marker = join(root, 'pid'),
    abort = new AbortController()
  const pending = maintenanceRuntime.run(
    [
      process.execPath,
      '-e',
      `require('node:fs').writeFileSync(${JSON.stringify(marker)},String(process.pid));setInterval(()=>{},1000)`,
    ],
    root,
    abort.signal,
    10000,
    10000,
  )
  let pid = 0
  for (let tries = 0; tries < 100 && pid === 0; tries++) {
    try {
      pid = Number(await readFile(marker, 'utf8'))
    } catch (error) {
      if (!(error instanceof Error) || !('code' in error) || error.code !== 'ENOENT') throw error
    }
    if (pid === 0) await new Promise(resolve => setTimeout(resolve, 10))
  }
  expect(pid).toBeGreaterThan(0)
  abort.abort()
  await expect(pending).rejects.toThrow('cancelled')
  expect(() => process.kill(pid, 0)).toThrow()
})
test('native commands enforce time and output budgets', async () => {
  await expect(
    maintenanceRuntime.run([process.execPath, '-e', 'setInterval(()=>{},1000)'], root, signal(), 30, 10000),
  ).rejects.toThrow('timed out')
  await expect(
    maintenanceRuntime.run([process.execPath, '-e', "console.log('x'.repeat(10000))"], root, signal(), 10000, 100),
  ).rejects.toThrow('output limit')
})
test('checks that rewrite the checked files cannot deliver a candidate', async () => {
  const f = await fixture()
  f.spec.checks[0]!.argv = [process.execPath, '-e', "require('node:fs').writeFileSync('menu.txt','Unverified text')"]
  const report = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime)
  expect(report.outcome).toBe('RUNTIME_FAILED')
  expect(report.summary).toContain('Checks modified')
  expect(f.calls.some(argv => argv[1] === 'push')).toBe(false)
})

test('native commands do not inherit harness state or credential-shaped environment names', async () => {
  const names = ['MAINTENANCE_TEST_SECRET', 'DSH_MAINTENANCE_TEST_STATE']
  for (const name of names) vi.stubEnv(name, 'fixture-private-value')
  const result = await command([
    process.execPath,
    '-e',
    'console.log(JSON.stringify({secret:process.env.MAINTENANCE_TEST_SECRET===undefined,harness:process.env.DSH_MAINTENANCE_TEST_STATE===undefined}))',
  ])
  expect(JSON.parse(result)).toEqual({ secret: true, harness: true })
})

test('retries a saved native receipt without running another Agent or tool', async () => {
  const attempt = 'native-attempt', epoch = 'native-runtime', specHash = 'a'.repeat(64)
  const path = join(root, `maintenance-attempt-${attempt}.json`)
  const report = { epoch: 1, specHash, outcome: 'RUNTIME_FAILED', resultSha: null, prUrl: null, summary: 'Saved', checks: [] }
  await writeFile(path, JSON.stringify({ phase: 'READY', epoch: 1, specHash, runtimeEpoch: epoch, report }))
  let calls = 0
  const account = { syncRequest: async (_path: string, _body?: object): Promise<unknown> => {
    calls++
    if (calls === 1) throw new Error('Offline')
    return {}
  } }
  await expect(flushDesktopMaintenanceReceipts(root, account, 'original-device', epoch)).rejects.toThrow('Offline')
  expect(JSON.parse(await readFile(path, 'utf8'))).toMatchObject({ phase: 'READY' })
  await flushDesktopMaintenanceReceipts(root, account, 'original-device', epoch)
  await flushDesktopMaintenanceReceipts(root, account, 'original-device', epoch)
  expect(calls).toBe(2)
  expect(JSON.parse(await readFile(path, 'utf8'))).toMatchObject({ phase: 'SENT' })
})

test('retains an earlier runtime receipt for review without submitting it as current work', async () => {
  const path = join(root, 'maintenance-attempt-native-old.json'), specHash = 'b'.repeat(64)
  await writeFile(path, JSON.stringify({ phase: 'READY', epoch: 1, specHash, runtimeEpoch: 'old-runtime',
    report: { epoch: 1, specHash, outcome: 'RUNTIME_FAILED', resultSha: null, prUrl: null, summary: 'Saved', checks: [] } }))
  await flushDesktopMaintenanceReceipts(root, { syncRequest: async () => { throw new Error('Must not send') } }, 'device', 'current-runtime')
  expect(JSON.parse(await readFile(path, 'utf8'))).toMatchObject({ phase: 'STALE', report: { summary: 'Saved' } })
})

test('uses the original Host tool environment and removes credentials before launch', async () => {
  const result = await maintenanceRuntime.run([process.execPath, '-e',
    'process.stdout.write(JSON.stringify({path:process.env.PATH,secret:process.env.TEST_TOKEN,home:process.env.DSH_HOME}))'],
  root, signal(), 10000, 10000, { PATH: '/original/client/tools', TEST_TOKEN: 'private', DSH_HOME: '/private/account' })
  expect(result.exitCode).toBe(0)
  expect(JSON.parse(result.output)).toEqual({ path: '/original/client/tools' })
})

test('an admitted path scope prevents delivery of unrelated changed files', async () => {
  const f = await fixture()
  const report = await executeMaintenance({ ...f.spec, allowedPaths: ['scripts'] }, 1, 'a'.repeat(64), f.config, signal(), f.runtime)
  expect(report.outcome).toBe('RUNTIME_FAILED')
  expect(f.calls.some(call => call[0] === 'gh' || (call[0] === 'git' && call[1] === 'push'))).toBe(false)
})

test('verification uses the pinned clean commit without invoking the model or pushing', async () => {
  const f = await fixture(), repair = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime)
  f.calls.length = 0
  const report = await executeMaintenance({ ...f.spec, jobId:'verification-1', mode:'VERIFY', candidateSha:repair.resultSha,
    prUrl:repair.prUrl, requiredCi:['ci'], issues:[] }, 1, 'b'.repeat(64), f.config, signal(), f.runtime)
  expect(report.outcome).toBe('VERIFIED'); expect(report.resultSha).toBe(repair.resultSha)
  expect(f.calls.some(c => c[0] === 'native-agent' || c[1] === 'push' || c[0] === 'gh')).toBe(false)
  expect(report.modelUsage?.calls).toBe(0)
  expect(report.summary).toContain(repair.resultSha!)
  await expect(JSON.stringify({ ...report, resultSha: '<pinned-candidate>',
    summary: report.summary.replace(repair.resultSha!, '<pinned-candidate>') }, null, 2) + '\n')
    .toMatchFileSnapshot(`./expected/maintenance/verification-${platform}.json`)
})
test('authorized delivery checks CI and uses atomic head matching before commenting and closing', async () => {
  const f = await fixture(), repair = await executeMaintenance(f.spec, 1, 'a'.repeat(64), f.config, signal(), f.runtime)
  let merged = false, comments = 0, closes = 0, ci = 'SUCCESS', remoteSha = repair.resultSha
  const runtime: MaintenanceRuntime = { ...f.runtime, async run(argv,cwd,abort,timeout,limit) {
    if(argv[0] !== 'gh') return f.runtime.run(argv,cwd,abort,timeout,limit)
    if(argv[1] === 'pr') return { exitCode:0,output:JSON.stringify({ headRefOid:remoteSha,baseRefName:'main',state:merged?'MERGED':'OPEN',mergeCommit:merged?{ oid:'c'.repeat(40) }:null,statusCheckRollup:[{ __typename:'CheckRun',name:'ci',status:'COMPLETED',conclusion:ci }] }) }
    if(argv.includes('PUT')) {expect(argv).toContain(`sha=${repair.resultSha}`);merged=true;return { exitCode:0,output:'{"merged":true}' }}
    if(argv.includes('--paginate')) return { exitCode:0,output:'[[]]' }
    if(argv.includes('POST')) {comments++;return { exitCode:0,output:'{"id":71}' }}
    if(argv.includes('PATCH')) {closes++;return { exitCode:0,output:'{"state":"closed"}' }}
    throw new Error('Unexpected remote operation')
  } }
  const spec = { ...f.spec,mode:'DELIVER',jobId:'delivery-1',candidateSha:repair.resultSha,prUrl:repair.prUrl,requiredCi:['ci'],issues:['https://github.com/example/project/issues/8'],checks:[{ id:'delivery',argv:['git','diff','--check'],timeoutSeconds:30 }] }
  ci='FAILURE';expect((await executeMaintenance(spec,1,'b'.repeat(64),f.config,signal(),runtime)).outcome).toBe('RUNTIME_FAILED');expect(merged).toBe(false)
  ci='SUCCESS';remoteSha='d'.repeat(40);expect((await executeMaintenance(spec,1,'b'.repeat(64),f.config,signal(),runtime)).outcome).toBe('RUNTIME_FAILED');expect(merged).toBe(false)
  remoteSha=repair.resultSha; const report=await executeMaintenance(spec,1,'b'.repeat(64),f.config,signal(),runtime)
  expect(report.outcome).toBe('DELIVERED');expect(comments).toBe(1);expect(closes).toBe(1)
  expect(JSON.parse(report.summary)).toMatchObject({ candidateSha:repair.resultSha,mergeSha:'c'.repeat(40),issues:[{ commentId:71,closed:true }] })
})

// The wire entry must settle invalid or offline assignments before touching the workspace.
test('expired initial assignment is quarantined without starting native tools', async () => {
  const f = await fixture()
  f.calls.length = 0
  const { HistorySyncHttpError } = await import('../src/funnyworker-sync-error.ts')
  const account = { async syncRequest() { throw new HistorySyncHttpError(409) } }
  const frame = { epoch: 1, specHash: 'a'.repeat(64), runtimeEpoch: 'runtime-1', leaseDurationMs: 9000, spec: f.spec }
  await runDesktopMaintenance(root, account, 'device-1', 'attempt-1', frame, signal(), f.runtime)
  expect(f.calls).toEqual([])
  expect(JSON.parse(await readFile(join(root, 'maintenance-attempt-attempt-1.json'), 'utf8'))).toMatchObject({ phase: 'STALE' })
  await runDesktopMaintenance(root, account, 'device-1', 'attempt-1', frame, signal(), f.runtime)
  expect(f.calls).toEqual([])
})
test('offline initial confirmation leaves no running journal and can retry the same assignment', async () => {
  const f = await fixture()
  f.calls.length = 0
  const frame = { epoch: 1, specHash: 'a'.repeat(64), runtimeEpoch: 'runtime-1', leaseDurationMs: 90000, spec: f.spec }
  await expect(runDesktopMaintenance(root, { async syncRequest() { throw new TypeError('offline') } },
    'device-1', 'attempt-1', frame, signal(), f.runtime)).rejects.toBeInstanceOf(MaintenanceDeliveryPending)
  expect(f.calls).toEqual([])
  await expect(readFile(join(root, 'maintenance-attempt-attempt-1.json'))).rejects.toMatchObject({ code: 'ENOENT' })
  const calls: string[] = []
  const account = { async syncRequest(path: string) { calls.push(path); return { expiresAt: Date.now() + 90000 } } }
  await runDesktopMaintenance(root, account, 'device-1', 'attempt-1', frame, signal(), f.runtime)
  expect(JSON.parse(await readFile(join(root, 'maintenance-attempt-attempt-1.json'), 'utf8'))).toMatchObject({ phase: 'SENT' })
  const count = calls.length
  await runDesktopMaintenance(root, account, 'device-1', 'attempt-1', frame, signal(), f.runtime)
  expect(calls).toHaveLength(count)
  expect(f.calls.filter(c => c[0] === 'native-agent')).toHaveLength(1)
})
