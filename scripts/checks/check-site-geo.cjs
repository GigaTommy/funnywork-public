#!/usr/bin/env node
// Verify the published bytes without JavaScript, then hydrate and switch languages.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const {webcrypto,createHash} = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const {JSDOM} = createRequire(path.join(root,'apps/desktop/package.json'))('jsdom');
const directories = process.argv.slice(2);
if (!directories.length) throw Error('Pass one or more build-site-geo output directories');
const primary = () => 'https://funnywork.funnypixelsapp.com';
let count=0;
(async()=>{
for (const directory of directories) {
  const dir=path.resolve(directory);
  for(const name of fs.readdirSync(path.join(dir,'static'))) {
    const match=/[.]([a-f0-9]{16})[.](?:js|css|png|jpg|webp|mp4|txt)$/.exec(name);assert(match,name);
    assert.equal(createHash('sha256').update(fs.readFileSync(path.join(dir,'static',name))).digest('hex').slice(0,16),match[1],name+' content hash');
  }
  const sitemap=new JSDOM(fs.readFileSync(path.join(dir,'sitemap.xml'),'utf8'),{contentType:'application/xml'});
  const urls=[...sitemap.window.document.querySelectorAll('loc')].map(n=>new URL(n.textContent));
  assert.equal(urls.length,14);
  const origin=urls[0].origin;
  assert(urls.every(u=>u.origin===origin&&!u.search));
  const robots=fs.readFileSync(path.join(dir,'robots.txt'),'utf8');
  assert(robots.includes('Sitemap: '+origin+'/sitemap.xml'));
  const disallows=[...robots.matchAll(/^Disallow:\s*(.+)$/gm)].map(m=>m[1]);
  for(const privatePath of ['/api/account/chats','/admin/index.html','/ruoyi/login','/actuator/health','/dsh-desk/releases/x']) assert(disallows.some(p=>privatePath.startsWith(p)));
  for(const bot of ['Baiduspider','Googlebot','OAI-SearchBot','KimiBot']) {
    assert(robots.includes('User-agent: '+bot+'\n'));
    assert(robots.includes('Disallow: /\nAllow: /$\n'));
    assert(robots.includes('Allow: /solutions.html$\n'));
    assert(!robots.includes('Allow: /api/'));
  }
  for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('.html'))) {
    const html=fs.readFileSync(path.join(dir,name),'utf8');
    const en=name.endsWith('.en.html'),lang=en?'en':'zh';
    const dom=new JSDOM(html,{url:origin+'/'+name,runScripts:'outside-only'}),w=dom.window,d=w.document;
    assert.equal(d.documentElement.lang,en?'en':'zh-CN');
    const repository=d.querySelector('footer a[href="https://github.com/GigaTommy/funnywork-public"]');
    assert(repository, name+' repository footer');
    assert.equal(repository.textContent,'GitHub · GigaTommy/funnywork-public');
    assert.equal(repository.target,'_blank');
    assert(repository.relList.contains('noopener') && repository.relList.contains('noreferrer'));
    assert(!/beian\.miit\.gov\.cn|ICP备/.test(d.querySelector('footer').outerHTML));
    if (name === 'index.html' || name === 'index.en.html') {
      const verification=JSON.parse(fs.readFileSync(path.join(root,'designs/funnywork-site/search-verification.json'),'utf8'))[origin];
      if (verification) assert.equal(d.querySelector('meta[name="baidu-site-verification"]').content,verification);
    }
    assert(d.querySelector('main h1').textContent.trim());
    assert.equal(/[\u4e00-\u9fff]/.test(d.querySelector('main h1').textContent),!en);
    if(name.startsWith('download')) {
      const manifest=JSON.parse(Buffer.from(JSON.parse(fs.readFileSync(path.join(dir,'downloads.json'),'utf8')).payload,'base64'));
      assert(!d.querySelector('main .eyebrow'),'Download brand must not be repeated');
      for(const card of d.querySelectorAll('[data-target]')) {
        const artifact=manifest.artifacts.find(a=>a.target===card.dataset.target);
        if(artifact){assert(card.querySelector('[data-version]').textContent.includes(artifact.version));assert(![...card.querySelectorAll('[data-copy="pending"],[data-copy="later"]')].some(e=>!e.hidden),'Available platforms must not be announced as pending');}
        const a=card.querySelector('[data-installer]');assert(a.hidden&&!a.hasAttribute('href'),'Static download links must await browser verification');
      }
    }
    const rawTitle=d.title,rawDescription=d.querySelector('meta[name="description"]').content;
    assert(rawDescription.length>15);
    const canonical=new URL(d.querySelector('link[rel="canonical"]').href);
    assert.equal(canonical.origin,primary(lang));assert(!canonical.search);
    const graph=JSON.parse(d.getElementById('search-structured-data').textContent)['@graph'];
    assert.equal(graph.find(n=>n['@type']==='WebPage').url,canonical.href);
    assert(!html.includes('aggregateRating'));
    assert(!/funnywork[.]funnypixels[.]cn|support@funnypixels[.]cn|mainland entry point|境外后端/.test(html),'Published content must use the current website and contact');
    const licensing = d.querySelector('[data-license]');
    assert(licensing && new URL(licensing.href).hash === '#licensing');
    assert.equal(new URL(licensing.href).pathname, en ? '/privacy.en.html' : '/privacy.html');
    if(name.startsWith('privacy')) {
      assert(d.querySelector('#licensing'));
      for(const file of ['LICENSE','COMMERCIAL-LICENSE.txt','THIRD_PARTY_NOTICES.md']) { const a=d.querySelector('[data-license-document=\"'+file+'\"]'); assert(a); const target=new URL(a.href); assert.equal(target.origin,origin); assert.deepEqual(fs.readFileSync(path.join(dir,target.pathname.slice(1))),fs.readFileSync(path.join(root,file))); }
    }

    assert.equal(graph.find(n=>n['@type']==='SoftwareApplication')['@id'],'https://funnywork.funnypixelsapp.com/#software');
    for(const alternate of d.querySelectorAll('link[hreflang]')) assert.equal(new URL(alternate.href).origin,primary(alternate.hreflang==='en'?'en':'zh'));
    for(const a of d.querySelectorAll('a[href]')) {
      const url=new URL(a.href);
      if(url.origin!==origin)continue;
      const file=url.pathname==='/'?'index.html':url.pathname==='/download/'?'download.html':url.pathname.slice(1);
      assert(fs.existsSync(path.join(dir,file)),name+' broken link '+url.href);
    }
    for(const el of d.querySelectorAll('img[src],source[src],script[src],link[rel="stylesheet"]')) {
      const url=new URL(el.src||el.href);
      assert(fs.existsSync(path.join(dir,url.pathname.slice(1))),name+' missing asset '+url.href);
    }
    for(const img of d.querySelectorAll('img[srcset]'))for(const candidate of img.srcset.split(','))assert(fs.existsSync(path.join(dir,candidate.trim().split(/\s+/)[0].slice(1))),'Missing responsive image');
    assert(!d.querySelector('[style]'),'CSP blocks inline styles');
    const headings=[...d.querySelectorAll('main h1,main h2')].map(el=>el.textContent);
    w.IntersectionObserver=class {observe(){}};w.matchMedia=()=>({matches:false});
    w.HTMLMediaElement.prototype.pause=()=>{};w.HTMLMediaElement.prototype.load=()=>{};
    w.HTMLDialogElement.prototype.close=function(){this.open=false;};
    w.TextDecoder=TextDecoder;w.AbortController=AbortController;
    Object.defineProperty(w.crypto,'subtle',{value:webcrypto.subtle});
    w.fetch=async()=>({ok:true,text:async()=>fs.readFileSync(path.join(dir,'downloads.json'),'utf8')});
    const asset=name=>d.querySelectorAll('script[src]')&&[...d.querySelectorAll('script[src]')].find(s=>new RegExp('/'+name+'[.][a-f0-9]{16}[.]js$').test(s.src));
    const searchScript=asset('search');assert(searchScript,'Missing hashed search script');
    w.eval(fs.readFileSync(path.join(dir,new URL(searchScript.src).pathname.slice(1)),'utf8'));
    const runtimeScript=asset(name.startsWith('download')?'downloads':'site');assert(runtimeScript,'Missing hashed runtime');
    w.eval(fs.readFileSync(path.join(dir,new URL(runtimeScript.src).pathname.slice(1)),'utf8'));
    assert.equal(d.title,rawTitle);assert.equal(d.querySelector('meta[name="description"]').content,rawDescription);
    for(const next of [en?'zh':'en',lang]) {
      d.querySelector(`[data-lang="${next}"]`).click();
      assert.equal(d.documentElement.lang,next==='en'?'en':'zh-CN');
      assert.equal(new URL(d.querySelector('link[rel="canonical"]').href).origin,primary(next));
      assert.equal(/[\u4e00-\u9fff]/.test(d.querySelector('main h1').textContent),next==='zh');
    }
    assert.deepEqual([...d.querySelectorAll('main h1,main h2')].map(el=>el.textContent),headings,'Hydration must preserve the pre-rendered language after a round trip');
    if(name.startsWith('download')) {for(let i=0;i<100&&d.body.dataset.state==='checking';i++)await new Promise(r=>setTimeout(r,10));const manifest=JSON.parse(Buffer.from(JSON.parse(fs.readFileSync(path.join(dir,'downloads.json'),'utf8')).payload,'base64'));assert.equal(d.querySelectorAll('[data-installer]:not([hidden])').length,manifest.artifacts.length+(manifest.testflight&&Date.parse(manifest.testflight.expires)>Date.now()?1:0));}
    if(name.startsWith('download')) {
      w.fetch=async()=>({ok:true,text:async()=>'{"payload":"invalid","signature":"invalid"}'});
      d.getElementById('retry').click();
      for(let i=0;i<100&&d.body.dataset.state==='checking';i++)await new Promise(r=>setTimeout(r,10));
      assert.equal(d.body.dataset.state,'failed');
      assert.equal(d.querySelectorAll('[data-installer]:not([hidden])').length,0,'Invalid live manifest must never enable snapshot links');
    }
    w.close();count++;
  }
  for(const forbidden of ['README.md','office-data.json','enterprise-copy.json','materials','_d_meta.json']) assert(!fs.existsSync(path.join(dir,forbidden)));
  sitemap.window.close();
}
console.log(`${count} static localized pages passed: raw content, canonical/hreflang, schema, links/assets, crawler rules, hydration and signed downloads`);
})().catch(error=>{console.error(error);process.exitCode=1;});
