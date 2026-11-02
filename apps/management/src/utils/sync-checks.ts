export const syncPlatforms = ['macos', 'windows', 'linux'] as const;
export type SyncPlatform = (typeof syncPlatforms)[number];
/** Verification authority with the fixed Codex observation executor, not native task execution. */
export function syncPreviewRequest(repositoryId: string, platform: SyncPlatform, after = '') {
  return { repositoryId, action: 'VERIFY', purpose: 'SYNC_CHECK', platform, after, query: '' } as const;
}
export interface SyncSelection {
  repositoryId: string;
  baseSha: string;
  expectedVersion: string;
  devices: Record<SyncPlatform, string>;
}
export interface SyncCapability {
  deviceId: string;
  platform: string;
  available: boolean;
  online: boolean;
  executors?: { executor: string; protocolVersion: number; executorVersion: string; capabilities: string[] }[];
}
export const syncPlatform = (value: string) => (value === 'darwin' ? 'macos' : value === 'win32' ? 'windows' : value);
export function syncRequest(selection: SyncSelection, capabilities: Partial<Record<SyncPlatform, SyncCapability>>) {
  if (
    !selection.repositoryId ||
    !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(selection.baseSha) ||
    selection.expectedVersion.length > 80 ||
    !/^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?$/.test(selection.expectedVersion)
  )
    return undefined;
  const ids = syncPlatforms.map((platform) => selection.devices[platform]);
  if (ids.some((id) => !id) || new Set(ids).size !== 3) return undefined;
  if (
    syncPlatforms.some((platform) => {
      const capability = capabilities[platform];
      return (
        !capability?.available ||
        !capability.online ||
        capability.deviceId !== selection.devices[platform] ||
        capability.platform !== platform ||
        !capability.executors?.some(
          (executor) => executor.executor === 'funnywork-codex-v1' && executor.protocolVersion === 1 && executor.capabilities.includes('sync-check')
        )
      );
    })
  )
    return undefined;
  return {
    repositoryId: selection.repositoryId,
    baseSha: selection.baseSha,
    expectedVersion: selection.expectedVersion,
    executor: 'funnywork-codex-v1',
    protocolVersion: 1,
    targets: syncPlatforms.map((platform) => ({ deviceId: selection.devices[platform], platform }))
  };
}
export const syncStates: Record<string, string> = {
  QUEUED: '等待终端',
  CLAIMED: '检查中',
  CHECKED: '已检查',
  UNAVAILABLE: '环境不可用',
  FAILED: '检查失败',
  UNKNOWN: '停止状态未知，持续隔离',
  BLOCKED: '授权或运行期变化，已阻止',
  EXPIRED: '等待超时',
  STOPPED: '已确认停止'
};
