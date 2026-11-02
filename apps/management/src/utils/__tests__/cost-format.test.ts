import { describe, it, expect } from 'vitest';
import { formatCostMicros } from '../cost-format';
describe('exact ledger money formatting', () => {
  it('preserves micro-CNY, zero and amounts beyond the JS safe integer range', () => {
    expect(formatCostMicros('0')).toBe('¥0.000000');
    expect(formatCostMicros('28')).toBe('¥0.000028');
    expect(formatCostMicros('9007199254740993')).toBe('¥9,007,199,254.740993');
  });
  it('keeps unavailable or unsafe values unknown instead of claiming zero cost', () => {
    for (const value of [undefined, null, '', 'NaN', -1, 0.1, Number.MAX_SAFE_INTEGER + 1])
      expect(formatCostMicros(value, 'Unknown')).toBe('Unknown');
  });
});
