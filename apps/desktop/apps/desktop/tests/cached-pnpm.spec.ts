import { expect, it, vi } from 'vitest'
import { installCachedPnpm } from '../scripts/cached-pnpm.mjs'

it('does not access the registry when cached dependencies verify offline', async () => {
  const run = vi.fn(async () => undefined)
  await installCachedPnpm(run)
  expect(run.mock.calls).toEqual([[true]])
})

it.each(['NO_OFFLINE_META', 'NO_OFFLINE_TARBALL', 'CANNOT_DOWNLOAD_BINARY_OFFLINE'])('fetches only after %s', async (code) => {
  const run = vi.fn().mockRejectedValueOnce(new Error(`[ERR_PNPM_${code}] missing`)).mockResolvedValueOnce(undefined)
  await installCachedPnpm(run)
  expect(run.mock.calls).toEqual([[true], [false]])
})

it.each(['TARBALL_INTEGRITY', 'OUTDATED_LOCKFILE', 'VERIFY_DEPS_BEFORE_RUN'])('does not retry %s through the network', async (code) => {
  const error = new Error(`[ERR_PNPM_${code}] invalid`)
  const run = vi.fn().mockRejectedValue(error)
  await expect(installCachedPnpm(run)).rejects.toBe(error)
  expect(run.mock.calls).toEqual([[true]])
})
