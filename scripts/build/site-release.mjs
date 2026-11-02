/** Sign and verify immutable, explicitly selected public download artifacts. Private keys stay off web hosts. */
import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { createReadStream, readFileSync } from 'node:fs';
import { readFile, writeFile, stat, mkdtemp, rm } from 'node:fs/promises';
import { resolve, isAbsolute, join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
export const RELEASE_COMPAT_PUBLIC_KEY = readFileSync(new URL('../../deploy/release-compat-public-key.pem',import.meta.url));
export function compatibilityMessage(payload, signature) { return Buffer.concat([Buffer.from('FunnyWork downloads compatibility v1\0'),payload,signature]); }
export async function sha256(path) { const h=createHash('sha256');for await(const chunk of createReadStream(path))h.update(chunk);return h.digest('hex'); }
export function validateRelease(data, now=Date.now()) {
 if(data.schema!==1 || !Number.isFinite(Date.parse(data.issued)) || !Number.isFinite(Date.parse(data.expires)) || Date.parse(data.expires)<=now || Date.parse(data.issued)>now+300000 || Date.parse(data.expires)-Date.parse(data.issued)>1000*86400*180)throw Error('Invalid or expired release');
 if(!Array.isArray(data.artifacts)||data.artifacts.length>10)throw Error('Invalid artifacts');
 const seen=new Set();for(const a of data.artifacts){
  if(!['mac-arm64','mac-x64','win-x64','android'].includes(a.target)||seen.has(a.target))throw Error('Invalid target');seen.add(a.target);
  if(!/^[a-zA-Z0-9._-]{1,80}$/.test(a.version)||!/^[a-f0-9]{64}$/.test(a.sha256)||!Number.isSafeInteger(a.bytes)||a.bytes<=0)throw Error('Invalid artifact identity');
  if(!/^\/dsh-desk\/(?:releases|android)\/[a-zA-Z0-9._/-]+$/.test(a.path)||a.path.includes('..')||a.path.includes('//')||!a.path.endsWith({android:'.apk','mac-arm64':'.dmg','mac-x64':'.dmg','win-x64':'.exe'}[a.target]))throw Error('Invalid artifact path');
  if(!a.signer || /debug/i.test(a.path+' '+a.signer) || ((/unsigned/i.test(a.path+' '+a.signer) || a.signing==='unsigned' || a.channel==='test') && !(a.target==='win-x64' && a.signing==='unsigned' && a.channel==='test' && /unsigned/i.test(a.path) && /unsigned/i.test(a.signer))))throw Error('Unqualified artifact');
 }
 const f=data.testflight;if(f!==undefined&&(!f||typeof f.url!=='string'||!/^https:\/\/testflight\.apple\.com\/join\/[A-Za-z0-9]{8}$/.test(f.url)||typeof f.version!=='string'||!/^[a-zA-Z0-9._-]{1,80}$/.test(f.version)||typeof f.build!=='string'||!/^\d{1,20}$/.test(f.build)||typeof f.expires!=='string'||!Number.isFinite(Date.parse(f.expires))||Date.parse(f.expires)<=Date.parse(data.issued)||Date.parse(f.expires)>Date.parse(data.expires)))throw Error('Invalid TestFlight invitation');
 return data;
}
export function verifyRelease(envelope, publicKey, now=Date.now(), compatPublicKey=RELEASE_COMPAT_PUBLIC_KEY) {
 const bytes=Buffer.from(envelope.payload,'base64');if(bytes.length>32768||!verify(null,bytes,publicKey,Buffer.from(envelope.signature,'base64')))throw Error('Invalid release signature');
 if(envelope.compatSignature!==undefined){const signature=Buffer.from(envelope.compatSignature,'base64'),key=compatPublicKey?.type==='public'?compatPublicKey:createPublicKey(compatPublicKey);if(signature.length!==64||key.asymmetricKeyType!=='ec'||key.asymmetricKeyDetails?.namedCurve!=='prime256v1'||!verify('sha256',compatibilityMessage(bytes,Buffer.from(envelope.signature,'base64')),{key,dsaEncoding:'ieee-p1363'},signature))throw Error('Invalid compatibility signature');}
 return validateRelease(JSON.parse(bytes),now);
}
async function main(){
 const [mode,input,key,output]=process.argv.slice(2);if(!['sign','verify'].includes(mode)||!input||!key||!output)throw Error('Usage: site-release.mjs sign|verify input key output');
 const pem=await readFile(key);const data=JSON.parse(await readFile(input,'utf8'));
 if(mode==='verify'){const d=verifyRelease(data,pem);await writeFile(output,JSON.stringify(d,null,2)+'\n');return;}
 const artifacts=[];for(const a of data.artifacts){
  if(a.target.startsWith('mac-')){
   if(process.platform!=='darwin')throw Error('macOS qualification requires macOS');
   execFileSync('xcrun',['stapler','validate',a.file],{stdio:'pipe'});
   execFileSync('spctl',['--assess','--type','open','--context','context:primary-signature',a.file],{stdio:'pipe'});
   const auditRoot=process.env.FUNNYWORK_RELEASE_AUDIT_DIR;if(!auditRoot||!isAbsolute(auditRoot))throw Error('Set absolute FUNNYWORK_RELEASE_AUDIT_DIR on the work volume');
   const mount=await mkdtemp(join(auditRoot,'release-dmg-'));let attached=false;
   try{
    execFileSync('hdiutil',['attach',a.file,'-readonly','-nobrowse','-mountpoint',mount],{stdio:'pipe'});attached=true;
    const app=join(mount,'funnywork.app');
    execFileSync('codesign',['--verify','--deep','--strict','-R','=identifier \"com.funnyworker.desktop\" and certificate leaf[subject.OU] = \"VL3HPHK35K\"',app],{stdio:'pipe'});
    const architecture=execFileSync('lipo',['-archs',join(app,'Contents/MacOS/funnywork')],{encoding:'utf8'}).trim();
    if(!architecture.split(' ').includes(a.target==='mac-arm64'?'arm64':'x86_64'))throw Error('Mac architecture mismatch');
    const version=execFileSync('/usr/libexec/PlistBuddy',['-c','Print :CFBundleShortVersionString',join(app,'Contents/Info.plist')],{encoding:'utf8'}).trim();if(version!==a.version)throw Error('Mac version mismatch');
   }finally{if(attached)execFileSync('hdiutil',['detach',mount],{stdio:'pipe'});await rm(mount,{recursive:true});}

  }else if(a.target==='android'){
   if(!process.env.FUNNYWORK_APKSIGNER||!process.env.FUNNYWORK_AAPT)throw Error('Set FUNNYWORK_APKSIGNER and FUNNYWORK_AAPT');
   const result=execFileSync(process.env.FUNNYWORK_APKSIGNER,['verify','--verbose','--print-certs',a.file],{encoding:'utf8'});
   if(!result.includes('certificate SHA-256 digest: '+a.signer))throw Error('Android signer mismatch');
   const badging=execFileSync(process.env.FUNNYWORK_AAPT,['dump','badging',a.file],{encoding:'utf8'});
   if(!badging.includes("versionName='"+a.version+"'"))throw Error('Android version mismatch');
   if(badging.includes('application-debuggable')||!badging.includes("name='com.funnyworker.android'"))throw Error('Not a qualified release APK');
  }else throw Error('Target requires a platform signature qualification implementation');
  const s=await stat(a.file);if(!s.isFile())throw Error('Not a file');const {file,...entry}=a;artifacts.push({...entry,bytes:s.size,sha256:await sha256(file)});}
 const payload=Buffer.from(JSON.stringify(validateRelease({...data,artifacts})));const privateKey=createPrivateKey(pem);if(privateKey.asymmetricKeyType!=='ed25519')throw Error('Expected Ed25519 key');
 const compatFile=process.env.FUNNYWORK_RELEASE_COMPAT_SIGNING_KEY_FILE;if(!compatFile||!isAbsolute(compatFile))throw Error('Set absolute FUNNYWORK_RELEASE_COMPAT_SIGNING_KEY_FILE');
 const compatStat=await stat(compatFile);if(process.platform!=='win32'&&(compatStat.mode&0o077)!==0)throw Error('Compatibility private key must have restricted permissions');
 const compatKey=createPrivateKey(await readFile(compatFile));if(compatKey.asymmetricKeyType!=='ec'||compatKey.asymmetricKeyDetails?.namedCurve!=='prime256v1'||!createPublicKey(compatKey).export({type:'spki',format:'der'}).equals(createPublicKey(RELEASE_COMPAT_PUBLIC_KEY).export({type:'spki',format:'der'})))throw Error('Compatibility signing identity mismatch');
 const signature=sign(null,payload,privateKey),envelope={payload:payload.toString('base64'),signature:signature.toString('base64'),compatSignature:sign('sha256',compatibilityMessage(payload,signature),{key:compatKey,dsaEncoding:'ieee-p1363'}).toString('base64')};verifyRelease(envelope,createPublicKey(privateKey));await writeFile(output,JSON.stringify(envelope)+'\n');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)main().catch(e=>{console.error(e.message);process.exitCode=1});
