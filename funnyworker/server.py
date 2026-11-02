import argparse
import json
import mimetypes
import secrets
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from .control import Control, Conflict
from .executor import Executor
from .accounts import Accounts, Unauthorized

WEB=Path(__file__).resolve().parent/'web'

class App:
    def __init__(self, root, harness_config=None):
        self.control=Control(root)
        self.accounts=Accounts(self.control)
        self.token=secrets.token_urlsafe(32)
        self.desktop_token=secrets.token_urlsafe(32)
        from .model_config import private_write
        private_write(self.control.root/'client'/'control-connection.json', json.dumps({
            'token':self.desktop_token,'url':None}).encode())
        self.shutdown=threading.Event()
        self.model_service=None
        if (self.control.root/'server'/'model.enc').exists():
            from .model_config import ModelService
            self.model_service=ModelService(self.control.root)
        self.port=None
        self.model_sync=threading.Event()
        if self.model_service:self.model_sync.set()
        self.executor=Executor(self.control,self.shutdown,harness_config,self.receive_model if self.model_service else None)
        self.thread=threading.Thread(target=self.work,name='local-worker',daemon=True)

    def receive_model(self):
        request=urllib.request.Request(f'http://127.0.0.1:{self.port}/api/model/package',headers={'X-FunnyWorker-Token':self.token})
        with urllib.request.urlopen(request,timeout=5) as response:
            package=json.load(response)
        with self.control.lock:
            config=self.model_service.activate(package)
            self.control.db.commit()
        return config

    def work(self):
        while not self.shutdown.is_set():
            if self.model_sync.is_set():
                self.model_sync.clear()
                try:self.receive_model()
                except Exception:
                    with self.control.lock,self.control.db:
                        self.control.event(None,'model-rejected','客户端拒绝模型设置，请重新发布')
            task=self.control.claim()
            if task: self.executor.execute(task)
            else: self.shutdown.wait(.15)

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass

    def respond(self,status,body,content_type='application/json; charset=utf-8',attachment=None):
        raw=json.dumps(body,ensure_ascii=False).encode() if isinstance(body,(dict,list)) else body
        self.send_response(status)
        self.send_header('Content-Type',content_type)
        self.send_header('Content-Length',str(len(raw)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('X-Frame-Options','DENY')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        if attachment: self.send_header('Content-Disposition',f'attachment; filename="{attachment}"')
        self.end_headers()
        self.wfile.write(raw)

    def gate(self,mutation=False):
        origin=f'http://127.0.0.1:{self.server.server_port}'
        if self.headers.get('Host')!=f'127.0.0.1:{self.server.server_port}':
            self.respond(403,{'error':'仅允许本机地址访问'}); return False
        supplied=self.headers.get('Origin')
        if supplied and supplied!=origin:
            self.respond(403,{'error':'拒绝跨站访问'}); return False
        if self.headers.get('Sec-Fetch-Site')=='cross-site':
            self.respond(403,{'error':'拒绝跨站访问'}); return False
        if urlsplit(self.path).path.startswith('/api/account/'):
            return True
        if urlsplit(self.path).path.startswith('/api/desktop/'):
            if not secrets.compare_digest(self.headers.get('X-FunnyWorker-Desktop-Token',''), self.server.app.desktop_token):
                self.respond(403,{'error':'客户端凭证无效'}); return False
            return True
        if mutation and not secrets.compare_digest(self.headers.get('X-FunnyWorker-Token',''),self.server.app.token):
            self.respond(403,{'error':'操作凭证无效'}); return False
        return True

    def do_GET(self):
        if not self.gate(): return
        path=urlsplit(self.path).path
        app=self.server.app
        if path=='/api/account/session':
            try:self.respond(200,app.accounts.verify(self.headers.get('Authorization','').removeprefix('Bearer ')))
            except Unauthorized as exc:self.respond(401,{'error':str(exc)})
        elif path=='/api/desktop/state':
            self.respond(200,app.control.snapshot())
        elif path=='/api/state':
            state=app.control.snapshot()
            state['capabilities']={'harness':bool(app.executor.harness_config or (app.model_service and app.model_service.metadata()['published_version'])),'sandbox':'trusted-tools','desktop':False}
            state['model']=app.model_service.metadata() if app.model_service else {'configured':False}
            self.respond(200,state)
        elif path=='/api/model/package':
            if not self.gate(True):return
            if not app.model_service:
                self.respond(404,{'error':'模型设置不可用'});return
            with app.control.lock:
                try:package=app.model_service.package()
                except FileNotFoundError:
                    self.respond(404,{'error':'模型设置尚未发布'});return
            self.respond(200,package)
        elif path.startswith('/api/tasks/'):
            task_id=path.removeprefix('/api/tasks/')
            with app.control.lock:
                row=app.control.db.execute('SELECT * FROM tasks WHERE id=?',(task_id,)).fetchone()
                if row:
                    detail=dict(row)
                    if detail['artifact'] and detail['artifact'].endswith('.txt'):
                        output=app.control.root/'artifacts'/detail['artifact']
                        if output.is_file():detail['output']=output.read_text(encoding='utf-8')[:100000]
                    detail['events']=[dict(r) for r in app.control.db.execute('SELECT * FROM events WHERE task=? ORDER BY seq',(task_id,))]
                else: detail=None
            self.respond(200,detail) if detail else self.respond(404,{'error':'任务不存在'})
        elif path.startswith('/artifacts/'):

            name=path.removeprefix('/artifacts/')
            with app.control.lock:
                row=app.control.db.execute("SELECT artifact FROM tasks WHERE artifact=? AND status='SUCCEEDED'",(name,)).fetchone()
            target=app.control.root/'artifacts'/name
            if not row or target.parent.resolve()!=(app.control.root/'artifacts').resolve() or not target.is_file():
                self.respond(404,{'error':'结果不存在'}); return
            self.respond(200,target.read_bytes(),mimetypes.guess_type(name)[0] or 'text/plain',name)
        elif path in {'/','/client','/admin','/app.js','/style.css','/logo.svg'}:
            target=WEB/('index.html' if path in {'/','/client','/admin'} else path[1:])
            raw=target.read_bytes().replace(b'__TOKEN__',app.token.encode()) if path in {'/','/client','/admin'} else target.read_bytes()
            self.respond(200,raw,{'/':'text/html; charset=utf-8','/client':'text/html; charset=utf-8','/admin':'text/html; charset=utf-8','/app.js':'text/javascript; charset=utf-8','/style.css':'text/css; charset=utf-8','/logo.svg':'image/svg+xml'}[path])
        else: self.respond(404,{'error':'页面不存在'})

    def do_POST(self):
        if not self.gate(True): return
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=200000: raise ValueError('请求大小不合法')
            if self.headers.get('Content-Type','').split(';')[0]!='application/json': raise ValueError('需要JSON请求')
            body=json.loads(self.rfile.read(length))
            if not isinstance(body,dict): raise ValueError('请求需为对象')
            path=urlsplit(self.path).path
            app=self.server.app
            if path=='/api/account/login':
                result=app.accounts.login(body.get('username'),body.get('password'),body.get('device'))
            elif path=='/api/account/logout':
                token=self.headers.get('Authorization','').removeprefix('Bearer ')
                app.accounts.verify(token);app.accounts.logout(token);result={'ok':True}
            elif path=='/api/desktop/claim':
                result={'task':app.control.claim('desktop')}
            elif path in {'/api/desktop/check','/api/desktop/progress','/api/desktop/finish','/api/desktop/exited'}:
                with app.control.lock:
                    row=app.control.db.execute("SELECT * FROM tasks WHERE id=? AND executor='desktop'",(body['id'],)).fetchone()
                    if not row or row['attempt']!=body['attempt']:
                        raise Conflict('执行授权不匹配')
                    task=dict(row)
                    if path.endswith('/check'):
                        result={'valid':app.control.valid(task),'node':app.control.node()}
                    elif path.endswith('/progress'):
                        percent=body['percent'];stage=body['stage']
                        if type(percent) is not int or not 0<=percent<=100 or not isinstance(stage,str) or len(stage)>300:
                            raise ValueError('进度无效')
                        app.control.progress(task,percent,stage);result={'ok':True}
                    elif path.endswith('/finish'):
                        if not isinstance(body['content'],str):raise ValueError('结果无效')
                        app.control.finish(task,body['content'],'txt');result={'ok':True}
                    else:
                        if not isinstance(body['message'],str) or type(body.get('uncertain',False)) is not bool:
                            raise ValueError('退出状态无效')
                        # This is an acknowledgement that the owning process has drained.
                        app.control.failed(task,body['message'],body.get('uncertain',False));result={'ok':True}
            elif path=='/api/mode':
                result=app.control.mode(body['mode'],body['version'])
            elif path=='/api/tasks':
                if body.get('kind')=='harness' and not (app.executor.harness_config or app.model_service):
                    raise Conflict('智能助手暂不可用，请联系管理员')
                result={'id':app.control.create(body.get('title'),body.get('kind'),body.get('source'),body.get('input',''),body.get('key'),body.get('executor','local'))}
            elif path in {'/api/model/save','/api/model/publish'}:
                if not app.model_service:raise Conflict('请先配置模型凭证')
                with app.control.lock,app.control.db:
                    if path.endswith('/save'):
                        result=app.model_service.edit(body['model'],body['thinking'],body['max_tokens'],body['version'])
                    else:
                        result=app.model_service.publish(body['version'])
                        app.model_sync.set()
                    app.control.event(None,'model','模型设置已保存' if path.endswith('/save') else '模型设置已发布')
            elif path=='/api/stop':
                app.control.stop(body['id']);result={'ok':True}
            else:
                self.respond(404,{'error':'接口不存在'});return
            self.respond(200,result)
        except Unauthorized as exc:self.respond(401,{'error':str(exc)})
        except Conflict as exc: self.respond(409,{'error':str(exc)})
        except (ValueError,KeyError,TypeError) as exc: self.respond(400,{'error':str(exc)})


def load_harness(path):
    if not path: return None
    config=json.loads(Path(path).read_text())
    if config.get('acknowledge_trusted_profile') is not True:
        raise ValueError('启用Harness前须明确批准profile；此原型不提供模型代码安全隔离')
    for key in ('dsh_bin','profile','provider','model','base_url'):
        if not isinstance(config.get(key),str) or not config[key].strip(): raise ValueError(f'缺少Harness配置：{key}')
    endpoint=urlsplit(config['base_url'])
    if endpoint.scheme not in {'http','https'} or not endpoint.hostname or endpoint.username or endpoint.password:
        raise ValueError('模型地址无效或含凭证')
    if not Path(config['dsh_bin']).is_file(): raise ValueError('Harness程序不存在')
    if not isinstance(config.get('patches'),list) or not config['patches'] or any(not isinstance(p,str) or not Path(p).is_file() for p in config['patches']):
        raise ValueError('必须提供已审核的本地profile补丁')
    return config


def main():
    parser=argparse.ArgumentParser(description='FunnyWorker本机原型')
    parser.add_argument('--port',type=int,default=8765)
    parser.add_argument('--data',default='.local/prototype')
    parser.add_argument('--harness-config')
    args=parser.parse_args()
    app=App(args.data,load_harness(args.harness_config))
    server=ThreadingHTTPServer(('127.0.0.1',args.port),Handler)
    server.app=app
    app.port=server.server_port
    from .model_config import private_write
    private_write(app.control.root/'client'/'control-connection.json',json.dumps({
        'url':f'http://127.0.0.1:{app.port}','token':app.desktop_token}).encode())
    app.thread.start()
    print(f'FunnyWorker 已启动：http://127.0.0.1:{server.server_port}',flush=True)
    try: server.serve_forever(poll_interval=.2)
    except KeyboardInterrupt: pass
    finally:
        app.shutdown.set()
        app.thread.join(15)
        server.server_close()
        if not app.thread.is_alive(): app.control.db.close()

if __name__=='__main__': main()
