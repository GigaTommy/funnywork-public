import { expect, it } from 'vitest'
import { LlmError } from '@deepseek-ai/dsh-llm'
import { admissionTransportFailure } from '../src/admission-error.ts'
it('preserves explicit admission denials', () => {
  const denied = new LlmError('denied', 'USER_MODEL_BUDGET_EXHAUSTED')
  expect(admissionTransportFailure('CONTROL', denied)).toBe(denied)
})
it.each([
  [new SyntaxError('secret'), 'INVALID_JSON'], [new DOMException('secret', 'TimeoutError'), 'TIMEOUT'],
  [new DOMException('secret', 'AbortError'), 'ABORTED'], [new Error('secret'), 'UNCLASSIFIED'],
  ['secret', 'UNCLASSIFIED'], [Object.assign(new Error('secret'), { code: 'ENOENT' }), 'FILE_MISSING'],
  [new Error('secret', { cause: Object.assign(new Error('private'), { code: 'ENOTFOUND' }) }), 'DNS'],
])('redacts the native error at the transport boundary', (error, reason) => {
  const safe = admissionTransportFailure('CONTROL', error)
  expect(safe.code).toBe(`ADMISSION_CONTROL_${reason}`)
  expect(safe.message).not.toContain('secret')
  expect(safe.message).not.toContain('private')
})
