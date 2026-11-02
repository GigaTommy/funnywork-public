#!/usr/bin/env python3
"""Exercise the real task-center HTTP API against an isolated PostgreSQL large dataset.

Requires an explicit already-built JAR. Never connects to a production database.
All generated data, credentials and logs stay under .local/agent/.
"""
import argparse,concurrent.futures,hashlib,json,os,secrets,shutil,socket,subprocess,time,urllib.request,urllib.error
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',required=True,type=Path);p.add_argument('--rows',type=int,default=1000000);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if not 10000<=a.rows<=2000000:p.error('rows must be 10,000–2,000,000')
 if os.uname().sysname=='Darwin' and not Path('/Volumes/SSD').is_mount():p.error('External SSD is not mounted')
 out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);out.chmod(0o700);data=out/'postgres';pg=Path(os.environ.get('FUNNYWORKER_PG_BIN','/opt/homebrew/opt/postgresql@16/bin'));ports=(19439,19440)
 if data.exists():p.error('Existing test database; inspect before retrying')
 for port in ports:
  with socket.socket() as s:
   if s.connect_ex(('127.0.0.1',port))==0:p.error('Test port occupied: '+str(port))
 def sql(text):return subprocess.check_output([str(pg/'psql'),'-h','127.0.0.1','-p',str(ports[0]),'-U','scale','-d','postgres','-v','ON_ERROR_STOP=1','-At'],input=text,text=True)
 def start(args,name,env=None):
  f=(out/name).open('w');handles.append(f);c=subprocess.Popen([str(x) for x in args],stdout=f,stderr=f,env=env);children.append(c);return c
 handles=[];children=[];passed=False;results=[]
 try:
  subprocess.run([str(pg/'initdb'),'-D',str(data),'-U','scale','--auth=trust','--no-locale'],check=True,stdout=subprocess.DEVNULL)
  start([pg/'postgres','-D',data,'-h','127.0.0.1','-p',ports[0],'-k','','-c','shared_buffers=128MB'],'postgres.log')
  for _ in range(100):
   if subprocess.run([str(pg/'pg_isready'),'-h','127.0.0.1','-p',str(ports[0])],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
   time.sleep(.1)
  token=secrets.token_urlsafe(40);limited=secrets.token_urlsafe(40)
  env={**os.environ,'FUNNYWORK_PORT':str(ports[1]),'FUNNYWORK_DB_URL':f'jdbc:postgresql://127.0.0.1:{ports[0]}/postgres','FUNNYWORK_DB_USER':'scale','FUNNYWORK_DB_PASSWORD':'','FUNNYWORK_BOOTSTRAP_TOKEN':token,'FUNNYWORK_ENTERPRISE_ONLY_TENANTS':'','FUNNYWORK_SCHEDULING_ENABLED':'false','FUNNYWORK_MODEL_INFERENCE_ENABLED':'false','FUNNYWORK_IDENTITY_CONFIG':'','FUNNYWORK_GATEWAY_HOSTS':''}
  jar=a.jar.resolve();digest=hashlib.sha256(jar.read_bytes()).hexdigest()
  start(['java','-Xmx768m','-Djava.io.tmpdir='+os.environ['TMPDIR'],'-jar',jar],'business.log',env)
  base=f'http://127.0.0.1:{ports[1]}'
  for _ in range(120):
   try:
    with urllib.request.urlopen(base+'/actuator/health',timeout=2) as r:
     if json.load(r)['status']=='UP':break
   except (OSError,ValueError):pass
   if any(c.poll() is not None for c in children):raise RuntimeError('Isolated service exited; inspect logs')
   time.sleep(.5)
  else:raise RuntimeError('Isolated business startup timeout')
  sql("""insert into principals values('scope-admin','demo','USER','Scoped admin','["ADMIN"]','["sandbox"]',true,1);
insert into api_tokens values('%s','scope-admin',%s,false);
insert into tenant_guards(tenant_id) values('other');
insert into principals values('other-owner','other','USER','Other owner','["VIEWER"]','["sandbox"]',true,1);
insert into principals select 'member-'||lpad(g::text,4,'0'),'demo','USER','Member '||lpad(g::text,4,'0'),'["VIEWER"]','["sandbox"]',true,1 from generate_series(0,999) g;
insert into agent_versions values('scale-agent','demo','Scale agent','1','model.read.v1','PUBLISHED','bootstrap-admin');
insert into agent_versions values('other-agent','other','Other agent','1','model.read.v1','PUBLISHED','other-owner');
insert into execution_projects(id,tenant_id,zone,name) select 'project-'||g,'demo','sandbox','Project '||g from generate_series(0,9) g;
insert into task_departments select 'department-'||g,'demo','sandbox','Department '||g,1700000000000 from generate_series(0,19) g;
insert into management_task_limits values('scope-admin','demo',true,'["department-2"]',true,'["project-3"]',1);
insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at,title) values('cross-tenant','other','other-owner',1,'sandbox','other-agent','FAILED','other','hash',1700000000000,'Private other tenant'),('cross-zone','demo','member-0000',1,'restricted','scale-agent','FAILED','restricted','hash',1700000000000,'Private other zone');
"""%(hashlib.sha256(limited.encode()).hexdigest(),int(time.time()*1000)+3600000))
  def request(path,credential=token):
   started=time.perf_counter();r=urllib.request.Request(base+'/api/v1/management-tasks'+path,headers={'Authorization':'Bearer '+credential})
   try:
    with urllib.request.urlopen(r,timeout=30) as f:value=json.load(f);status=f.status
   except urllib.error.HTTPError as e:value=json.load(e);status=e.code
   return status,value,(time.perf_counter()-started)*1000
  previous=0
  for count in sorted(set([min(100000,a.rows),a.rows])):
   started=time.perf_counter()
   sql(f"""insert into tasks(id,tenant_id,owner_id,owner_auth_version,zone,agent_id,state,request_key,request_hash,created_at,title)
select 'task-'||lpad(g::text,8,'0'),'demo','member-'||lpad((g%1000)::text,4,'0'),1,'sandbox','scale-agent',(array['SUCCEEDED','FAILED','RUNNING','OUTPUT_READY','CANCELLED'])[1+(g%5)],'scale-'||g,'hash',1700000000000+g/20,'Task '||g from generate_series({previous+1},{count}) g;
insert into task_management_context select 'task-'||lpad(g::text,8,'0'),'demo','department-'||(g%20),'project-'||((g/20)%10),'EXPLICIT_ASSIGNMENT',1,1700000000000,'bootstrap-admin' from generate_series({previous+1},{count}) g;
ANALYZE tasks; ANALYZE task_management_context; ANALYZE principals; ANALYZE agent_versions;""")
   seed_ms=(time.perf_counter()-started)*1000;previous=count
   samples={}
   cases={'first':'?limit=50','owner':'?owner=member-0001&limit=50','department':'?department=department-2&limit=50','project':'?project=project-3&limit=50','search':'?query=Task%20999&limit=50','members':'/members?query=Member','options':'/options'}
   first=None
   for name,path in cases.items():
    times=[]
    for repeat in range(5):
     status,value,ms=request(path);assert status==200,(name,status);times.append(ms)
     if name=='first':assert value['total']==count and sum(r['count'] for r in value['summary'])==count;first=value
     if name=='owner':assert value['total']==count//1000
    samples[name]={'p50Ms':round(sorted(times)[2],2),'maxMs':round(max(times),2)}
   seen=set();page=first
   for _ in range(20):
    ids=[r['id'] for r in page['items']];assert not seen.intersection(ids);seen.update(ids)
    path=f"?limit=50&before={page['nextCreatedAt']}&beforeId={page['nextId']}"
    status,page,_=request(path);assert status==200 and page['total']==count
   status,scope,_=request('?limit=100',limited);assert status==200
   expected=(count-62)//200+1 if count>=62 else 0
   assert scope['total']==expected and all(r['department_id']=='department-2' and r['project_id']=='project-3' for r in scope['items'])
   assert request('/cross-tenant')[0]==404 and request('/cross-zone')[0]==404 and request('/task-00000001',limited)[0]==404
   with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    burst=list(pool.map(lambda i:request('?limit=50' if i%2==0 else '?owner=member-0001&limit=50'),range(32)))
   assert all(status==200 for status,_,_ in burst)
   latencies=sorted(ms for _,_,ms in burst);p95=latencies[int(.95*(len(latencies)-1))]
   record={'rows':count,'seedMs':round(seed_ms),'sequential':samples,'parallelClients':8,'parallelRequests':32,'parallelP95Ms':round(p95,2),'paginationUniqueRows':len(seen),'scopeTotal':scope['total'],'isolationPassed':True,'thresholdsPassed':samples['first']['maxMs']<2000 and samples['options']['maxMs']<1000 and p95<5000}
   results.append(record);(out/'results.json').write_text(json.dumps({'jarSha256':digest,'results':results},indent=2)+'\n');print(json.dumps(record),flush=True)
  plan=sql("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) select t.id,t.created_at from tasks t join agent_versions v on v.id=t.agent_id and v.tenant_id=t.tenant_id join principals p on p.id=t.owner_id and p.tenant_id=t.tenant_id left join task_management_context c on c.task_id=t.id and c.tenant_id=t.tenant_id where t.tenant_id='demo' and t.zone in ('sandbox') and (t.created_at<1700000025000 or (t.created_at=1700000025000 and t.id<'task-00500000')) order by t.created_at desc,t.id desc limit 51;")
  (out/'deep-page-plan.json').write_text(plan)
  passed=all(r['thresholdsPassed'] for r in results)
  if not passed:raise RuntimeError('Scale thresholds failed; inspect results')
 finally:
  for child in reversed(children):
   if child.poll() is None:child.terminate()
   try:child.wait(timeout=20)
   except subprocess.TimeoutExpired:child.kill();child.wait()
  for h in handles:h.close()
  if passed:shutil.rmtree(data)
  print('Isolated services stopped; '+('synthetic database cleaned' if passed else 'failed evidence/database retained: '+str(out)),flush=True)
if __name__=='__main__':main()
