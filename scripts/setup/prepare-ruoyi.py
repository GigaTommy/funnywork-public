#!/usr/bin/env python3
"""Prepare isolated local management dependencies; never modifies existing databases."""
import hashlib, json, os, pathlib, re, secrets, subprocess, sys, time
root=pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root))
from funnyworker.model_config import private_write, protect_directory
subprocess.run([sys.executable,str(root/'scripts/prepare-config-templates.py')],check=True)
local=root/'.local/ruoyi'; local.mkdir(parents=True,exist_ok=True);os.chmod(local,0o700)
protect_directory(local)
secret=local/'credentials.json'
if not secret.exists():
    secret.write_text(json.dumps({'database':secrets.token_urlsafe(32),'redis':secrets.token_urlsafe(32),'jwt':secrets.token_urlsafe(48),'username':'admin','password':secrets.token_urlsafe(18)}));os.chmod(secret,0o600)
c=json.loads(secret.read_text())
login=local/'admin-login.json';login.write_text(json.dumps({'username':c['username'],'password':c['password']}));os.chmod(login,0o600)
pg=pathlib.Path(os.environ.get('FUNNYWORKER_PG_BIN','/opt/homebrew/opt/postgresql@16/bin'))
windows = os.name == 'nt'
project_file = local/'compose-project'
if project_file.exists():
    project_name = project_file.read_text(encoding='utf-8').strip()
else:
    project_name = 'funnyworker-'+hashlib.sha256(str(root).encode()).hexdigest()[:8]
    if windows: private_write(project_file, project_name.encode())
if not re.fullmatch(r'funnyworker-[a-z0-9-]+', project_name):
    raise ValueError('Invalid local Compose project name')
compose = ['docker', 'compose', '-p', project_name, '-f', str(root/'scripts/setup/compose.windows.yml')]
compose_env = {**os.environ, 'FUNNYWORKER_LOCAL_DB_PASSWORD':c['database'], 'FUNNYWORKER_LOCAL_REDIS_PASSWORD':c['redis']}
def command(args):
    if windows and pathlib.Path(args[0]).parent == pg:
        values = [str(value) for value in args[1:]]
        return compose+['exec','-T','-e','PGPASSWORD', 'postgres', pathlib.Path(args[0]).name]+values
    return [str(x) for x in args]
def run(args,**kwargs):
    if windows: kwargs['env']={**compose_env, **kwargs.get('env',{})}
    if windows and pathlib.Path(args[0]).parent == pg and '-f' in args:
        position=args.index('-f')
        kwargs.update(input=pathlib.Path(args[position+1]).read_text(encoding='utf-8'),text=True,encoding='utf-8')
        args=args[:position]+args[position+2:]
    return subprocess.run(command(args),check=True,stdout=subprocess.DEVNULL,**kwargs)
