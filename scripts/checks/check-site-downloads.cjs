const assert=require('node:assert/strict'),fs=require('node:fs'),{webcrypto,generateKeyPairSync,sign}=require('node:crypto'),{createRequire}=require('node:module'),path=require('node:path');
const {JSDOM}=createRequire(path.resolve('apps/desktop/package.json'))('jsdom');
const site=path.resolve(process.env.FUNNYWORK_DOWNLOAD_TEST_SITE||'designs/funnywork-site')+path.sep,html=fs.readFileSync(site+'download.html','utf8'),js=fs.readFileSync(site+'downloads.js','utf8'),good=fs.readFileSync(process.argv[2]||site+'downloads.json','utf8');
const changed=JSON.parse(good),changedSignature=Buffer.from(changed.signature,'base64');changedSignature[0]^=1;changed.signature=changedSignature.toString('base64');const tamperedRelease=JSON.stringify(changed);
const tick=()=>new Promise(resolve=>setTimeout(resolve,10));
async function settled(d){for(let i=0;i<100&&d.body.dataset.state==='checking';i++)await tick();assert.notEqual(d.body.dataset.state,'checking');}
function page(host,lang,script,response,options={}){
 const dom=new JSDOM(html,{url:options.url||'https://'+host+'/download/?lang='+lang,runScripts:'outside-only'}),w=dom.window;
 if(options.userAgent)Object.defineProperty(w.navigator,'userAgent',{value:options.userAgent});
 if(options.blockStorage)Object.defineProperty(w,'localStorage',{get(){throw new w.DOMException('Storage blocked','SecurityError');}});
 if(options.staleHtml)w.document.querySelector('[data-wechat-guide]').remove();
 const calls=[];w.cryptoCalls=calls;
 const subtle={importKey:async(...args)=>{calls.push(args[2].name);if(args[2].name==='Ed25519'&&options.noEd25519)throw new w.DOMException('Unrecognized algorithm name',options.noEd25519);return webcrypto.subtle.importKey(...args);},verify:async(...args)=>options.rejectNative&&args[0]==='Ed25519'?false:webcrypto.subtle.verify(...args)};
 Object.defineProperty(w.crypto,'subtle',{value:options.noCrypto?undefined:subtle});w.TextDecoder=options.noTextDecoder?undefined:TextDecoder;w.AbortController=options.noAbort?undefined:options.brokenAbort?function(){throw new Error('AbortController unavailable');}:AbortController;
 if(options.timeout){const set=w.setTimeout.bind(w);w.setTimeout=(fn,ms)=>set(fn,ms===15000?1:ms);}
 w.xhrCalls=[];w.clipboardWrites=[];
 w.fetch=options.noFetch?undefined:async()=>{if(options.timeout)return new Promise(()=>{});if(options.networkError)throw new TypeError('Failed to fetch');return {ok:!options.httpStatus||options.httpStatus<300,url:options.responseUrl,redirected:options.redirected,text:response};};
 w.XMLHttpRequest=options.noXhr?undefined:class{
  open(method,url,async){w.xhrCalls.push({method,url,async});}setRequestHeader(){}abort(){if(this.onabort)this.onabort();}
  send(){if(options.timeout)return;Promise.resolve().then(async()=>{if(options.networkError){this.onerror();return;}this.status=options.httpStatus||200;this.responseURL=options.responseUrl||'https://'+host+'/downloads.json';this.responseText=await response();this.onload();});}
 };
 Object.defineProperty(w.navigator,'clipboard',{value:options.clipboard==='success'?{writeText:async value=>{w.clipboardWrites.push(value);}}:options.clipboard==='denied'?{writeText:async()=>{throw new w.DOMException('Clipboard denied','NotAllowedError');}}:undefined});
 w.document.execCommand=()=>options.legacyCopy===true;w.eval(script);return dom;
}
(async()=>{
 const published=JSON.parse(Buffer.from(JSON.parse(good).payload,'base64')),publishedCount=published.artifacts.length+(published.testflight&&Date.parse(published.testflight.expires)>Date.now()?1:0);
 for(const host of ['regional.example.invalid','funnywork.funnypixelsapp.com'])for(const lang of ['zh','en'])for(const tampered of [false,true]){
  const dom=page(host,lang,js,async()=>tampered?tamperedRelease:good),w=dom.window,d=w.document;
  await settled(d);const links=[...d.querySelectorAll('[data-installer]')].filter(a=>!a.hidden);assert.equal(links.length,tampered?0:publishedCount);
  if(!tampered){assert(links.every(a=>a.closest('[data-target]').dataset.target==='ios'?a.href===published.testflight.url:new URL(a.href).host===host));d.querySelector('[data-lang="'+(lang==='en'?'zh':'en')+'"]').click();assert(d.querySelector('[data-target="android"] [data-installer]').textContent.includes(lang==='en'?'下载':'Download'));}
  else assert.equal(d.body.dataset.state,'failed');w.close();
 }
 // A real signed fixture checks newly enabled targets and revocation of stale links after a failed retry.
 const {privateKey,publicKey}=generateKeyPairSync('ed25519'),now=Date.now();
 const fixture={schema:1,issued:new Date(now-1000).toISOString(),expires:new Date(now+86400000).toISOString(),testflight:{url:'https://testflight.apple.com/join/zEQNT6HH',version:'0.1.0',build:'18',expires:new Date(now+3600000).toISOString()},artifacts:[{target:'mac-x64',version:'0.2.1-alpha.1',bytes:1000,path:'/dsh-desk/releases/intel.dmg',sha256:'a'.repeat(64),signer:'Developer ID Application'}]};const payload=Buffer.from(JSON.stringify(fixture));
 const ec=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
 const message=(payload,signature)=>Buffer.concat([Buffer.from('FunnyWork downloads compatibility v1\0'),payload,signature]);
 const signedFixture=value=>{const b=Buffer.from(JSON.stringify(value)),signature=sign(null,b,privateKey);return JSON.stringify({payload:b.toString('base64'),signature:signature.toString('base64'),compatSignature:sign('sha256',message(b,signature),{key:ec.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64')});};
 const envelope=signedFixture(fixture);let body=envelope;
 const fixtureScript=js.replace(/const trustedKey='[^']+'/,"const trustedKey='"+publicKey.export({type:'spki',format:'der'}).toString('base64')+"'").replace(/const compatKey='[^']+'/,"const compatKey='"+ec.publicKey.export({type:'spki',format:'der'}).toString('base64')+"'");
 const dom=page('regional.example.invalid','zh',fixtureScript,async()=>body),d=dom.window.document;await settled(d);
 const card=d.querySelector('[data-target="mac-x64"]'),link=card.querySelector('[data-installer]');
 assert.equal(link.hidden,false);assert.equal(card.querySelector('[data-copy="later"]').hidden,true);assert.equal(card.querySelector('[data-copy="pending"]').hidden,true);assert.equal(card.querySelector('details').hidden,false);
 assert.equal(d.querySelector('[data-target="win-x64"] [data-copy="later"]').hidden,false);
 const ios=d.querySelector('[data-target="ios"] [data-installer]');assert.equal(ios.hidden,false);assert.equal(ios.href,fixture.testflight.url);assert.equal(ios.hasAttribute('download'),false);assert.equal(ios.target,'_blank');assert(ios.rel.includes('noopener'));assert(ios.textContent.includes('加入'));
 d.querySelector('[data-lang="en"]').click();assert(card.querySelector('[data-copy="intelNote"]').textContent.includes('Intel Macs'));assert.equal(card.querySelector('[data-copy="later"]').hidden,true);
 body=JSON.stringify({payload:payload.toString('base64'),signature:Buffer.alloc(64).toString('base64')});d.querySelector('#retry').click();
 assert.equal(link.hidden,true);assert.equal(link.hasAttribute('href'),false);assert.equal(card.querySelector('[data-hash]').textContent,'');assert.equal(card.querySelector('[data-version]').textContent,'');
 await settled(d);assert.equal(d.body.dataset.state,'failed');assert.equal(card.querySelector('details').hidden,true);assert.equal(card.querySelector('[data-copy="later"]').hidden,false);
 body=envelope;d.querySelector('#retry').click();await settled(d);assert.equal(link.hidden,false);assert.equal(card.querySelector('[data-copy="pending"]').hidden,true);assert.equal(ios.hidden,false);assert(ios.textContent.includes('Join'));
 body=signedFixture({...fixture,testflight:{...fixture.testflight,expires:new Date(now-100).toISOString()}});d.querySelector('#retry').click();await settled(d);assert.equal(d.body.dataset.state,'ready');assert.equal(link.hidden,false);assert.equal(ios.hidden,true);assert.equal(ios.hasAttribute('href'),false);
 body=signedFixture({...fixture,testflight:{...fixture.testflight,url:'https://testflight.apple.com.evil.example/join/zEQNT6HH'}});d.querySelector('#retry').click();await settled(d);assert.equal(d.body.dataset.state,'failed');assert.equal(ios.hidden,true);assert.equal(link.hidden,true);dom.window.close();
 const compatFixture={...fixture,artifacts:[...fixture.artifacts,...published.artifacts.filter(a=>a.target==='android')]},compatBody=signedFixture(compatFixture);
 for(const host of ['regional.example.invalid','funnywork.funnypixelsapp.com'])for(const lang of ['zh','en'])for(const noEd25519 of ['NotSupportedError','TypeError']){
  const dom=page(host,lang,fixtureScript,async()=>compatBody,{noEd25519,noAbort:true}),d=dom.window.document;await settled(d);
  assert.equal(d.body.dataset.state,'ready');const a=d.querySelector('[data-target="android"] [data-installer]');assert.equal(a.hidden,false);assert.equal(new URL(a.href).host,host);assert(a.href.endsWith('.apk'));assert(dom.window.cryptoCalls.includes('ECDSA'));dom.window.close();
 }
 for(const field of ['payload','signature','compatSignature']){
  const corrupted=JSON.parse(compatBody),bytes=Buffer.from(corrupted[field],'base64');bytes[0]^=1;corrupted[field]=bytes.toString('base64');
  for(const options of [{},{noEd25519:'NotSupportedError'}]){const dom=page('regional.example.invalid','zh',fixtureScript,async()=>JSON.stringify(corrupted),options),d=dom.window.document;await settled(d);assert.equal(d.body.dataset.state,'failed');assert.equal(d.body.dataset.failure,'invalid');assert.equal(d.querySelector('[data-target="android"] [data-installer]').hidden,true);dom.window.close();}
 }
 const refused=page('regional.example.invalid','en',fixtureScript,async()=>compatBody,{rejectNative:true});await settled(refused.window.document);assert.equal(refused.window.document.body.dataset.state,'failed');assert(!refused.window.cryptoCalls.includes('ECDSA'));refused.window.close();
 for(const [options,reason]of [[{noCrypto:true},'unsupported'],[{networkError:true},'network'],[{timeout:true,noAbort:true},'network']]){const dom=page('regional.example.invalid','en',fixtureScript,async()=>compatBody,options),d=dom.window.document;await settled(d);assert.equal(d.body.dataset.failure,reason);assert(d.querySelector('#status').textContent.includes(reason==='network'?'connection':'browser'));assert.equal(d.querySelector('[data-target="android"] [data-installer]').hidden,true);dom.window.close();}
 const wrongKey=generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey.export({type:'spki',format:'der'}).toString('base64'),wrongKeyScript=fixtureScript.replace(/const compatKey='[^']+'/,"const compatKey='"+wrongKey+"'");
 const wrong=page('regional.example.invalid','en',wrongKeyScript,async()=>compatBody,{noEd25519:'NotSupportedError'});await settled(wrong.window.document);assert.equal(wrong.window.document.body.dataset.failure,'invalid');assert.equal(wrong.window.document.querySelector('[data-target="android"] [data-installer]').hidden,true);wrong.window.close();
 const legacy=page('regional.example.invalid','en',fixtureScript,async()=>{const b=JSON.parse(compatBody);delete b.compatSignature;return JSON.stringify(b);},{noEd25519:'NotSupportedError'});await settled(legacy.window.document);assert.equal(legacy.window.document.body.dataset.failure,'unsupported');legacy.window.close();
 console.log('Legacy browser: missing Ed25519 and AbortController, bilingual Android buttons, bound dual signatures, invalid-signature refusal and distinct errors PASS');
 const profiles=require('./mobile-browser-profiles.cjs');let profileCases=0;
 for(const host of ['regional.example.invalid','funnywork.funnypixelsapp.com'])for(const lang of ['zh','en'])for(const profile of profiles)for(const capability of [{},{noEd25519:'NotSupportedError'},{noEd25519:'TypeError',noFetch:true,noTextDecoder:true,noAbort:true,blockStorage:true}]){
  const dom=page(host,lang,fixtureScript,async()=>compatBody,{userAgent:profile.userAgent,...capability}),d=dom.window.document;await settled(d);
  assert.equal(d.body.dataset.state,'ready',profile.name);const a=d.querySelector('[data-target="android"] [data-installer]');assert.equal(a.hidden,false);assert.equal(new URL(a.href).host,host);assert(a.download.endsWith('.apk'));assert.equal(d.querySelector('[data-wechat-guide]').hidden,!profile.wechat);
  if(profile.android)assert.equal(d.querySelector('.download-grid article').dataset.target,'android');
  assert.equal([...d.querySelectorAll('[data-installer]')].filter(a=>!a.hidden).length,compatFixture.artifacts.length+1);
  if(capability.noFetch)assert.deepEqual(dom.window.xhrCalls,[{method:'GET',url:'/downloads.json',async:true}]);
  profileCases++;dom.window.close();
 }
 for(const capability of [{brokenAbort:true},{noFetch:true,httpStatus:403},{noFetch:true,responseUrl:'https://evil.example/downloads.json'},{responseUrl:'https://evil.example/downloads.json'},{redirected:true},{noFetch:true,noXhr:true},{noFetch:true,noAbort:true,timeout:true}]){
  const dom=page('regional.example.invalid','zh',fixtureScript,async()=>compatBody,capability),d=dom.window.document;await settled(d);
  assert.equal(d.body.dataset.state,capability.brokenAbort?'ready':'failed');if(!capability.brokenAbort)assert.equal(d.querySelector('[data-target="android"] [data-installer]').hidden,true);dom.window.close();
 }
 const wechat=profiles.find(p=>p.name==='WeChat Android X5').userAgent;
 for(const host of ['regional.example.invalid','funnywork.funnypixelsapp.com'])for(const lang of ['zh','en'])for(const copy of [{clipboard:'success'},{clipboard:'denied',legacyCopy:true},{clipboard:'denied'},{}]){
  const dom=page(host,lang,fixtureScript,async()=>compatBody,{userAgent:wechat,url:'https://'+host+'/download/?lang='+lang+'&token=sample-not-a-real-token&from=singlemessage',...copy}),d=dom.window.document;await settled(d);d.querySelector('[data-copy-page]').click();await tick();
  const expected='https://'+host+'/download/?lang='+lang+'#android',input=d.querySelector('[data-download-page-link]');assert.equal(input.value,expected);assert.equal(input.readOnly,true);assert(!input.value.includes('token='));
  if(copy.clipboard==='success')assert.deepEqual(dom.window.clipboardWrites,[expected]);
  const status=d.querySelector('[data-copy-status]').textContent;assert(status.includes(copy.clipboard==='success'||copy.legacyCopy?(lang==='zh'?'已复制':'copied'):(lang==='zh'?'长按':'Touch and hold')));
  d.querySelector('[data-lang="'+(lang==='zh'?'en':'zh')+'"]').click();assert.equal(input.value,'https://'+host+'/download/?lang='+(lang==='zh'?'en':'zh')+'#android');assert.equal(d.querySelector('[data-copy-status]').textContent,'');dom.window.close();
 }
 const stale=page('regional.example.invalid','zh',fixtureScript,async()=>compatBody,{userAgent:wechat,staleHtml:true});await settled(stale.window.document);assert.equal(stale.window.document.body.dataset.state,'ready');assert.equal(stale.window.document.querySelector('[data-target="android"] [data-installer]').hidden,false);stale.window.close();
 const restricted=page('regional.example.invalid','zh',fixtureScript,async()=>compatBody,{userAgent:wechat,noCrypto:true}),rd=restricted.window.document;await settled(rd);assert.equal(rd.body.dataset.failure,'unsupported');assert(rd.querySelector('#status').textContent.includes('浏览器打开'));assert.equal(rd.querySelector('[data-wechat-guide]').hidden,false);rd.querySelector('[data-copy-page]').click();await tick();assert(rd.querySelector('[data-copy-status]').textContent.includes('长按'));assert.equal(rd.querySelector('[data-target="android"] [data-installer]').hidden,true);restricted.window.close();
 const blocked=page('regional.example.invalid','zh',fixtureScript,async()=>tamperedRelease,{userAgent:wechat}),bd=blocked.window.document;await settled(bd);assert.equal(bd.querySelector('[data-target="android"] [data-installer]').hidden,true);assert.equal(bd.querySelector('[data-wechat-guide]').hidden,false);blocked.window.close();
 console.log('Mobile profiles: '+profileCases+' regional/language/capability cases; Android ordering, WeChat-only guidance, XHR and UTF-8 fallbacks, clipboard/manual copy and redirect refusal PASS');
 console.log('Download page: regional links, bilingual switching, signed availability, failed retry cleanup and recovery PASS');
})().catch(e=>{console.error(e);process.exitCode=1});
