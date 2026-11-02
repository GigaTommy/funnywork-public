/** HTTP failures of the authenticated account synchronization capability. */
export class HistorySyncHttpError extends Error {
  /** @param status - service response status, without credentials or response content. */
  constructor(readonly status: number) {
    super(`History sync failed (${status})`)
  }
}
