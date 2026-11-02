import { afterEach, describe, expect, it, vi } from 'vitest';
import { createTaskSubmission } from '../task-submission';

const session = vi.hoisted(() => ({ token: 'owner-session' }));
vi.mock('@/utils/auth', () => ({ getToken: () => session.token }));
const body = { agentId: 'agent', zone: 'sandbox', inputs: ['input'], dispatchPaused: true };
afterEach(() => { vi.unstubAllGlobals(); session.token = 'owner-session'; });

describe('task creation retries', () => {
  it('reuses the server key after a lost response, even after editing and returning to the original draft', async () => {
    const requests: RequestInit[] = [];
    const committed = new Map<string, string>();
    vi.stubGlobal('fetch', vi.fn(async (_url: string, request: RequestInit) => {
      requests.push(request);
      const key = new Headers(request.headers).get('Idempotency-Key')!;
      expect(key).toMatch(/^[A-Za-z0-9._:-]{1,120}$/);
      const reused = committed.has(key);
      if (!reused) committed.set(key, 'task-' + committed.size);
      if (requests.length < 3) throw new TypeError('connection lost after commit');
      return new Response(JSON.stringify({ taskId: committed.get(key), reused }));
    }));
    const submit = createTaskSubmission();
    await expect(submit(body)).rejects.toThrow();
    await expect(submit({ ...body, inputs: ['edited'] })).rejects.toThrow();
    await expect(submit(body)).resolves.toEqual({ taskId: 'task-0', reused: true });
    expect(committed.size).toBe(2);
    expect(requests[0].body).toBe(requests[2].body);
  });

  it('uses a fresh key for a confirmed new task and after a session change', async () => {
    const keys: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (_url: string, request: RequestInit) => {
      keys.push(new Headers(request.headers).get('Idempotency-Key')!);
      if (keys.length === 2) throw new TypeError('offline');
      return new Response('{"taskId":"confirmed"}');
    }));
    const submit = createTaskSubmission();
    await submit(body);
    await expect(submit(body)).rejects.toThrow();
    session.token = 'new-owner-session';
    await submit(body);
    expect(new Set(keys).size).toBe(3);
  });
});
