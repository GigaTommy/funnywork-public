/** Provision complete model and policy state before starting the account's workspace. */
import type { FunnyWorkerAccount } from './funnyworker-account.ts'
import { prepareFunnyWorkerInference, prepareFunnyWorkerInferenceDevice } from './funnyworker-inference.ts'
import { prepareFunnyWorkerPolicy, prepareFunnyWorkerPolicyDevice } from './funnyworker-policy.ts'
import type { FunnyWorkerStartupRecorder } from './funnyworker-startup-timing.ts'

/**
 * Merge authenticated configuration requests where supported; legacy initialization remains parallel.
 * @param home - Account-isolated data directory.
 * @param account - Authenticated account with the existing permission and budget transport.
 * @param version - Packaged client version for policy receipts.
 * @param timing - Optional recorder containing no account identifiers.
 * @returns Complete model and policy lifecycles; a partial credential is revoked before rejection.
 */
export async function prepareFunnyWorkerStartup(home: string, account: FunnyWorkerAccount, version: string,
  timing?: FunnyWorkerStartupRecorder): Promise<{
  inference: Awaited<ReturnType<typeof prepareFunnyWorkerInference>>
  policy: Awaited<ReturnType<typeof prepareFunnyWorkerPolicy>>
}> {
  const [modelDevice, policyDevice] = await Promise.all([
    prepareFunnyWorkerInferenceDevice(home), prepareFunnyWorkerPolicyDevice(home),
  ])
  const seed: unknown = await account.startupRequest?.({ modelDevice, policyDevice })
  let inferenceSeed: { bootstrap: unknown; credential: unknown; catalog: unknown } | undefined
  let policySeed: { value: unknown } | undefined
  if (seed !== undefined) {
    if (typeof seed !== 'object' || seed === null || !('schema' in seed) || seed.schema !== 1
      || !('inference' in seed) || typeof seed.inference !== 'object' || seed.inference === null
      || !('bootstrap' in seed.inference) || !('credential' in seed.inference) || !('catalog' in seed.inference)
      || !('policy' in seed)) {
      await account.inferenceRequest?.('credentials/revoke', { deviceId: modelDevice }).catch((_revocationUnavailable: unknown) => { /* Expiry bounds unreachable credentials. */ })
      throw new Error('Invalid combined startup configuration')
    }
    inferenceSeed = { bootstrap: seed.inference.bootstrap, credential: seed.inference.credential, catalog: seed.inference.catalog }
    policySeed = { value: seed.policy }
  }
  const measure = <T>(stage: 'model-configuration' | 'organization-policy', action: () => Promise<T>): Promise<T> =>
    timing === undefined ? action() : timing.measure(stage, action)
  const [inference, policy] = await Promise.allSettled([
    measure('model-configuration', () => prepareFunnyWorkerInference(home, account, inferenceSeed)),
    measure('organization-policy', () => prepareFunnyWorkerPolicy(home, account, version, policySeed)),
  ])
  if (inference.status === 'fulfilled' && policy.status === 'fulfilled') return { inference: inference.value, policy: policy.value }
  if (inference.status === 'fulfilled') await inference.value.dispose()
  if (inference.status === 'rejected') {
    if (seed !== undefined) await account.inferenceRequest?.('credentials/revoke', { deviceId: modelDevice }).catch((_revocationUnavailable: unknown) => { /* Expiry bounds unreachable credentials. */ })
    throw inference.reason
  }
  if (policy.status === 'rejected') throw policy.reason
  throw new Error('Startup initialization failed')
}
