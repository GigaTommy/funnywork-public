/** Isolated saved-value parsing. Termination contains parser OOM, long work and cancellation. */
import { Worker } from 'node:worker_threads'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { z } from 'zod'

/** Independent parser lifetime and V8 heap ceiling; external bytes are bounded by archive limits. */
export interface WorkbookParserLimits {
  timeoutMs: number
  heapMiB: number
  /** Combined JSON byte budget, at most 64 MiB; checked before buffers cross to the host. */
  maxOutputBytes?: number
}
const resultSchema = z.object({
  files: z.array(z.object({
    role: z.enum(['workbook', 'sheet']), key: z.string(), name: z.string(), data: z.instanceof(Uint8Array),
  })).max(251),
  warnings: z.array(z.string()).max(20),
})
// The module URL is package-owned; spreadsheet contents are data, never executable code.
const bootstrap = `
import { parentPort, workerData } from 'node:worker_threads';
import { getHeapStatistics } from 'node:v8';
try {
  // Host V8 flags can override Worker limits; reject instead of silently parsing with an unbounded heap.
  if (getHeapStatistics().heap_size_limit > (workerData.heapMiB + 64) * 1024 * 1024) {
    throw new Error('Workbook parser memory capacity cannot be enforced under host V8 configuration');
  }
  const { parseWorkbook } = await import(workerData.moduleURL);
  const result = await parseWorkbook(workerData.data, workerData.maxCells, new AbortController().signal, workerData.maxOutputBytes);
  parentPort.postMessage({ result }, result.files.map(file => file.data.buffer));
} catch (error) {
  parentPort.postMessage({ error: error instanceof Error ? error.message : String(error) });
}
`

/** Produce sparse previews in an owned Worker, preserving the caller's original bytes.
 * @param data - fixed XLSX contents.
 * @param maxCells - visited-cell and display-area ceiling.
 * @param signal - cancellation terminates and drains the Worker before rejection.
 * @param limits - defaults to 30 seconds and a 512 MiB old-generation heap.
 * @returns independently transferable workbook index and windows.
 * @throws if parsing exceeds capacity, time, memory, or is cancelled; source remains intact.
 */
export async function workbookPreview(
  data: Uint8Array, maxCells: number, signal: AbortSignal,
  limits: WorkbookParserLimits = { timeoutMs: 30000, heapMiB: 512 },
): Promise<z.infer<typeof resultSchema>> {
  signal.throwIfAborted()
  const maxOutputBytes = limits.maxOutputBytes ?? 64 * 1024 * 1024
  if (!Number.isSafeInteger(maxCells) || maxCells < 1 || data.byteLength > 32 * 1024 * 1024
    || !Number.isSafeInteger(limits.timeoutMs) || limits.timeoutMs < 1 || limits.timeoutMs > 120000
    || !Number.isSafeInteger(limits.heapMiB) || limits.heapMiB < 16 || limits.heapMiB > 2048
    || !Number.isSafeInteger(maxOutputBytes) || maxOutputBytes < 1 || maxOutputBytes > 64 * 1024 * 1024) {
    throw new Error('Invalid workbook parser capacity')
  }
  const source = new URL(import.meta.url).pathname.endsWith('.ts')
  const moduleURL = new URL(source ? './workbook-parse.ts' : './workbook-parse.js', import.meta.url).href
  // Source runs use the repository's ESM launcher; packaged runs load compiled JS directly.
  const execArgv = source ? ['--import', pathToFileURL(createRequire(import.meta.url).resolve('tsx/esm')).href] : []
  const owned = new Uint8Array(data)
  const worker = new Worker(new URL('data:text/javascript,' + encodeURIComponent(bootstrap)), {
    workerData: { moduleURL, data: owned, maxCells, maxOutputBytes, heapMiB: limits.heapMiB }, transferList: [owned.buffer], execArgv,
    resourceLimits: { maxOldGenerationSizeMb: limits.heapMiB, maxYoungGenerationSizeMb: 16, stackSizeMb: 4 },
  })
  return await new Promise<z.infer<typeof resultSchema>>((resolve, reject) => {
    let settled = false
    const finish = (error: unknown, result?: z.infer<typeof resultSchema>): void => {
      if (settled) return
      settled = true; clearTimeout(timer); signal.removeEventListener('abort', aborted)
      void worker.terminate().then(() => {
        worker.removeAllListeners()
        if (error !== undefined) reject(error instanceof Error ? error : new Error(typeof error === 'string' ? error : 'Workbook parser failed'))
        else if (result !== undefined) resolve(result)
        else reject(new Error('Workbook parser returned no result'))
      }, reject)
    }
    const aborted = (): void => { finish(signal.reason ?? new DOMException('Workbook parsing cancelled', 'AbortError')) }
    const timer = setTimeout(() => { finish(new Error('Workbook preview parser timed out')) }, limits.timeoutMs)
    worker.once('error', (error: Error) => {
      const memory = 'code' in error && error.code === 'ERR_WORKER_OUT_OF_MEMORY'
      finish(new Error(memory ? 'Workbook preview exceeds parser memory capacity' : error.message))
    })
    worker.once('exit', (code) => { finish(new Error(`Workbook parser exited before completion (${code})`)) })
    worker.once('message', (message: unknown) => {
      if (typeof message !== 'object' || message === null) { finish(new Error('Invalid workbook parser response')); return }
      if ('error' in message && typeof message.error === 'string') { finish(new Error(message.error)); return }
      const parsed = resultSchema.safeParse('result' in message ? message.result : undefined)
      if (parsed.success) finish(undefined, parsed.data)
      else finish(new Error('Invalid workbook parser response'))
    })
    signal.addEventListener('abort', aborted, { once: true })
    if (signal.aborted) aborted()
  })
}
