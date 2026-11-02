const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = readFileSync(__dirname+'/probe-qianwen-mac.command','utf8');
const start=source.indexOf("var pageHook='('+function(){");
const actualStart=source.indexOf('function(){',start);
const end=source.indexOf("}.toString()+')()';",actualStart)+1;
assert(actualStart>0&&end>actualStart);
const attributes = new Map();
const listeners = new Map();
const response = {status:200,headers:new Headers({'content-type':'text/event-stream','set-cookie':'private-cookie','x-private':'private-value'})};
const promise = Promise.resolve(response);
const nativeFetch=()=>promise;
class XHR {open(){} send(){} setRequestHeader(){} }
const nativeOpen=XHR.prototype.open;
const context={URL,Headers,XMLHttpRequest:XHR,location:{origin:'https://www.qianwen.com',href:'https://www.qianwen.com/'},
  document:{documentElement:{setAttribute:(k,v)=>attributes.set(k,v)},addEventListener:(k,v)=>listeners.set(k,v),removeEventListener:k=>listeners.delete(k)},
  setTimeout:()=>1,clearTimeout:()=>{},fetch:nativeFetch};
context.window=context;
vm.createContext(context);
vm.runInContext('('+source.slice(actualStart,end)+')()',context);
assert.notEqual(context.fetch,nativeFetch);
const observed=context.fetch('https://chat2.qianwen.com/api/v2/chat?nonce=PRIVATE_QUERY',{method:'POST',headers:{Authorization:'PRIVATE_AUTH','x-csrf-token':'PRIVATE_CSRF'},body:JSON.stringify({model:'Qwen',messages:[{content:'PRIVATE_CHAT'}],session_id:'PRIVATE_SESSION',token:'PRIVATE_TOKEN'})});
assert.equal(observed,promise,'preserve original response promise');
(async()=>{
  await observed; await Promise.resolve();
  const serialized=attributes.get('data-fw-probe-records');
  for(const secret of ['PRIVATE_QUERY','PRIVATE_AUTH','PRIVATE_CSRF','PRIVATE_CHAT','PRIVATE_SESSION','PRIVATE_TOKEN','private-cookie','private-value'])assert(!serialized.includes(secret),secret+' leaked');
  const row=JSON.parse(serialized).find(r=>r.method==='POST');
  assert.equal(row.url,'https://chat2.qianwen.com/api/v2/chat');
  assert.equal(row.request_shape.messages[0].content,'string');
  assert.equal(row.content_type,'text/event-stream');
  assert.equal(row.status,200);
  assert.equal(row.model,'Qwen');
  listeners.get('fw-probe-stop')();
  assert.equal(context.fetch,nativeFetch);
  assert.equal(XHR.prototype.open,nativeOpen);
  assert(!context.__fwQwenProbe);
  console.log('PASS: metadata redaction, request shape, response metadata, promise identity and cleanup');
})().catch(e=>{console.error(e);process.exitCode=1;});
