/** FunnyWork fixed-version file publishing; legacy present remains path-only. */
import { brandString } from '@deepseek-ai/dsh-brand'
import type { OfficeSourceKey } from '@deepseek-ai/dsh-office-to-pdf'
import { randomUUID } from 'node:crypto'
import { basename, extname } from 'node:path'
import type { Context } from '@deepseek-ai/cordis'
import z from '@deepseek-ai/schemastery'
import { z as schema } from 'zod'
import { defineTool, type ToolExecution } from '@deepseek-ai/dsh-tools'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import type {} from '@deepseek-ai/dsh-agent'
import type {} from '@deepseek-ai/dsh-fs'
import type {} from '@deepseek-ai/dsh-attachment'
import type {} from '@deepseek-ai/dsh-office-to-pdf'
import type {} from '@deepseek-ai/dsh-system-prompt'
import type { Session } from '@deepseek-ai/dsh-session'
import type { PublishedResult, PublishedResultId, PresentedFile, ResultVariant } from './types.ts'
import { workbookPreview } from './workbook.ts'

/** Loader identity of the fixed-version publisher. */
export const name = 'publish-result'
/** Required authoritative services; conversion is optional. */
export const inject = ['tools', 'fs', 'attachments', 'sessionProjections', 'systemPrompt']
/** Deployment limits for publication and speculative previews. */
export interface Config {
  maxBytes: number
  maxPreviewBytes: number
  maxPreviewTotalBytes: number
  maxResults: number
  maxWorkbookCells: number
  workbookTimeoutMs: number
  workbookHeapMiB: number
}
/** Capacity matches the initial cross-device object limit. */
export const Config: z<Config> = z.object({
  maxBytes: z.number().min(1).default(32 * 1024 * 1024),
  maxPreviewBytes: z.number().min(1).default(32 * 1024 * 1024),
  maxPreviewTotalBytes: z.number().min(1).default(64 * 1024 * 1024),
  maxResults: z.number().min(1).default(100),
  maxWorkbookCells: z.number().min(1).default(250000),
  workbookTimeoutMs: z.number().min(1).max(120000).default(30000),
  workbookHeapMiB: z.number().min(64).max(2048).default(512),
})

const attachmentSchema = schema.object({
  attachmentId: schema.string().transform(value => brandString<PublishedResult['original']['attachment']['attachmentId']>(value)),
  name: schema.string(), bytes: schema.number(),
})
const fileSchema = schema.object({ type: schema.literal('file'), attachment: attachmentSchema })
const resultSchema = schema.object({
  schema: schema.literal(1), id: schema.string().transform(value=>brandString<PublishedResultId>(value)),
  version: schema.number(), turn: schema.number(), previousHash: schema.string().optional(), original: fileSchema,
  variants: schema.array(schema.object({ role: schema.enum(['pdf','thumbnail','workbook','sheet']), key: schema.string(), content: fileSchema })), previewState: schema.enum(['pending','ready','failed']), warnings: schema.array(schema.string()) })
// The cache is private JSON; branded attachment ids are validated by the authoritative store on use.
const projection = {
  key: 'publishedResults', stateVersion: 1,
  stateSchema: schema.record(schema.string(), resultSchema.transform(({ previousHash,...value }) => ({
    ...value,...previousHash === undefined ? {} : { previousHash },
  }))),
  init: (): Record<string, PublishedResult> => ({}),
  apply: (state: Record<string, PublishedResult>, event: import('@deepseek-ai/dsh-session').SessionEvent): Record<string, PublishedResult> => {
    if (event.type !== 'deliverables/presented') return state
    let next = state
    for (const file of event.data.files) if (file.result !== undefined) {
      const current=next[file.result.id]
      next = { ...next, [`${file.result.id}:${file.result.version}`]: file.result,
        ...current===undefined||current.version<=file.result.version?{ [file.result.id]:file.result }:{} }
    }
    return next
  },
} as const
// Shared storage owns the preview admission queue across all scoped publisher plugins.
const previewQueues=new WeakMap<import('@deepseek-ai/dsh-attachment').AttachmentStore,Promise<void>>()

/** Install publication, preview retry and their recoverable native declarations.
 * @param ctx - scoped tool services.
 * @param config - publication capacities.
 */
