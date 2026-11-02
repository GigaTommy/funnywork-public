import { describe, expect, it } from 'vitest';
import { syncRequest, syncPreviewRequest, syncPlatforms, syncPlatform, syncStates, type SyncSelection, type SyncCapability } from '../sync-checks';
function fixture() {
  const selection: SyncSelection = {
    repositoryId: 'repo',
    baseSha: 'a'.repeat(40),
    expectedVersion: '1.2.3',
    devices: { macos: 'mac', windows: 'win', linux: 'lin' }
  };
  const capabilities = Object.fromEntries(
    syncPlatforms.map((platform) => [
      platform,
      {
        platform,
        deviceId: selection.devices[platform],
        available: true,
        online: true,
        executors: [{ executor: 'funnywork-codex-v1', protocolVersion: 1, executorVersion: '1', capabilities: ['sync-check'] }]
      }
    ])
  ) as Record<(typeof syncPlatforms)[number], SyncCapability>;
  return { selection, capabilities };
}
describe('bounded three-platform sync request', () => {
  it('previews each platform through VERIFY with the fixed read-only purpose', () => {
    for (const platform of syncPlatforms) {
      expect(syncPreviewRequest('repo', platform, 'cursor')).toEqual({ repositoryId: 'repo', action: 'VERIFY', purpose: 'SYNC_CHECK', platform, after: 'cursor', query: '' });
    }
  });
  it('creates exactly three explicit targets without prompt, command or write options', () => {
    const { selection, capabilities } = fixture();
    const body = syncRequest(selection, capabilities)!;
    expect(body.targets.map((target) => target.platform)).toEqual(['macos', 'windows', 'linux']);
    expect(Object.keys(body).sort()).toEqual(['baseSha', 'executor', 'expectedVersion', 'protocolVersion', 'repositoryId', 'targets']);
    selection.devices.macos = 'different';
    expect(body.targets[0].deviceId).toBe('mac');
  });
  it('rejects missing platforms, duplicate devices and branch names', () => {
    const { selection, capabilities } = fixture();
    selection.devices.linux = '';
    expect(syncRequest(selection, capabilities)).toBeUndefined();
    selection.devices.linux = 'mac';
    expect(syncRequest(selection, capabilities)).toBeUndefined();
    selection.devices.linux = 'lin';
    selection.baseSha = 'main';
    expect(syncRequest(selection, capabilities)).toBeUndefined();
  });
  it('fails closed on stale selection, offline device, unknown protocol or missing capability', () => {
    for (const patch of [{ deviceId: 'stale' }, { online: false }, { available: false }, { platform: 'windows' }, { executors: [] }]) {
      const { selection, capabilities } = fixture();
      Object.assign(capabilities.macos, patch);
      expect(syncRequest(selection, capabilities)).toBeUndefined();
    }
    const { selection, capabilities } = fixture();
    capabilities.linux.executors![0].protocolVersion = 2;
    expect(syncRequest(selection, capabilities)).toBeUndefined();
  });
  it('labels unknown as quarantined and normalizes registered desktop platform values', () => {
    expect(syncPlatform('darwin')).toBe('macos');
    expect(syncPlatform('win32')).toBe('windows');
    expect(syncStates.UNKNOWN).toContain('隔离');
  });
});
