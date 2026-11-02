/** Keep pagination arithmetic outside the SFC transform and reject invalid page values. */
export function directoryPreviewOffset(page: number): number {
  if (!Number.isSafeInteger(page) || page < 1) return 0;
  return page * 50 - 50;
}
