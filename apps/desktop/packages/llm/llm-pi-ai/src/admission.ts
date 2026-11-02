/** Enterprise admission transport. The owner supplies durable storage and credentials. */
import { LlmError } from '@deepseek-ai/dsh-llm'
import type { GenerateOptions, PrepareLlmHttp } from '@deepseek-ai/dsh-llm'
import { admissionTransportFailure } from './admission-error.ts'
import { enterpriseHttp } from './enterprise-http.ts'

/** Report recognized allowance failures without forwarding response diagnostics. */
async function admissionFailure(response: Response): Promise<LlmError> {
  const messages: Record<string, string> = {
    USER_MODEL_BUDGET_EXHAUSTED: 'Insufficient model allowance. Check your balance or contact your administrator for a top-up.',
    USER_MODEL_FUNDS_RESERVED: 'Your allowance is reserved by unfinished tasks. Wait for them to finish, then try again.',
    GLOBAL_MODEL_FUNDS_RESERVED: 'Model funds are reserved by unfinished tasks. Try again after they finish.',
    MODEL_BUDGET_REQUIRED: 'No model allowance. Claim credit from your account menu or contact your administrator.',
    MODEL_BUDGET_EXPIRED: 'Model allowance is inactive or expired. Contact your administrator.',
    GLOBAL_MODEL_BUDGET_EXHAUSTED: 'Model service is unavailable. Contact your administrator.',
    MODEL_BUDGET_PAUSED: 'Model service is unavailable. Contact your administrator.',
    MODEL_PRICE_REQUIRED: 'Model service is unavailable. Contact your administrator.',
    USER_OUTPUT_LIMIT: 'Output exceeds your account allowance. Refresh the model catalog and try again.',
  }
  try {
    const value: unknown = await response.json()
    if (typeof value === 'object' && value !== null && 'code' in value && typeof value.code === 'string' && Object.hasOwn(messages, value.code)) {
      const message = messages[value.code]
      if (message !== undefined) return new LlmError(message, value.code)
    }
  } catch (_error) { /* Malformed responses retain the generic admission classification. */ }
  return new LlmError(`Model admission returned ${response.status}`, 'ADMISSION_REJECTED')
}

/** Durable identifiers for one logical call and its single execution attempt. */
export interface AdmissionRecord {
  callId: string
  logicalCallId: string
  ticketId?: string
}

/** Admission endpoints, polling policy, storage and account credential seams. */
export interface AdmissionTransportOptions {
  origin: string
  deploymentId: string
  model: string
  deviceId: string
  pollMs: number
  maxPollMs?: number
  jitterRatio?: number
  requestTimeoutMs: number
  /** A fresh scoped entry credential; never an upstream model key. */
  credential(): Promise<string>
  /** Must atomically create/read a durable record before admission is submitted. */
  acquire(fingerprint: string): Promise<AdmissionRecord>
  save(fingerprint: string, record: AdmissionRecord): Promise<void>
  remove(fingerprint: string, record: AdmissionRecord): Promise<void>
  waiting?(options: GenerateOptions, ticketId: string, waiting: boolean): void
  fetch?: typeof fetch
}
/** A prepared transport whose disposal waits for admission cleanup to finish. */
export interface AdmissionTransport extends PrepareLlmHttp {
  dispose(): Promise<void>
}

/**
 * Bind one actual serialized model request to one single-use server permit.
 * @param config - pinned deployment, durable storage and scoped credential callbacks.
 * @returns a prepare hook with disposal that awaits pending cancellation cleanup.
 */
