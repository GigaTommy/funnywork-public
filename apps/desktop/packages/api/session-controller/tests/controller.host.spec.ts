import { Context } from '@deepseek-ai/cordis'
import AgentRegistry from '@deepseek-ai/dsh-agent'
import type { Agent } from '@deepseek-ai/dsh-agent'
import { createUserMessage, LlmAttemptId } from '@deepseek-ai/dsh-llm'
import SessionStore, { SESSION_FORMAT_VERSION, SessionId, SessionLogOffset } from '@deepseek-ai/dsh-session'
import type { SessionEvent, SessionHeader } from '@deepseek-ai/dsh-session'
import { RemoteError } from '@deepseek-ai/dsh-typert-protocol'
import { describe, expect, it, vi } from 'vitest'
import SessionController from '../src/index.ts'
import type { ApiSessionAgentController } from '../src/agent.ts'
import { createSessionTestController, testSessionPersistence } from './test-remote.ts'

function emptyInbox(): Agent['inbox'] {
  const unsupported = (): never => { throw new Error('This fixture has no pending input to mutate') }
  return { nextTurn: [], nextStep: [], clear: unsupported, append: unsupported, prepend: unsupported,
    replace: unsupported, remove: unsupported, splice: unsupported }
}

const defaults = {
  defaultModelSelection: () => ({ provider: 'fixture', model: 'fixture-model' }),
  cwd: '/tmp',
}