export function apply(ctx: Context, config: Config): void {
  for (const value of Object.values(config)) if (!Number.isSafeInteger(value) || value < 1) throw new Error('Result capacities must be positive integers')
  ctx.sessionProjections.register(projection)
  ctx.systemPrompt.section({ name:'funnywork:fixed-results',order:ctx.systemPrompt.getSectionOrder('DELIVERABLE_FILE_REFERENCES')+1,
    text:'When delivering a generated Word, Excel, PowerPoint or PNG file, use publish_result instead of present to preserve a fixed downloadable version for connected devices. For a requested revision, inspect_result reads the fixed original. Never edit its attachment path: copy to a new workspace file, edit the copy, then use the provided resultId and expectedVersion to publish. Preview failure does not mean the original file was lost; prepare_result_preview can retry it. Do not publish intermediate scripts or unrelated workspace files.' })
  const pending = new WeakMap<ToolExecution, { session: Session; turn: number; file: PresentedFile }>()
  const jobs = new Set<Promise<void>>()
  const active = new Set<string>()
  const lifetime = new AbortController()
  ctx.effect(() => async () => { lifetime.abort(); await Promise.allSettled(jobs) })
  async function bytes(result: PublishedResult): Promise<Uint8Array> {
    const chunks: Uint8Array[] = []; let total = 0
    for await (const chunk of ctx.attachments.readFileStream(result.original.attachment, lifetime.signal)) {
      total += chunk.length; if (total > config.maxBytes) throw new Error('Result exceeds preview capacity')
      chunks.push(chunk)
    }
    const data = new Uint8Array(total); let offset = 0
    for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length }
    return data
  }
  function schedule(session: Session, turn: number, file: PresentedFile, callId: import('@deepseek-ai/dsh-llm/brand').ToolCallId): void {
    const result = file.result
    if (result === undefined) return
    const key = `${session.id}:${result.id}:${result.version}`
    if (active.has(key)) return
    active.add(key)
    const prior=previewQueues.get(ctx.attachments)??Promise.resolve()
    const task = prior.catch(() => {}).then(async () => {
      const variants: ResultVariant[] = [...result.variants]; const warnings: string[] = []
      async function save(role: ResultVariant['role'], key: string, data: Uint8Array, name: string): Promise<void> {
        if (data.byteLength > config.maxPreviewBytes) throw new Error('Preview exceeds synchronization capacity')
        if (variants.some(value => value.key === key)) return
        if(variants.reduce((total,value)=>total+value.content.attachment.bytes,0)+data.byteLength>config.maxPreviewTotalBytes)throw new Error('Combined previews exceed synchronization capacity')
        const attachment = await ctx.attachments.saveFile({ data, name })
        lifetime.signal.throwIfAborted(); variants.push({ role, key, content: { type: 'file', attachment } })
      }
      try {
        lifetime.signal.throwIfAborted()
        const data = await bytes(result), extension = extname(result.original.attachment.name).slice(1).toLowerCase()
        if (extension === 'png') {
          const { default: sharp } = await import('sharp')
          const thumbnail = await sharp(data, { limitInputPixels: 40000000 }).rotate().resize(720,720,{ fit: 'inside', withoutEnlargement: true }).png().toBuffer()
          await save('thumbnail','thumbnail',thumbnail,'preview.png')
        } else {
          if (extension === 'xlsx') {
            const workbook = await workbookPreview(data, config.maxWorkbookCells, lifetime.signal, {
              timeoutMs: config.workbookTimeoutMs, heapMiB: config.workbookHeapMiB,
              maxOutputBytes: Math.min(config.maxPreviewTotalBytes, 64 * 1024 * 1024),
            })
            for (const part of workbook.files) await save(part.role,part.key,part.data,part.name)
            warnings.push(...workbook.warnings)
          }
          const converter = ctx.get('officeToPdf')
          if (converter !== undefined && (extension === 'docx' || extension === 'pptx' || extension === 'xlsx')) {
            const converted = await converter.convert({ extension, priority: 'background', source: { key: brandString<OfficeSourceKey>(String(result.original.attachment.attachmentId)), version: String(result.version), bytes: data.byteLength, read: () => Promise.resolve({ bytes: data, version: String(result.version) }) } }, lifetime.signal)
            await save('pdf','pdf',converted.pdf,'preview.pdf')
            if(converted.thumbnail!==undefined)await save('thumbnail','thumbnail',converted.thumbnail,'preview.png')
            if(converted.thumbnailWarning!==undefined)warnings.push(converted.thumbnailWarning)
            if (converted.missingFonts.length > 0) warnings.push('missing-fonts')
          } else if (extension !== 'xlsx') throw new Error('Preview converter is unavailable')
        }
        lifetime.signal.throwIfAborted()
        session.append('deliverables/presented',{ turn,callId,files:[{ ...file,result:{ ...result,variants,previewState:'ready',warnings } }] })
      } catch {
        if (!lifetime.signal.aborted) session.append('deliverables/presented',{ turn,callId,files:[{ ...file,result:{ ...result,variants,previewState:'failed',warnings:[...warnings,'preview-unavailable'] } }] })
      }
    }).catch((error: unknown) => { ctx.logger.warn('Result preview event could not be persisted: %s', error) }).finally(() => { active.delete(key); jobs.delete(task); if(previewQueues.get(ctx.attachments)===task)previewQueues.delete(ctx.attachments) })
    previewQueues.set(ctx.attachments,task)
    jobs.add(task)
  }
  ctx.tools.register(defineTool({
    name:'publish_result',description:'Publish an existing Word, Excel, PowerPoint or PNG file as a fixed downloadable result. Call after creating the file. To revise an earlier result, provide its resultId and expectedVersion. Publishing preserves the original bytes; preview preparation can finish later.',
    parameters:{ path:{ type:'string',required:true },description:{ type:'string' },resultId:{ type:'string' },expectedVersion:{ type:'integer' } },
    output:{ schema:{ type:'object',additionalProperties:false,properties:{ resultId:{ type:'string',required:true },version:{ type:'integer',required:true },name:{ type:'string',required:true },sha256:{ type:'string',required:true } } },render:(_args,value)=>[{ type:'text',text:`Published ${value.name}, result ${value.resultId}, version ${value.version}. Original saved; synchronization and preview preparation are separate.` }] },
    async execute(args,exec){
      const session=exec.agent?.session,cwd=session?.header.cwd,boundary=session===undefined?undefined:ctx.sessionProjections.stateOf(session,'turnBoundary')
      if(session===undefined||cwd===undefined||boundary?.openTurnStartSeq==null)throw new Error('Publishing requires a workspace and an open turn')
      if(!['.docx','.xlsx','.pptx','.png'].includes(extname(args.path).toLowerCase()))throw new Error('Publish a docx, xlsx, pptx or png file')
      const state=ctx.sessionProjections.stateOf(session,'publishedResults')??{},previous=args.resultId===undefined||!Object.hasOwn(state,args.resultId)?undefined:state[args.resultId]
      if(args.resultId!==undefined&&(previous===undefined||previous.version!==args.expectedVersion))throw new Error('Result version changed; read the current result before revising')
      if(Object.keys(state).filter(key=>key.includes(':')).length>=1000)throw new Error('Stored result version capacity reached')
      if(previous!==undefined&&previous.version>=1000)throw new Error('Result version limit reached')
      if(args.resultId===undefined&&Object.keys(state).filter(key=>!key.includes(':')).length>=config.maxResults)throw new Error('Result count exceeds the configured capacity')
      const before=await ctx.fs.lstat(args.path,{ cwd },exec.signal)
      if(before?.type!=='file')throw new Error('Publish an existing regular file')
      const target=await ctx.fs.resolve(args.path,{ cwd,signal:exec.signal }),info=await ctx.fs.stat(target,exec.signal)
      if(info===undefined||info.type!=='file')throw new Error('Result source is unavailable')
      if(info.size!==undefined&&info.size>config.maxBytes)throw new Error('Result exceeds synchronization capacity')
      const source=async function*():AsyncIterable<Uint8Array>{
        let offset=0
        while(true){
          exec.signal.throwIfAborted()
          const chunk=await ctx.fs.readByteRange(target,{ offset,length:Math.min(384000,config.maxBytes-offset+1) },exec.signal)
          if(chunk.length===0)break
          offset+=chunk.length;if(offset>config.maxBytes)throw new Error('Result exceeds synchronization capacity')
          yield chunk
        }
        const after=await ctx.fs.stat(target,exec.signal)
        if(after?.version!==info.version||info.size!==undefined&&offset!==info.size)throw new Error('Result source changed while publishing')
      }
      const attachment=await ctx.attachments.saveFileStream({ data:source(),name:basename(args.path),signal:exec.signal })
      exec.signal.throwIfAborted()
      const path=ctx.attachments.fileHostPath(attachment)
      if(path===undefined)throw new Error('Fixed result storage requires a host file provider')
      const result:PublishedResult={ schema:1,id:previous?.id??brandString<PublishedResultId>(randomUUID()),
        version:(previous?.version??0)+1,turn:boundary.lastTurn,
        ...previous===undefined?{}:{ previousHash:previous.original.attachment.attachmentId.slice(7) },
        original:{ type:'file',attachment },variants:[],previewState:'pending',warnings:[],
      }
      pending.set(exec,{ session,turn:boundary.lastTurn,
        file:{ path,...args.description===undefined?{}:{ description:args.description },result },
      })
      return{ resultId:result.id,version:result.version,name:attachment.name,sha256:attachment.attachmentId.slice(7) }
    },
  }))
  ctx.tools.register(defineTool({
    name:'inspect_result',description:'Read the fixed original path and revision of a published result before applying version-specific feedback. Copy it to a new workspace file before editing; never overwrite the attachment path. Omit version for the latest revision.',
    parameters:{ resultId:{ type:'string',required:true },version:{ type:'integer' } },
    output:{ schema:{ type:'object',additionalProperties:false,properties:{ resultId:{ type:'string',required:true },version:{ type:'integer',required:true },path:{ type:'string',required:true },sha256:{ type:'string',required:true } } },render:(_args,value)=>[{ type:'text',text:`Result ${value.resultId}, version ${value.version}: ${value.path}, SHA-256 ${value.sha256}. Read this fixed original before revising.` }] },
    execute(args,exec){
      const session=exec.agent?.session
      if(session===undefined)throw new Error('Result inspection requires the owning Session')
      const state=ctx.sessionProjections.stateOf(session,'publishedResults')??{}
      const key=args.version===undefined?args.resultId:`${args.resultId}:${args.version}`
      const result=Object.hasOwn(state,key)?state[key]:undefined
      if(result===undefined)throw new Error('Result version is unavailable in this Session')
      const path=ctx.attachments.fileHostPath(result.original.attachment)
      if(path===undefined)throw new Error('Original result is unavailable')
      return Promise.resolve({ resultId:result.id,version:result.version,path,sha256:result.original.attachment.attachmentId.slice(7) })
    },
  }))
  ctx.tools.register(defineTool({
    name:'prepare_result_preview',description:'Retry preview preparation for a published result without changing its original file. Use the resultId and version returned by publish_result.',
    parameters:{ resultId:{ type:'string',required:true },version:{ type:'integer',required:true } },
    output:{ schema:{ type:'object',additionalProperties:false,properties:{ resultId:{ type:'string',required:true },version:{ type:'integer',required:true } } },render:(_args,value)=>[{ type:'text',text:`Preparing preview for result ${value.resultId}, version ${value.version}; original unchanged.` }] },
    execute(args,exec){
      const session=exec.agent?.session
      if(session===undefined)throw new Error('Preview preparation requires the owning Session')
      const state=ctx.sessionProjections.stateOf(session,'publishedResults'),key=`${args.resultId}:${args.version}`,result=state!==undefined&&Object.hasOwn(state,key)?state[key]:undefined
      const boundary=ctx.sessionProjections.stateOf(session,'turnBoundary')
      if(result===undefined||result.version!==args.version||boundary?.openTurnStartSeq==null)throw new Error('Result version is not current in this Session')
      const path=ctx.attachments.fileHostPath(result.original.attachment)
      if(path===undefined)throw new Error('Original result is unavailable')
      pending.set(exec,{ session,turn:result.turn,file:{ path,result:{ ...result,previewState:'pending' } } })
      return Promise.resolve({ resultId:result.id,version:result.version })
    },
  }))
  ctx.on('agent/created',({ agent })=>{
    const state=ctx.sessionProjections.stateOf(agent.session,'publishedResults')??{}
    for(const [key,result] of Object.entries(state))if(key.includes(':')&&result.previewState==='pending'){
      const path=ctx.attachments.fileHostPath(result.original.attachment)
      if(path!==undefined)schedule(agent.session,result.turn,{ path,result },ToolCallId(`preview:${result.id}:${result.version}`))
    }
  })
  ctx.on('tools/result',(exec,outcome)=>{
    const publication=pending.get(exec);pending.delete(exec)
    if(publication===undefined||outcome.isError)return
    publication.session.append('deliverables/presented',{ turn:publication.turn,callId:exec.callId,files:[publication.file] })
    schedule(publication.session,publication.turn,publication.file,exec.callId)
  })
}
