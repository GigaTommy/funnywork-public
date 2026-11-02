#!/bin/bash
# Standalone macOS Chrome inspector. No Node/Python, browser restart, or profile access.
set -eu
OUT=${1:-"$(cd "$(dirname "$0")" && pwd)/qianwen-report-$(date +%Y%m%d-%H%M%S).json"}
mkdir -p "$(dirname "$OUT")"
umask 077
REPORT=$(mktemp "${OUT}.partial.XXXXXX")
trap 'rm -f "$REPORT"' EXIT
osascript -l JavaScript <<'JXA' > "$REPORT"
ObjC.import('Foundation');
function sleep(s) { $.NSThread.sleepForTimeInterval(s); }
function say(s) { console.log(s); }
function run() {
  // Address each running Chrome by PID: name-based Apple Events may select a headless instance.
  var sys = Application('System Events');
  var processes = sys.applicationProcesses.whose({name:'Google Chrome'})();
  var match, chrome;
  for (var p of processes) {
    var app = Application(p.unixId());
    try {
      for (var win of app.windows()) for (var tab of win.tabs()) {
        if (/^https:\/\/(www\.)?qianwen\.com(?:\/|$)/i.test(tab.url())) {
          match = tab; chrome = app; break;
        }
      }
    } catch (_) {}
    if (match) break;
  }
  if (!match) throw new Error('未找到千问官网标签页。请在 Chrome 打开 https://www.qianwen.com 并登录后重试。');
  var report = {version:1, captured_at:new Date().toISOString(), site:'https://www.qianwen.com',
    scope:'passive request metadata; one synthetic UI message in a new tab',
    excludes:['cookies','tokens','chat text','request values','response body'],
    test:{sent:false}, observations:[], limitations:[
      'Only fetch/XHR in the test document are instrumented; workers, WebSockets and redirects are not fully observed.',
      'Visible response headers are limited by CORS; missing headers do not prove server configuration.',
      'A successful website chat is not proof of a supported model API or reverse proxy compatibility.'
    ]};
  function evaluate(tab, code) { return tab.execute({javascript:code}); }
  // Read metadata only, never DOM chat content or browser storage.
  report.existing_page = JSON.parse(evaluate(match, '('+function(){
    const clean = raw => {try {const u=new URL(raw); return u.origin+u.pathname.split('/').map(p=>/[a-f0-9]{24,}/i.test(p)?'[id]':p).join('/');}catch{return null;}};
    return JSON.stringify({origin:location.origin,resources:[...new Set(performance.getEntriesByType('resource').filter(x=>/fetch|xmlhttprequest/.test(x.initiatorType)).map(x=>clean(x.name)))].slice(-60)});
  }.toString()+')()'));
  var testTab = chrome.Tab({url:'https://www.qianwen.com/'});
  chrome.windows[0].tabs.push(testTab);
  var instrumented=false;
  try {
    say('已找到千问页面；正在新标签页准备检测。');
    var ready=false;
    for(var i=0;i<30;i++) {
      sleep(1);
      try { ready=evaluate(testTab,"location.origin==='https://www.qianwen.com' && !!document.querySelector('[contenteditable=true][role=textbox],textarea')"); }catch(_){}
      if(ready)break;
    }
    if(!ready)throw new Error('测试页未出现输入框，请确认已登录。');
    var pageHook='('+function(){
      if(window.__fwQwenProbe)throw new Error('A probe is already active');
      let active=true;
      const records=[], originals={fetch:window.fetch,open:XMLHttpRequest.prototype.open,send:XMLHttpRequest.prototype.send,header:XMLHttpRequest.prototype.setRequestHeader};
      const meta=new WeakMap();
      const publish=()=>{if(active)document.documentElement.setAttribute('data-fw-probe-records',JSON.stringify(records));};
      const cleanKeys=o=>o&&typeof o==='object'?Object.keys(o).filter(k=>/^[a-zA-Z_][a-zA-Z0-9_.-]{0,63}$/.test(k)).slice(0,60):[];
      const shape=(o,d=0)=>{if(d>3)return Array.isArray(o)?'array':typeof o;if(Array.isArray(o))return o.length?[shape(o[0],d+1)]:[];if(o&&typeof o==='object'){const r={};for(const k of cleanKeys(o))r[k]=/token|password|cookie|secret|authorization/i.test(k)?'[redacted]':shape(o[k],d+1);return r;}return typeof o;};
      function begin(url,method,headers,body,transport){
        try{
          const u=new URL(url,location.href);if(!/^https?:$/.test(u.protocol))return null;
          if(/login|logout|oauth|password|\/token(?:\/|$)/i.test(u.pathname))return null;
          const r={transport,method:String(method||'GET').toUpperCase(),url:u.origin+u.pathname.split('/').map(p=>p.length>64||/[a-f0-9]{24,}/i.test(p)?'[id]':p).join('/'),query_keys:[...new Set(u.searchParams.keys())].filter(k=>/^[\w.-]{1,64}$/.test(k)),request_header_names:[],status:null};
          try{r.request_header_names=[...new Headers(headers||{}).keys()];}catch(_){}
          if(typeof body==='string'&&body.length<262144){try{const b=JSON.parse(body);r.request_shape=shape(b);for(const k of ['model','modelId','model_id'])if(typeof b[k]==='string'&&/^[a-zA-Z][\w./:-]{0,99}$/.test(b[k])){r.model=b[k];}}catch(_){r.body_format='non-json';}}
          if(records.length<100)records.push(r);publish();return r;
        }catch(_){return null;}
      }
      function end(r,status,headers){if(!r)return;r.status=status;try{const h=new Headers(headers);r.response_header_names=[...h.keys()];r.content_type=h.get('content-type');r.framing={x_frame_options:h.get('x-frame-options'),frame_ancestors:(h.get('content-security-policy')||'').match(/frame-ancestors[^;]*/i)?.[0]||null};}catch(_){}publish(); }
      const wrappedFetch=function(input,init){let r;try{r=begin(typeof input==='string'||input instanceof URL?String(input):input.url,init?.method||input?.method,init?.headers||input?.headers,init?.body,'fetch');}catch(_){}const p=Reflect.apply(originals.fetch,this,arguments);p.then(res=>end(r,res.status,res.headers),()=>{if(r)r.network_error=true;});return p;};
      const open=function(method,url){meta.set(this,{method,url,headers:{}});return Reflect.apply(originals.open,this,arguments);};
      const header=function(k,v){const m=meta.get(this);if(m)m.headers[k]='';return Reflect.apply(originals.header,this,arguments);};
      const send=function(body){const m=meta.get(this);if(m){const r=begin(m.url,m.method,m.headers,body,'xhr');this.addEventListener('loadend',()=>{let h={};try{for(const line of this.getAllResponseHeaders().trim().split(/[\r\n]+/)){const at=line.indexOf(':');if(at>0)h[line.slice(0,at)]=line.slice(at+1).trim();}}catch(_){}end(r,this.status,h);},{once:true});}return Reflect.apply(originals.send,this,arguments);};
      window.fetch=wrappedFetch;XMLHttpRequest.prototype.open=open;XMLHttpRequest.prototype.send=send;XMLHttpRequest.prototype.setRequestHeader=header;
      let timer;
      const stop=()=>{active=false;if(window.fetch===wrappedFetch)window.fetch=originals.fetch;if(XMLHttpRequest.prototype.open===open)XMLHttpRequest.prototype.open=originals.open;if(XMLHttpRequest.prototype.send===send)XMLHttpRequest.prototype.send=originals.send;if(XMLHttpRequest.prototype.setRequestHeader===header)XMLHttpRequest.prototype.setRequestHeader=originals.header;clearTimeout(timer);document.removeEventListener('fw-probe-stop',stop);delete window.__fwQwenProbe;};
      window.__fwQwenProbe={snapshot:()=>JSON.stringify(records),stop};timer=setTimeout(stop,90000);
      document.addEventListener('fw-probe-stop',stop,{once:true});publish();
      fetch(location.origin+'/',{method:'HEAD',credentials:'same-origin',redirect:'error'}).catch(()=>{});
      return true;
    }.toString()+')()';
    // Apple Events can execute in an isolated JS world. A DOM script installs the hook
    // in the page world; reuse the document's own CSP nonce without changing policy.
    evaluate(testTab,"(()=>{const s=document.createElement('script');const n=document.querySelector('script[nonce]');if(n)s.nonce=n.nonce;s.textContent="+JSON.stringify(pageHook)+";document.documentElement.appendChild(s);s.remove();return true;})()");
    if(!evaluate(testTab,"document.documentElement.hasAttribute('data-fw-probe-records')"))throw new Error('页面未允许采集器运行，未发送测试消息。');
    instrumented=true;
    var sent=evaluate(testTab,'('+function(){
      const input=document.querySelector('[contenteditable=true][role=textbox],textarea');
      if(!input)return 'INPUT_MISSING';
      const draft=input.cloneNode(true);
      draft.querySelectorAll('[contenteditable=false],[data-slate-zero-width]').forEach(e=>e.remove());
      if(String(input.value||draft.textContent||'').replace(/[\u200B\uFEFF]/g,'').trim())return 'INPUT_NOT_EMPTY';
      input.focus();
      if(input.tagName==='TEXTAREA'){Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,'请只回复 OK。');input.dispatchEvent(new Event('input',{bubbles:true}));}
      else {
        const data=new DataTransfer();data.setData('text/plain','请只回复 OK。');
        input.dispatchEvent(new ClipboardEvent('paste',{bubbles:true,cancelable:true,clipboardData:data}));
      }
      return 'READY';
    }.toString()+')()');
    if(sent!=='READY')throw new Error('输入框非空，已停止，未覆盖草稿。');
    sleep(1);
    var clicked=evaluate(testTab,"(()=>{const b=document.querySelector('button[aria-label=\"发送消息\"]');if(!b||b.disabled)return false;b.click();return true;})()");
    if(!clicked)throw new Error('未找到可用的发送按钮。');
    report.test.sent=true;
    say('已发送合成测试消息，采集请求元数据（最多 60 秒）。');
    for(var j=0;j<30;j++) {
      sleep(2);
      var done=evaluate(testTab,"(()=>{const rows=JSON.parse(document.documentElement.getAttribute('data-fw-probe-records')||'[]');return rows.some(r=>r.request_shape?.messages&&r.status===200&&r.content_type)&&Array.from(document.querySelectorAll('p,span,div')).some(e=>e.children.length===0&&/^OK[.。！!]?$/i.test(e.textContent.trim()));})()");
      if(done)break;
    }
    report.observations=JSON.parse(evaluate(testTab,"document.documentElement.getAttribute('data-fw-probe-records')||'[]'"));
    report.performance=JSON.parse(evaluate(testTab,'('+function(){return JSON.stringify(performance.getEntriesByType('resource').filter(e=>/fetch|xmlhttprequest/.test(e.initiatorType)).slice(-100).map(e=>{const u=new URL(e.name);return {url:u.origin+u.pathname.split('/').map(p=>/[a-f0-9]{24,}/i.test(p)?'[id]':p).join('/'),query_keys:[...u.searchParams.keys()].filter(k=>/^[\w.-]{1,64}$/.test(k)),status:e.responseStatus,content_type:e.contentType||null,protocol:e.nextHopProtocol,transport:e.initiatorType};}));}.toString()+')()'));
    report.test.synthetic_reply_exact_ok=evaluate(testTab,"Array.from(document.querySelectorAll('p,span,div')).some(e=>e.children.length===0&&/^OK[.。！!]?$/i.test(e.textContent.trim()))");
    report.test.completed_request_count=report.observations.filter(x=>x.status>=200&&x.status<300).length;
    report.test.inference_candidates=report.observations.filter(x=>x.request_shape?.messages&&/chat|completion|conversation|message/i.test(x.url)).map(x=>x.url);
    report.test.response_content_not_read=true;
  }catch(err){report.error=String(err.message||err);say('检测未完成：'+report.error);}
  finally{if(instrumented)try{evaluate(testTab,"document.dispatchEvent(new Event('fw-probe-stop'));document.documentElement.removeAttribute('data-fw-probe-records');true");}catch(_){} }
  // The synthetic test tab remains available for the user to inspect; never close existing tabs.
  report.test.tab_left_open=true;
  return JSON.stringify(report,null,2);
}
JXA
mv "$REPORT" "$OUT"
printf '报告已保存：%s\n' "$OUT"