describe('SessionController facade', () => {
  it('exposes an active reply preview through replication and removes it at settlement', async () => {
    const ctx = new Context()
    await ctx.plugin(SessionStore)
    await ctx.plugin(AgentRegistry)
    try {
      const controller = createSessionTestController(ctx, defaults)
      const session = ctx.sessions.create(SessionId('mobile-live'), { meta: { cwd: '/workspace' } })
      session.append('turn/start', { turn: 1 })
      const agent = { id: session.id, session, status: 'running', ctx, inbox: emptyInbox() } as Agent
      await ctx.agents.register(agent)
      const attemptId = LlmAttemptId('mobile-active')
      ctx.emit('agent/assistant-stream', { agent, frame: { type: 'start', attemptId, revision: 1, turn: 1, step: 1 } })
      ctx.emit('agent/assistant-stream', { agent, frame: { type: 'chunk', attemptId, revision: 2, index: 0, time: 1,
        chunk: { type: 'text-delta', index: 0, text: 'Visible reply' } } })
      ctx.emit('llm/admission-updated', { sessionId: session.id, waiting: 2 })
      expect(controller.replicaState()).toMatchObject({ 'mobile-live': { running: true, admissionWaiting: 2,
        liveAssistant: { attemptId: 'mobile-active', startedAfterSeq: 0, turnStart: 0, text: 'Visible reply', truncated: false } } })
      ctx.emit('agent/assistant-stream', { agent, frame: { type: 'end', attemptId, revision: 3, index: 1,
        outcome: { kind: 'abandoned' } } })
      ctx.emit('llm/admission-updated', { sessionId: session.id, waiting: 0 })
      expect(controller.replicaState()).toMatchObject({ 'mobile-live': { admissionWaiting: 0 } })
      expect(JSON.stringify(controller.replicaState())).not.toContain('liveAssistant')
    } finally { await ctx.fiber.dispose() }
  })
  it('aggregates nested runtime children without including an independent fork', async () => {
    const ctx = new Context()
    await ctx.plugin(SessionStore); await ctx.plugin(AgentRegistry)
    try {
      const controller = createSessionTestController(ctx, defaults)
      const make = (id: string, parent?: Agent): Agent => {
        const session = ctx.sessions.create(SessionId(id), { meta: { cwd: '/workspace',
          ...(parent === undefined ? {} : { parentSession: parent.id }),
        } })
        session.append('turn/start', { turn: 1 })
        return { id: session.id, session, status: 'running', ctx, inbox: emptyInbox() } as Agent
      }
      const root = make('queue-root'), child = make('queue-child'), grandchild = make('queue-grandchild'), fork = make('queue-fork', root)
      const detach = [ctx.agents.enter(root, undefined), ctx.agents.enter(child, root),
        ctx.agents.enter(grandchild, child), ctx.agents.enter(fork, undefined)]
      try {
        ctx.emit('llm/admission-updated', { sessionId: child.id, waiting: 1 })
        ctx.emit('llm/admission-updated', { sessionId: grandchild.id, waiting: 2 })
        ctx.emit('llm/admission-updated', { sessionId: fork.id, waiting: 4 })
        expect(controller.replicaState()).toMatchObject({ 'queue-root': { admissionWaiting: 3, controllable: true },
          'queue-child': { admissionWaiting: 3, controllable: false }, 'queue-fork': { admissionWaiting: 4, controllable: true } })
        ctx.emit('llm/admission-updated', { sessionId: grandchild.id, waiting: 0 })
        expect(controller.replicaState()).toMatchObject({ 'queue-root': { admissionWaiting: 1 } })
        detach[1]!()
        expect(controller.replicaState()).toMatchObject({ 'queue-root': { admissionWaiting: 0 } })
      } finally { for (const stop of detach.reverse()) stop() }
    } finally { await ctx.fiber.dispose() }
  })

  it('does not require the Tools service', () => {
    expect(SessionController.inject).not.toContain('tools')
  })

  it('owns Host service methods and publishes Agent lifecycle projections', async () => {
    const ctx = new Context()
    await ctx.plugin(SessionStore)
    await ctx.plugin(AgentRegistry)
    const sessionId = SessionId('controller-session')
    const header: SessionHeader = {
      version: SESSION_FORMAT_VERSION,
      id: sessionId,
      createdAt: 1,
      cwd: '/workspace',
      isSeeded: false,
    }
    const events: SessionEvent[] = []
    const inspect = vi.fn(() => Promise.resolve({
      meta: header,
      inheritedEventCount: SessionLogOffset(0),
      events,
    }))
    ctx.provide('sessionPersistence', testSessionPersistence(ctx, {
      list: () => Promise.resolve([header]),
      inspect,
    }) as never)
    let uploadResolver: ((sessionId: SessionId) => Promise<Agent>) | undefined
    ctx.provide('fileUploads', {
      registerAgentResolver: (resolve: (sessionId: SessionId) => Promise<Agent>) => {
        uploadResolver = resolve
        return () => {}
      },
      resolve: () => undefined,
      bindPrompt: () => ({ commit: () => {}, [Symbol.dispose]: () => {} }),
      retirePrompt: () => {},
    } as never)
    const controller = createSessionTestController(ctx, defaults)
    const status = vi.fn()
    const failure = vi.fn()
    const activity = vi.fn()
    ctx.on('api-session/status', status)
    ctx.on('api-session/error', failure)
    ctx.on('api-session/activity', activity)

    await expect(controller.inspect(sessionId)).resolves.toEqual({
      meta: header,
      inheritedEventCount: SessionLogOffset(0),
      events,
    })
    expect(inspect).toHaveBeenCalledOnce()

    const session = ctx.sessions.create(sessionId, { meta: header })
    const agent = {
      id: sessionId,
      session,
      status: 'idle',
      ctx,
    } as Agent
    await ctx.agents.register(agent)
    const resolveUploadAgent = (id: SessionId): Promise<Agent> => {
      if (uploadResolver === undefined) throw new Error('file upload resolver was not registered')
      return uploadResolver(id)
    }
    await expect(resolveUploadAgent(sessionId)).resolves.toBe(agent)
    const activationError = new RemoteError('session/not-found', 'missing upload session', { sessionId })
    vi.spyOn(
      (controller as unknown as { agents: ApiSessionAgentController }).agents,
      'resolveAgent',
    ).mockResolvedValueOnce({ error: activationError })
    await expect(resolveUploadAgent(sessionId)).rejects.toBe(activationError)
    const consumeSelection = vi.spyOn(
      (controller as unknown as { agents: ApiSessionAgentController }).agents,
      'consumeSelection',
    )

    await expect(controller.resolveAgent(sessionId)).resolves.toEqual({ agent })
    await expect(controller.inspect(sessionId)).resolves.toEqual({
      meta: header,
      inheritedEventCount: SessionLogOffset(0),
      events,
    })
    expect(inspect).toHaveBeenCalledOnce()
    ctx.emit('agent/status', { agent, status: 'running' })
    ctx.emit('agent/error', { agent, turn: 1, step: 0, error: new Error('fixture failure') })
    session.append('user/message', createUserMessage({
      content: [{ type: 'text', text: 'hello' }],
      source: { kind: 'user' },
    }), { surfaceOp: 'append' })
    session.append('user/message', createUserMessage({
      content: [{ type: 'text', text: 'browser prompt' }],
      source: { kind: 'user', rpcId: 'controller-rpc' as never },
    }), { surfaceOp: 'append' })
    expect(status).toHaveBeenCalledWith(sessionId, true)
    expect(failure).toHaveBeenCalledWith(sessionId, expect.stringContaining('fixture failure'))
    expect(activity).toHaveBeenCalledWith(sessionId, expect.any(Number))
    session.append('request/header', {
      header: { config: { provider: 'fixture', model: 'fixture-model' } },
      reason: 'initial',
    })
    expect(consumeSelection).toHaveBeenCalledWith(
      agent, 'fixture', 'fixture-model', undefined,
    )
    const unowned = ctx.sessions.create(SessionId('controller-unowned'), {
      meta: { cwd: '/workspace' },
    })
    unowned.append('request/header', {
      header: { config: { provider: 'fixture', model: 'other-model' } },
      reason: 'initial',
    })
    expect(consumeSelection).toHaveBeenCalledTimes(1)

    const abort = new AbortController()
    const iterator = controller.follow({
      address: { kind: 'session', sessionId },
    }, abort.signal)[Symbol.asyncIterator]()
    await expect(iterator.next()).resolves.toMatchObject({
      done: false,
      value: { type: 'snapshot', cursor: 2 },
    })
    abort.abort()
    await expect(iterator.next()).resolves.toEqual({ done: true, value: undefined })
  })

  it.each(['success', 'domain-error', 'throw'] as const)(
    'promotes a prepared follow observation in the background: %s',
    async (outcome) => {
      const ctx = new Context()
      await ctx.plugin(SessionStore)
      await ctx.plugin(AgentRegistry)
      const sessionId = SessionId(`background-${outcome}`)
      const header: SessionHeader = {
        version: SESSION_FORMAT_VERSION, id: sessionId, createdAt: 1, cwd: '/workspace', isSeeded: false,
      }
      ctx.provide('sessionPersistence', testSessionPersistence(ctx, {
        list: () => Promise.resolve([header]),
        inspect: () => Promise.resolve({
          meta: header,
          inheritedEventCount: SessionLogOffset(0),
          events: [],
        }),
      }) as never)
      const controller = createSessionTestController(ctx, defaults)
      const agents = (controller as unknown as { agents: ApiSessionAgentController }).agents
      const apiError = vi.fn()
      ctx.on('api-session/error', apiError)
      const logError = vi.spyOn(ctx.logger, 'error').mockImplementation(() => {})
      const live = { id: sessionId, session: { id: sessionId }, ctx, status: 'idle' } as unknown as Agent
      const resolve = vi.spyOn(agents, 'resolveObservedAgent')
      if (outcome === 'success') resolve.mockResolvedValue({ agent: live })
      else if (outcome === 'domain-error') {
        resolve.mockResolvedValue({
          error: new RemoteError('gateway/internal', 'activation unavailable', {}),
        })
      } else {
        resolve.mockRejectedValue(new Error('activation crashed'))
      }
      const abort = new AbortController()
      const iterator = controller.follow({
        address: { kind: 'session', sessionId },
      }, abort.signal)[Symbol.asyncIterator]()

      await expect(iterator.next()).resolves.toMatchObject({ value: { type: 'snapshot' } })
      const waiting = iterator.next()
      await vi.waitFor(() => { expect(resolve).toHaveBeenCalledOnce() })
      if (outcome === 'domain-error') {
        await vi.waitFor(() => {
          expect(apiError).toHaveBeenCalledWith(sessionId, 'activation unavailable')
        })
      } else if (outcome === 'throw') {
        await vi.waitFor(() => {
          expect(logError).toHaveBeenCalledWith(expect.stringContaining('activation crashed'))
        })
      } else {
        expect(apiError).not.toHaveBeenCalled()
      }
      abort.abort()
      await expect(waiting).resolves.toMatchObject({ done: true })
      await ctx.fiber.dispose()
    },
  )

  it('waits for an admitted background promotion during teardown', async () => {
    const ctx = new Context()
    await ctx.plugin(SessionStore)
    await ctx.plugin(AgentRegistry)
    const sessionId = SessionId('background-disposal')
    const header: SessionHeader = {
      version: SESSION_FORMAT_VERSION, id: sessionId, createdAt: 1, cwd: '/workspace', isSeeded: false,
    }
    ctx.provide('sessionPersistence', testSessionPersistence(ctx, {
      list: () => Promise.resolve([header]),
      inspect: () => Promise.resolve({
        meta: header,
        inheritedEventCount: SessionLogOffset(0),
        events: [],
      }),
    }) as never)
    const controller = createSessionTestController(ctx, defaults)
    const agents = (controller as unknown as { agents: ApiSessionAgentController }).agents
    const started = Promise.withResolvers<undefined>()
    const release = Promise.withResolvers<undefined>()
    vi.spyOn(agents, 'resolveObservedAgent').mockImplementation(async () => {
      started.resolve(undefined)
      await release.promise
      return {
        agent: { id: sessionId, session: { id: sessionId }, ctx, status: 'idle' } as unknown as Agent,
      }
    })
    const iterator = controller.follow({
      address: { kind: 'session', sessionId },
    }, new AbortController().signal)[Symbol.asyncIterator]()

    await expect(iterator.next()).resolves.toMatchObject({ value: { type: 'snapshot' } })
    const waiting = iterator.next()
    await started.promise
    let disposed = false
    const disposal = ctx.fiber.dispose().then(() => { disposed = true })
    await Promise.resolve()
    expect(disposed).toBe(false)

    release.resolve(undefined)
    await disposal
    await expect(waiting).resolves.toMatchObject({ done: true })
  })
})
