/* Developer-only Node tests; delivered browser files have no Node dependency. */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname,'browser-model-probe.js'),'utf8');
const SECRET='fixture-never-export-this-token';
function setup(t,mode='ok') {
  const calls=[];
  const fetcher=(address,init={})=>{
    calls.push({address,init});
    const body=JSON.parse(init.body||'{}'), messages=body.messages||[], chat=!String(address).endsWith('/messages');
    if(mode==='denied') return Promise.resolve(new Response('{}',{status:403,headers:{'content-type':'application/json'}}));
    if(mode==='rate') return Promise.resolve(new Response('{}',{status:429,headers:{'content-type':'application/json'}}));
    if(mode==='pending')return new Promise((resolve,reject)=>init.signal?.addEventListener('abort',()=>reject(new Error(SECRET)),{once:true}));
    if(body.stream){
      let events=chat?[{choices:[{delta:{content:'FW_STREAM_'}}]},{choices:[{delta:{content:'OK'}}]},'[DONE]']:[{type:'content_block_delta',delta:{text:'FW_STREAM_OK'}},{type:'message_stop'}];
      if(mode==='truncated')events=events.slice(0,-1);
      if(mode==='sse-error')events=[{error:{message:SECRET}}];
      return Promise.resolve(new Response(events.map(e=>'data: '+(typeof e==='string'?e:JSON.stringify(e))+'\n\n').join(''),{headers:{'content-type':'text/event-stream'}}));
    }
    if(body.tools&&messages.length===1){
      const call={id:'tool-1',type:'function',function:{name:mode==='bad-tool'?'run_shell':'probe_echo',arguments:'{"code":"FW_TOOL_OK"}'}};
      return Promise.resolve(Response.json(chat?{choices:[{message:{role:'assistant',content:null,tool_calls:[call]}}]}:{content:[{type:'tool_use',id:'tool-1',name:'probe_echo',input:{code:'FW_TOOL_OK'}}]}));
    }
    let text='FW_PROBE_OK';
    if(body.tools)text=mode==='bad-result'?'ignored tool':chat?messages.at(-1).content:messages.at(-1).content[0].content;
    else if(messages.length===3)text=messages[0].content.split(': ')[1];
    else if(messages[0]?.content?.includes('JSON object'))text='{"probe":"FW_JSON_OK","n":7}';
    const value=chat?{choices:[{message:{content:text}}]}:{content:[{type:'text',text}]};
    value.usage={input_tokens:12,output_tokens:7,malicious:SECRET};
    return Promise.resolve(Response.json(value));
  };
  const context=vm.createContext({URL,Headers,Blob,Response,TextDecoder,AbortController,setTimeout,clearTimeout,
    location:{href:'https://chat.example.internal/app',origin:'https://chat.example.internal',protocol:'https:'},crypto:webcrypto,fetch:fetcher});
  vm.runInContext(source,context);
  const api=context.FunnyWorkPOC;
  t.after(()=>api.stop());
  async function observe(protocol='chat_completions',address) {
    const url=address||'https://chat.example.internal/v1/'+(protocol==='messages'?'messages':'chat/completions');
    await context.fetch(url,{method:'POST',headers:{Authorization:'Bearer '+SECRET,'x-csrf-token':SECRET},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'PRIVATE_MESSAGE_NEVER_EXPORT'}],stream:false})});
    return api.snapshot().records.at(-1)?.id;
  }
  return {context,api,calls,observe,fetcher};
}
const harEntry=(url,body,response={})=>({request:{url,method:'POST',headers:[{name:'Authorization',value:'Bearer '+SECRET}],postData:{text:JSON.stringify(body)}},response:{status:200,content:{mimeType:'application/json',text:JSON.stringify(response)}}});
test('installation is inert until start; preserves native fetch identity',t=>{
 const {context,api,calls,fetcher}=setup(t);assert.equal(calls.length,0);assert.equal(context.fetch,fetcher);assert.equal(api.snapshot().records.length,0);
});
test('passive fetch retains original promise and response; reports no secrets or message text',async t=>{
 const {context,api,calls,observe,fetcher}=setup(t);api.start();await observe();const report=api.snapshot();assert.equal(report.records.length,1);assert.equal(report.records[0].protocol_candidate,'chat_completions');assert(!JSON.stringify(report).includes(SECRET));assert(!JSON.stringify(report).includes('PRIVATE_MESSAGE'));
 assert.equal(calls.length,1);api.stop();assert.equal(context.fetch,fetcher);
});
test('complete Chat Completions capability loop',async t=>{
 const {api,observe,calls}=setup(t);api.start();const id=await observe();const report=await api.test(id,{checks:['basic','multi_turn','streaming','structured','tools']});assert(report.checks.every(c=>c.status==='PASS'),JSON.stringify(report));assert.equal(report.request_count,6);assert.equal(calls.length,7);assert(!JSON.stringify(report).includes(SECRET));assert.equal(calls[1].init.redirect,'error');assert.equal(calls[1].init.credentials,'same-origin');
});
test('complete Messages capability loop',async t=>{
 const {api,observe}=setup(t);api.start();const id=await observe('messages');const report=await api.test(id,{checks:['multi_turn','streaming','structured','tools']});assert(report.checks.every(c=>c.status==='PASS'),JSON.stringify(report));
});
test('basic request is automatic; optional duplicate checks deduplicated',async t=>{
 const {api,observe}=setup(t);api.start();const report=await api.test(await observe(),{checks:['multi_turn','multi_turn']});assert.equal(report.request_count,2);
});
test('stop clears replay credentials and blocks future probes',async t=>{
 const {api,observe}=setup(t);api.start();const id=await observe();api.stop();await assert.rejects(api.test(id),/LIVE_STANDARD/);
});
test('rejects cross-origin active replay even when observed in page traffic',async t=>{
 const {api,observe,calls}=setup(t);api.start();const id=await observe('chat_completions','https://other.example.internal/v1/chat/completions');await assert.rejects(api.test(id),/CROSS_ORIGIN/);assert.equal(calls.length,1);
});
test('explicit cross-origin option only reuses the observed endpoint and credential mode',async t=>{
 const {api,observe,calls}=setup(t);api.start();const id=await observe('chat_completions','https://other.example.internal/v1/chat/completions');const report=await api.test(id,{allowCrossOrigin:true});assert.equal(report.checks[0].status,'PASS');assert.equal(calls[1].address,'https://other.example.internal/v1/chat/completions');assert.equal(calls[1].init.credentials,'same-origin');
});
test('401/403 and limits are blocked rather than unsupported',async t=>{
 for(const mode of ['denied','rate']){const {api,observe}=setup(t,mode);api.start();const report=await api.test(await observe(),{checks:['multi_turn','tools']});assert.equal(report.checks.length,1);assert.equal(report.checks[0].status,'BLOCKED');api.stop();}
});
test('bad or incomplete streams never pass',async t=>{
 for(const mode of ['truncated','sse-error']){const {api,observe}=setup(t,mode);api.start();const report=await api.test(await observe(),{checks:['streaming']});assert.equal(report.checks.at(-1).status,'NOT_CONFIRMED');assert(!JSON.stringify(report).includes(SECRET));api.stop();}
});
test('tool names and tool result consumption checked without executing provider code',async t=>{
 for(const mode of ['bad-tool','bad-result']){const {api,observe}=setup(t,mode);api.start();const report=await api.test(await observe(),{checks:['tools']});assert.equal(report.checks.at(-1).status,'NOT_CONFIRMED');api.stop();}
});
test('HAR parsing strips auth, message text, query values and login requests',t=>{
 const {api}=setup(t);const report=api.analyzeHAR({log:{entries:[harEntry('https://chat.example/v1/chat/completions?token='+SECRET,{model:'test-model',messages:[{role:'user',content:'PRIVATE_HAR_MESSAGE'}]}),harEntry('https://identity.example/login',{username:'private',password:SECRET})]}});
 assert.equal(report.records.length,1);assert.equal(report.records[0].url,'https://chat.example/v1/chat/completions');assert.equal(report.records[0].query_keys[0],'token');assert(!JSON.stringify(report).includes(SECRET));assert(!JSON.stringify(report).includes('PRIVATE_HAR_MESSAGE'));
});
test('HAR detects custom wrapper without inventing model ID or capabilities',t=>{
 const {api}=setup(t);const report=api.analyzeHAR({log:{entries:[harEntry('https://chat.example/api/chat',{query:'private',application_id:'app-1',conversation_id:'old'},{answer:'private answer'})]}});assert.equal(report.records[0].protocol_candidate,'custom_or_unknown');assert.equal(report.records[0].model_ids.length,0);assert.equal(report.records[0].capability_status,'OBSERVED_ONLY_NOT_VERIFIED');
});
test('model list IDs read only from known containers; reflected secret rejected',t=>{
 const {api}=setup(t);const entry=harEntry('https://chat.example/models',null,{data:[{id:'test-model'},{id:SECRET}],other:{model:'invented'}});entry.request.method='GET';const report=api.analyzeHAR({log:{entries:[entry]}});assert.equal(JSON.stringify(report.records[0].model_ids),'["test-model"]');
});
test('HAR bounds records and entries; no network access',t=>{
 const {api,calls}=setup(t);const entry=harEntry('https://chat.example/v1/messages',{model:'test-model',messages:[]});const report=api.analyzeHAR({log:{entries:Array.from({length:1100},()=>entry)}});assert.equal(report.records.length,60);assert.equal(report.inspected_entries,1000);assert.equal(calls.length,0);
});
test('HTML embeds identical collector; no external scripts or assets',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../../designs/enterprise-model-poc/index.manual.html'),'utf8');const embedded=html.match(/<script id="fw-core">\n([\s\S]*?)\n<\/script>/)[1];assert.equal(embedded,source);assert(!/<script[^>]+src=|<link[^>]+href=|<img/i.test(html));assert(html.includes("connect-src 'none'"));for(const script of html.matchAll(/<script(?: [^>]*)?>([\s\S]*?)<\/script>/g))new vm.Script(script[1]);
});
