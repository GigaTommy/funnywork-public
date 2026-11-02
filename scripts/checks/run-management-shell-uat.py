#!/usr/bin/env python3
"""Serve the real management shell with isolated PostgreSQL, Redis and identity/business services.

Only synthetic accounts/data are created. Keep this foreground process alive during manual UI
acceptance; Ctrl-C stops its own children. Logs and private credentials stay in .local/agent/.
"""
from pathlib import Path
import json
import os
import secrets
import signal
import socket
import subprocess
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / '.local/agent/packages/management-shell'
PG = Path(os.environ.get('FUNNYWORKER_PG_BIN','/opt/homebrew/opt/postgresql@16/bin'))
CLIENT = 'e5cd7e4891bf95d1d19206ce24a7b32e'
PASSWORD = 'ShellAcceptance2026!'
children = []
handles = []


def run(args, **kwargs):
    return subprocess.run([str(x) for x in args], check=True, **kwargs)


def sql(database, statement):
    return subprocess.check_output([str(PG/'psql'), '-h', '127.0.0.1', '-p', '19434', '-U', 'shelluat', '-d', database,
                                   '-v', 'ON_ERROR_STOP=1', '-At'], input=statement, text=True).strip()


def start(name, args, env=None, cwd=ROOT):
    stream = (OUT/(name+'.log')).open('w')
    handles.append(stream)
    process = subprocess.Popen([str(x) for x in args], cwd=cwd, env=env or os.environ.copy(), stdout=stream, stderr=stream, start_new_session=True)
    children.append(process)
    return process


