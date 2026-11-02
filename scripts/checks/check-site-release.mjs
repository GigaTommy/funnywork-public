import assert from 'node:assert/strict';import {generateKeyPairSync,sign} from 'node:crypto';import {validateRelease,verifyRelease,compatibilityMessage} from '../build/site-release.mjs';
const {publicKey,privateKey}=generateKeyPairSync('ed25519');const now=Date.now(),d={schema:1,issued:new Date(now).toISOString(),expires:new Date(now+86400000).toISOString(),artifacts:[{target:'android',version:'1.0',path:'/dsh-desk/android/1.0/FunnyWork.apk',sha256:'a'.repeat(64),bytes:123,signer:'release-cert'}]};
const bytes=Buffer.from(JSON.stringify(d)),e={payload:bytes.toString('base64'),signature:sign(null,bytes,privateKey).toString('base64')};assert.deepEqual(verifyRelease(e,publicKey),d);
assert.throws(()=>verifyRelease({...e,payload:Buffer.from(JSON.stringify({...d,artifacts:[]})).toString('base64')},publicKey));assert.throws(()=>verifyRelease(e,generateKeyPairSync('ed25519').publicKey));assert.throws(()=>verifyRelease(e,publicKey,now+86400001));
for(const path of ['https://evil.example/app.apk','/dsh-desk/android/../x.apk','/dsh-desk/android//x.apk','/dsh-desk/android/x.exe','/dsh-desk/android/debug.apk'])assert.throws(()=>validateRelease({...d,artifacts:[{...d.artifacts[0],path}]}));
assert.throws(()=>validateRelease({...d,artifacts:[...d.artifacts,...d.artifacts]}));
const testflight={url:'https://testflight.apple.com/join/AbCd1234',version:'0.1.0',build:'18',expires:new Date(now+43200000).toISOString()};assert.doesNotThrow(()=>validateRelease({...d,testflight}));
for(const patch of [{expires:new Date(now+86400001).toISOString()},{expires:d.issued},{expires:'invalid'},{url:'https://evil.example/join/AbCd1234'},{build:'18beta'}])assert.throws(()=>validateRelease({...d,testflight:{...testflight,...patch}}),/Invalid TestFlight invitation/);
console.log('Signed release: tampering, wrong key, expiry, target, path, TestFlight bounds and unsigned/debug rejection PASS');
const invitation={url:'https://testflight.apple.com/join/zEQNT6HH',version:'0.1.0',build:'18',expires:new Date(now+3600000).toISOString()};
assert.equal(validateRelease({...d,testflight:invitation}).testflight.url,invitation.url);
for(const url of ['http://testflight.apple.com/join/zEQNT6HH','https://evil.example/join/zEQNT6HH','https://testflight.apple.com.evil.example/join/zEQNT6HH','https://testflight.apple.com/join/zEQNT6HH?x=1','https://user@testflight.apple.com/join/zEQNT6HH','https://testflight.apple.com/join/../zEQNT6HH'])assert.throws(()=>validateRelease({...d,testflight:{...invitation,url}}));
for(const expires of ['invalid',new Date(now-1).toISOString(),new Date(now+2*86400000).toISOString()])assert.throws(()=>validateRelease({...d,testflight:{...invitation,expires}}));
console.log('TestFlight: exact Apple invitation origin/path, build identity and expiry bounds PASS');

const ec=generateKeyPairSync('ec',{namedCurve:'prime256v1'}),signature=Buffer.from(e.signature,'base64');
const dual={...e,compatSignature:sign('sha256',compatibilityMessage(bytes,signature),{key:ec.privateKey,dsaEncoding:'ieee-p1363'}).toString('base64')};
assert.deepEqual(verifyRelease(dual,publicKey,now,ec.publicKey),d);
assert.throws(()=>verifyRelease(dual,publicKey,now,generateKeyPairSync('ec',{namedCurve:'prime256v1'}).publicKey));
for(const field of ['signature','compatSignature']){const value=Buffer.from(dual[field],'base64');value[0]^=1;assert.throws(()=>verifyRelease({...dual,[field]:value.toString('base64')},publicKey,now,ec.publicKey));}
assert.throws(()=>verifyRelease({...dual,compatSignature:''},publicKey,now,ec.publicKey));
console.log('Compatibility signature: P-256 binds exact payload and Ed25519 signature; wrong key and both signature tampering rejected PASS');
