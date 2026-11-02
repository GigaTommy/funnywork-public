#!/usr/bin/env node
/* Real isolated Chromium UI and HTTP fixture checks; not an enterprise endpoint test. */
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const http=require('node:http');
const {once}=require('node:events');
const {pathToFileURL}=require('node:url');
const argv=Object.fromEntries(process.argv.slice(2).reduce((pairs,value,index,all)=>value.startsWith('--')?[...pairs,[value.slice(2),all[index+1]]]:pairs,[]));
if(!argv.playwright||!argv.chrome||!argv.output)throw new Error('--playwright, --chrome, --output required');
const {chromium}=require(path.resolve(argv.playwright));
const root=path.resolve(__dirname,'../..'), out=path.resolve(argv.output);
fs.mkdirSync(out,{recursive:true,mode:0o700});
const helper=fs.readFileSync(path.join(root,'scripts/checks/browser-model-probe.js'),'utf8');
const pageHTML=fs.readFileSync(path.join(root,'designs/enterprise-model-poc/index.manual.html'),'utf8');
const requests=[],errors=[];let browser,crossServer;
const server=http.createServer(async(req,res)=>{
 if(req.url==='/'){res.writeHead(200,{'content-type':'text/html; charset=utf-8'});res.end(pageHTML);return;}
 if(req.url==='/chat'){res.writeHead(200,{'content-type':'text/html; charset=utf-8','set-cookie':'fw_poc=synthetic; HttpOnly; SameSite=Lax; Path=/'});res.end('<!doctype html><title>Isolated chat fixture</title><p>Local test service only</p>');return;}
 if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return;}
 if(!req.url.startsWith('/v1/')){res.writeHead(404);res.end();return;}
 if(!req.headers.cookie?.includes('fw_poc=synthetic')){res.writeHead(401);res.end('{}');return;}
 let text='';for await(const chunk of req)text+=chunk;
 const body=JSON.parse(text);requests.push({url:req.url,body});const messages=body.messages||[],chat=!req.url.startsWith('/v1/messages');
 const send=value=>{res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));};
 if(body.stream){
  res.writeHead(200,{'content-type':'text/event-stream'});
  if(req.url.includes('slow')){res.write(': heartbeat\n\n');return;}
  const events=chat?[{choices:[{delta:{content:'FW_STREAM_OK'}}]},'[DONE]']:[{type:'content_block_delta',delta:{text:'FW_STREAM_OK'}},{type:'message_stop'}];
  for(const e of events)res.write('data: '+(typeof e==='string'?e:JSON.stringify(e))+'\n\n');res.end();return;
 }
 if(body.tools&&messages.length===1){send(chat?{choices:[{message:{role:'assistant',content:null,tool_calls:[{type:'function',id:'tool-1',function:{name:'probe_echo',arguments:'{"code":"FW_TOOL_OK"}'}}]}}]}:{content:[{type:'tool_use',id:'tool-1',name:'probe_echo',input:{code:'FW_TOOL_OK'}}]});return;}
 let answer='FW_PROBE_OK';if(body.tools)answer=chat?messages.at(-1).content:messages.at(-1).content[0].content;
 else if(messages.length===3)answer=messages[0].content.split(': ')[1];
 else if(messages[0]?.content?.includes('JSON object'))answer='{"probe":"FW_JSON_OK","n":7}';
 send(chat?{choices:[{message:{content:answer}}],usage:{prompt_tokens:8,completion_tokens:4}}:{content:[{type:'text',text:answer}],usage:{input_tokens:8,output_tokens:4}});
});
(async()=>{
 server.listen(0,'127.0.0.1');await once(server,'listening');const base='http://127.0.0.1:'+server.address().port;
 crossServer=http.createServer((req,res)=>{res.setHeader('Access-Control-Allow-Origin',base);res.setHeader('Access-Control-Allow-Credentials','true');res.setHeader('Access-Control-Allow-Methods','POST, OPTIONS');res.setHeader('Access-Control-Allow-Headers','content-type');if(req.method==='OPTIONS'){res.writeHead(204);res.end();return;}server.emit('request',req,res);});crossServer.listen(0,'127.0.0.1');await once(crossServer,'listening');const cross='http://127.0.0.1:'+crossServer.address().port;
 browser=await chromium.launch({headless:true,executablePath:argv.chrome,args:['--disable-background-networking','--no-first-run']});
 const context=await browser.newContext({viewport:{width:1280,height:1000},colorScheme:'light'});
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{Object.defineProperty(navigator,'clipboard',{value:{writeText:async text=>{window.__copied=text;}}});});
 await page.goto(base);await page.getByRole('button',{name:'加载演示记录'}).click();await page.getByRole('button',{name:'查看',exact:true}).click();assert(await page.locator('#copyTest').isDisabled());
 await page.screenshot({path:path.join(out,'desktop-light.png'),fullPage:true});
 await page.getByRole('button',{name:'查看脚本与操作说明'}).click();await page.getByRole('button',{name:'复制脚本',exact:true}).click();assert((await page.evaluate(()=>window.__copied)).includes('FunnyWorkPOC.start()'));await page.getByRole('button',{name:'关闭说明'}).click();
 const bad={name:'bad.json',mimeType:'application/json',buffer:Buffer.from('{bad')};await page.locator('#fileInput').setInputFiles(bad);await page.waitForFunction(()=>document.getElementById('status').classList.contains('error'));
 const chat=await context.newPage();chat.on('pageerror',e=>errors.push(e.message));await chat.goto(base+'/chat');await chat.addScriptTag({content:helper});await chat.evaluate(()=>FunnyWorkPOC.start());
 const observation=await chat.evaluate(async()=>{await fetch('/v1/chat/completions',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'SYNTHETIC_PRIVATE_TEXT'}]})});return FunnyWorkPOC.snapshot();});
 assert.equal(observation.records.length,1);assert(!JSON.stringify(observation).includes('SYNTHETIC_PRIVATE_TEXT'));
 await page.locator('#fileInput').setInputFiles({name:'observation.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(observation))});await page.getByRole('button',{name:'查看',exact:true}).click();assert(await page.locator('#copyTest').isEnabled());
 await page.locator('#checks input[value="tools"]').check();await page.locator('#copyTest').click();assert((await page.evaluate(()=>window.__copied)).includes('"tools"'));
 const tested=await chat.evaluate(id=>FunnyWorkPOC.test(id,{checks:['multi_turn','streaming','structured','tools']}),observation.records[0].id);assert(tested.checks.every(c=>c.status==='PASS'),JSON.stringify(tested));assert.equal(tested.request_count,6);
 await page.locator('#fileInput').setInputFiles({name:'tested.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(tested))});await page.waitForFunction(()=>document.querySelectorAll('#tableBody tr').length===5);assert((await page.locator('#tableBody').innerText()).includes('PASS'));
 // Actual XHR instrumentation and restoration, including body/schema extraction.
 const xhr=await chat.evaluate(async()=>{const original=XMLHttpRequest.prototype.open;await new Promise((resolve,reject)=>{const x=new XMLHttpRequest();x.open('POST','/v1/messages');x.setRequestHeader('content-type','application/json');x.onloadend=resolve;x.onerror=reject;x.send(JSON.stringify({model:'test-model',messages:[{role:'user',content:'synthetic xhr'}]}));});await new Promise(resolve=>setTimeout(resolve,0));const report=FunnyWorkPOC.snapshot();FunnyWorkPOC.stop();return{report,restored:XMLHttpRequest.prototype.open!==original};});assert.equal(xhr.report.records.at(-1).protocol_candidate,'messages');assert(xhr.restored);
 await chat.evaluate(()=>FunnyWorkPOC.start());const messageId=await chat.evaluate(async()=>{await fetch('/v1/messages',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'synthetic'}]})});return FunnyWorkPOC.snapshot().records.at(-1).id;});
 const messageTests=await chat.evaluate(id=>FunnyWorkPOC.test(id,{checks:['multi_turn','streaming','structured','tools']}),messageId);assert(messageTests.checks.every(c=>c.status==='PASS'),JSON.stringify(messageTests));
 const crossId=await chat.evaluate(async address=>{await fetch(address+'/v1/chat/completions',{method:'POST',credentials:'include',headers:{'content-type':'application/json'},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'synthetic cross origin'}]})});return FunnyWorkPOC.snapshot().records.at(-1).id;},cross);
 const crossTest=await chat.evaluate(id=>FunnyWorkPOC.test(id,{allowCrossOrigin:true}),crossId);assert.equal(crossTest.checks[0].status,'PASS',JSON.stringify(crossTest));
 // Verify stop aborts a pending SSE probe without touching the application's requests.
 const slowId=await chat.evaluate(async()=>{await fetch('/v1/chat/completions?mode=slow',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({model:'test-model',messages:[{role:'user',content:'synthetic'}]})});return FunnyWorkPOC.snapshot().records.at(-1).id;});
 await chat.evaluate(id=>{window.__pendingTest=FunnyWorkPOC.test(id,{checks:['streaming']});},slowId);
 for(let i=0;i<100&&!requests.some(r=>r.url.includes('slow')&&r.body.stream);i++)await new Promise(r=>setTimeout(r,20));
 assert(requests.some(r=>r.url.includes('slow')&&r.body.stream));await chat.evaluate(()=>FunnyWorkPOC.stop());const aborted=await chat.evaluate(()=>window.__pendingTest);assert.equal(aborted.checks.at(-1).status,'NOT_CONFIRMED');
 await page.getByRole('button',{name:'加载演示记录'}).click();await page.getByRole('button',{name:'查看',exact:true}).click();await page.emulateMedia({colorScheme:'dark'});await page.screenshot({path:path.join(out,'desktop-dark.png'),fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(out,'mobile-dark.png'),fullPage:true});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
 await page.getByRole('button',{name:'清空',exact:true}).click();assert(await page.locator('#export').isDisabled());
 const offline=await context.newPage();await offline.goto(pathToFileURL(path.join(root,'designs/enterprise-model-poc/index.manual.html')).href);await offline.getByRole('button',{name:'加载演示记录'}).click();assert.equal(await offline.locator('#tableBody tr').count(),1);await offline.close();
 assert.deepEqual(errors,[]);
 const result={passed:true,isolated_chromium:true,ui_import_copy_clear:true,offline_file_load:true,light_dark_mobile:true,http_only_cookie_auth:true,explicit_cross_origin_cors_cookie:true,fetch_and_xhr_capture:true,chat_full_loop:true,messages_full_loop:true,stop_aborts_probe:true,page_errors:errors,enterprise_endpoint_tested:false,requests:requests.length};
 fs.writeFileSync(path.join(out,'browser-check.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
})().catch(error=>{console.error(error.stack);process.exitCode=1;}).finally(async()=>{await browser?.close();crossServer?.closeAllConnections();crossServer?.close();server.closeAllConnections();server.close();});
