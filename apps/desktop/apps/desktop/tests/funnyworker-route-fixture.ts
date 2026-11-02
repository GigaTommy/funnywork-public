/** Account fixtures model public readiness separately from authenticated operations. */
export function routeFixture(send: (url: string, init?: RequestInit) => Promise<Response>): typeof fetch {
  return async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (url.endsWith('/api/account/session') && new Headers(init?.headers).get('User-Agent') === 'FunnyWork-entry-check/1.0') {
      return new Response('{}', { status: 401 })
    }
    return send(url, init)
  }
}
