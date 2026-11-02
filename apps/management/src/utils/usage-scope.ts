type UsageScope = {from: unknown; until: unknown; zones: unknown};

/** Pages may only be combined when they describe the same period and authorized zones. */
export function sameUsageScope(previous: UsageScope, next: UsageScope): boolean {
  const zones = (value: unknown): string | null =>
    Array.isArray(value) && value.every(zone => typeof zone === 'string')
      ? JSON.stringify([...new Set(value)].sort()) : null;
  const before = zones(previous.zones), after = zones(next.zones);
  return before !== null && after !== null && before === after
    && previous.from === next.from && previous.until === next.until;
}
