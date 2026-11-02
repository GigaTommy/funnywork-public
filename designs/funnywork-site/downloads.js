/* Verify release metadata before enabling same-origin installer links. This does not replace OS signature checks. */
(()=>{'use strict';
const trustedKey='MCowBQYDK2VwAyEAJnZSjErILZs7Jpl4tZqcOVA8+470WnocdhYRYVpTlz4=';
const compatKey='MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE3vIypwb9ah+jLtj9UIL+giA3Cg+SforSVuutIjVlyXJKDSeyQGYhEyfakt2sKo2NXot/6xL1YAWJZjHv+TRtUQ==';
const words={zh:{title:'下载 FunnyWork',lead:'安装 FunnyWork，登录后使用。',download:'下载安装包',checking:'正在核验下载信息…',ready:'下载信息已核验。安装时请确认发布者，并保留系统安全检查。',failed:'下载信息暂时无法核验。请稍后重试，或联系客服。',network:'下载信息暂时无法加载，请检查网络后重试。',invalid:'下载信息校验未通过，请重试或联系客服。',unsupported:'当前浏览器暂不支持下载校验，请更新浏览器或联系客服。',version:'版本',checksum:'SHA-256 校验值',retry:'重新核验',later:'准备中',contact:'联系珠珠',mac:'Mac · Apple 芯片',intel:'Mac · Intel',windows:'Windows',unsigned:'未签名版',android:'Android',ios:'iPhone / iPad',iosNote:'iOS 17 及以上。可用 TestFlight 邀请在核验后显示。',iosLater:'暂无可用邀请',iosDownload:'加入 TestFlight',iosReady:'iOS 17 及以上，通过 Apple TestFlight 安装到 iPhone 或 iPad。',iosExpired:'体验构建已到期，请检查最新邀请或联系客服。',macNote:'macOS 13 及以上，适用于 Apple 芯片 Mac。',intelNote:'macOS 13 及以上，适用于 Intel Mac。',wechatUnsupported:'微信内暂无法完成校验，请按安卓卡片中的提示在浏览器打开。',wechatTitle:'微信下载帮助',wechatHelp:'微信可能限制安装包下载。点右上角 ⋯，选择“在浏览器打开”后再下载。',copyPage:'复制下载页链接',pageLinkLabel:'下载页链接',copying:'正在复制…',copied:'已复制，请粘贴到手机浏览器打开。',manualCopy:'请长按上方链接复制，再粘贴到手机浏览器打开。',androidNote:'Android 10 及以上。下载 APK 后按系统提示确认安装。',pending:'安装包验证完成后开放下载。',back:'返回官网',privacy:'隐私与网站说明',security:'安全与架构',hint:'选择设备对应版本；手机聊天使用有效账号，远程任务需同账号电脑在线。',licenseSummary:'个人学习等许可范围内的非商业使用免费；企业内部使用及其他商业用途需先取得商业授权。',history:'更新与反馈',historyNote:'Mac 可在应用内检查更新，Android 新版在此页下载。遇到问题可在 App 内反馈，或联系客服。'},en:{title:'Get FunnyWork',lead:'Sign in to start using FunnyWork.',download:'Download installer',checking:'Verifying download information…',ready:'Download information verified. Check the publisher during installation and keep system security checks enabled.',failed:'Download information could not be verified. Try again later or contact support.',network:'Download information could not be loaded. Check your connection and try again.',invalid:'Download verification failed. Try again or contact support.',unsupported:'This browser cannot verify downloads. Update your browser or contact support.',version:'Version',checksum:'SHA-256 checksum',retry:'Verify again',later:'Coming soon',contact:'Contact Gigi',mac:'Mac · Apple silicon',intel:'Mac · Intel',windows:'Windows',unsigned:'Unsigned',android:'Android',ios:'iPhone / iPad',iosNote:'iOS 17 or later. Available TestFlight invitations appear after verification.',iosLater:'No invitation available',iosDownload:'Join TestFlight',iosReady:'iOS 17 or later. Install with Apple TestFlight on your iPhone or iPad.',iosExpired:'This build has expired. Check for a current invitation or contact support.',macNote:'macOS 13 or later. For Apple silicon Macs.',intelNote:'macOS 13 or later. For Intel Macs.',wechatUnsupported:'WeChat cannot verify this download. Follow the Android card instructions to open it in your browser.',wechatTitle:'Download help for WeChat',wechatHelp:'WeChat may limit APK downloads. Tap ⋯ at the top right and choose Open in browser, then download.',copyPage:'Copy download page link',pageLinkLabel:'Download page link',copying:'Copying…',copied:'Link copied. Paste it into your phone browser.',manualCopy:'Touch and hold the link above to copy it, then paste it into your phone browser.',androidNote:'Android 10 or later. Download the APK and follow the system installation prompts.',pending:'Downloads open after package verification.',back:'Back to website',privacy:'Privacy & website information',security:'Security & architecture',hint:'Choose your device version. Phone chat needs an active account; remote tasks need an online computer on the same account.',licenseSummary:'Personal learning and other permitted non-commercial uses are free of license fees. Enterprise internal use and other commercial purposes require a commercial license first.',history:'Updates & feedback',historyNote:'Check for Mac updates in the app. New Android releases appear here. Send feedback in the app or contact support if you need help.'}};
let language='zh',release,sequence=0,copyState='',copySequence=0;
const userAgent=navigator.userAgent||'',androidDevice=/Android|\bAdr\b/i.test(userAgent),wechatAndroid=androidDevice&&/MicroMessenger/i.test(userAgent);
const androidCard=document.querySelector('[data-target="android"]'),guide=document.querySelector('[data-wechat-guide]'),pageLink=document.querySelector('[data-download-page-link]'),copyPageButton=document.querySelector('[data-copy-page]'),copyFeedback=document.querySelector('[data-copy-status]');
if(androidDevice&&androidCard)androidCard.parentNode.insertBefore(androidCard,androidCard.parentNode.firstElementChild);
function downloadPageUrl(){const url=new URL('/download/',location.origin);url.searchParams.set('lang',language);url.hash='android';return url.href;}
function renderGuide(){
 if(!guide||!pageLink||!copyPageButton||!copyFeedback)return;
 const t=words[language];guide.hidden=!wechatAndroid;guide.setAttribute('aria-label',t.wechatTitle);pageLink.setAttribute('aria-label',t.pageLinkLabel);
 const url=downloadPageUrl();if(pageLink.value!==url)pageLink.value=url;
 copyPageButton.disabled=copyState==='copying';copyFeedback.textContent=copyState?t[copyState]:'';
}
const decode=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));
function render(){
 const t=words[language];document.documentElement.lang=language==='en'?'en':'zh-CN';document.title=t.title+' — FunnyWork';
 document.querySelectorAll('[data-copy]').forEach(e=>e.textContent=t[e.dataset.copy]);
 document.querySelectorAll('[data-lang]').forEach(e=>e.setAttribute('aria-pressed',String(e.dataset.lang===language)));
 document.querySelectorAll('[data-page]').forEach(e=>e.href=e.dataset.page+'?lang='+language);
 document.querySelectorAll('[data-target]').forEach(card=>{
  const link=card.querySelector('[data-installer]');
  const snapshotValid=Date.parse(document.body.dataset.releaseExpires)>Date.now();
  if(card.dataset.target==='ios'){
   const invite=release?release.testflight:snapshotValid&&card.dataset.releaseBuild?{version:card.dataset.releaseVersion,build:card.dataset.releaseBuild,expires:card.dataset.releaseExpires}:null,active=Boolean(invite&&Date.parse(invite.expires)>Date.now()),verifiedActive=Boolean(release&&active);
   link.hidden=!verifiedActive;link.removeAttribute('download');card.querySelector('details').hidden=true;
   card.querySelector('[data-copy="iosLater"]').hidden=active;
   card.querySelector('[data-copy="iosNote"]').textContent=active?t.iosReady:invite?t.iosExpired:t.iosNote;
   card.querySelector('[data-version]').textContent=active?t.version+' '+invite.version+' ('+invite.build+')':'';
   if(verifiedActive){link.href=invite.url;link.textContent=t.iosDownload+' ↗';link.target='_blank';link.rel='noopener noreferrer';}
   else{link.removeAttribute('href');link.textContent='';}
   return;
  }
  const verifiedArtifact=release&&release.artifacts.find(a=>a.target===card.dataset.target);
  const artifact=verifiedArtifact||(!release&&snapshotValid&&card.dataset.releaseVersion?{version:card.dataset.releaseVersion,bytes:Number(card.dataset.releaseBytes)}:null);
  link.hidden=!verifiedArtifact;
  card.querySelector('details').hidden=!verifiedArtifact;
  card.querySelectorAll('[data-copy="later"],[data-copy="pending"]').forEach(e=>e.hidden=Boolean(artifact));
  card.querySelector('[data-version]').textContent=artifact?t.version+' '+artifact.version+' · '+(artifact.bytes/1048576).toFixed(1)+' MB':'';
  card.querySelector('[data-hash]').textContent=verifiedArtifact?verifiedArtifact.sha256:'';
  if(verifiedArtifact){link.href=verifiedArtifact.path;link.download=verifiedArtifact.path.split('/').pop();link.textContent=t.download+(verifiedArtifact.signing==='unsigned'?' · '+t.unsigned:'')+' ↗';}
  else{link.removeAttribute('href');link.textContent='';card.querySelector('details').open=false;}
 });
 document.getElementById('status').textContent=t[release?'ready':document.body.dataset.state==='failed'?(document.body.dataset.failure||'failed'):document.body.dataset.state||'checking'];
 if(wechatAndroid&&guide&&!release&&document.body.dataset.failure==='unsupported')document.getElementById('status').textContent=t.wechatUnsupported;
 renderGuide();
 window.fwSetSearchMetadata?.(language);
}
function validate(data){const now=Date.now();if(data.schema!==1||!Number.isFinite(Date.parse(data.issued))||!Number.isFinite(Date.parse(data.expires))||Date.parse(data.issued)>now+300000||Date.parse(data.expires)<=now||Date.parse(data.expires)-Date.parse(data.issued)>180*86400000||!Array.isArray(data.artifacts)||data.artifacts.length>10)throw Error('Invalid release');const seen=new Set();for(const a of data.artifacts){if(!['mac-arm64','mac-x64','win-x64','android'].includes(a.target)||seen.has(a.target)||!/^[a-zA-Z0-9._-]{1,80}$/.test(a.version)||!/^\/dsh-desk\/(?:releases|android)\/[a-zA-Z0-9._/-]+$/.test(a.path)||a.path.includes('..')||a.path.includes('//')||!a.path.endsWith({android:'.apk','mac-arm64':'.dmg','mac-x64':'.dmg','win-x64':'.exe'}[a.target])||!a.signer||/debug/i.test(a.path+' '+a.signer)||(/unsigned/i.test(a.path+' '+a.signer)||a.signing==='unsigned'||a.channel==='test')&&!(a.target==='win-x64'&&a.signing==='unsigned'&&a.channel==='test'&&/unsigned/i.test(a.path)&&/unsigned/i.test(a.signer))||!/^[a-f0-9]{64}$/.test(a.sha256)||!Number.isSafeInteger(a.bytes)||a.bytes<=0)throw Error('Invalid artifact');seen.add(a.target);}const f=data.testflight;if(f!==undefined&&(!f||typeof f.url!=='string'||!/^https:\/\/testflight\.apple\.com\/join\/[A-Za-z0-9]{8}$/.test(f.url)||typeof f.version!=='string'||!/^[a-zA-Z0-9._-]{1,80}$/.test(f.version)||typeof f.build!=='string'||!/^\d{1,20}$/.test(f.build)||typeof f.expires!=='string'||!Number.isFinite(Date.parse(f.expires))||Date.parse(f.expires)<=Date.parse(data.issued)||Date.parse(f.expires)>Date.parse(data.expires)))throw Error('Invalid TestFlight invitation');return data;}
function failure(reason){const error=new Error(reason);error.downloadReason=reason;return error;}
function compatibilityMessage(bytes,signature){const prefix=Uint8Array.from('FunnyWork downloads compatibility v1\0',c=>c.charCodeAt(0)),message=new Uint8Array(prefix.length+bytes.length+signature.length);message.set(prefix);message.set(bytes,prefix.length);message.set(signature,prefix.length+bytes.length);return message;}
async function verifyEnvelope(envelope,bytes){
 const signature=decode(envelope.signature);if(signature.length!==64)throw failure('invalid');
 const subtle=typeof crypto!=='undefined'&&crypto.subtle;if(!subtle||typeof subtle.importKey!=='function'||typeof subtle.verify!=='function')throw failure('unsupported');
 let nativeVerified=false;
 try{const key=await subtle.importKey('spki',decode(trustedKey),{name:'Ed25519'},false,['verify']);if(!await subtle.verify('Ed25519',key,signature,bytes))throw failure('invalid');nativeVerified=true;}
 catch(error){if(error.name!=='NotSupportedError'&&error.name!=='TypeError')throw error;}
 if(envelope.compatSignature!==undefined){const compatSignature=decode(envelope.compatSignature);if(compatSignature.length!==64)throw failure('invalid');let key;
  try{key=await subtle.importKey('spki',decode(compatKey),{name:'ECDSA',namedCurve:'P-256'},false,['verify']);}catch(error){if(error.name==='NotSupportedError'||error.name==='TypeError')throw failure('unsupported');throw error;}
  if(!await subtle.verify({name:'ECDSA',hash:'SHA-256'},key,compatSignature,compatibilityMessage(bytes,signature)))throw failure('invalid');
 }else if(!nativeVerified)throw failure('unsupported');
}
function decodePayload(bytes){
 if(typeof TextDecoder==='function')return new TextDecoder().decode(bytes);
 let encoded='';for(const byte of bytes)encoded+='%'+('0'+byte.toString(16)).slice(-2);return decodeURIComponent(encoded);
}
function checkMetadataUrl(value){if(!value)return;const url=new URL(value,location.href);if(url.origin!==location.origin||url.pathname!=='/downloads.json'||url.search||url.hash||url.username||url.password)throw failure('network');}
function requestMetadata(controller){
 if(typeof fetch==='function'){
  const options={cache:'no-store',redirect:'error'};if(controller)options.signal=controller.signal;
  return fetch('/downloads.json',options).then(response=>{if(!response.ok||response.redirected)throw failure('network');checkMetadataUrl(response.url);return response.text();}).catch(()=>{throw failure('network');});
 }
 if(typeof XMLHttpRequest!=='function')return Promise.reject(failure('unsupported'));
 return new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest();
  xhr.onload=()=>{try{if(xhr.status<200||xhr.status>=300)throw failure('network');checkMetadataUrl(xhr.responseURL);resolve(xhr.responseText);}catch(error){reject(failure('network'));}};
  xhr.onerror=xhr.ontimeout=xhr.onabort=()=>reject(failure('network'));
  try{xhr.open('GET','/downloads.json',true);xhr.timeout=15000;xhr.setRequestHeader('Cache-Control','no-cache');if(controller&&controller.signal&&typeof controller.signal.addEventListener==='function')controller.signal.addEventListener('abort',()=>xhr.abort(),{once:true});xhr.send();}catch(error){reject(failure('network'));}
 });
}
async function load(){
 const token=++sequence;release=undefined;document.body.dataset.state='checking';delete document.body.dataset.failure;render();
 let controller=null,timer;
 try{
  if(typeof AbortController==='function')try{controller=new AbortController();}catch(error){}
  const timeout=new Promise((resolve,reject)=>{timer=setTimeout(()=>{if(controller&&typeof controller.abort==='function')try{controller.abort();}catch(error){}reject(failure('network'));},15000);});
  const text=await Promise.race([requestMetadata(controller),timeout]);if(text.length>50000)throw failure('invalid');
  const envelope=JSON.parse(text),bytes=decode(envelope.payload);if(bytes.length>32768)throw failure('invalid');await verifyEnvelope(envelope,bytes);
  const data=validate(JSON.parse(decodePayload(bytes)));if(token===sequence){release=data;document.body.dataset.state='ready';}
 }catch(error){if(token===sequence){document.body.dataset.state='failed';document.body.dataset.failure=error.downloadReason||'invalid';}}
 finally{clearTimeout(timer);if(token===sequence)render();}
}
document.querySelectorAll('[data-lang]').forEach(b=>b.addEventListener('click',()=>{language=b.dataset.lang;copySequence++;copyState='';const url=new URL(location.href);url.searchParams.set('lang',language);history.replaceState(null,'',url);try{localStorage.setItem('funnywork-site-language',language);}catch(error){}render();}));document.getElementById('retry').addEventListener('click',load);
if(copyPageButton&&guide&&pageLink&&copyFeedback)copyPageButton.addEventListener('click',async()=>{
 const token=++copySequence,text=downloadPageUrl();copyState='copying';renderGuide();let copied=false;
 try{if(navigator.clipboard&&typeof navigator.clipboard.writeText==='function'){await navigator.clipboard.writeText(text);copied=true;}}catch(error){}
 if(token!==copySequence)return;
 if(!copied){pageLink.focus();pageLink.select();if(typeof pageLink.setSelectionRange==='function')pageLink.setSelectionRange(0,text.length);try{copied=typeof document.execCommand==='function'&&document.execCommand('copy')===true;}catch(error){}}
 copyState=copied?'copied':'manualCopy';renderGuide();
});
language=(new URLSearchParams(location.search).get('lang')||(document.documentElement.lang==='en'?'en':'zh'))==='en'?'en':'zh';render();load();
})();
