/** Desktop ownership spans the complete turn and waits for canceled input to settle. */

import { Context } from '@deepseek-ai/cordis'
import { agentEvents, type Agent } from '@deepseek-ai/dsh-agent'
import { mountAgentLoopTestDependencies, mountAgentLoopTestHarness } from '@deepseek-ai/dsh-agent-loop-testkit'
import { DesktopTurnLock } from '@deepseek-ai/dsh-computer-use/turn-lock'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import { SessionId } from '@deepseek-ai/dsh-session'
import { expect, it, onTestFinished, vi } from 'vitest'

async function setup() {
  const ctx = new Context()
  onTestFinished(() => ctx.fiber.dispose())
  await mountAgentLoopTestDependencies(ctx)
  const harness = await mountAgentLoopTestHarness(ctx)
  const first = await harness.create(SessionId('desktop-first'))
  const second = await harness.create(SessionId('desktop-second'))
  const close = vi.fn(async () => {})
  const lock = new DesktopTurnLock(ctx, close)
  ctx.effect(() => () => lock.dispose())
  const body = vi.fn(async () => 'done')
  ctx.tools.register({
    name: 'desktop_probe', description: 'Exercise desktop ownership.', parameters: { type: 'object' },
    output: { schema: { type: 'string' }, render: (_args, value) => {
      if (typeof value !== 'string') throw new Error('Expected string probe output')
      return [{ type: 'text', text: value }]
    } },
    execute: () => body(),
  })
  ctx.on('tools/execute', async (exec, next) => {
    if (exec.name !== 'desktop_probe') return await next()
    return await lock.run(exec, async (lease) => {
      lease.usedNativeSession = true
      const previous = exec.signal
      exec.signal = lease.signal
      try { return await next() } finally { exec.signal = previous }
    })
  })
  async function start(agent: Agent, signal = new AbortController().signal) {
    agent.session.append('turn/start', { turn: 1 })
    await agentEvents(ctx, agent).waterfall('agent/pre-step', { messages: [], turn: 1, step: 1, signal }, async () => ({ kind: 'enter', messages: [] }))
  }
  let call = 0
  const execute = (agent: Agent) => ctx.tools.execute({
    agent, name: 'desktop_probe', callId: ToolCallId(`ownership-${++call}`), arguments: {}, signal: new AbortController().signal,
  })
  return { ctx, first, second, lock, body, close, start, execute }
}

it('keeps another session out between observe and act until the durable turn ends', async () => {
  const f = await setup()
  await f.start(f.first)
  await f.start(f.second)
  expect((await f.execute(f.first)).isError).toBe(false)
  expect(f.lock.busy).toBe(true)
  const blocked = await f.execute(f.second)
  expect(blocked.isError).toBe(true)
  expect(JSON.stringify(blocked.content)).toContain('COMPUTER_BUSY')
  expect(f.body).toHaveBeenCalledTimes(1)
  expect((await f.execute(f.first)).isError).toBe(false)
  f.first.session.append('turn/end', { turn: 1, reason: { kind: 'completed' } })
  await vi.waitFor(() => { expect(f.lock.busy).toBe(false) })
  expect((await f.execute(f.second)).isError).toBe(false)
  expect(f.close).toHaveBeenCalledTimes(1)
})

it('holds canceled ownership until admitted work settles and aborts queued calls', async () => {
  const f = await setup()
  const turn = new AbortController()
  await f.start(f.first, turn.signal)
  await f.start(f.second)
  const entered = Promise.withResolvers<undefined>()
  const settled = Promise.withResolvers<string>()
  f.body.mockImplementationOnce(async () => { entered.resolve(undefined); return await settled.promise })
  const running = f.execute(f.first)
  await entered.promise
  const queued = f.execute(f.first)
  turn.abort()
  expect((await f.execute(f.second)).isError).toBe(true)
  expect(f.close).not.toHaveBeenCalled()
  settled.resolve('settled input')
  await running
  expect((await queued).isError).toBe(true)
  await vi.waitFor(() => { expect(f.lock.busy).toBe(false) })
  expect(f.body).toHaveBeenCalledTimes(1)
  expect((await f.execute(f.second)).isError).toBe(false)
})

it('releases a disposed session and refuses new work after native cleanup fails', async () => {
  const f = await setup()
  await f.start(f.first)
  await f.start(f.second)
  await f.execute(f.first)
  f.close.mockRejectedValueOnce(new Error('native connection uncertain'))
  f.ctx.emit('session/disposed', f.first.session)
  await vi.waitFor(() => { expect(f.lock.cleanupFailure).toContain('COMPUTER_CLEANUP_FAILED') })
  const result = await f.execute(f.second)
  expect(result.isError).toBe(true)
  expect(JSON.stringify(result.content)).toContain('restart the host')
})
