import { expect, it, vi } from 'vitest'
import { createDeviceLink, deviceLinkImage } from '../src/funnyworker-device-link.ts'

function account(result: unknown, origin = 'https://example.com') {
  return { username: 'user', dataKey: 'key', serverOrigin: origin, budget: async () => { throw new Error('Budget unused in this fixture') }, check: async () => true, logout: async () => undefined, policyRequest: async () => undefined, syncRequest: vi.fn(async () => result) }
}
const response = { id: 'code', secret: 'a'.repeat(64), expiresIn: 120000 }
it('uses the current device, encodes only origin and connection fields, and includes request latency in expiry', async () => {
  const start = Date.now(), source = account(response)
  const link = await createDeviceLink(source, 'desktop')
  expect(source.syncRequest).toHaveBeenCalledWith('/devices/desktop/link', {})
  expect(link.expiresAt).toBeGreaterThanOrEqual(start + 120000)
  const url = new URL(link.uri)
  expect(url.protocol).toBe('funnywork:'); expect(url.hostname).toBe('connect')
  expect(Object.fromEntries(url.searchParams)).toEqual({ v: '1', server: 'https://example.com', id: 'code', secret: response.secret })
  expect(await deviceLinkImage(link)).toMatch(/^data:image\/png;base64,/)
})
it('rejects unsafe origins and missing identities before sending a request', async () => {
  for (const origin of ['http://127.0.0.1:8765', 'https://user:pass@example.com', 'https://example.com/other', 'https://example.com?token=abc']) {
    const source = account(response, origin)
    await expect(createDeviceLink(source, 'desktop')).rejects.toThrow()
    expect(source.syncRequest).not.toHaveBeenCalled()
  }
  const source = account(response)
  await expect(createDeviceLink(source, '../other')).rejects.toThrow()
  expect(source.syncRequest).not.toHaveBeenCalled()
})
it('rejects malformed and oversized wire fields without rendering a code', async () => {
  for (const result of [null, {}, { ...response, id: '../x' }, { ...response, secret: 'bad' }, { ...response, expiresIn: true }, { ...response, expiresIn: 120001 }, { ...response, expiresIn: 0.5 }]) {
    await expect(createDeviceLink(account(result), 'desktop')).rejects.toThrow('Invalid device connection response')
  }
})
