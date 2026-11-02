/** Durable file deliveries produced by the present tool. */
import type { ToolCallId } from '@deepseek-ai/dsh-llm/brand'
import type { FileAttachmentRef } from '@deepseek-ai/dsh-attachment'
import type { Branded } from '@deepseek-ai/dsh-brand'

/** Opaque identity of one logical cross-device delivery. */
export type PublishedResultId = Branded<'PublishedResultId'>

/** One independently transferable representation of a fixed result version. */
export interface ResultVariant {
  role: 'pdf' | 'thumbnail' | 'workbook' | 'sheet'
  key: string
  content: { type: 'file'; attachment: FileAttachmentRef }
}

/** Account replication metadata; source bytes never change after publication. */
export interface PublishedResult {
  schema: 1
  id: PublishedResultId
  version: number
  turn: number
  previousHash?: string
  original: { type: 'file'; attachment: FileAttachmentRef }
  variants: ResultVariant[]
  previewState: 'pending' | 'ready' | 'failed'
  warnings: string[]
}

/** A declared filesystem file whose current contents remain at its source path. */
export interface PresentedFile {
  /** Original absolute path or path relative to the Session working directory. */
  path: string
  /** Optional description supplied by the model. */
  description?: string
  /** Optional fixed version published by FunnyWork; legacy path declarations omit this field. */
  result?: PublishedResult
}

declare module '@deepseek-ai/dsh-session-projection/types' {
  interface SessionProjectionStateMap {
    /** Current complete result manifests, folded from their native declarations. */
    publishedResults: Record<string, PublishedResult>
  }
}

declare module '@deepseek-ai/dsh-session/types' {
  interface SessionEventMap {
    /** Declared filesystem files from a successful final present result, including nested calls. */
    'deliverables/presented': { turn: number; callId: ToolCallId; files: PresentedFile[] }
  }
}
