/** Independent Host-process fixture: uses real Agent events and the production desktop turn lock. */
import { createInterface } from 'node:readline'
import { setImmediate } from 'node:timers/promises'
import { Context } from '@deepseek-ai/cordis'
import { agentEvents } from '@deepseek-ai/dsh-agent'
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '@deepseek-ai/dsh-agent-loop-testkit'
import { DesktopTurnLock } from '@deepseek-ai/dsh-computer-use/turn-lock'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import { SessionId } from '@deepseek-ai/dsh-session'

const path = process.argv[2]
if (path === undefined) throw new Error('Expected absolute desktop lease path')
const cleanup = Promise.withResolvers<undefined>()
const gated = process.argv[3] === 'gated'
const ctx = new Context()
await mountAgentLoopTestDependencies(ctx)
const harness = await mountAgentLoopTestHarness(ctx)
const agent = await harness.create(SessionId(`process-${process.pid}`))
const turn = new AbortController()
const report = (event: string, extra: object = {}) => { process.stdout.write(`${JSON.stringify({ event, pid: process.pid, ...extra })}\n`) }
const lock = new DesktopTurnLock(ctx, async () => {
  report('cleanup-started')
  if (gated) await cleanup.promise
  if (process.argv[3] === 'failing') throw new Error('Native cleanup acknowledgement missing')
}, { path, waitMs: 100 })
ctx.effect(() => () => lock.dispose())
ctx.tools.register({
  name: 'observe_desktop', description: 'Task-owned process lease fixture; no desktop input is dispatched.',
  parameters: { type: 'object' }, output: { schema: { type: 'string' }, render: () => [] },
  execute: (_args, execution) => lock.run(execution, async (lease) => { lease.usedNativeSession = true; return 'observed' }),
})
agent.session.append('turn/start', { turn: 1 })
await agentEvents(ctx, agent).waterfall('agent/pre-step', { messages: [], turn: 1, step: 1, signal: turn.signal }, async () => ({ kind: 'enter', messages: [] }))
let calls = 0
const input = createInterface({ input: process.stdin })
input.on('line', (line) => {
  void (async () => {
    if (line === 'observe') {
      const result = await ctx.tools.execute({ agent, name: 'observe_desktop', arguments: {}, callId: ToolCallId(`process-call-${++calls}`), signal: turn.signal })
      report(result.isError ? 'blocked' : 'observed', { content: result.content })
    } else if (line === 'end' || line === 'cancel') {
      if (line === 'cancel') turn.abort()
      else agent.session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })
      while (lock.busy) await setImmediate()
      report('released')
    } else if (line === 'cleanup') cleanup.resolve(undefined)
  })().catch((error: unknown) => { report('failure', { error: error instanceof Error ? error.message : String(error) }); process.exitCode = 1 })
})
report('ready')
