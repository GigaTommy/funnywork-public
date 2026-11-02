"""Small synthetic live checks; sends no source-project data to the provider."""
import json
import re
import time
import urllib.request
import uuid
from pathlib import Path
base='http://127.0.0.1:8765'
with urllib.request.urlopen(base+'/client') as response:page=response.read().decode()
token=re.search(r'name="fw-token" content="([^"]+)"',page)[1]
def request(path,payload=None):
    req=urllib.request.Request(base+path,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json','X-FunnyWorker-Token':token})
    with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
def wait(tid):
    until=time.monotonic()+100
    while time.monotonic()<until:
        t=request('/api/tasks/'+tid)
        if t['status'] not in {'QUEUED','RUNNING','STOPPING'}:return t
        time.sleep(.2)
    raise TimeoutError('Live task deadline exceeded')
results=[]
cases=[('连接检查','只回复：连接正常','连接正常'),('文稿整理','根据以下材料生成三条简短待办，不添加事实：周一整理文件；周二核对表格；周三提交报告。','周三'),('表格分析','计算下表各部门工时及总计，仅依据数据：综合办32小时，业务科45小时，技术组38小时。请给出总工时。','115')]
for title,prompt,expected in cases:
    started=time.monotonic();payload={'title':title,'kind':'harness','source':'employee','input':prompt,'key':uuid.uuid4().hex}
    tid=request('/api/tasks',payload)['id'];task=wait(tid)
    passed=task['status']=='SUCCEEDED' and expected in task.get('output','')
    results.append({'case':title,'passed':passed,'status':task['status'],'elapsed':round(time.monotonic()-started,2),'task':tid,'output':task.get('output','')})
    print(json.dumps({k:v for k,v in results[-1].items() if k!='output'},ensure_ascii=False),flush=True)
    if not passed:raise RuntimeError('真实模型验证失败')
tid=request('/api/tasks',{'title':'停止检查','kind':'harness','source':'employee','input':'请写一篇3000字的办公自动化说明。','key':uuid.uuid4().hex})['id']
for _ in range(100):
    if request('/api/tasks/'+tid)['status']=='RUNNING':break
    time.sleep(.05)
request('/api/stop',{'id':tid});task=wait(tid)
# The interrupted provider request is uncertain, with no result committed.
passed=task['status'] in {'UNKNOWN','CANCELLED'} and task['artifact'] is None
results.append({'case':'停止检查','passed':passed,'status':task['status'],'task':tid})
print(json.dumps(results[-1],ensure_ascii=False),flush=True)
Path('.local/live-model-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
if not passed:raise RuntimeError('停止检查失败')
print('真实模型基础检查全部通过。',flush=True)
