/** Enterprise-owned connection lifetime; apply the configured deadline through TLS establishment. */
import type { Agent, fetch as httpFetch } from 'undici'
import { funnyWorkIdentity, funnyWorkTransport, proxyRouteFor } from '@deepseek-ai/dsh-http-proxy'

/**
 * Load Node HTTP on the first request; keep connections origin-pinned and respect explicit proxy routing.
 * @param origin - enterprise service origin without a path.
 * @param timeoutMs - configured connection and TLS establishment deadline.
 * @returns an owned transport; close it after all requests have settled.
 */
export function enterpriseHttp(origin: string, timeoutMs: number): { fetch: typeof fetch; close(): Promise<void> } {
  let connection: Promise<{ direct: Agent; httpFetch: typeof httpFetch }> | undefined
  let closed = false
  const connect = (): Promise<{ direct: Agent; httpFetch: typeof httpFetch }> => {
    connection ??= import('undici').then(({ Agent, fetch }) => ({
      // proxy-exempt: Owned direct connections apply only when proxyRouteFor marks the origin unproxied.
      direct: new Agent({ connect: { timeout: timeoutMs } }),
      httpFetch: fetch,
    }))
    return connection
  }
  const routed = funnyWorkTransport(origin, async (input, init) => {
    const url = new URL(input)
    if (funnyWorkIdentity(url.origin) !== funnyWorkIdentity(origin)) throw new Error('Enterprise transport origin mismatch')
    if (init?.body != null && typeof init.body !== 'string') throw new Error('Enterprise transport requires a serialized body')
    if (closed) throw new Error('Enterprise transport is closed')
    const { direct, httpFetch } = await connect()
    const route = proxyRouteFor(url)
    const response = await httpFetch(url, {
      method: init?.method ?? 'GET',
      ...(init?.body == null ? {} : { body: init.body }),
      ...(init?.signal == null ? {} : { signal: init.signal }),
      headers: [...new Headers(init?.headers)],
      // proxy-exempt: Configured proxies keep their dispatcher; only unproxied routes use the owned direct agent.
      dispatcher: route.proxied ? route.dispatcher : direct,
      redirect: 'error',
    })
    const reader: ReadableStreamDefaultReader<Uint8Array> | undefined = response.body?.getReader()
    const body = reader === undefined ? null : new ReadableStream<Uint8Array>({
      async pull(controller) {
        try {
          const chunk = await reader.read()
          if (chunk.done) controller.close()
          else controller.enqueue(chunk.value)
        } catch (error) { controller.error(error) }
      },
      async cancel(reason) { await reader.cancel(reason) },
    })
    return new Response(body, { status: response.status, statusText: response.statusText, headers: [...response.headers] })
  })
  return {
    async fetch(input, init) {
      if (typeof input !== 'string' && !(input instanceof URL)) throw new Error('Enterprise transport requires an explicit URL')
      if (new URL(input).origin !== origin) throw new Error('Enterprise transport origin mismatch')
      return routed.fetch(input, init)
    },
    async close() {
      closed = true
      if (connection !== undefined) await (await connection).direct.close()
    },
  }
}
