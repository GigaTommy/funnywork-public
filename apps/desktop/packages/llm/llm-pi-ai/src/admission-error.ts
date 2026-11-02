/** Redacted diagnostics: never copy native messages, URLs, bodies or credential paths. */
import { LlmError } from '@deepseek-ai/dsh-llm'

type Phase = 'CREDENTIAL' | 'STORE' | 'CONTROL' | 'CONTROL_RESPONSE' | 'EXECUTE' | 'STREAM'
const reasons: Record<string, string> = {
  UND_ERR_CONNECT_TIMEOUT: 'CONNECT_TIMEOUT', ECONNRESET: 'CONNECTION_RESET',
  UND_ERR_SOCKET: 'SOCKET', ENOTFOUND: 'DNS', EAI_AGAIN: 'DNS',
  ECONNREFUSED: 'CONNECTION_REFUSED', ETIMEDOUT: 'CONNECT_TIMEOUT',
  CERT_HAS_EXPIRED: 'TLS', UNABLE_TO_VERIFY_LEAF_SIGNATURE: 'TLS',
  ERR_TLS_CERT_ALTNAME_INVALID: 'TLS', ENOENT: 'FILE_MISSING', EACCES: 'FILE_ACCESS',
}

/**
 * Classify native causes without persisting their potentially sensitive messages.
 * @param phase - operation that failed.
 * @param error - native failure, retained only as an in-process cause.
 * @returns a stable phase/code classification with a redacted message.
 */
export function admissionTransportFailure(phase: Phase, error: unknown): LlmError {
  if (error instanceof LlmError) return error
  let cause = error, reason = 'UNCLASSIFIED'
  for (let depth = 0; depth < 5 && cause instanceof Error; depth++) {
    if ('code' in cause && typeof cause.code === 'string' && Object.hasOwn(reasons, cause.code)) {
      const classified = reasons[cause.code]
      if (classified === undefined) continue
      reason = classified
      break
    }
    if (cause.name === 'TimeoutError') reason = 'TIMEOUT'
    else if (cause.name === 'AbortError') reason = 'ABORTED'
    else if (cause instanceof SyntaxError) reason = 'INVALID_JSON'
    cause = cause.cause
  }
  return new LlmError(`Model transport phase ${phase}: ${reason}; request identity retained for reconciliation`,
    `ADMISSION_${phase}_${reason}`, { cause: error })
}
