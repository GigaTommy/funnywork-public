import { afterEach, expect, it, vi } from 'vitest';
import { webApi } from '../../../../../packages/management-react/src/web-api';

afterEach(() => vi.unstubAllGlobals());

it('preserves capability, cursor and literal search in the retained React API adapter', async () => {
  const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(
    url.endsWith('/me') ? { kind: 'USER', roles: ['ADMIN'] } : { items: [] }
  ), { status: 200 }));
  vi.stubGlobal('fetch', fetcher);
  await webApi('connect', '', 'test-only-session');
  try {
    await webApi('agentPage', 'cursor+one', 'model.read.v1', '文档 100%_ &two');
    const url = new URL(fetcher.mock.calls[1][0], 'http://localhost');
    expect(url.pathname).toBe('/api/v1/agent-definitions/page');
    expect(url.searchParams.get('query')).toBe('文档 100%_ &two');
    expect(url.searchParams.get('after')).toBe('cursor+one');
    expect(url.searchParams.get('capability')).toBe('model.read.v1');
    expect([...url.searchParams.keys()]).toEqual(['limit', 'after', 'capability', 'query']);
  } finally {
    await webApi('disconnect');
  }
});
