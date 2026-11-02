import {createUserMessage} from '@deepseek-ai/dsh-llm'
export const name = 'history-sync-cold-fixture'
export const inject = ['sessionPersistence','agents','approval','connection','webServer','attachments','userQuestions']
export async function apply(ctx) {
  let releaseLive
  ctx.on('llm/stream', (options,next) => {
    if (!options.messages.some(message => message.role === 'user' && message.content.some(part => part.type === 'text' && part.text === '[mobile-live-fixture]'))) return next()
    return (async function* () {
      yield {type:'block-start',index:0,blockType:'text'}
      yield {type:'text-delta',index:0,text:'Live fixture reply'}
      await new Promise(resolve => {
        releaseLive=resolve
        options.signal?.addEventListener('abort',resolve,{once:true})
      })
      options.signal?.removeEventListener('abort',releaseLive)
      releaseLive=undefined
      yield {type:'block-end',index:0,block:{type:'text',text:'Live fixture reply'}}
      yield {type:'finish',reason:{kind:'stop'}}
    })()
  })
  ctx.effect(() => ctx.get('connection').fetch.register({path:'/api/history-fixture/live-release',methods:['POST'],requestBody:'buffered',fetch:async () => {
    releaseLive?.()
    return new Response('{}',{headers:{'content-type':'application/json'}})
  }}))
  ctx.on('agent/created', ({agent}) => {
    if (agent.session.id === 'history-sync-cold') {
      console.error('history-sync-unexpected-activation')
      throw new Error('Cold replica must not activate an Agent')
    }
  }, {global:true})
  const writer = await ctx.sessionPersistence.create({version:4,id:'history-sync-cold',createdAt:1,isSeeded:false})
  await writer.append([{type:'turn/start',seq:0,time:1,data:{turn:1}}])
  await writer.flush()
  await writer.close()
  const ref=await ctx.attachments.saveFile({data:new TextEncoder().encode('fixture attachment'),name:'notes.txt'})
  const file=await ctx.sessionPersistence.create({version:4,id:'history-sync-file',createdAt:1,isSeeded:false})
  await file.append([{type:'turn/start',seq:0,time:1,data:{turn:1}},{type:'user/message',seq:1,time:2,surfaceOp:'append',data:createUserMessage({source:{kind:'user',rpcId:'fixture-file'},content:[{type:'file',attachment:ref}]})},{type:'turn/end',seq:2,time:3,data:{turn:1,reason:{kind:'completed'}}}])
  await file.flush();await file.close()
  let turn=0
  ctx.effect(() => ctx.get('connection').fetch.register({path:'/api/history-fixture/ask',methods:['POST'],requestBody:'buffered',fetch:async () => {
    const agent=ctx.get('agents').get('history-sync-approval')
    turn++
    agent.session.append('turn/start',{turn})
    const outcome=await ctx.get('approval').request({agent,toolName:'fixture_action',reason:'One fixture action'})
    agent.session.append('turn/end',{turn,reason:{kind:'completed'}})
    await ctx.sessionPersistence.flush()
    return new Response(JSON.stringify({outcome}),{headers:{'content-type':'application/json'}})
  }}))
  ctx.effect(() => ctx.get('connection').fetch.register({path:'/api/history-fixture/question',methods:['POST'],requestBody:'buffered',fetch:async () => {
    const agent=ctx.get('agents').get('history-sync-approval')
    const answer=await ctx.get('userQuestions').ask({agent,questions:[{id:'topic',question:'选择内容',options:[{label:'记录',description:'保存当前记录'}]}]})
    return new Response(JSON.stringify(answer),{headers:{'content-type':'application/json'}})
  }}))

}
