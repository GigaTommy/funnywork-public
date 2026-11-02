import process from 'node:process';
/** Synthetic UAs check CDN/download routing; browser capability and device tests are separate. */
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {verifyRelease} from '../build/site-release.mjs';
const profiles=createRequire(import.meta.url)('./mobile-browser-profiles.cjs');
const key=await readFile(new URL('../../deploy/release-public-key.pem',import.meta.url));
const hosts=[...new Set([new URL(process.env.FUNNYWORK_REGIONAL_ORIGIN || 'https://funnywork.funnypixelsapp.com').host,'funnywork.funnypixelsapp.com'])],results=[],fullHashes=[];
const request=(host,path,ua,options={})=>fetch('https://'+host+path,{...options,redirect:'error',signal:AbortSignal.timeout(30000),headers:{'User-Agent':ua,...options.headers}});
for(const host of hosts){
 for(let i=0;i<profiles.length;i+=3){
  const batch=await Promise.allSettled(profiles.slice(i,i+3).map(async profile=>{
   const response=await request(host,'/downloads.json',profile.userAgent);if(!response.ok)throw Error(profile.name+': metadata '+response.status);
   const catalog=verifyRelease(await response.json(),key),artifact=catalog.artifacts.find(a=>a.target==='android');if(!artifact)throw Error('Missing Android release');
   const head=await request(host,artifact.path,profile.userAgent,{method:'HEAD'}),type=(head.headers.get('content-type')||'').split(';')[0];
   if(head.status!==200||Number(head.headers.get('content-length'))!==artifact.bytes||!['application/octet-stream','application/vnd.android.package-archive'].includes(type)||!head.headers.get('content-disposition')?.includes('attachment')||head.headers.get('x-content-type-options')!=='nosniff')throw Error(profile.name+': invalid APK headers');
   const range=await request(host,artifact.path,profile.userAgent,{headers:{Range:'bytes=0-31'}}),bytes=new Uint8Array(await range.arrayBuffer());
   if(range.status!==206||range.headers.get('content-range')!=='bytes 0-31/'+artifact.bytes||bytes.length!==32||bytes[0]!==80||bytes[1]!==75||bytes[2]!==3||bytes[3]!==4)throw Error(profile.name+': invalid APK range/content');
   return {host,profile:profile.name,metadataSignature:'valid',version:artifact.version,apkStatus:head.status,bytes:artifact.bytes,type,rangeAndZipHeader:'passed'};
  }));
  for(const r of batch){if(r.status==='rejected')throw r.reason;results.push(r.value);}
 }
 console.log(host+': '+profiles.length+' synthetic browser UAs — metadata signatures, APK headers, range and ZIP header PASS');
 if(process.argv.includes('--full-android')){
  const response=await request(host,'/downloads.json','FunnyWork-release-audit/1.0'),catalog=verifyRelease(await response.json(),key),artifact=catalog.artifacts.find(a=>a.target==='android');
  const apk=await fetch('https://'+host+artifact.path,{redirect:'error',signal:AbortSignal.timeout(180000),headers:{'User-Agent':'FunnyWork-release-audit/1.0'}});if(!apk.ok)throw Error(host+': full APK '+apk.status);
  const hash=createHash('sha256');let bytes=0;for await(const chunk of apk.body){hash.update(chunk);bytes+=chunk.length;}const sha256=hash.digest('hex');if(bytes!==artifact.bytes||sha256!==artifact.sha256)throw Error(host+': public APK hash mismatch');
  fullHashes.push({host,bytes,sha256,streamedWithoutDiskDownload:true});console.log(host+': complete public APK SHA-256 PASS');
 }
}
const report=process.argv.find(a=>a.startsWith('--report='));if(report)await writeFile(report.slice('--report='.length),JSON.stringify({actualMobileDevicesVerified:false,syntheticUaCases:results,fullHashes},null,2)+'\n',{mode:0o600});
