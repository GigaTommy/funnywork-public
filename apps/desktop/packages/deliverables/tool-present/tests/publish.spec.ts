/** Fixed publications execute through a Loader composition and retain original byte identities. */
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createHash } from 'node:crypto'
import { Context } from '@deepseek-ai/cordis'
import Loader from '@deepseek-ai/cordis-plugin-loader'
import Include from '@deepseek-ai/cordis-plugin-include'
import AgentRegistry, { type Agent } from '@deepseek-ai/dsh-agent'
import { unsupportedInbox } from '@deepseek-ai/dsh-agent-loop-testkit'
import LocalFileSystem from '@deepseek-ai/dsh-fs-local'
import LocalAttachments from '@deepseek-ai/dsh-attachment-local'
import { createScope, type Scope } from '@deepseek-ai/dsh-scope'
import { ToolCallId } from '@deepseek-ai/dsh-llm'
import { SESSION_FORMAT_VERSION, Session, SessionId } from '@deepseek-ai/dsh-session'
import SessionProjections from '@deepseek-ai/dsh-session-projection'
import { turnBoundaryProjectionDefinition } from '@deepseek-ai/dsh-agent-loop'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import Tools from '@deepseek-ai/dsh-tools'
import sharp from 'sharp'
import { expect, it, onTestFinished, vi } from 'vitest'
import * as Publish from '../src/publish.ts'

it('loads publication, preserves a PNG snapshot, completes its preview and refuses stale revisions',async()=>{
  const root=await mkdtemp(join(tmpdir(),'funnywork-results-')),ctx=new Context()
  onTestFinished(async()=>{try{await ctx.fiber.dispose()}finally{await rm(root,{ recursive:true,force:true })}})
  const entries=[
    { id:'prompt',name:'prompt' },{ id:'tools',name:'tools' },{ id:'agents',name:'agents' },{ id:'fs',name:'fs',config:{ cwd:root } },
    { id:'attachments',name:'attachments',config:{ dshHome:root } },{ id:'projections',name:'projections' },{ id:'publish',name:'publish',config:{ maxBytes:100000,maxPreviewBytes:100000,maxResults:5,maxWorkbookCells:1000 } },
  ]
  await writeFile(join(root,'cordis.yml'),JSON.stringify(entries.map(entry=>({ ...entry,name:`cordis:${entry.name}` }))))
  const modules=new Map<string,unknown>([['prompt',SystemPrompt],['tools',Tools],['agents',AgentRegistry],['fs',LocalFileSystem],['attachments',LocalAttachments],['projections',SessionProjections],['publish',Publish]])
  ctx.baseUrl=pathToFileURL(root).href+'/'
  await ctx.plugin(Loader);ctx.loader.builtins.include=Include
  Object.assign(ctx.loader.builtins,Object.fromEntries(modules))
  await ctx.loader.create({ name:'cordis:include',config:{ path:pathToFileURL(join(root,'cordis.yml')).href } });await ctx.loader.await()
  ctx.sessionProjections.register(turnBoundaryProjectionDefinition)
  let scope:Scope
  const id=SessionId('publisher'),session=Session.create(id,[],{ version:SESSION_FORMAT_VERSION,id,createdAt:0,cwd:root,isSeeded:false })
  const agent:Agent={ id,session,options:{},inbox:unsupportedInbox(),status:'idle',get ctx(){return scope.ctx},send:()=>{},followup:()=>{},steer:()=>({ outcome:Promise.resolve({ status:'rejected' as const }) }),inject:()=>{},cancel(){},runMaintenance:task=>task(new AbortController().signal),whenIdle:()=>Promise.resolve() }
  await ctx.plugin(Object.assign((inner:Context)=>{scope=createScope(inner,agent)},{ inject:['tools'] }));await ctx.agents.register(agent)
  session.append('turn/start',{ turn:1 })
  const original=await sharp({ create:{ width:3,height:2,channels:4,background:'#ffffff' } }).png().toBuffer()
  await writeFile(join(root,'image.png'),original)
  let call=0
  const execute=(arguments_:object,name='publish_result')=>ctx.tools.execute({ name,arguments:arguments_,agent,callId:ToolCallId(`call-${++call}`),signal:new AbortController().signal })
  const first=await execute({ path:'image.png' });expect(first.isError).toBe(false)
  if(first.isError)throw new Error('Publication failed')
  const state=ctx.sessionProjections.stateOf(session,'publishedResults')!,result=Object.values(state)[0]!
  expect(first.content.map(block=>block.type==='text'?block.text.replace(result.id,'<result-id>'):block)).toMatchInlineSnapshot(`
    [
      "Published image.png, result <result-id>, version 1. Original saved; synchronization and preview preparation are separate.",
    ]
  `)
  expect(result.original.attachment.attachmentId).toBe(`sha256:${createHash('sha256').update(original).digest('hex')}`)
  await writeFile(join(root,'image.png'),'changed source')
  expect(await readFile(ctx.attachments.fileHostPath(result.original.attachment)!)).toEqual(original)
  await vi.waitFor(()=>{ expect(ctx.sessionProjections.stateOf(session,'publishedResults')![result.id]!.previewState).toBe('ready') })
  expect((await execute({ resultId:result.id,version:1 },'inspect_result')).isError).toBe(false)
  expect((await execute({ resultId:'missing' },'inspect_result')).isError).toBe(true)
  expect((await execute({ path:'image.png',resultId:result.id,expectedVersion:0 })).isError).toBe(true)
  await writeFile(join(root,'image.png'),original)
  expect((await execute({ path:'image.png',resultId:result.id,expectedVersion:1 })).isError).toBe(false)
  expect((await execute({ resultId:result.id,version:1 },'prepare_result_preview')).isError).toBe(false)
  await vi.waitFor(()=>{ expect(ctx.sessionProjections.stateOf(session,'publishedResults')![`${result.id}:1`]!.previewState).toBe('ready') })
  expect(ctx.sessionProjections.stateOf(session,'publishedResults')![result.id]!.version).toBe(2)
  const fiber=[...ctx.loader.entries()].find(entry=>entry.options.id==='publish')?.fiber
  await fiber?.dispose();expect(ctx.tools.get('publish_result',agent)).toBeUndefined()
})