pw=local/'pg-password';pw.write_text(c['database']);os.chmod(pw,0o600)
data=local/'postgres'
if windows:
    run(compose+['up','-d'])
    for attempt in range(30):
        probe=subprocess.run(compose+['exec','-T','postgres','pg_isready','-U','fwruoyi','-d','postgres'],env=compose_env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if probe.returncode==0:break
        time.sleep(1)
    else:raise SystemExit('Local PostgreSQL did not become ready.')
else:
    if not (data/'PG_VERSION').exists():run([pg/'initdb','-D',data,'-U','fwruoyi','--pwfile',pw,'--auth-local=trust','--auth-host=scram-sha-256','--encoding=UTF8','--locale=C'])
    if subprocess.run([pg/'pg_ctl','-D',data,'status'],stdout=subprocess.DEVNULL).returncode!=0:
        run([pg/'pg_ctl','-D',data,'-l',local/'postgres.log','-o',f'-p 16432 -h 127.0.0.1 -k {local}','start'])
env={**compose_env,'PGPASSWORD':c['database']} if windows else {**os.environ,'PGPASSWORD':c['database']}
pg_port='5432' if windows else '16432'
def sql(statement):return subprocess.check_output(command([pg/'psql','-h','127.0.0.1','-p',pg_port,'-U','fwruoyi','-d','postgres','-tAc',statement]),env=env,text=True,encoding='utf-8').strip()
if not sql("SELECT 1 FROM pg_database WHERE datname='funnyworker_management'"):run([pg/'createdb','-h','127.0.0.1','-p',pg_port,'-U','fwruoyi','funnyworker_management'],env=env)
args=[pg/'psql','-h','127.0.0.1','-p',pg_port,'-U','fwruoyi','-d','funnyworker_management','-v','ON_ERROR_STOP=1']
if not (local/'initialized').exists():
    for filename in ['postgres_ry_vue_5.X.sql','postgres_ry_workflow.sql']:
        run(args+['-f',root/'services/identity/script/sql/postgres'/filename],env=env)
    # bcrypt via stdin so the password does not appear in a process argument.
    if windows:
        import bcrypt
        hashed=bcrypt.hashpw(c['password'].encode(),bcrypt.gensalt(rounds=10,prefix=b'2a')).decode()
    else:
        hashed=subprocess.check_output(['/usr/sbin/htpasswd','-niBC','10','admin'],input=c['password']+'\n',text=True).strip().split(':',1)[1].replace('$2y$','$2a$')
    cleanup=f"""UPDATE sys_user SET password='{hashed}',nick_name='系统管理员',email='',phonenumber='' WHERE user_id=1;
DELETE FROM sys_user_role WHERE user_id IN (3,4); DELETE FROM sys_user WHERE user_id IN (3,4);
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE menu_id=5 OR menu_id BETWEEN 1500 AND 1511);
DELETE FROM sys_menu WHERE menu_id=5 OR menu_id BETWEEN 1500 AND 1511;
DELETE FROM sys_role_menu WHERE menu_id=4;
DELETE FROM sys_menu WHERE menu_id=4;
DELETE FROM sys_role_menu WHERE role_id IN (3,4) AND NOT EXISTS (SELECT 1 FROM sys_user_role WHERE sys_user_role.role_id=sys_role_menu.role_id);
DELETE FROM sys_role_dept WHERE role_id IN (3,4) AND NOT EXISTS (SELECT 1 FROM sys_user_role WHERE sys_user_role.role_id=sys_role_dept.role_id);
DELETE FROM sys_role WHERE role_id IN (3,4) AND NOT EXISTS (SELECT 1 FROM sys_user_role WHERE sys_user_role.role_id=sys_role.role_id);
UPDATE sys_tenant SET company_name='FunnyWorker' WHERE tenant_id='000000';
UPDATE sys_dept SET dept_name='FunnyWorker' WHERE dept_id=100;
UPDATE sys_oss_config SET status='1';
UPDATE sys_menu SET visible='1' WHERE menu_id IN (4,117,120,118);
UPDATE sys_dept SET dept_name='管理部门',parent_id=100,ancestors='0,100',leader=null,phone='',email='' WHERE dept_id=103;
UPDATE sys_dept SET del_flag='2' WHERE dept_id NOT IN (100,103);
UPDATE sys_tenant SET contact_user_name='系统管理员',contact_phone='' WHERE tenant_id='000000';
"""
    run(args,input=cleanup,text=True,encoding='utf-8',env=env);(local/'initialized').write_text('5.6.2\n')
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-mobile-session.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-android-session.sql'],env=env)
run(args+['-f',root/'scripts/setup/ruoyi-business-menus.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-identity-directory.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-directory-jobs.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-directory-previews.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-directory-menu-fix.sql'],env=env)
run(args+['--single-transaction','-f',root/'scripts/setup/ruoyi-directory-audit.sql'],env=env)
redis_conf=local/'redis.conf';redis_conf.write_text(f'bind 127.0.0.1\nport 16379\nrequirepass {c["redis"]}\ndir {local}\nappendonly yes\ndaemonize yes\npidfile {local}/redis.pid\nlogfile {local}/redis.log\n');os.chmod(redis_conf,0o600)
if not windows:
    probe=subprocess.run(['redis-cli','-p','16379','ping'],env={**os.environ,'REDISCLI_AUTH':c['redis']},stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if probe.returncode!=0:run(['redis-server',redis_conf])
config=local/'application-funnyworker.yml';config.write_text(f'''server:
  address: 127.0.0.1
  port: 8767
spring:
  application:
    name: FunnyWorker
  boot:
    admin:
      client:
        enabled: false
  datasource:
    dynamic:
      p6spy: false
      datasource:
        master:
          driverClassName: org.postgresql.Driver
          url: jdbc:postgresql://127.0.0.1:16432/funnyworker_management
          username: fwruoyi
          password: {c['database']}
  data:
    redis:
      host: 127.0.0.1
      port: 16379
      database: 0
      password: {c['redis']}
sa-token:
  jwt-secret-key: {c['jwt']}
api-decrypt:
  enabled: false
snail-job:
  enabled: false
sse:
  enabled: false
captcha:
  enable: false
springdoc:
  api-docs:
    enabled: false
''');os.chmod(config,0o600)
print('Management database and configuration prepared.')
