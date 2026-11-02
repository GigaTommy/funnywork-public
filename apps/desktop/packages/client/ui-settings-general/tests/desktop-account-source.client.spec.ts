import { expect, it, vi } from 'vitest'
import { DesktopAccountSource } from '../src/client/desktop-account-source.ts'
import type { DesktopAccountProfile } from '../src/types.ts'

it('keeps a saved picture when an earlier profile read finishes late', async () => {
  let resolveOld: (profile: DesktopAccountProfile) => void = () => {}
  const oldRead = new Promise<DesktopAccountProfile>((resolve) => { resolveOld = resolve })
  const updated = { username: 'Gigi', roles: [], avatarUrl: 'data:image/jpeg;base64,AAAA' }
  const source = new DesktopAccountSource({ profile: () => oldRead, updateAvatar: async () => updated,
    signOut: async () => {}, budget: async () => { throw new Error('unused') } })
  try {
    await source.updateAvatar()
    resolveOld({ username: 'Gigi', roles: [] })
    await Promise.resolve()
    expect(source.store.getSnapshot()).toEqual(updated)
  } finally { source.dispose() }
})

it('refreshes another device picture and stops refreshing after disposal', async () => {
  vi.useFakeTimers()
  const profile = vi.fn(async () => ({ username: 'Gigi', roles: [], avatarUrl: 'data:image/jpeg;base64,BBBB' }))
  const source = new DesktopAccountSource({ profile, signOut: async () => {}, budget: async () => { throw new Error('unused') } })
  try {
    await vi.advanceTimersByTimeAsync(15000)
    expect(profile).toHaveBeenCalledTimes(2)
    expect(source.store.getSnapshot()?.avatarUrl).toBe('data:image/jpeg;base64,BBBB')
    source.dispose()
    await vi.advanceTimersByTimeAsync(15000)
    expect(profile).toHaveBeenCalledTimes(2)
  } finally { source.dispose(); vi.useRealTimers() }
})
