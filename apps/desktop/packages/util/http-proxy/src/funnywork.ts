/** Trusted FunnyWork entry selection; transport addresses never identify an account. */
/**
 * Resolve the canonical service and one operator-configured regional HTTPS entry.
 * @returns Approved origins sharing the official account authority.
 */
export function funnyWorkOrigins(): readonly [string, ...string[]] {
  const international = 'https://funnywork.funnypixelsapp.com'
  const regional = process.env.FUNNYWORK_REGIONAL_ORIGIN
  if (regional === undefined || regional === '') return [international]
  const url = new URL(regional)
  if (url.protocol !== 'https:' || url.username || url.password || url.port
    || url.pathname !== '/' || url.search || url.hash || url.origin !== regional) {
    throw new Error('FUNNYWORK_REGIONAL_ORIGIN must be a canonical HTTPS origin')
  }
  return regional === international ? [international] : [international, regional]
}

/**
 * Preserve the original official account namespace across both public entries.
 * @param origin Configured HTTPS origin, or an isolated self-hosted origin.
 * @returns Stable account and credential namespace.
 */
export function funnyWorkIdentity(origin: string): string {
  const official = funnyWorkOrigins()
  return official.some(value => value === origin) ? official[0] : origin
}

/**
 * Create an independently owned transport with credential-free entry discovery.
 * Only approved account APIs may cross official entries. POSTs are never replayed.
 * @param origin Stable configured service origin.
 * @param send Caller-owned HTTP implementation retaining its proxy and connection policy.
 * @returns Fetch operation and explicit readiness operation; no process-global routing state.
 */
export function funnyWorkTransport(origin: string, send: (url: string, init?: RequestInit) => Promise<Response>): {
  ready: () => Promise<string>
  fetch: (input: string | URL, init?: RequestInit) => Promise<Response>
} {
  const official = funnyWorkOrigins()
  const identity = funnyWorkIdentity(origin)
  const managed = identity === official[0]
  let active: string | undefined
  let selecting: Promise<string> | undefined
  const unavailable = new Map<string, number>()
  const select = async (): Promise<string> => {
    if (!managed) return origin
    if (active !== undefined) return active
    if (selecting !== undefined) return selecting
    const controllers: AbortController[] = []
    const now = Date.now()
    const candidates = official.filter(value => (unavailable.get(value) ?? 0) <= now)
    const eligible = candidates.length === 0 ? [...official] : candidates
    selecting = Promise.any(eligible.map(async (endpoint) => {
      const controller = new AbortController()
      controllers.push(controller)
      const response = await send(endpoint + '/api/account/session', {
        method: 'GET', redirect: 'error',
        signal: AbortSignal.any([controller.signal, AbortSignal.timeout(4000)]),
        headers: { 'User-Agent': 'FunnyWork-entry-check/1.0' },
      })
      const reachable = response.status === 401
      await response.body?.cancel()
      if (!reachable) throw new Error('Official account entry is unavailable')
      return endpoint
    })).then((endpoint) => {
      active = endpoint
      return endpoint
    }).finally(() => {
      for (const controller of controllers) controller.abort()
      selecting = undefined
    })
    return selecting
  }
  const fail = (endpoint: string): void => {
    unavailable.set(endpoint, Date.now() + 30000)
    if (active === endpoint) active = undefined
  }
  return {
    ready: select,
    async fetch(input: string | URL, init: RequestInit = {}): Promise<Response> {
      const url = new URL(input)
      if (funnyWorkIdentity(url.origin) !== identity || url.username || url.password ||
          (managed && !url.pathname.startsWith('/api/account/'))) {
        throw new Error('Account transport origin or path mismatch')
      }
      const endpoint = await select()
      init.signal?.throwIfAborted()
      const invoke = (target: string) => send(target + url.pathname + url.search, { ...init, redirect: 'error' })
      let response: Response
      try {
        response = await invoke(endpoint)
      } catch (error) {
        if (!managed || init.signal?.aborted) throw error
        fail(endpoint)
        if ((init.method ?? 'GET').toUpperCase() !== 'GET') throw error
        return invoke(await select())
      }
      if (managed && [502, 503, 504].includes(response.status)) {
        fail(endpoint)
        if ((init.method ?? 'GET').toUpperCase() === 'GET') {
          await response.body?.cancel()
          return invoke(await select())
        }
      }
      return response
    },
  }
}
