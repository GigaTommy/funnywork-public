import { expect, it, vi } from 'vitest'
import { enterpriseHttp } from '../src/enterprise-http.ts'
import { admissionTransport } from '../src/index.ts'

const imports = vi.hoisted(() => vi.fn<() => void>())
vi.mock('undici', () => {
  imports()
  throw new Error('Node HTTP is unavailable in this host')
})

it('imports the public adapter and closes an unused transport without loading Node HTTP', async () => {
  expect(admissionTransport).toBeTypeOf('function')
  const http = enterpriseHttp('https://enterprise.invalid', 30)
  await http.close()
  await expect(http.fetch('https://enterprise.invalid')).rejects.toThrow('Enterprise transport is closed')
  expect(imports).not.toHaveBeenCalled()
})

it('loads Node HTTP on an enterprise request and reports an unavailable runtime', async () => {
  const http = enterpriseHttp('https://enterprise.invalid', 30)
  await expect(http.fetch('https://other.invalid')).rejects.toThrow('Enterprise transport origin mismatch')
  expect(imports).not.toHaveBeenCalled()
  await expect(http.fetch('https://enterprise.invalid')).rejects.toMatchObject({ cause: { message: 'Node HTTP is unavailable in this host' } })
  expect(imports).toHaveBeenCalledOnce()
  await expect(http.close()).rejects.toMatchObject({ cause: { message: 'Node HTTP is unavailable in this host' } })
})
