#!/usr/bin/env node
// Developer-only real PowerShell + CDP + isolated browser regression.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');const {once}=require('node:events');
const args=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>v.startsWith('--')?[...a,[v.slice(2),all[i+1]]]:a,[]));
if(!args.powershell||!args.chrome||!args.output)throw new Error('--powershell --chrome --output required');
const root=path.resolve(__dirname,'../..'),out=path.resolve(args.output);fs.mkdirSync(out,{recursive:true,mode:0o700});
const calls=[];let running;
const server=http.createServer(async(req,res)=>{
 const u=new URL(req.url,'http://fixture');
 if(u.pathname==='/login'){res.writeHead(200,{'content-type':'text/html'});res.end('<!doctype html><script>fetch("/session",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({username:"fixture",password:"fixture-password-never-export"})}).then(()=>location.replace("/chat?mode='+u.searchParams.get('mode')+'"))</script>');return;}
 if(u.pathname==='/session'){for await(const _ of req){}res.writeHead(200,{'set-cookie':'fw_native=fixture-secret; HttpOnly; SameSite=Lax; Path=/','content-type':'application/json'});res.end('{}');return;}
 if(u.pathname==='/chat'){
  if(!req.headers.cookie?.includes('fw_native=fixture-secret')){res.writeHead(302,{location:'/login?mode='+u.searchParams.get('mode')});res.end();return;}
  const protocol=u.searchParams.get('mode')==='messages'?'messages':'chat/completions';if(u.searchParams.get('mode')==='custom'){res.writeHead(200,{'content-type':'text/html'});res.end('<!doctype html><script>setTimeout(()=>fetch("/api/chat",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({query:"fixture-private-chat-never-export",application_id:"app-1"})}).then(r=>r.json()),100)</script>');return;}res.writeHead(200,{'content-type':'text/html'});res.end('<!doctype html><title>Local synthetic chat</title><script>setTimeout(()=>fetch("/v1/'+protocol+'?mode='+u.searchParams.get('mode')+'",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({model:"fixture-model",messages:[{role:"user",content:"fixture-private-chat-never-export"}]} )}).then(r=>r.json()),100)</script>');return;
 }
 if(u.pathname==='/api/chat'){for await(const _ of req){}res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({answer:'synthetic custom reply'}));return;}
 if(!u.pathname.startsWith('/v1/')){res.writeHead(404);res.end();return;}
 if(!req.headers.cookie?.includes('fw_native=fixture-secret')){res.writeHead(401);res.end('{}');return;}
 let raw='';for await(const c of req)raw+=c;const body=JSON.parse(raw);calls.push({url:u.pathname,body});
 const messages=body.messages||[],chat=!u.pathname.endsWith('/messages');
 if(u.searchParams.get('mode')==='blocked'&&body.max_tokens){res.writeHead(429);res.end('{}');return;}
 const send=value=>{res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(value));};
 if(body.stream){const events=chat?[{choices:[{delta:{content:'FW_STREAM_OK'}}]},'[DONE]']:[{type:'content_block_delta',delta:{text:'FW_STREAM_OK'}},{type:'message_stop'}];res.writeHead(200,{'content-type':'text/event-stream'});res.end(events.map(v=>'data: '+(typeof v==='string'?v:JSON.stringify(v))+'\n\n').join(''));return;}
 if(body.tools&&messages.length===1){send(chat?{choices:[{message:{role:'assistant',content:null,tool_calls:[{type:'function',id:'tool-1',function:{name:'probe_echo',arguments:'{"code":"FW_TOOL_OK"}'}}]}}]}:{content:[{type:'tool_use',id:'tool-1',name:'probe_echo',input:{code:'FW_TOOL_OK'}}]});return;}
 let text='FW_PROBE_OK';if(body.tools)text=chat?messages.at(-1).content:messages.at(-1).content[0].content;
 else if(messages.length===3)text=messages[0].content.split(': ')[1];else if(messages[0]?.content?.includes('JSON object'))text='{"probe":"FW_JSON_OK","n":7}';
 send(chat?{choices:[{message:{content:text}}],usage:{prompt_tokens:8,completion_tokens:4}}:{content:[{type:'text',text}],usage:{input_tokens:8,output_tokens:4}});
});
function execute(mode){return new Promise((resolve,reject)=>{
 const base='http://127.0.0.1:'+server.address().port;const work=path.join(out,mode+'-'+Date.now());fs.mkdirSync(work,{recursive:true,mode:0o700});
 const env={...process.env,POWERSHELL_TELEMETRY_OPTOUT:'1',XDG_CACHE_HOME:path.join(out,'cache'),XDG_CONFIG_HOME:path.join(out,'config'),XDG_DATA_HOME:path.join(out,'data')};
 const child=spawn(path.resolve(args.powershell),['-NoProfile','-NonInteractive','-File',path.resolve(args.runner||path.join(root,'scripts/checks/start-model-browser-check.ps1')),'-NoGui','-HeadlessBrowser','-ChatUrl',base+'/chat?mode='+mode,'-AuthUrl',base+'/login','-BrowserPath',path.resolve(args.chrome),'-WorkingRoot',work,'-TimeoutSeconds','60'],{env});running=child;let stdout='',stderr='';child.stdout.on('data',d=>stdout+=d);child.stderr.on('data',d=>stderr+=d);
 const timeout=setTimeout(()=>{child.kill('SIGTERM');reject(new Error('PowerShell test process timed out'));},90000);
 child.on('error',e=>{clearTimeout(timeout);reject(e);});child.on('close',code=>{clearTimeout(timeout);running=null;
  try{assert.equal(code,['blocked','custom'].includes(mode)?2:0,JSON.stringify({mode,code,stdout,stderr}));assert(!/fixture-password|fixture-secret|fixture-private-chat/.test(stdout+stderr));const dirs=fs.readdirSync(work).map(p=>path.join(work,p)).filter(p=>fs.statSync(p).isDirectory());assert.equal(dirs.length,1);const report=JSON.parse(fs.readFileSync(path.join(dirs[0],'report.json'),'utf8'));assert(!/fixture-password|fixture-secret|fixture-private-chat/.test(JSON.stringify(report)));assert(!fs.existsSync(path.join(dirs[0],'browser-profile')),'Browser profile not cleaned');if(mode==='blocked')assert.equal(report.result.checks[0].status,'BLOCKED');else if(mode==='custom'){assert.equal(report.result.checks[0].status,'NOT_TESTED');assert.equal(report.result.request_count,0);}else{assert(report.result.checks.every(c=>c.status==='PASS'));assert.equal(report.result.request_count,6);}resolve({mode,passed:true,checks:report.result.checks.map(c=>({name:c.name,status:c.status})),profile_removed:true,stdout});}catch(e){fs.writeFileSync(path.join(work,'failure-output.json'),JSON.stringify({stdout,stderr,code},null,2));reject(e);}
 });
});}
(async()=>{server.listen(0,'127.0.0.1');await once(server,'listening');const results=[];for(const mode of ['chat','messages','blocked','custom'])results.push(await execute(mode));fs.writeFileSync(path.join(out,'native-check.json'),JSON.stringify({passed:true,results,windows_forms_tested:false,enterprise_tested:false},null,2));console.log(JSON.stringify({passed:true,results,requests:calls.length}));})().catch(e=>{console.error(e.stack);process.exitCode=1;}).finally(()=>{running?.kill();server.closeAllConnections();server.close();});
