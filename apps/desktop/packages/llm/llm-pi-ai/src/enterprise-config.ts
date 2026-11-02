/** Validated deployment settings for the enterprise entry. */
import z from '@deepseek-ai/schemastery'

/** Electron-owned files and configurable transport timing. */
export interface EnterpriseOptions {
  /** Pinned enterprise origin, without a path. */
  origin: string
  /** Absolute Electron-owned scoped credential file. */
  credentialFile: string
  /** Absolute private directory for admission recovery records. */
  stateDirectory: string
  /** Authorized catalog refresh interval in milliseconds. */
  refreshMs: number
  /** Initial ticket polling delay in milliseconds. */
  pollMs: number
  /** Maximum polling backoff in milliseconds. */
  maxPollMs: number
  /** Random polling jitter fraction, from zero to one half. */
  jitterRatio: number
  /** Timeout for short admission API requests in milliseconds. */
  requestTimeoutMs: number
  /** Maximum stream idle time after admission in milliseconds. */
  streamIdleTimeoutMs: number
  /** Maximum accumulated encoded image request bytes. */
  maxRequestImageBytes: number
}
/** Plain enterprise configuration; timing fields use schema defaults when omitted. */
export type EnterpriseInput = Pick<EnterpriseOptions, 'origin' | 'credentialFile' | 'stateDirectory'> &
  Partial<Omit<EnterpriseOptions, 'origin' | 'credentialFile' | 'stateDirectory'>>

/** Schema applied before the Host mounts enterprise routes. */
export const EnterpriseSchema: z<EnterpriseInput, EnterpriseOptions> = z.object({
  origin: z.string().required(),
  credentialFile: z.string().required(),
  stateDirectory: z.string().required(),
  refreshMs: z.number().step(1).min(1000).max(2147483647).default(15000),
  pollMs: z.number().step(1).min(1).max(10000).default(1000),
  maxPollMs: z.number().step(1).min(1).max(10000).default(10000),
  jitterRatio: z.number().min(0).max(0.5).default(0.2),
  requestTimeoutMs: z.number().step(1).min(1).max(60000).default(30000),
  streamIdleTimeoutMs: z.number().step(1).min(1).max(2147483647).default(60000),
  maxRequestImageBytes: z.number().step(1).min(1).max(12 * 1024 * 1024).default(8 * 1024 * 1024),
})
