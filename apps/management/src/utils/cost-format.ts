/** Money arrives as exact micro-CNY strings; unavailable or unsafe values stay unknown. */
export function formatCostMicros(value: unknown, unknownLabel = '未知'): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return unknownLabel;
  if (typeof value !== 'string' && typeof value !== 'number') return unknownLabel;
  const text = String(value);
  if (!/^\d+$/.test(text)) return unknownLabel;
  const amount = BigInt(text),
    whole = (amount / 1000000n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return '¥' + whole + '.' + (amount % 1000000n).toString().padStart(6, '0');
}
