"""Employee accounts and revocable device sessions owned by FunnyWorker."""
import hashlib
import hmac
import secrets
import time

class Unauthorized(ValueError):
    pass

class Accounts:
    def __init__(self, control):
        self.control=control
        with control.lock, control.db:
            control.db.executescript('''
            CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, salt BLOB, password BLOB, active INTEGER);
            CREATE TABLE IF NOT EXISTS account_sessions(token_hash TEXT PRIMARY KEY, username TEXT,
                device TEXT, expires REAL, revoked INTEGER DEFAULT 0);
            CREATE TABLE IF NOT EXISTS login_failures(username TEXT PRIMARY KEY, count INTEGER, retry_after REAL);
            ''')

    def set_password(self, username, password):
        if not isinstance(username,str) or not 1<=len(username)<=80 or username.strip()!=username:
            raise ValueError('账号无效')
        if not isinstance(password,str) or not 12<=len(password)<=256:
            raise ValueError('密码需为12至256字')
        salt=secrets.token_bytes(16)
        digest=hashlib.scrypt(password.encode(),salt=salt,n=16384,r=8,p=1)
        with self.control.lock,self.control.db:
            self.control.db.execute('INSERT INTO users VALUES(?,?,?,1) ON CONFLICT(username) DO UPDATE SET salt=excluded.salt,password=excluded.password,active=1',(username,salt,digest))
            self.control.db.execute('UPDATE account_sessions SET revoked=1 WHERE username=?',(username,))
            self.control.db.execute('DELETE FROM login_failures WHERE username=?',(username,))

    def login(self, username, password, device):
        if not isinstance(username,str) or not 1<=len(username)<=80 or not isinstance(password,str) or len(password)>256 or not isinstance(device,str) or not 1<=len(device)<=120:
            raise Unauthorized('账号或密码不正确')
        with self.control.lock,self.control.db:
            prior=self.control.db.execute('SELECT * FROM login_failures WHERE username=?',(username,)).fetchone()
            if prior and prior['retry_after']>time.time():raise Unauthorized('请稍后重试')
            row=self.control.db.execute('SELECT * FROM users WHERE username=?',(username,)).fetchone()
            digest=hashlib.scrypt(password.encode(),salt=row['salt'] if row else b'unknown-account!',n=16384,r=8,p=1)
            if not row or not row['active'] or not hmac.compare_digest(digest,row['password']):
                count=(prior['count'] if prior else 0)+1
                self.control.db.execute('INSERT OR REPLACE INTO login_failures VALUES(?,?,?)',(username,count,time.time()+min(300,2**min(count,8))))
                # Commit failures despite the raised authentication error.
                self.control.db.commit()
                raise Unauthorized('账号或密码不正确')
            self.control.db.execute('DELETE FROM login_failures WHERE username=?',(username,))
            token=secrets.token_urlsafe(48);expires=time.time()+8*3600
            self.control.db.execute('INSERT INTO account_sessions VALUES(?,?,?,?,0)',(hashlib.sha256(token.encode()).hexdigest(),username,device,expires))
            self.control.event(None,'account','员工账号已登录')
            return {'token':token,'username':username,'device':device,'expires':expires}

    def verify(self, token):
        if not isinstance(token,str) or not 1<=len(token)<=200:raise Unauthorized('登录已失效')
        with self.control.lock:
            row=self.control.db.execute('SELECT s.username,s.device,s.expires FROM account_sessions s JOIN users u ON u.username=s.username WHERE s.token_hash=? AND s.revoked=0 AND s.expires>? AND u.active=1',(hashlib.sha256(token.encode()).hexdigest(),time.time())).fetchone()
            if not row:raise Unauthorized('登录已失效')
            return dict(row)

    def logout(self, token):
        with self.control.lock,self.control.db:
            self.control.db.execute('UPDATE account_sessions SET revoked=1 WHERE token_hash=?',(hashlib.sha256(token.encode()).hexdigest(),))


def main():
    import argparse,getpass
    from .control import Control
    parser=argparse.ArgumentParser(description='管理FunnyWorker员工账号')
    parser.add_argument('username');parser.add_argument('--data',default='.local/prototype')
    args=parser.parse_args();password=getpass.getpass('设置密码：')
    if password!=getpass.getpass('再次输入：'):raise SystemExit('密码不一致')
    control=Control(args.data,recover=False)
    try:Accounts(control).set_password(args.username,password)
    finally:control.db.close()
    print('账号已保存，旧会话已撤销。')

if __name__=='__main__':main()
