import { vi as regionalEnvironment, beforeEach as configureRegionalEntry, afterEach as restoreRegionalEntry } from 'vitest'
import { afterEach, expect, it, vi } from 'vitest'
afterEach(() => { vi.unstubAllGlobals() })
import { parseEnterprisePresets } from '../src/enterprise-presets.ts'
const row = { id: 'enterprise-review-v1', version: 1, name: 'Review', description: '', persona: 'Review code.', base: 'standard', selectable: true }
const catalog = { schemaVersion: 1, allowPersonalPresets: false, defaultPresetId: row.id, items: [row] }
it('accepts current literal personas and denies invalid revision identities and executable templates', () => {
  expect(parseEnterprisePresets(catalog).items[0]?.persona).toBe('Review code.')
  for (const patch of [{ persona: '{{model}}' }, { persona: '' }, { base: 'arbitrary-plugin' }, { version: 2 }, { name: '' }, { selectable: 'true' }]) {
    expect(() => parseEnterprisePresets({ ...catalog, items: [{ ...row, ...patch }] })).toThrow()
  }
  expect(() => parseEnterprisePresets({ ...catalog, items: [row, row] })).toThrow()
  expect(() => parseEnterprisePresets({ ...catalog, defaultPresetId: 'missing' })).toThrow()
  expect(() => parseEnterprisePresets({ ...catalog, schemaVersion: 2 })).toThrow()
})

it.each([false, true])('synchronizes personas and replaces revisions; mainland-only: %s', async (mainlandOnly) => {
  const { createServer } = await import('node:http')
  const { harness, declare, contribution } = await import('../../../preset/agent-preset-registry/tests/harness.ts')
  const { installEnterprisePresets } = await import('../src/enterprise-presets.ts')
  const { vi } = await import('vitest')
  const ctx = await harness()
  const warnings: string[] = []
  vi.spyOn(ctx.logger, 'warn').mockImplementation((value) => { warnings.push(String(value)) })
  let response = catalog
  const headers: string[] = []
  const server = createServer((request, result) => {
    headers.push(request.headers.authorization ?? '')
    result.setHeader('Content-Type', 'application/json')
    result.end(JSON.stringify(response))
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    await declare(ctx, { ...contribution('standard'), plugins: [...contribution('standard').plugins,
      { name: '@deepseek-ai/dsh-persona', disabled: true, config: { prefix: 'Original' } }] })
    const address = server.address()
    if (address === null || typeof address === 'string') throw new Error('Missing test server address')
    const localOrigin = `http://127.0.0.1:${address.port}`
    const origin = mainlandOnly ? 'https://funnywork.funnypixelsapp.com' : localOrigin
    if (mainlandOnly) {
      const originalFetch = globalThis.fetch
      vi.stubGlobal('fetch', async (input: string | URL | Request, init?: RequestInit) => {
        const target = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url)
        if (target.origin === origin) throw new TypeError('Overseas entry unreachable')
        if (target.origin !== 'https://regional.example.invalid') throw new Error('Unexpected entry')
        if (new Headers(init?.headers).get('User-Agent') === 'FunnyWork-entry-check/1.0') {
          expect(new Headers(init?.headers).has('Authorization')).toBe(false)
          return new Response('{}', { status: 401 })
        }
        return originalFetch(localOrigin + target.pathname, init)
      })
    }
    installEnterprisePresets(ctx, { origin, credentialFile: '/unused', stateDirectory: '/unused',
      refreshMs: 1000, pollMs: 1, maxPollMs: 1, jitterRatio: 0, requestTimeoutMs: 1000, streamIdleTimeoutMs: 1000,
      maxRequestImageBytes: 1 },
    async () => 'mwi.device-only')
    await vi.waitFor(() => {
      expect({ id: ctx.agentPresets.defaultId, warnings }).toEqual({ id: row.id, warnings: [] })
    }, { timeout: 3000 })
    const next = { ...row, id: 'enterprise-review-v2', version: 2, persona: 'Current instructions' }
    response = { ...catalog, items: [next], defaultPresetId: next.id }
    await vi.waitFor(() =>{  expect(ctx.agentPresets.defaultId).toBe(next.id) }, { timeout: 3000 })
    await expect(ctx.agentPresets.readDocument(row.id)).rejects.toThrow()
    expect((await ctx.agentPresets.readDocument(next.id)).content).toContain('Current instructions')
    expect(headers.every(value => value === 'Bearer mwi.device-only')).toBe(true)
  } finally {
    await ctx.fiber.dispose()
    await new Promise<void>((resolve, reject) => server.close((error) => { if (error) reject(error); else resolve() }))
  }
})

it('stops synchronization while Loader settlement is pending without blocking Host shutdown', async () => {
  const { harness } = await import('../../../preset/agent-preset-registry/tests/harness.ts')
  const { installEnterprisePresets } = await import('../src/enterprise-presets.ts')
  const { vi } = await import('vitest')
  const ctx = await harness()
  const settlement = vi.spyOn(ctx.loader, 'await').mockImplementation(() => new Promise<void>(() => {}))
  installEnterprisePresets(ctx, { origin: 'http://127.0.0.1:1', credentialFile: '/unused', stateDirectory: '/unused',
    refreshMs: 1000, pollMs: 1, maxPollMs: 1, jitterRatio: 0, requestTimeoutMs: 1000, streamIdleTimeoutMs: 1000,
    maxRequestImageBytes: 1 },
  async () => 'mwi.device-only')
  await vi.waitFor(() =>{  expect(settlement).toHaveBeenCalled() })
  await ctx.fiber.dispose()
}, 3000)

configureRegionalEntry(() => { regionalEnvironment.stubEnv('FUNNYWORK_REGIONAL_ORIGIN', 'https://regional.example.invalid') })
restoreRegionalEntry(() => { regionalEnvironment.unstubAllEnvs() })
