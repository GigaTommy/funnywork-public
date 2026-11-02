/** Opt-in Typert Host endpoints for fixed Codex initialization checks. */
import type { Context } from '@deepseek-ai/cordis'
import { Remote, TypertRemoteService } from '@deepseek-ai/dsh-typert-protocol'
import { brandString } from '@deepseek-ai/dsh-brand'
import type { WorkspaceId } from '@deepseek-ai/dsh-workspace'
import { CodexMaintenanceController } from './maintenance.ts'
import type { MaintenanceStart, MaintenanceAddress, MaintenanceCursor, MaintenanceCapabilities, MaintenanceReceipt, MaintenanceEvents } from './maintenance-types.ts'

declare module '@deepseek-ai/cordis' {
  interface Context { codexMaintenance: CodexMaintenanceService }
}

/** Available only when the deployment explicitly enables maintenance checks. */
export class CodexMaintenanceService extends TypertRemoteService {
  private readonly controller: CodexMaintenanceController
  constructor(ctx: Context, disposeGraceMs: number) {
    super(ctx, 'codexMaintenance')
    this.controller = new CodexMaintenanceController({
      resolveWorkspace: id => ctx.get('workspaceRegistry')?.get(brandString<WorkspaceId>(id))?.path,
      spawn: spec => ctx.subprocess.spawn(spec),
      probeCwd: process.cwd(), disposeGraceMs,
    })
    ctx.effect(() => () => this.controller.dispose())
  }
  /** Check the actual executable without starting a model turn.
   * @returns Observed executable capabilities and whether the probe process stopped.
   */
  @Remote('capabilities')
  capabilities(): Promise<MaintenanceCapabilities> { return this.controller.capabilities() }
  /** Start a fixed bounded check in a registered workspace.
   * @param request Fixed check limits, registered workspace and runtime identity.
   * @returns Initial run identity and process state; acceptance does not imply completion.
   */
  @Remote('start')
  start(request: MaintenanceStart): MaintenanceReceipt { return this.controller.start(request) }
  /** Read bounded structured facts after a monotonic cursor.
   * @param request Run identity, runtime generation and last observed sequence number.
   * @returns Bounded events, next cursor and current process state.
   */
  @Remote('events')
  events(request: MaintenanceCursor): MaintenanceEvents { return this.controller.events(request) }
  /** Confirm process teardown after cancellation.
   * @param request Run identity and runtime generation to cancel.
   * @returns Latest run state and explicit process-stop confirmation after teardown.
   */
  @Remote('cancel')
  cancel(request: MaintenanceAddress): Promise<MaintenanceReceipt> { return this.controller.cancel(request) }
  /** Reconcile this Host generation without assuming stopped after restart.
   * @param request Run identity and runtime generation to inspect.
   * @returns Known process state, or unknown when this Host cannot confirm it.
   */
  @Remote('reconcile')
  reconcile(request: MaintenanceAddress): MaintenanceReceipt { return this.controller.reconcile(request) }
}
