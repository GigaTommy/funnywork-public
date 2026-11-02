/** Model links carry only a deployment ID, never an inference endpoint or credential. */
export function readUsageDeployment(query: Record<string, unknown>): string {
  const value = query.deployment;
  if (value === undefined) return '';
  if (typeof value !== 'string' || !/^[-A-Za-z0-9_]{1,64}$/.test(value)) throw new Error('Invalid deployment link');
  return value;
}
