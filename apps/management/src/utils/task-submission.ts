import { getToken } from '@/utils/auth';
import { businessRequest } from './funnyworker-api';

/** Keep ambiguous submissions retryable while this task form remains mounted. */
export function createTaskSubmission() {
  let session = getToken();
  const attempts = new Map<string, string>();
  return async (body: { title?: string; agentId: string; zone: string; inputs: string[]; dispatchPaused: boolean }) => {
    if (session !== getToken()) {
      attempts.clear();
      session = getToken();
    }
    const payload = JSON.stringify(body);
    let key = attempts.get(payload);
    if (!key) {
      key = crypto.randomUUID();
      attempts.set(payload, key);
    }
    const result = await businessRequest('/tasks', 'POST', body, key);
    // Only this payload is confirmed. Another edited draft may still have an unknown outcome.
    attempts.delete(payload);
    return result;
  };
}
