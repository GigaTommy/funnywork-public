/* FunnyWork browser POC. No dependencies. Paste into the logged-in CHAT page console.
 * Starts only when FunnyWorkPOC.start() is called. No background uploads or storage.
 */
(() => {
  'use strict';
  if (globalThis.FunnyWorkPOC?.version === '1.0') return;
  const root = globalThis;
  const LIMIT = 2 * 1024 * 1024;
  const allowedHeaders = /^(authorization|api-key|anthropic-version|x-[a-z0-9-]+)$/i;
  let active = false, generation = 0, counter = 0, running = false, timer;
  const controllers = new Set();
  const records = [], privateCalls = new Map(), xhrData = new WeakMap();
  let nativeFetch = root.fetch;
  const proto = root.XMLHttpRequest?.prototype;
  let originals = proto && {open: proto.open, send: proto.send, setRequestHeader: proto.setRequestHeader};
  let wrappedFetch, wrappedOpen, wrappedSend, wrappedHeader;
  const fail = code => { const error=new Error(code); error.fwCode=code; throw error; };
  const clone = value => JSON.parse(JSON.stringify(value));
  const keys = value => value && typeof value === 'object' && !Array.isArray(value)
    ? Object.keys(value).filter(k => /^[A-Za-z_][A-Za-z0-9_.-]{0,47}$/.test(k)).slice(0,40) : [];
  const parse = value => { try { return typeof value === 'string' && value.length <= 65536 ? JSON.parse(value) : null; } catch { return null; } };
  function safeId(value, secrets = []) {
    if (typeof value !== 'string' || !/^[A-Za-z0-9_./:@+\-]{1,160}$/.test(value) || /^[A-Za-z0-9_-]{40,}$/.test(value)) return null;
    if (secrets.some(s => s && (value === s || s.length >= 4 && value.includes(s)))) return null;
    return value;
  }
  function headersOf(value) {
    try { return new Headers(value || {}); } catch { return new Headers(); }
  }
  function safeUrl(value) {
    const url = new URL(value, root.location?.href || 'https://invalid.example/');
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) fail('INVALID_HTTP_URL');
    const path = url.pathname.split('/').map(p => p.length > 48 || /^[a-f0-9-]{32,}$/i.test(p) ? '[id]' : p).join('/');
    return {url: url.origin + path, query_keys: [...new Set(url.searchParams.keys())].filter(k => /^[A-Za-z_][A-Za-z0-9_-]{0,47}$/.test(k)).slice(0,30)};
  }
  function identify(address, method, body, headerInput, mime = '', status = null, response = null) {
    const url = new URL(address, root.location?.href || 'https://invalid.example/');
    const fields = keys(body);
    if (/\/(login|logout|signin|authorize|oauth2?|saml|token|password)(\/|$)/i.test(url.pathname) || fields.some(k => /password|passwd|secret/i.test(k))) return null;
    const relevant = /chat|completion|conversation|models|inference|generate|messages|prediction/i.test(url.pathname) ||
      fields.some(k => ['messages','query','prompt','question','inputs'].includes(k));
    if (!relevant || !['GET','POST'].includes(method)) return null;
    const headers = headersOf(headerInput);
    const secretParts = [];
    headers.forEach((v,k) => { if (/authorization|cookie|key|token/i.test(k)) secretParts.push(...v.split(/[\s;=]+/)); });
    let protocol = 'custom_or_unknown';
    if (Array.isArray(body?.messages) && typeof body?.model === 'string') {
      if (/\/chat\/completions\/?$/i.test(url.pathname) || response?.choices?.[0]?.message) protocol = 'chat_completions';
      else if (/\/messages\/?$/i.test(url.pathname) || Array.isArray(response?.content)) protocol = 'messages';
    }
    const ids = new Set();
    for (const value of [body?.model, body?.model_id, body?.modelId, response?.model]) { const id = safeId(value,secretParts); if (id) ids.add(id); }
    // Read only known model-list containers; never search arbitrary conversation text.
    if (/models/i.test(url.pathname)) {
      const list = Array.isArray(response?.data) ? response.data : Array.isArray(response?.models) ? response.models : [];
      for (const item of list.slice(0,100)) { const id = safeId(item?.id || item?.name || item?.model,secretParts); if (id) ids.add(id); }
    }
    return {...safeUrl(url.href), method, status: Number.isInteger(status) ? status : null,
      protocol_candidate: protocol, model_ids: [...ids], request_fields: fields,
      response_fields: keys(response), auth_header_names: [...headers.keys()].filter(k => /authorization|cookie|key|csrf|token/i.test(k)).slice(0,20),
      response_kind: /text\/event-stream/i.test(mime) ? 'SSE' : /json/i.test(mime) ? 'JSON' : /html/i.test(mime) ? 'HTML' : 'UNKNOWN',
      capability_status: 'OBSERVED_ONLY_NOT_VERIFIED'};
  }
  function add(address, method, body, headers, mime, status, response, live, credentials='same-origin') {
    try {
      const item = identify(address,method,body,headers,mime,status,response);
      if (!item || records.length >= 60) return;
      item.id = 'request-' + (++counter);
      item.same_origin = new URL(address,root.location.href).origin === root.location.origin;
      records.push(item);
      if (live && item.protocol_candidate !== 'custom_or_unknown' && item.model_ids.length && method === 'POST') {
        const retained = {};
        headersOf(headers).forEach((value,key) => { if (allowedHeaders.test(key)) retained[key] = value; });
        privateCalls.set(item.id,{address: new URL(address,root.location.href).href, headers: retained,
          model: body.model, protocol: item.protocol_candidate, credentials:['same-origin','include','omit'].includes(credentials)?credentials:'same-origin'});
      }
    } catch { /* Never break the application's original request. */ }
  }
  function start() {
    if (active) return {status:'ALREADY_CAPTURING'};
    if (!/^https?:$/.test(root.location?.protocol || '')) fail('START_ON_LOGGED_IN_CHAT_PAGE');
    nativeFetch = root.fetch;
    if (proto) originals = {open:proto.open,send:proto.send,setRequestHeader:proto.setRequestHeader};
    records.length=0; privateCalls.clear();
    active = true; generation++;
    const epoch = generation;
    wrappedFetch = function(input, init) {
      const promise = nativeFetch.apply(this,arguments);
      try {
        const address = typeof input === 'string' || input instanceof URL ? String(input) : input.url;
        const method = String(init?.method || input?.method || 'GET').toUpperCase();
        const headers = init?.headers || input?.headers;
        const credentials = init?.credentials || input?.credentials || 'same-origin';
        const body = parse(init?.body); // Do not consume or clone Request bodies or response streams.
        promise.then(response => {
          if (active && generation === epoch) add(address,method,body,headers,response.headers.get('content-type') || '',response.status,null,true,credentials);
        },() => {});
      } catch { /* Observe only; return the original Promise and Response. */ }
      return promise;
    };
    root.fetch = wrappedFetch;
    if (proto) {
      wrappedOpen = function(method,address) { xhrData.set(this,{method:String(method).toUpperCase(),address:String(address),headers:{}}); return originals.open.apply(this,arguments); };
      wrappedHeader = function(name,value) { const data=xhrData.get(this); if (data) data.headers[name]=value; return originals.setRequestHeader.apply(this,arguments); };
      wrappedSend = function(body) {
        const data = xhrData.get(this);
        if (data) this.addEventListener('loadend',() => {
          if (!active || generation !== epoch) return;
          try {
            const response = this.responseType === 'json' ? this.response : !this.responseType || this.responseType === 'text' ? parse(this.responseText) : null;
            add(data.address,data.method,parse(body),data.headers,this.getResponseHeader('content-type') || '',this.status,response,true,this.withCredentials?'include':'same-origin');
          } catch {}
        },{once:true});
        return originals.send.apply(this,arguments);
      };
      proto.open=wrappedOpen; proto.send=wrappedSend; proto.setRequestHeader=wrappedHeader;
    }
    timer = setTimeout(stop,5*60*1000);
    return {status:'CAPTURING', expires_in_seconds:300, next:'Send one synthetic chat message, then FunnyWorkPOC.snapshot()'};
  }
  function stop() {
    active=false; generation++; clearTimeout(timer); privateCalls.clear();
    for (const controller of controllers) controller.abort(); controllers.clear();
    if (root.fetch === wrappedFetch) root.fetch=nativeFetch;
    if (proto && proto.open === wrappedOpen) proto.open=originals.open;
    if (proto && proto.send === wrappedSend) proto.send=originals.send;
    if (proto && proto.setRequestHeader === wrappedHeader) proto.setRequestHeader=originals.setRequestHeader;
    return {status:'STOPPED', ephemeral_credentials_cleared:true, records:records.length};
  }
  function snapshot() { return {schema_version:1,source:'browser-observation',created_at:new Date().toISOString(),records:clone(records),
    limits:{request_bodies:'NOT_EXPORTED',credentials:'NOT_EXPORTED',websocket:'NOT_CAPTURED',context_limit:'NOT_TESTED',concurrency:'NOT_TESTED'},
    notes:['Endpoints and model names are internal information.','Protocol candidates and SSE headers alone do not prove model capabilities.']}; }
  function download(value = snapshot(), filename = 'funnywork-browser-observation.json') {
    const link = root.document.createElement('a');
    link.href=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:'application/json'}));
    link.download=filename; link.click(); setTimeout(()=>URL.revokeObjectURL(link.href),1000);
    return {status:'DOWNLOADED'};
  }
  function analyzeHAR(har) {
    if (!Array.isArray(har?.log?.entries)) fail('EXPECTED_HAR_LOG_ENTRIES');
    const found=[];
    for (const entry of har.log.entries.slice(0,1000)) {
      try {
        const request=entry.request, response=entry.response;
        const headers=(request.headers || []).map(h=>[h.name,h.value]);
        const text=response?.content?.text;
        const decoded=typeof text === 'string' && text.length <= 65536 && response.content.encoding === 'base64' ? atob(text) : text;
        const item=identify(request.url,String(request.method).toUpperCase(),parse(request.postData?.text),headers,
          response?.content?.mimeType || '',response?.status,parse(decoded));
        if (item) { item.id='har-'+(found.length+1); found.push(item); }
        if (found.length>=60) break;
      } catch {}
    }
    return {schema_version:1,source:'har-import',created_at:new Date().toISOString(),records:found,
      inspected_entries:Math.min(har.log.entries.length,1000),total_entries:har.log.entries.length,
      limits:{request_bodies:'NOT_EXPORTED',credentials:'NOT_EXPORTED',context_limit:'NOT_TESTED',concurrency:'NOT_TESTED'}};
  }
  async function readBounded(response, signal, stream = false) {
    const reader=response.body?.getReader(); if (!reader) fail('EMPTY_RESPONSE');
    const decoder=new TextDecoder('utf-8',{fatal:true}); let bytes=0, text='', carry='', events=[];
    const abort=()=>{ reader.cancel().catch(()=>{}); };
    signal.addEventListener('abort',abort,{once:true});
    try {
      while (true) {
        if (signal.aborted) fail('REQUEST_TIMEOUT');
        const {value,done}=await reader.read();
        if (signal.aborted) fail('REQUEST_TIMEOUT');
        if (done) break;
        bytes+=value.length; if (bytes>LIMIT) fail('RESPONSE_SIZE_LIMIT');
        const decoded=decoder.decode(value,{stream:true});
        if (!stream) { text+=decoded; continue; }
        carry+=decoded;
        if (carry.length>65536 && !carry.includes('\n\n') && !carry.includes('\r\n\r\n')) fail('SSE_EVENT_SIZE_LIMIT');
        let match;
        while ((match=/\r?\n\r?\n/.exec(carry))) {
          const block=carry.slice(0,match.index); carry=carry.slice(match.index+match[0].length);
          if (block.length>65536) fail('SSE_EVENT_SIZE_LIMIT');
          const data=block.split(/\r?\n/).filter(line=>line.startsWith('data:')).map(line=>line.slice(5).replace(/^ /,'')).join('\n');
          if (!data) continue;
          const event=data==='[DONE]'?'[DONE]':JSON.parse(data);
          if (event?.error || event?.type==='error') fail('UPSTREAM_STREAM_ERROR');
          events.push(event); if (events.length>4096) fail('SSE_EVENT_LIMIT');
          if (event==='[DONE]' || event?.type==='message_stop') return {events};
        }
      }
      if (stream) fail('STREAM_TERMINAL_EVENT_MISSING');
      return JSON.parse(text+decoder.decode());
    } finally { signal.removeEventListener('abort',abort); reader.cancel().catch(()=>{}); }
  }
  function extractText(value,protocol) {
    const text=protocol==='chat_completions' ? value?.choices?.[0]?.message?.content : value?.content?.filter(b=>b.type==='text').map(b=>b.text).join('');
    if (typeof text!=='string') fail('TEXT_SCHEMA_MISMATCH'); return text;
  }
  function usageOf(value) {
    return Object.fromEntries(Object.entries(value?.usage || {}).filter(([k,v])=>/^(prompt_tokens|completion_tokens|total_tokens|input_tokens|output_tokens)$/.test(k)&&Number.isSafeInteger(v)&&v>=0));
  }
  async function test(id,options={}) {
    if (running) fail('TEST_ALREADY_RUNNING');
    const call=privateCalls.get(id);
    if (!active || !call) fail('LIVE_STANDARD_REQUEST_REQUIRED_CAPTURE_AGAIN');
    if (new URL(call.address).origin!==root.location.origin && options.allowCrossOrigin !== true) fail('CROSS_ORIGIN_REQUIRES_EXPLICIT_OPTION');
    const checks=options.checks || ['basic'];
    if (!Array.isArray(checks) || checks.some(c=>!['basic','multi_turn','streaming','structured','tools'].includes(c))) fail('INVALID_CHECKS');
    running=true;
    const report={source:'browser-capability-test',request_id:id,checks:[],request_count:0,limits:{context:'NOT_TESTED',concurrency:'NOT_TESTED',images:'NOT_TESTED',autonomous_tools:'NOT_TESTED'}};
    async function infer(messages,extra={},stream=false) {
      if (!active) fail('CAPTURE_STOPPED');
      const controller=new AbortController(), timeout=setTimeout(()=>controller.abort(),30000);
      controllers.add(controller);
      try {
        report.request_count++;
        const response=await nativeFetch.call(root,call.address,{method:'POST',credentials:call.credentials,redirect:'error',headers:{...call.headers,'Content-Type':'application/json'},
          body:JSON.stringify({model:call.model,messages,max_tokens:128,stream,...extra}),signal:controller.signal});
        if (!response.ok) fail(response.status===401||response.status===403?'AUTH_REQUIRED':response.status===429?'RATE_LIMITED':'HTTP_'+response.status);
        if (stream && !response.headers.get('content-type')?.includes('text/event-stream')) fail('EXPECTED_SSE');
        return await readBounded(response,controller.signal,stream);
      } finally { clearTimeout(timeout); controllers.delete(controller); }
    }
    const user=content=>[{role:'user',content}];
    const marker=()=> 'FW_'+root.crypto.randomUUID().replace(/-/g,'').slice(0,12);
    try {
      for (const name of [...new Set(['basic',...checks])]) {
        try {
          let evidence={};
          if (name==='basic') {
            const value=await infer(user('Reply with exactly FW_PROBE_OK.'));
            if (!extractText(value,call.protocol).includes('FW_PROBE_OK')) fail('EXPECTED_TEXT_MISSING'); evidence={usage:usageOf(value)};
          } else if (name==='multi_turn') {
            const code=marker(); const value=await infer([{role:'user',content:'Remember this code: '+code},{role:'assistant',content:'I will remember the code.'},{role:'user',content:'Return only the code from the first message.'}]);
            if (!extractText(value,call.protocol).includes(code)) fail('HISTORY_NOT_CONFIRMED'); evidence={caller_supplied_history_verified:true};
          } else if (name==='structured') {
            const value=await infer(user('Return only a JSON object exactly {"probe":"FW_JSON_OK","n":7}.'),call.protocol==='chat_completions'?{response_format:{type:'json_object'}}:{});
            const parsed=JSON.parse(extractText(value,call.protocol));
            if (parsed.probe!=='FW_JSON_OK'||parsed.n!==7||Object.keys(parsed).length!==2) fail('JSON_MISMATCH'); evidence={valid_json_observed:true,schema_enforcement:'NOT_TESTED'};
          } else if (name==='streaming') {
            const value=await infer(user('Reply with exactly FW_STREAM_OK.'),{},true); let text='',terminal=false;
            for(const event of value.events){
              if(event==='[DONE]'){terminal=call.protocol==='chat_completions';continue;}
              if(call.protocol==='chat_completions') text+=(event.choices||[]).map(c=>c.delta?.content||'').join('');
              else {text+=event.type==='content_block_delta'?(event.delta?.text||''):'';terminal ||= event.type==='message_stop';}
            }
            if(!terminal||!text.includes('FW_STREAM_OK'))fail('STREAM_NOT_CONFIRMED'); evidence={terminal_event:true,proxy_buffering:'NOT_TESTED'};
          } else if (name==='tools') {
            const chat=call.protocol==='chat_completions', schema={type:'object',properties:{code:{type:'string'}},required:['code'],additionalProperties:false};
            const extra=chat?{tools:[{type:'function',function:{name:'probe_echo',description:'Return a test code.',parameters:schema}}],tool_choice:{type:'function',function:{name:'probe_echo'}}}:
              {tools:[{name:'probe_echo',description:'Return a test code.',input_schema:schema}],tool_choice:{type:'tool',name:'probe_echo'}};
            const messages=user('Call probe_echo with code FW_TOOL_OK, then repeat the tool result exactly.'); const value=await infer(messages,extra);
            const assistant=chat?value.choices?.[0]?.message:{role:'assistant',content:value.content};
            const calls=chat?assistant?.tool_calls:value.content?.filter(b=>b.type==='tool_use');
            if(!Array.isArray(calls)||calls.length!==1)fail('EXPECTED_SINGLE_TOOL'); const tool=calls[0];
            const args=chat?JSON.parse(tool.function?.arguments):tool.input;
            if((chat?tool.function?.name:tool.name)!=='probe_echo'||args?.code!=='FW_TOOL_OK'||Object.keys(args).length!==1||typeof tool.id!=='string'||!tool.id)fail('TOOL_ARGUMENTS_MISMATCH');
            const code=marker(); messages.push({...assistant,role:'assistant'});messages.push(chat?{role:'tool',tool_call_id:tool.id,content:code}:{role:'user',content:[{type:'tool_result',tool_use_id:tool.id,content:code}]});
            extra.tool_choice=chat?'none':{type:'auto'}; const final=await infer(messages,extra);
            if(!extractText(final,call.protocol).includes(code))fail('TOOL_ROUNDTRIP_NOT_CONFIRMED'); evidence={forced_tool_roundtrip_verified:true};
          }
          report.checks.push({name,status:'PASS',evidence});
        } catch(error) {
          // Only our fixed codes may reach exported reports. Never export arbitrary server text.
          const code=typeof error.fwCode==='string'&&/^[A-Z][A-Z0-9_]{2,80}$/.test(error.fwCode)?error.fwCode:'RESPONSE_OR_NETWORK_ERROR';
          const blocked=['AUTH_REQUIRED','RATE_LIMITED'].includes(code);
          report.checks.push({name,status:blocked?'BLOCKED':'NOT_CONFIRMED',reason:code}); if(blocked) break;
        }
      }
      return report;
    } finally {running=false;}
  }
  root.FunnyWorkPOC=Object.freeze({version:'1.0',start,stop,snapshot,download,analyzeHAR,test});
})();