def request(path, body=None, token=None, method=None):
    headers = {'Content-Type':'application/json', 'clientid':CLIENT}
    if token: headers['Authorization']='Bearer '+token
    req = urllib.request.Request('http://127.0.0.1:8767'+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        result=json.load(response)
    if result.get('code')!=200: raise RuntimeError(path+': '+str(result.get('msg')))
    return result


def wait_http(url, name):
    for _ in range(100):
        try:
            with urllib.request.urlopen(url, timeout=2): return
        except (OSError, urllib.error.HTTPError):
            if any(p.poll() is not None for p in children): raise RuntimeError(name+' child exited; inspect local logs')
            time.sleep(1)
    raise RuntimeError(name+' startup timeout')


def main():
    if not Path('/Volumes/SSD').is_mount(): raise RuntimeError('External SSD is not mounted')
    OUT.mkdir(parents=True, exist_ok=True)
    os.chmod(OUT, 0o700)
    (OUT/'ready.json').unlink(missing_ok=True)
    for port in (19434,19380,8767,18765,19195):
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',port))==0: raise RuntimeError('Reserved UAT port occupied: '+str(port))
    data=OUT/'postgres'
    if data.exists(): raise RuntimeError('Existing UAT database retained; explicitly archive/remove it before a new run')
    run([PG/'initdb','-D',data,'-U','shelluat','--auth=trust','--encoding=UTF8','--locale=C'], stdout=subprocess.DEVNULL)
    start('postgres',[PG/'postgres','-D',data,'-p','19434','-h','127.0.0.1','-k',OUT])
    for _ in range(30):
        if subprocess.run([str(PG/'pg_isready'),'-h','127.0.0.1','-p','19434'], stdout=subprocess.DEVNULL).returncode==0: break
        time.sleep(.2)
    sql('postgres','create database shell_identity; create database shell_business;')
    for file in ('postgres_ry_vue_5.X.sql','postgres_ry_workflow.sql'):
        sql('shell_identity',(ROOT/'services/identity/script/sql/postgres'/file).read_text())
    hashed=subprocess.check_output(['/usr/sbin/htpasswd','-niBC','10','admin'],input=PASSWORD+'\n',text=True).strip().split(':',1)[1].replace('$2y$','$2a$')
    sql('shell_identity',f"update sys_user set password='{hashed}' where user_id=1; update sys_oss_config set status='1';")
    for file in ('ruoyi-business-menus.sql','ruoyi-mobile-session.sql','ruoyi-android-session.sql','ruoyi-identity-directory.sql','ruoyi-directory-jobs.sql','ruoyi-directory-previews.sql','ruoyi-directory-menu-fix.sql','ruoyi-directory-audit.sql'):
        sql('shell_identity','BEGIN;\n'+(ROOT/'scripts/setup'/file).read_text()+'\nCOMMIT;')
    redis_secret=secrets.token_hex(24)
    redis=OUT/'redis.conf'
    redis.write_text(f'bind 127.0.0.1\nport 19380\nrequirepass {redis_secret}\ndir {OUT}\nappendonly no\nsave ""\n')
    os.chmod(redis,0o600)
    start('redis',['redis-server',redis])
    config=OUT/'identity.yml'
    config.write_text(f'''server:
  address: 127.0.0.1
  port: 8767
spring:
  boot.admin.client.enabled: false
  datasource.dynamic:
    p6spy: false
    datasource.master:
      driverClassName: org.postgresql.Driver
      url: jdbc:postgresql://127.0.0.1:19434/shell_identity
      username: shelluat
      password: ''
  data.redis:
    host: 127.0.0.1
    port: 19380
    database: 0
    password: {redis_secret}
sa-token.jwt-secret-key: {secrets.token_hex(48)}
api-decrypt.enabled: false
snail-job.enabled: false
sse.enabled: false
captcha.enable: false
springdoc.api-docs.enabled: false
logging.file.path: {OUT}/identity-logs
''')
    os.chmod(config,0o600)
    java=Path(os.environ['JAVA_HOME'])/'bin/java'
    start('identity',[java,'-Djava.io.tmpdir='+os.environ['TMPDIR'],'-jar',ROOT/'services/identity/ruoyi-admin/target/ruoyi-admin.jar','--spring.profiles.active=dev','--spring.config.additional-location=file:'+str(config)])
    wait_http('http://127.0.0.1:8767/auth/tenant/list','identity')
    admin=request('/auth/login',{'tenantId':'000000','username':'admin','password':PASSWORD,'clientId':CLIENT,'grantType':'password'})['data']['access_token']
    cases={
      'shell_manager':(['ADMIN'],['funnyworker:task-center:read','funnyworker:annual:read','funnyworker:models:read','funnyworker:usage:read']),
      'shell_operator':(['ADMIN','OPERATOR','VIEWER'],['funnyworker:tasks:read','funnyworker:task-center:read','funnyworker:annual:read','funnyworker:models:read','funnyworker:usage:read']),
      'shell_viewer':(['VIEWER'],['funnyworker:tasks:read']),
      'shell_model':(['ADMIN'],['funnyworker:models:read','funnyworker:usage:read']),
      'shell_unbound':([],['funnyworker:tasks:read','funnyworker:task-center:read'])
    }
    accounts={}
    for index,(username,(roles,permissions)) in enumerate(cases.items(),start=9001):
        menus=sql('shell_identity',"select menu_id from sys_menu where perms in ("+','.join("'"+p+"'" for p in permissions)+") or menu_id=8000;").splitlines()
        request('/system/role',{'roleName':username,'roleKey':username,'roleSort':99,'status':'0','menuCheckStrictly':True,'deptCheckStrictly':True,'dataScope':'1','menuIds':menus,'deptIds':[]},admin)
        role=request('/system/role/list?roleKey='+username,token=admin)['rows'][0]['roleId']
        request('/system/user',{'userName':username,'nickName':username,'deptId':103,'password':PASSWORD,'status':'0','sex':'0','roleIds':[role],'postIds':[]},admin)
        user=request('/system/user/list?userName='+username,token=admin)['rows'][0]['userId']
        accounts[username]={'userId':str(user),'principalId':username,'roles':roles,'permissions':permissions}
    env={**os.environ,'FUNNYWORK_PORT':'18765','FUNNYWORK_DB_URL':'jdbc:postgresql://127.0.0.1:19434/shell_business','FUNNYWORK_DB_USER':'shelluat','FUNNYWORK_DB_PASSWORD':'','FUNNYWORK_BOOTSTRAP_TOKEN':secrets.token_hex(32),'FUNNYWORK_SCHEDULING_ENABLED':'false','FUNNYWORK_MODEL_INFERENCE_ENABLED':'false'}
    start('business',[java,'-Djava.io.tmpdir='+os.environ['TMPDIR'],'-jar',ROOT/'services/business/target/funnyworker-server-0.1.0-SNAPSHOT.jar','--funnywork.local-management-bootstrap=true'],env)
    wait_http('http://127.0.0.1:18765/actuator/health','business')
    for username,account in accounts.items():
        if not account['roles']:continue
        sql('shell_business',f"insert into principals values('{username}','demo','USER','{username}','{json.dumps(account['roles'])}','[\"sandbox\"]',true,1); insert into management_identity_links values('000000','{account['userId']}','{username}');")
    sql('shell_business', '''
insert into execution_projects(id,tenant_id,zone,name) values('shell-project','demo','sandbox','验收项目');
insert into task_departments values('shell-department','demo','sandbox','验收部门',1792458000000);
insert into agent_versions values('shell-annual-agent','demo','年报验收','1','report.annual.v1','PUBLISHED','shell_operator');
insert into agent_versions values('shell-maintenance-agent','demo','维护验收','1','repository.maintain.v1','PUBLISHED','shell_operator');
insert into agent_versions values('shell-model-agent','demo','普通执行','1','model.read.v1','PUBLISHED','shell_viewer');
insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at,title) values
('shell-annual-task','demo','shell_operator',1,'sandbox','shell-annual-agent','OUTPUT_READY','shell-annual','hash',1792458000000,'年报往返验收'),
('shell-maintenance-task','demo','shell_operator',1,'sandbox','shell-maintenance-agent','RUNNING','shell-maintenance','hash',1792457000000,'维护往返验收'),
('shell-personal-task','demo','shell_viewer',1,'sandbox','shell-model-agent','READY','shell-personal','hash',1792456000000,'个人只读任务');
insert into task_plans(task_id,tenant_id,version,input_count,request_hash,created_at) select id,'demo',1,1,'hash',1792458000000 from tasks;
insert into work_units(id,task_id,ordinal,input_text,state,tenant_id) values
('shell-annual-unit','shell-annual-task',0,'{}','READY','demo'),
('shell-maintenance-unit','shell-maintenance-task',0,'{}','READY','demo');
insert into annual_datasets(id,tenant_id,owner_id,zone,project_id,name,report_year,materials_json,snapshot_hash,agent_id,created_at) values
('shell-dataset','demo','shell_operator','sandbox','shell-project','验收企业',2025,'[]','hash','shell-annual-agent',1792458000000);
insert into annual_report_jobs(task_id,unit_id,tenant_id,dataset_id,platform,spec_json,spec_hash,state,created_at) values
('shell-annual-task','shell-annual-unit','demo','shell-dataset','macos','{}','hash','WAITING',1792458000000);
insert into maintenance_repositories(id,tenant_id,zone,agent_id,slug,base_branch,checks_json) values
('shell-repository','demo','sandbox','shell-maintenance-agent','synthetic/acceptance','main','[]');
insert into maintenance_problems(id,tenant_id,owner_id,repository_id,task_id,request_key,request_hash,title,requirement_json,platform,state,created_at) values
('shell-problem','demo','shell_operator','shell-repository','shell-maintenance-task','shell-problem','hash','维护往返验收','{"requirement":"仅供界面验收，不执行代码","acceptance":"核对导航与权限"}','macos','IN_PROGRESS',1792457000000);
insert into maintenance_specs values('shell-maintenance-unit','demo','shell-problem','macos','{}','hash');
insert into task_management_context select id,'demo','shell-department','shell-project','EXPLICIT_ASSIGNMENT',1,1792458000000,'shell_operator' from tasks;
''')
    (OUT/'accounts.json').write_text(json.dumps({'password':PASSWORD,'accounts':accounts},indent=2))
    os.chmod(OUT/'accounts.json',0o600)
    env={**os.environ,'VITE_APP_BASE_API':'/ruoyi','VITE_APP_ENCRYPT':'false','VITE_APP_CONTEXT_PATH':'/','VITE_APP_PORT':'19195','VITE_IDENTITY_PROXY':'http://127.0.0.1:8767','VITE_BUSINESS_PROXY':'http://127.0.0.1:18765','BROWSER':'none'}
    start('frontend',['corepack','pnpm','exec','vite','--host','127.0.0.1','--port','19195','--strictPort'],env,ROOT/'apps/management')
    wait_http('http://127.0.0.1:19195/','frontend')
    (OUT/'ready.json').write_text(json.dumps({'url':'http://127.0.0.1:19195/','synthetic':True,'identity':'real','business':'real','database':'isolated PostgreSQL'}))
    print('Real shell UAT ready: http://127.0.0.1:19195/ (synthetic accounts only)',flush=True)
    while True:
        if any(p.poll() is not None for p in children):raise RuntimeError('UAT process exited; inspect logs')
        time.sleep(1)


if __name__=='__main__':
    def stop(*_):raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,stop)
    try:main()
    except KeyboardInterrupt:pass
    finally:
        for process in reversed(children):
            if process.poll() is None:os.killpg(process.pid,signal.SIGTERM)
        for process in reversed(children):
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
        for handle in handles:handle.close()
        (OUT/'ready.json').unlink(missing_ok=True)