export function admissionTransport(config: AdmissionTransportOptions): AdmissionTransport {
  const origin = new URL(config.origin)
  if (
    origin.origin !== config.origin ||
    (origin.protocol !== 'https:' &&
      !(origin.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(origin.hostname)))
  ) {
    throw new Error('Enterprise origin must be HTTPS or local HTTP without a path')
  }
  const maxPollMs = config.maxPollMs ?? config.pollMs,
    jitterRatio = config.jitterRatio ?? 0
  if (maxPollMs < config.pollMs || !Number.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 0.5)
    throw new Error('Invalid admission backoff')
  for (const value of [config.pollMs, maxPollMs, config.requestTimeoutMs]) {
    if (!Number.isSafeInteger(value) || value <= 0 || value > 2_147_483_647) throw new Error('Invalid admission timing')
  }
  const http = config.fetch === undefined ? enterpriseHttp(config.origin, config.requestTimeoutMs) : undefined
  const transport = config.fetch ?? http?.fetch
  if (transport === undefined) throw new Error('Enterprise transport is unavailable')
  const active = new Set<string>()
  const lifetime = new AbortController(),
    pending = new Set<Promise<void>>()
  const track = (work: Promise<void>): void => {
    pending.add(work)
    void work.then(
      () => pending.delete(work),
      () => pending.delete(work),
    )
  }
  const json = async (path: string, signal: AbortSignal, body?: object): Promise<Record<string, unknown>> => {
    // Admission POST is an idempotent lookup-or-create by the durable call identity.
    // The second exchange reconciles a lost response; execution is never repeated here.
    const bounded = AbortSignal.any([signal, AbortSignal.timeout(config.requestTimeoutMs)])
    for (let exchange = 0; ; exchange++) {
      let token: string
      try { token = await config.credential() }
      catch (error) { throw admissionTransportFailure('CREDENTIAL', error) }
      if (!token.startsWith('mwi.')) throw new LlmError('Scoped entry credential required', 'ENTRY_CREDENTIAL_REQUIRED')
      let response: Response
      try {
        response = await transport(`${config.origin}/api/account/inference/${path}`, {
          method: body === undefined ? 'GET' : 'POST',
          redirect: 'error',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
          signal: bounded,
        })
      } catch (error) {
        const failure = admissionTransportFailure('CONTROL', error)
        // Only GET and lookup-or-create admit are safe to reconcile. Cancel is not replayed.
        if (exchange === 0 && !bounded.aborted && (body === undefined || path === 'tickets') &&
          ['ADMISSION_CONTROL_CONNECT_TIMEOUT', 'ADMISSION_CONTROL_CONNECTION_RESET', 'ADMISSION_CONTROL_SOCKET'].includes(failure.code)) continue
        throw failure
      }
      if (!response.ok) throw await admissionFailure(response)
      let result: unknown
      try { result = await response.json() }
      catch (error) { throw admissionTransportFailure('CONTROL_RESPONSE', error) }
      if (typeof result !== 'object' || result === null || Array.isArray(result))
        throw new LlmError('Invalid admission response', 'ADMISSION_PROTOCOL')
      return result as Record<string, unknown>
    }
  }

  const prepare: PrepareLlmHttp = async (options, payload, upstream) => {
    const signal = AbortSignal.any([upstream, lifetime.signal])
    signal.throwIfAborted()
    const body: unknown = JSON.parse(payload)
    if (typeof body !== 'object' || body === null || Array.isArray(body))
      throw new LlmError('Invalid model payload', 'ADMISSION_PROTOCOL')
    const request = body as Record<string, unknown>
    const budget = request.max_tokens ?? request.max_completion_tokens
    if (request.model !== config.model || typeof budget !== 'number' || !Number.isSafeInteger(budget) || budget <= 0) {
      throw new LlmError('Model and output budget must match the deployment', 'ADMISSION_PROTOCOL')
    }
    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload)))]
      .map(byte => byte.toString(16).padStart(2, '0'))
      .join('')
    const trigger = options.messages.findLast(
      message => message.role === 'user' && message.source?.kind === 'user' && message.id !== undefined,
    )
    const taskId = trigger?.id ?? ''
    const fingerprint = JSON.stringify([
      config.deploymentId,
      options.sessionId ?? '',
      taskId,
      options.purpose ?? 'agent',
      digest,
    ])
    if (active.has(fingerprint)) throw new LlmError('An identical call is already pending', 'ADMISSION_ALREADY_PENDING')
    signal.throwIfAborted()
    active.add(fingerprint)
    const completion = Promise.withResolvers<void>()
    pending.add(completion.promise)
    let ticketId: string | undefined
    let record: AdmissionRecord | undefined
    let cancelSent = false
    const release = (): void => {
      active.delete(fingerprint)
      signal.removeEventListener('abort', cancel)
      pending.delete(completion.promise)
      completion.resolve()
    }
    const cancel = (): void => {
      if (ticketId === undefined || record === undefined || cancelSent) return
      cancelSent = true
      const cancellingTicket = ticketId
      const cancellingRecord = record
      const work = json(`tickets/${encodeURIComponent(cancellingTicket)}/cancel`, new AbortController().signal, {})
        .then(async () => await json(`tickets/${encodeURIComponent(cancellingTicket)}`, new AbortController().signal))
        .then(async (result) => {
          if (['CANCELLED', 'EXPIRED', 'SUCCEEDED', 'FAILED'].includes(String(result.state)))
            await config.remove(fingerprint, cancellingRecord)
        })
        .catch(() => {
          /* Keep the durable ticket for later reconciliation. */
        })
        .finally(release)
      track(work)
    }
    signal.addEventListener('abort', cancel, { once: true })
    try {
      try { record = await config.acquire(fingerprint) }
      catch (error) { throw admissionTransportFailure('STORE', error) }
      const admittedRecord = record
      let ticket: Record<string, unknown>
      if (record.ticketId === undefined) {
        ticket = await json('tickets', signal, {
          deploymentId: config.deploymentId,
          callId: record.callId,
          logicalCallId: record.logicalCallId,
          deviceId: config.deviceId,
          sessionId: options.sessionId ?? '',
          taskId,
          parentTaskId: '',
          purpose: options.purpose ?? 'agent',
          requestDigest: digest,
          maxOutputTokens: budget,
        })
      } else ticket = await json(`tickets/${encodeURIComponent(record.ticketId)}`, signal)
      if (typeof ticket.id !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(ticket.id))
        throw new LlmError('Invalid ticket identifier', 'ADMISSION_PROTOCOL')
      ticketId = ticket.id
      try { await config.save(fingerprint, { ...record, ticketId }) }
      catch (error) { throw admissionTransportFailure('STORE', error) }
      signal.throwIfAborted()
      config.waiting?.(options, ticketId, ticket.state === 'WAITING')
      let delayMs = config.pollMs
      while (ticket.state === 'WAITING') {
        await new Promise<void>((resolve, reject) => {
          const abort = (): void => {
            clearTimeout(timer)
            reject(signal.reason instanceof Error ? signal.reason : new LlmError('Admission cancelled', 'ABORTED'))
          }
          const timer = setTimeout(
            () => {
              signal.removeEventListener('abort', abort)
              resolve()
            },
            Math.max(1, Math.round(delayMs * (1 - jitterRatio + Math.random() * jitterRatio * 2))),
          )
          signal.addEventListener('abort', abort, { once: true })
          if (signal.aborted) abort()
        })
        ticket = await json(`tickets/${encodeURIComponent(ticketId)}`, signal)
        delayMs = Math.min(maxPollMs, delayMs * 2)
        config.waiting?.(options, ticketId, ticket.state === 'WAITING')
      }
      config.waiting?.(options, ticketId, false)
      if (ticket.state !== 'GRANTED') {
        // Running/unknown/success cannot be transparently replayed after reconnect.
        if (['FAILED', 'CANCELLED', 'EXPIRED', 'SUCCEEDED'].includes(String(ticket.state)))
          await config.remove(fingerprint, admittedRecord)
        throw new LlmError(
          `Previous call is ${String(ticket.state)}; inspect its status before continuing`,
          'ADMISSION_RECOVERY_REQUIRED',
        )
      }
      let consumed = false
      return async (_input, init) => {
        if (consumed || init?.body !== payload)
          throw new LlmError('Admission permits are single-use and body-bound', 'ADMISSION_PAYLOAD_MISMATCH')
        consumed = true
        signal.throwIfAborted()
        try {
          const token = await config.credential().catch((error: unknown) => { throw admissionTransportFailure('CREDENTIAL', error) })
          if (!token.startsWith('mwi.'))
            throw new LlmError('Scoped entry credential required', 'ENTRY_CREDENTIAL_REQUIRED')
          const headers = new Headers({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' })
          const original = new Headers(init.headers)
          for (const name of ['anthropic-version', 'anthropic-beta']) {
            const value = original.get(name)
            if (value !== null) headers.set(name, value)
          }
          const response = await transport(`${config.origin}/api/account/inference/tickets/${ticketId}/execute`, {
            method: 'POST',
            redirect: 'error',
            headers,
            body: payload,
            signal,
          })
          if (!response.ok) throw await admissionFailure(response)
          if (response.body === null) {
            release()
            return response
          }
          const reader = response.body.getReader()
          const streamed = new ReadableStream<Uint8Array>({
            async pull(controller) {
              try {
                const chunk = await reader.read()
                if (!chunk.done) {
                  controller.enqueue(chunk.value)
                  return
                }
                controller.close()
                try {
                  const completed = await json(`tickets/${ticketId}`, new AbortController().signal)
                  if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'].includes(String(completed.state)))
                    await config.remove(fingerprint, admittedRecord)
                } catch {
                  /* Retain uncertain executions for status recovery. */
                } finally {
                  release()
                }
              } catch (error) {
                controller.error(admissionTransportFailure('STREAM', error))
                release()
              }
            },
            async cancel(reason) {
              cancel()
              try {
                await reader.cancel(reason)
              } finally {
                release()
              }
            },
          })
          return new Response(streamed, {
            status: response.status,
            statusText: response.statusText,
            headers: response.headers,
          })
        } catch (error) {
          // Retain the durable record: even an HTTP failure may have started inference.
          release()
          throw admissionTransportFailure('EXECUTE', error)
        }
      }
    } catch (error) {
      if (signal.aborted && ticketId !== undefined) cancel()
      release()
      throw error
    } finally {
      if (ticketId !== undefined) config.waiting?.(options, ticketId, false)
    }
  }
  return Object.assign(prepare, {
    async dispose() {
      lifetime.abort(new LlmError('Admission transport stopped', 'ABORTED'))
      while (pending.size > 0) await Promise.allSettled([...pending])
      await http?.close()
    },
  })
}
