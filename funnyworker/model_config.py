"""Server-managed credentials and signed, recipient-bound encrypted publication."""
import base64
import hashlib
import json
import os
import secrets
import time
import csv
import re
import subprocess
from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes


def canonical(value): return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()
def encode(raw): return base64.b64encode(raw).decode()
def decode(text): return base64.b64decode(text,validate=True)
def raw_public(key): return key.public_bytes(serialization.Encoding.Raw,serialization.PublicFormat.Raw)
def raw_private(key): return key.private_bytes(serialization.Encoding.Raw,serialization.PrivateFormat.Raw,serialization.NoEncryption())

@lru_cache(maxsize=1)
def windows_user_sid():
    raw=subprocess.check_output(['whoami','/user','/fo','csv','/nh']).decode('utf-8',errors='replace')
    sid=next(csv.reader(raw.splitlines()))[1]
    if not re.fullmatch(r'S-1-[0-9-]+',sid):raise ValueError('无法确认当前 Windows 用户身份')
    return sid

def protect_directory(path):
    """Restrict local credentials to their user, administrators and Windows SYSTEM."""
    path=Path(path)
    path.mkdir(parents=True,exist_ok=True,mode=0o700)
    if os.name=='nt':
        subprocess.run(['icacls',str(path),'/inheritance:r','/grant:r',
                        f'*{windows_user_sid()}:(OI)(CI)F','*S-1-5-18:(OI)(CI)F',
                        '*S-1-5-32-544:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL)
    else:os.chmod(path,0o700)
def private_write(path,data):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    temporary=path.with_suffix(path.suffix+'.'+secrets.token_hex(6)+'.tmp')
    fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'wb') as f: f.write(data);f.flush();os.fsync(f.fileno())
    os.replace(temporary,path);os.chmod(path,0o600)

def derived(private,public):
    return HKDF(algorithm=hashes.SHA256(),length=32,salt=None,info=b'funnyworker-model-envelope-v1').derive(private.exchange(public))

def seal(config,recipient_public,signing_key,node,version,now=None):
    now=int(time.time() if now is None else now)
    ephemeral=x25519.X25519PrivateKey.generate();nonce=os.urandom(12)
    header={'schema':1,'node':node,'version':version,'issued':now,'expires':now+86400,
        'recipient':hashlib.sha256(raw_public(recipient_public)).hexdigest(),
        'ephemeral':encode(raw_public(ephemeral.public_key())),'nonce':encode(nonce)}
    cipher=AESGCM(derived(ephemeral,recipient_public)).encrypt(nonce,canonical(config),canonical(header))
    payload={'header':header,'ciphertext':encode(cipher)}
    return dict(payload,signature=encode(signing_key.sign(canonical(payload))))

def unseal(envelope,device_key,pinned_signer,node,minimum_version=0,now=None):
    now=int(time.time() if now is None else now)
    payload={'header':envelope['header'],'ciphertext':envelope['ciphertext']}
    pinned_signer.verify(decode(envelope['signature']),canonical(payload))
    h=payload['header']
    if (h['schema']!=1 or h['node']!=node or type(h['version']) is not int or h['version']<minimum_version
        or h['issued']>now+60 or h['expires']<=now or h['expires']-h['issued']>86400
        or h['recipient']!=hashlib.sha256(raw_public(device_key.public_key())).hexdigest()):
        raise ValueError('配置包身份、版本或有效期不符')
    ephemeral=x25519.X25519PublicKey.from_public_bytes(decode(h['ephemeral']))
    plain=AESGCM(derived(device_key,ephemeral)).decrypt(decode(h['nonce']),decode(payload['ciphertext']),canonical(h))
    return json.loads(plain)

class ModelService:
    """Provisioning is local trusted administration; API responses omit secrets."""
    def __init__(self,root):
        self.root=Path(root);self.server=self.root/'server';self.client=self.root/'client'
        protect_directory(self.root)
        self.server.mkdir(parents=True,exist_ok=True,mode=0o700);self.client.mkdir(parents=True,exist_ok=True,mode=0o700)
        for folder in (self.root,self.server,self.client):os.chmod(folder,0o700)
        self.master=self._secret(self.server/'master.key',lambda:os.urandom(32))
        self.signer=ed25519.Ed25519PrivateKey.from_private_bytes(self._secret(self.server/'signing.key',lambda:raw_private(ed25519.Ed25519PrivateKey.generate())))
        self.device=x25519.X25519PrivateKey.from_private_bytes(self._secret(self.client/'device.key',lambda:raw_private(x25519.X25519PrivateKey.generate())))
        self.node=self._secret(self.client/'node.id',lambda:secrets.token_hex(16).encode()).decode()
        pin=self.client/'server-signing.pub'
        if not pin.exists():private_write(pin,raw_public(self.signer.public_key()))
        self.pinned=ed25519.Ed25519PublicKey.from_public_bytes(pin.read_bytes())
        if raw_public(self.pinned)!=raw_public(self.signer.public_key()):raise ValueError('服务端签名身份发生变化')
        self.version=0
        self._read()

    def _secret(self,path,create):
        if not path.exists():private_write(path,create())
        os.chmod(path,0o600);return path.read_bytes()

    def _read(self):
        p=self.server/'model.enc'
        if not p.exists():return None
        raw=p.read_bytes()
        config=json.loads(AESGCM(self.master).decrypt(raw[:12],raw[12:],b'funnyworker-server-config-v1'))
        self.version=config['version'];return config

    def _save(self,config):
        nonce=os.urandom(12)
        private_write(self.server/'model.enc',nonce+AESGCM(self.master).encrypt(nonce,canonical(config),b'funnyworker-server-config-v1'))
        self.version=config['version']

    def configure(self,endpoint,model,key,thinking='disabled',max_tokens=2048):
        url=urlsplit(endpoint)
        if url.scheme!='https' or url.hostname!='api.deepseek.com' or url.username or url.password or url.query or url.fragment or url.path.rstrip('/') not in {'','/v1'}:
            raise ValueError('当前凭证仅允许用于DeepSeek官方接口')
        if not isinstance(key,str) or not key.strip():raise ValueError('模型凭证为空')
        if not isinstance(model,str) or not 1<=len(model)<=100:raise ValueError('模型名称无效')
        self._save({'endpoint':endpoint.rstrip('/'),'model':model,'api_key':key,'thinking':thinking,'max_tokens':max_tokens,'version':self.version+1})

    def metadata(self):
        config=self._read();publication=self.server/'publication.json'
        published=None
        if publication.exists():published=json.loads(publication.read_text())['header']
        return {'configured':config is not None,'endpoint':config['endpoint'] if config else '',
            'model':config['model'] if config else '', 'thinking':config['thinking'] if config else 'disabled',
            'max_tokens':config['max_tokens'] if config else 2048,'version':self.version,
            'credential_configured':bool(config),'published_version':published['version'] if published else None,
            'expires':published['expires'] if published else None,'node':self.node,
            'client_version':json.loads((self.client/'receipt.json').read_text())['version'] if (self.client/'receipt.json').exists() else None}

    def edit(self,model,thinking,max_tokens,expected_version):
        c=self._read()
        if not c or type(expected_version) is not int or expected_version!=self.version:raise ValueError('模型设置已变化，请刷新后重试')
        if not isinstance(model,str) or not model or len(model)>100 or any(not (x.isalnum() or x in '-_.') for x in model):raise ValueError('模型名称无效')
        if thinking not in {'disabled','enabled'} or type(max_tokens) is not int or not 128<=max_tokens<=262144:raise ValueError('模型参数无效')
        c.update(model=model,thinking=thinking,max_tokens=max_tokens,version=self.version+1);self._save(c)
        return self.metadata()

    def publish(self,expected_version):
        c=self._read()
        if not c or type(expected_version) is not int or expected_version!=self.version:raise ValueError('模型设置已变化，请刷新后重试')
        package=seal(c,self.device.public_key(),self.signer,self.node,self.version)
        private_write(self.server/'publication.json',canonical(package))
        return self.metadata()

    def package(self):
        return json.loads((self.server/'publication.json').read_text())

    def activate(self,package):
        receipt_path=self.client/'receipt.json'
        receipt=json.loads(receipt_path.read_text()) if receipt_path.exists() else {'version':0}
        config=unseal(package,self.device,self.pinned,self.node,receipt['version'])
        if config.get('version')!=package['header']['version']:raise ValueError('配置版本不一致')
        private_write(self.client/'package.json',canonical(package))
        private_write(receipt_path,canonical({'version':config['version'],'received':int(time.time())}))
        return config
