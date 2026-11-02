import { describe, expect, it } from 'vitest'
import { LlmAttemptId } from '@deepseek-ai/dsh-llm'
import { SessionSeq } from '@deepseek-ai/dsh-session'
import { SessionAssistantStreamAccumulator } from '../src/assistant-stream.ts'

describe('SessionAssistantStreamAccumulator', () => {
  it('replicates only visible active text and clears it on settlement or missing frames', () => {
    const accumulator = new SessionAssistantStreamAccumulator()
    const attemptId = LlmAttemptId('mobile-preview')
    accumulator.accept({ type: 'start', attemptId, revision: 1, turn: 1, step: 1 }, SessionSeq(4))
    accumulator.accept({ type: 'chunk', attemptId, revision: 2, index: 0, time: 1,
      chunk: { type: 'reasoning-delta', index: 0, text: 'private reasoning' } }, SessionSeq(4))
    accumulator.accept({ type: 'chunk', attemptId, revision: 3, index: 1, time: 2,
      chunk: { type: 'text-delta', index: 1, text: 'Hello ' } }, SessionSeq(4))
    accumulator.accept({ type: 'chunk', attemptId, revision: 4, index: 2, time: 3,
      chunk: { type: 'text-delta', index: 1, text: 'world' } }, SessionSeq(4))
    expect(accumulator.preview()).toEqual({ attemptId: 'mobile-preview', startedAfterSeq: 4, text: 'Hello world', truncated: false })
    accumulator.accept({ type: 'end', attemptId, revision: 5, index: 3,
      outcome: { kind: 'abandoned' } }, SessionSeq(4))
    expect(accumulator.preview()).toBeUndefined()
    accumulator.accept({ type: 'start', attemptId, revision: 6, turn: 2, step: 1 }, SessionSeq(8))
    accumulator.accept({ type: 'chunk', attemptId, revision: 8, index: 0, time: 4,
      chunk: { type: 'text-delta', index: 0, text: 'gap' } }, SessionSeq(8))
    expect(accumulator.preview()).toBeUndefined()
  })

  it('bounds replicated text without splitting a surrogate pair', () => {
    const accumulator = new SessionAssistantStreamAccumulator()
    const attemptId = LlmAttemptId('large')
    accumulator.accept({ type: 'start', attemptId, revision: 1, turn: 1, step: 1 }, -1)
    accumulator.accept({ type: 'chunk', attemptId, revision: 2, index: 0, time: 1,
      chunk: { type: 'text-delta', index: 0, text: 'x'.repeat(15_999) + '😀end' } }, -1)
    expect(accumulator.preview()?.text).toBe('x'.repeat(15_999))
    expect(accumulator.preview()?.truncated).toBe(true)
  })
  it('replaces stale lifecycles, rejects frame gaps, and caches each baseline', () => {
    const accumulator = new SessionAssistantStreamAccumulator()
    const empty = accumulator.snapshot()
    expect(accumulator.snapshot()).toBe(empty)

    accumulator.accept({
      type: 'start', attemptId: LlmAttemptId('stale'), revision: 2, turn: 1, step: 1,
    }, -1)
    expect(accumulator.snapshot()).toEqual({ revision: 2 })

    accumulator.accept({
      type: 'start', attemptId: LlmAttemptId('current'), revision: 1, turn: 2, step: 3,
    }, SessionSeq(5))
    expect(accumulator.snapshot()).toMatchObject({
      revision: 1,
      activeAttempt: {
        attemptId: 'current', startedAfterSeq: 5, turn: 2, step: 3, nextIndex: 0, stream: [],
      },
    })

    accumulator.accept({
      type: 'chunk', attemptId: LlmAttemptId('other'), revision: 2, index: 0,
      time: 4, chunk: { type: 'text-delta', index: 0, text: 'lost' },
    }, SessionSeq(5))
    expect(accumulator.snapshot()).toEqual({ revision: 2 })

    accumulator.accept({
      type: 'start', attemptId: LlmAttemptId('settled'), revision: 3, turn: 2, step: 4,
    }, SessionSeq(8))
    accumulator.accept({
      type: 'chunk', attemptId: LlmAttemptId('settled'), revision: 4, index: 0,
      time: 5, chunk: { type: 'text-delta', index: 0, text: 'ok' },
    }, SessionSeq(8))
    const active = accumulator.snapshot()
    expect(active).toMatchObject({
      revision: 4,
      activeAttempt: {
        attemptId: 'settled', startedAfterSeq: 8, nextIndex: 1,
        stream: [{ type: 'text-chunks', time0: 5, index: 0, dt: [], texts: ['ok'] }],
      },
    })
    expect(accumulator.snapshot()).toBe(active)

    accumulator.accept({
      type: 'end', attemptId: LlmAttemptId('settled'), revision: 5, index: 1,
      outcome: { kind: 'abandoned' },
    }, SessionSeq(9))
    expect(accumulator.snapshot()).toEqual({ revision: 5 })
  })
})
