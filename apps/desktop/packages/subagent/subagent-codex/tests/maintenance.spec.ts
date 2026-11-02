import { PassThrough } from 'node:stream'
import { describe, expect, it } from 'vitest'
import type { SubprocessHandle, SubprocessOutcome, SubprocessSpawnSpec } from '@deepseek-ai/dsh-subprocess'
import { CodexMaintenanceController, type MaintenanceStart } from '../src/maintenance.ts'
const request: MaintenanceStart = { requestId: 'target-1', workspaceId: 'workspace-1', baselineSha: 'a'.repeat(40), runtimeEpoch: 'epoch-1', timeoutMs: 1000, maxOutputBytes: 16384 }
const address = { runId: request.requestId, runtimeEpoch: request.runtimeEpoch }
function fixture(options: { reply?: unknown; silent?: boolean; exitFailure?: boolean; overflow?: boolean } = {}) {
  const methods: string[] = []; const specs: SubprocessSpawnSpec[] = []
  const controller = new CodexMaintenanceController({ probeCwd: '/safe', disposeGraceMs: 1, resolveWorkspace: id => id === 'workspace-1' ? '/registered' : undefined,
    spawn(spec) {
      specs.push(spec)
      const stdin = new PassThrough(); const stdout = new PassThrough()
      let settle!: (value: SubprocessOutcome) => void
      const done = new Promise<SubprocessOutcome>((resolve) => { settle = resolve })
      stdin.on('data', (chunk: Buffer) => {
        for (const line of chunk.toString().trim().split('\n')) {
          const frame: unknown = JSON.parse(line)
          if (typeof frame !== 'object' || frame === null || !('method' in frame) || typeof frame.method !== 'string')
            throw new Error('Expected Codex request frame')
          methods.push(frame.method)
          if (frame.method === 'initialize' && !options.silent) queueMicrotask(() => stdout.write(JSON.stringify({ id: 'id' in frame ? frame.id : undefined, result: options.reply ?? { userAgent: 'deepseek-harness/0.153.4 (Linux; x86_64) (deepseek-harness; 0.0.1)' } }) + '\n'))
          if (options.overflow) queueMicrotask(() => stdout.write('x'.repeat(17000)))
        }
      })
      const handle: SubprocessHandle = { control: undefined, stdin, stdout, stderr: undefined, collected: {}, done,
        terminate: () => { if (!options.exitFailure) settle({ exitCode: 0, signal: null }) },
        waitForExit: async () => { if (options.exitFailure) throw new Error('SECRET teardown'); await done; return true },
      }
      return handle
    } })
  return { controller, methods, specs }
}
async function terminal(controller: CodexMaintenanceController) {
  for (let index = 0; index < 100; index++) {
    const result = controller.events({ ...address, after: 0 })
    if (result.state !== 'running') return result
    await new Promise(resolve => setTimeout(resolve, 2))
  }
  throw new Error('Run did not terminate')
}
describe('fixed Codex maintenance probes', () => {
  it('initializes without thread or model turn and confirms exit', async () => {
    const { controller, methods, specs } = fixture()
    expect(controller.start(request)).toMatchObject({ ...address, state: 'running', processStopped: false })
    const result = await terminal(controller)
    expect(result).toMatchObject({ state: 'succeeded', processStopped: true, nextSeq: 3 })
    expect(methods).toEqual(['initialize', 'initialized'])
    expect(result.events[1]).toEqual({ seq: 2, kind: 'capability', pluginVersion: '0.153.4', protocolVersion: 1 })
    expect(specs[0]).toMatchObject({ cwd: '/registered', env: {}, stdio: { stderr: { maxBytes: 0 } } })
    expect(controller.events({ ...address, after: 2 }).events).toHaveLength(1)
  })
  it('only advertises after initialize and confirmed exit', async () => {
    expect(await fixture().controller.capabilities()).toMatchObject({ available: true, processStopped: true, pluginVersion: '0.153.4' })
    expect(await fixture({ reply: { userAgent: 'codex_cli_rs/0.153.4 (linux)' } }).controller.capabilities()).toMatchObject({ available: true })
    expect(await fixture({ reply: {} }).controller.capabilities()).toMatchObject({ available: false, capabilities: [] })
    const failed = fixture({ exitFailure: true })
    expect(await failed.controller.capabilities()).toMatchObject({ available: false, processStopped: false })
    expect(() => failed.controller.start(request)).toThrow('capacity')
  })
  it('rejects invalid protocol output without exposing it', async () => {
    const { controller } = fixture({ reply: 'SECRET_TOKEN' })
    controller.start(request)
    const result = await terminal(controller)
    expect(result).toMatchObject({ state: 'failed', processStopped: true })
    expect(JSON.stringify(result)).not.toContain('SECRET')
  })
  it('cancels stalled initialization and waits for exit', async () => {
    const { controller } = fixture({ silent: true })
    controller.start(request)
    expect(await controller.cancel(address)).toMatchObject({ state: 'cancelled', processStopped: true })
  })
  it('bounds a silent protocol by the requested deadline', async () => {
    const { controller } = fixture({ silent: true })
    controller.start({ ...request, timeoutMs: 5 })
    expect(await terminal(controller)).toMatchObject({ state: 'cancelled', processStopped: true })
  })
  it('disposes an in-flight capability probe and rejects competing admission', async () => {
    const { controller } = fixture({ silent: true })
    const pending = controller.capabilities()
    expect(() => controller.start(request)).toThrow('capacity')
    await controller.dispose()
    expect(await pending).toMatchObject({ available: false })
  })
  it('keeps failed teardown unknown and blocks another admission', async () => {
    const { controller } = fixture({ exitFailure: true })
    controller.start(request)
    expect(await terminal(controller)).toMatchObject({ state: 'unknown', processStopped: false })
    expect(() => controller.start({ ...request, requestId: 'target-2' })).toThrow('capacity')
  })
  it('fences epochs and rejects mismatched retries or unsafe budgets', async () => {
    const { controller, specs } = fixture()
    controller.start(request); controller.start({ ...request })
    expect(specs).toHaveLength(1)
    expect(() => controller.start({ ...request, baselineSha: 'b'.repeat(40) })).toThrow('identity')
    expect(controller.reconcile({ ...address, runtimeEpoch: 'stale' })).toMatchObject({ state: 'unknown', processStopped: false })
    expect(await controller.cancel({ ...address, runtimeEpoch: 'stale' })).toMatchObject({ state: 'unknown' })
    expect(() => controller.start({ ...request, timeoutMs: 60001 })).toThrow('Invalid')
    expect(() => controller.events({ ...address, after: 999 })).toThrow('cursor')
    await terminal(controller)
  })
  it('rejects unregistered workspaces and bounds protocol output', async () => {
    const { controller } = fixture({ overflow: true })
    expect(() => controller.start({ ...request, workspaceId: 'missing' })).toThrow('workspace')
    controller.start(request)
    expect(await terminal(controller)).toMatchObject({ processStopped: true })
  })
})
