import { describe, it, expect } from 'vitest';
import { readUsageDeployment } from '../model-usage-context';
describe('model-to-usage context', () => {
  it('accepts one deployment ID and ignores unrelated route context', () => {
    expect(readUsageDeployment({ deployment: 'deployment-123', tab: 'models' })).toBe('deployment-123');
    expect(readUsageDeployment({})).toBe('');
  });
  it('rejects ambiguous, empty and external links without broadening the report', () => {
    for (const deployment of [null, '', [], ['a', 'b'], 'https://example.com', '../models', 'a'.repeat(65)])
      expect(() => readUsageDeployment({ deployment })).toThrow();
  });
});
