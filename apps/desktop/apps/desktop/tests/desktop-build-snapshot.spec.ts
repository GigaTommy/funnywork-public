/** Packaging metadata never combines identity from different checkout generations. */
import { beforeEach, expect, test, vi } from 'vitest'
const state = vi.hoisted(() => ({ headReads: 0, moved: false, missing: false, dirty: false }))
vi.mock('node:child_process', () => ({ execFileSync: (_bin: string, args: string[]) => {
  if (state.missing) throw new Error('git unavailable')
  if (args.join(' ') === 'rev-parse HEAD') return (state.moved && state.headReads++ > 0 ? 'c' : 'a').repeat(40)
  if (args[0] === 'rev-parse') return 'b'.repeat(40)
  return state.dirty ? ' M source.ts' : ''
} }))
import { readDesktopBuildCommit, resolveDesktopBuildCommit, desktopBuildCommitEnvironment } from '../scripts/desktop-build-commit.mjs'
beforeEach(() => { Object.assign(state, { headReads: 0, moved: false, missing: false, dirty: false }) })
test('records one pinned commit and tree with observed dirty state', () => {
  state.dirty = true
  const result = readDesktopBuildCommit('/test-only')
  expect(result).toEqual({ commit: 'a'.repeat(40), tree: 'b'.repeat(40), dirty: true })
  expect(resolveDesktopBuildCommit(desktopBuildCommitEnvironment(result))).toEqual(result)
})
test('refuses a HEAD that moves during identity collection', () => {
  state.moved = true
  expect(() => readDesktopBuildCommit('/test-only')).toThrow('changed')
})
test('does not invent identity when Git is unavailable', () => {
  state.missing = true
  expect(() => readDesktopBuildCommit('/test-only')).toThrow('unavailable')
})
