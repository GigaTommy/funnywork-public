/** Status-only failure of the authenticated model entry; never carries response secrets. */
export class ModelEntryHttpError extends Error {
  constructor(readonly status: number) {
    super(`Enterprise model entry returned ${status}`)
  }
}
