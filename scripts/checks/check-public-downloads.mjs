import process from 'node:process';
/** Read-only public release verification from an independent checkout. Nonzero exit is an alert signal. */
import {readFile} from 'node:fs/promises';import {verifyRelease} from '../build/site-release.mjs';
const key=await readFile(new URL('../../deploy/release-public-key.pem',import.meta.url));
for(const host of new Set([new URL(process.env.FUNNYWORK_REGIONAL_ORIGIN || 'https://funnywork.funnypixelsapp.com').host,'funnywork.funnypixelsapp.com'])){
 const request=(path,options={})=>fetch('https://'+host+path,{...options,redirect:'error',headers:{'User-Agent':'FunnyWork-independent-release-check/1.0',...options.headers},signal:AbortSignal.timeout(30000)});
 const page=await request('/download/');if(!page.ok||!page.headers.get('content-security-policy')?.includes("frame-ancestors 'none'"))throw Error(host+': download page or CSP failed');await page.body.cancel();
 const response=await request('/downloads.json');if(!response.ok)throw Error(host+': metadata unavailable');const raw=await response.text();if(raw.length>50000)throw Error('Oversize metadata');const data=verifyRelease(JSON.parse(raw),key);
 if(data.testflight&&Date.parse(data.testflight.expires)<=Date.now())throw Error(host+': TestFlight build expired');
 for(const a of data.artifacts){const head=await request(a.path,{method:'HEAD'});if(!head.ok||Number(head.headers.get('content-length'))!==a.bytes||head.headers.get('x-content-type-options')!=='nosniff')throw Error(host+': installer identity mismatch '+a.target);const range=await request(a.path,{headers:{Range:'bytes=0-1023'}});if(range.status!==206||(await range.arrayBuffer()).byteLength!==1024)throw Error(host+': range request failed');}
 console.log(host+': signed metadata, expiry, installer size, download CSP and range PASS');
}
