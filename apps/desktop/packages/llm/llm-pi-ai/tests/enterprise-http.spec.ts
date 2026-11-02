import { expect, it } from 'vitest'
import { createServer } from 'node:net'
import { once } from 'node:events'
import type { Socket } from 'node:net'
import { enterpriseHttp } from '../src/enterprise-http.ts'

it('applies the configured connection deadline to an uncompleted TLS handshake and closes its sockets', async () => {
  const sockets = new Set<Socket>()
  const server = createServer((socket) => { sockets.add(socket); socket.on('close', () => { sockets.delete(socket) }) })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('Missing listener address')
  const origin = `https://127.0.0.1:${address.port}`
  const http = enterpriseHttp(origin, 30)
  try {
    await expect(http.fetch(origin, { signal: AbortSignal.timeout(10000) })).rejects.toMatchObject({ cause: { code: 'UND_ERR_CONNECT_TIMEOUT' } })
    await expect(http.fetch('https://other.invalid/')).rejects.toThrow('Enterprise transport origin mismatch')
  } finally {
    await http.close()
    for (const socket of sockets) socket.destroy()
    await new Promise<void>((resolve) => { server.close(() => { resolve() }) })
  }
})
