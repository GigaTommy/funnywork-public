/** Client-safe fixed initialization protocol; no model execution inputs. */
/** Observed executable capability, unavailable until handshake and exit are confirmed. */
export interface MaintenanceCapabilities {
  available: boolean
  processStopped: boolean
  executor: 'funnywork-codex-v1'
  protocolVersion: 1
  executorVersion: string
  pluginVersion: string | null
  capabilities: string[]
}
/** Safe lifecycle facts; unknown never implies process termination. */
export type MaintenanceState = 'running' | 'succeeded' | 'failed' | 'cancelled' | 'unknown'
/** Fixed inspection request. Workspace resolution belongs to the Host registry. */
export interface MaintenanceStart {
  requestId: string
  workspaceId: string
  baselineSha: string
  runtimeEpoch: string
  timeoutMs: number
  maxOutputBytes: number
}
/** A run is scoped by both its dispatch identity and runtime generation. */
export interface MaintenanceAddress { runId: string; runtimeEpoch: string }
/** Monotonic, bounded polling cursor. */
export interface MaintenanceCursor extends MaintenanceAddress { after: number }
/** Process facts returned without logs, paths, prompts, or credentials. */
export interface MaintenanceReceipt extends MaintenanceAddress { state: MaintenanceState; processStopped: boolean }
/** Safe evidence event. Sequence numbers start at one. */
export interface MaintenanceEvent { seq: number; kind: 'started' | 'capability' | 'terminal'; state?: MaintenanceState; pluginVersion?: string; protocolVersion?: number }
/** Events after the requested cursor, with the latest process facts. */
export interface MaintenanceEvents extends MaintenanceReceipt { events: MaintenanceEvent[]; nextSeq: number }
