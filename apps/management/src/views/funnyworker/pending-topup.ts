/** A pending credit survives page reloads until the server confirms its receipt. */
export type PendingTopup = { owner: string; amountMicros: number; requestId: string };
const prefix = 'funnywork:model-topup:';
export function readPendingTopup(owner: string, storage: Storage = localStorage): PendingTopup | undefined {
  const raw = storage.getItem(prefix + owner);
  if (raw === null) return;
  const value = JSON.parse(raw);
  if (value.owner !== owner || !Number.isSafeInteger(value.amountMicros) || value.amountMicros <= 0 || value.amountMicros > 1000000000000 || typeof value.requestId !== 'string' || !/^[A-Za-z0-9_-]{16,64}$/.test(value.requestId)) throw new Error('Invalid pending top-up');
  return value;
}
export function savePendingTopup(value: PendingTopup, storage: Storage = localStorage): void {
  storage.setItem(prefix + value.owner, JSON.stringify(value));
}
export function clearPendingTopup(value: PendingTopup, storage: Storage = localStorage): void {
  if (readPendingTopup(value.owner, storage)?.requestId === value.requestId) storage.removeItem(prefix + value.owner);
}
