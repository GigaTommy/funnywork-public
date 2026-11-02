#!/usr/bin/env node
// Pre-render the existing translation code; publish these files, not the authoring directory.
const fs = require('node:fs');
const path = require('node:path');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '../..');
const {JSDOM} = createRequire(path.join(root, 'apps/desktop/package.json'))('jsdom');
const site = path.join(root, 'designs/funnywork-site');
const searchVerification = JSON.parse(fs.readFileSync(path.join(site, 'search-verification.json'), 'utf8'));
const {webcrypto,createPublicKey,verify,createHash} = require('node:crypto');
const buildMedia = require('./build-site-media.cjs');
const [origin, outputArg, manifestArg] = process.argv.slice(2);
if (!['https://funnywork.funnypixelsapp.com', process.env.FUNNYWORK_REGIONAL_ORIGIN].filter(Boolean).includes(origin) || !outputArg || !manifestArg) throw Error('Usage: node scripts/build/build-site-geo.cjs https://<official-host> <new-output-directory> <current-signed-manifest.json>');
const output = path.resolve(outputArg);
const allowed = path.join(root, '.local/releases') + path.sep;
if (!output.startsWith(allowed) || fs.existsSync(output)) throw Error('Use a new directory under .local/releases; existing releases are never overwritten');
const realRoot = fs.realpathSync(root);
if (!realRoot.startsWith('/Volumes/SSD/')) throw Error('This local publication build requires the mounted external work volume');
const releaseRoot = path.join(root, '.local/releases');
fs.mkdirSync(releaseRoot, {recursive:true});
if (!fs.realpathSync(releaseRoot).startsWith('/Volumes/SSD/')) throw Error('Release output must stay on the mounted external work volume');
let parent = path.dirname(output);
while (!fs.existsSync(parent)) parent = path.dirname(parent);
if (!fs.realpathSync(parent).startsWith(fs.realpathSync(releaseRoot) + path.sep) && fs.realpathSync(parent) !== fs.realpathSync(releaseRoot)) throw Error('Output cannot escape the release directory through symlinks');
const pages = ['guide', 'index', 'solutions', 'quarterly-review', 'security', 'privacy', 'download'];
const runtime = fs.readFileSync(path.join(site, 'site.js'), 'utf8');
let search = fs.readFileSync(path.join(site, 'search.js'), 'utf8');
const downloadJS = fs.readFileSync(path.join(site, 'downloads.js'), 'utf8');
// Read the existing download copy without executing its network/signature workflow.
const words = new Function(downloadJS.slice(downloadJS.indexOf('const words='), downloadJS.indexOf("let language=")) + ';return words;')();
const route = (slug, lang) => slug === 'index' ? (lang === 'en' ? '/index.en.html' : '/') : slug === 'download' && lang === 'zh' ? '/download/' : '/' + slug + (lang === 'en' ? '.en' : '') + '.html';
const manifestBytes=fs.readFileSync(path.resolve(manifestArg));
const envelope=JSON.parse(manifestBytes),payload=Buffer.from(envelope.payload,'base64');
const key=createPublicKey({key:Buffer.from(/const trustedKey='([^']+)'/.exec(downloadJS)[1],'base64'),format:'der',type:'spki'});
if(!verify(null,payload,key,Buffer.from(envelope.signature,'base64')))throw Error('Invalid download manifest signature');
const manifest=JSON.parse(payload);
if(Date.parse(manifest.expires)<=Date.now()||!Number.isFinite(Date.parse(manifest.expires)))throw Error('Expired download manifest');
fs.mkdirSync(output, {recursive:true});
const media=buildMedia(site,output);
const documents = {};
for(const name of ['LICENSE','COMMERCIAL-LICENSE.txt','THIRD_PARTY_NOTICES.md']) {
 const bytes=fs.readFileSync(path.join(root,name));
 const filename='funnywork-'+name.replace(/[^a-zA-Z0-9_-]/g,'-')+'.'+createHash('sha256').update(bytes).digest('hex').slice(0,16)+'.txt';
 fs.writeFileSync(path.join(output,'static',filename),bytes);
 documents[name]='/static/'+filename;
}
search=search.replace('const publishedDocuments = {};','const publishedDocuments = '+JSON.stringify(documents)+';');

search=search.replace('const publishedMedia = {};','const publishedMedia = '+JSON.stringify(media)+';');
(async()=>{
for (const slug of pages) for (const lang of ['zh','en']) {
  const dom = new JSDOM(fs.readFileSync(path.join(site, slug + '.html'), 'utf8'), {url:origin + route(slug,lang) + '?lang=' + lang, runScripts:'outside-only'});
  const w=dom.window, d=w.document;
  if (slug === 'index' && searchVerification[origin]) {
    const tag=d.createElement('meta');
    tag.name='baidu-site-verification'; tag.content=searchVerification[origin];
    d.head.append(tag);
  }
  d.documentElement.dataset.siteOrigin=origin;
  d.body.dataset.searchPage=slug;
  d.querySelectorAll('[data-t]').forEach(el=>{el.dataset.zh=el.innerHTML;});
  d.querySelectorAll('[data-placeholder]').forEach(el=>{el.dataset.zhPlaceholder=el.placeholder;});
  w.IntersectionObserver=class {observe(){}};
  w.matchMedia=()=>({matches:false});
  w.HTMLMediaElement.prototype.pause=()=>{};
  w.HTMLMediaElement.prototype.load=()=>{};
  if(slug==='download') {
    d.documentElement.lang=lang==='en'?'en':'zh-CN';
    d.querySelectorAll('[data-copy]').forEach(el=>{el.textContent=words[lang][el.dataset.copy];});
    d.querySelectorAll('[data-lang]').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.lang===lang)));
    w.TextDecoder=TextDecoder;w.AbortController=AbortController;
    Object.defineProperty(w.crypto,'subtle',{value:webcrypto.subtle});
    w.fetch=async()=>({ok:true,text:async()=>manifestBytes.toString()});
    w.eval(downloadJS);
    for(let i=0;i<200&&d.body.dataset.state==='checking';i++)await new Promise(r=>setTimeout(r,5));
    if(d.body.dataset.state!=='ready')throw Error('Download manifest rejected by client validation');
    for(const card of d.querySelectorAll('[data-target]')) {
      const artifact=manifest.artifacts.find(a=>a.target===card.dataset.target),invite=card.dataset.target==='ios'&&manifest.testflight;
      if(artifact){card.dataset.releaseVersion=artifact.version;card.dataset.releaseBytes=String(artifact.bytes);}
      if(invite){card.dataset.releaseVersion=invite.version;card.dataset.releaseBuild=invite.build;card.dataset.releaseExpires=invite.expires;}
    }
    d.querySelectorAll('[data-installer]').forEach(a=>{a.hidden=true;a.removeAttribute('href');a.removeAttribute('download');});
    d.getElementById('status').textContent='';
    delete d.body.dataset.state;
    d.body.dataset.releaseExpires=manifest.expires;
  } else { w.eval(runtime); }
  w.eval(search);
  d.querySelectorAll('img[src]').forEach(img=>{const name=img.getAttribute('src').split('?')[0].replace(/^\//,'');if(media[name])w.fwApplyImage(img,name);});
  for(const img of d.querySelectorAll('img[loading=lazy]'))img.decoding='async';
  const filename=slug+(lang==='en'?'.en':'')+'.html';
  fs.writeFileSync(path.join(output,filename),dom.serialize());
  w.close();
}
fs.writeFileSync(path.join(output,'downloads.json'),manifestBytes);
for(const name of ['styles.css','site.js','search.js','download.css','downloads.js']) {
 const bytes=Buffer.from(name==='search.js'?search:fs.readFileSync(path.join(site,name)));
 fs.writeFileSync(path.join(output,name),bytes);
 const ext=path.extname(name),hashed=path.basename(name,ext)+'.'+createHash('sha256').update(bytes).digest('hex').slice(0,16)+ext;
 fs.writeFileSync(path.join(output,'static',hashed),bytes);
 for(const page of fs.readdirSync(output).filter(n=>n.endsWith('.html'))) {
  const file=path.join(output,page);let html=fs.readFileSync(file,'utf8');
  html=html.replace(new RegExp('(src|href)="/?'+name.replace('.', '\\.')+'(?:\\?[^" ]*)?"','g'),'$1="/static/'+hashed+'"');fs.writeFileSync(file,html);
 }
}
// Explicit public media families. Never copy account data, authoring JSON, README, or demo materials.
fs.cpSync(path.join(site,'assets'),path.join(output,'assets'),{recursive:true});
fs.cpSync(path.join(site,'media/product'),path.join(output,'media/product'),{recursive:true});
const blocked=['/api/','/admin/','/ruoyi/','/actuator/','/dsh-desk/','/downloads.json'];
const searchBots=['Googlebot','Bingbot','Baiduspider','OAI-SearchBot','ChatGPT-User','Claude-SearchBot','PerplexityBot','KimiBot'];
// Specific public paths take precedence over an edge-managed Disallow: /.
// Training crawler policy and protected endpoints are not broadened.
const publicPaths=['/$','/index.html$','/index.en.html$','/download/','/robots.txt$','/sitemap.xml$','/static/','/assets/','/media/product/',...pages.filter(p=>p!=='index').flatMap(p=>['/'+p+'.html$','/'+p+'.en.html$'])];
const searchGroup=searchBots.map(bot=>'User-agent: '+bot+'\n').join('')+'Disallow: /\n'+publicPaths.map(p=>'Allow: '+p+'\n').join('');
fs.writeFileSync(path.join(output,'robots.txt'),'# Public website pages. Robots rules do not provide authentication.\nUser-agent: *\nAllow: /\n'+blocked.map(p=>'Disallow: '+p+'\n').join('')+'\n'+searchGroup+'\nSitemap: '+origin+'/sitemap.xml\n');
const primary = () => 'https://funnywork.funnypixelsapp.com';
const entries=pages.flatMap(slug=>['zh','en'].map(indexedLanguage=>'<url><loc>'+primary()+route(slug,indexedLanguage)+'</loc>'+['zh','en'].map(l=>'<xhtml:link rel="alternate" hreflang="'+(l==='zh'?'zh-CN':'en')+'" href="'+primary(l)+route(slug,l)+'"/>').join('')+'<xhtml:link rel="alternate" hreflang="x-default" href="'+primary('zh')+route(slug,'zh')+'"/></url>'));
fs.writeFileSync(path.join(output,'sitemap.xml'),'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n'+entries.join('\n')+'\n</urlset>\n');
console.log('Static GEO release created: '+output+' (14 localized pages, hashed static assets, robots.txt, sitemap.xml)');

})().catch(error=>{console.error(error);process.exitCode=1;});
