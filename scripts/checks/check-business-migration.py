#!/usr/bin/env python3
import json,pathlib,secrets,urllib.request,urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
c=json.loads((root/'.local/ruoyi/credentials.json').read_text());base='http://127.0.0.1:8765';cid='e5cd7e4891bf95d1d19206ce24a7b32e'
def req(path,method='GET',data=None,token=None,business=False):
 h={'Content-Type':'application/json','clientid':cid}
 if token:h['Authorization']='Bearer '+token
 if business:h['X-FunnyWorker-Management']='1'
 r=urllib.request.Request(base+path,headers=h,method=method,data=None if data is None else json.dumps(data).encode())
 try:
  with urllib.request.urlopen(r,timeout=30) as response:
   raw=response.read();return response.status,json.loads(raw) if raw else None
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def good(path,method='GET',data=None,token=None,business=False):
 status,value=req(path,method,data,token,business);assert status<300,(path,status,value)
 if isinstance(value,dict) and 'code' in value:assert value['code']==200,(path,value)
 return value
def login(name,password):return good('/ruoyi/auth/login','POST',{'tenantId':'000000','username':name,'password':password,'clientId':cid,'grantType':'password'})['data']['access_token']
admin=login('admin',c['password']);key='scope_'+secrets.token_hex(4);user=None;role=None;bound=False
try:
 for path in ['/me','/nodes','/tenant/scheduling-metrics','/model-profiles?zone=sandbox','/tasks','/management-bindings','/managed-gateways?zone=sandbox','/skills?zone=sandbox','/mcp-bindings?zone=sandbox','/tool-templates','/computer-controls','/recovery/cases?zone=sandbox']:
  good('/api/v1'+path,token=admin,business=True)
 # Updating identical metadata exercises the real save and optimistic conflict paths.
 profiles=good('/api/v1/model-profiles?zone=sandbox',token=admin,business=True)['items']
 if profiles:
  p=profiles[0];data={'name':p['name'],'zone':'sandbox','model':p['model'],'baseUrl':p['base_url'],'requireToken':p['require_token'],'enabled':p['enabled'],'expectedVersion':p['version']}
  good('/api/v1/model-profiles/'+p['id'],'PUT',data,admin,True)
  assert req('/api/v1/model-profiles/'+p['id'],'PUT',data,admin,True)[0]==409
 good('/ruoyi/system/role','POST',{'roleName':'权限验证','roleKey':key,'roleSort':99,'status':'0','dataScope':'5','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[8002],'deptIds':[]},admin)
 role=good('/ruoyi/system/role/list?roleKey='+key,token=admin)['rows'][0]['roleId'];password=secrets.token_urlsafe(16)
 good('/ruoyi/system/user','POST',{'userName':key,'nickName':'权限验证','deptId':103,'password':password,'status':'0','roleIds':[role],'postIds':[]},admin)
 user=good('/ruoyi/system/user/list?userName='+key,token=admin)['rows'][0]['userId'];employee=login(key,password)
 assert req('/api/v1/nodes',token=employee,business=True)[0]==403
 good('/api/v1/management-bindings','PUT',{'userId':str(user),'roles':['ADMIN','VIEWER'],'zones':['sandbox']},admin,True);bound=True
 good('/api/v1/nodes',token=employee,business=True)
 assert req('/api/account/login','POST',{'username':key,'password':password,'device':'migration-check'})[0]==403
 good('/ruoyi/system/role','PUT',{'roleId':role,'roleName':'权限验证','roleKey':key,'roleSort':99,'status':'0','dataScope':'5','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[8002,8120],'deptIds':[]},admin)
 desktop=good('/api/account/login','POST',{'username':key,'password':password,'device':'migration-check'})['token']
 assert desktop.startswith('ruoyi.')
 assert good('/api/account/session',token=desktop)['username']==key
 good('/api/account/logout','POST',{},desktop)
 assert req('/api/account/session',token=desktop)[0]==401
 employee=login(key,password)
 assert req('/api/v1/nodes/missing/admission','PUT',{'paused':True},employee,True)[0]==403
 assert req('/api/v1/model-profiles?zone=sandbox',token=employee,business=True)[0]==403
 # Remove role permissions without replacing the employee's active token.
 good('/ruoyi/system/role','PUT',{'roleId':role,'roleName':'权限验证','roleKey':key,'roleSort':99,'status':'0','dataScope':'5','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[],'deptIds':[]},admin)
 assert req('/api/v1/nodes',token=employee,business=True)[0] in (401,403)
 good('/ruoyi/system/role','PUT',{'roleId':role,'roleName':'权限验证','roleKey':key,'roleSort':99,'status':'0','dataScope':'5','menuCheckStrictly':True,'deptCheckStrictly':True,'menuIds':[8002],'deptIds':[]},admin)
 employee=login(key,password)
 good('/api/v1/nodes',token=employee,business=True)
 good('/ruoyi/system/user/changeStatus','PUT',{'userId':user,'status':'1'},admin)
 assert req('/api/v1/me',token=employee,business=True)[0]==401
 good('/ruoyi/system/user/changeStatus','PUT',{'userId':user,'status':'0'},admin)
 employee=login(key,password)
 good('/api/v1/nodes',token=employee,business=True)
 good('/api/v1/management-bindings/'+str(user),'DELETE',token=admin,business=True);bound=False
 assert req('/api/v1/me',token=employee,business=True)[0]==403
 assert req('/api/v1/users','POST',{},admin,True)[0]==403
 print('Passed: migrated business reads, model save/version conflict, explicit scope grant, read/write isolation, live permission removal, disabled-account rejection, business revocation and blocked legacy credential routes and desktop permission/login/session/logout.')
finally:
 if bound:good('/api/v1/management-bindings/'+str(user),'DELETE',token=admin,business=True)
 if user:good('/ruoyi/system/user/'+str(user),'DELETE',token=admin)
 if role:good('/ruoyi/system/role/'+str(role),'DELETE',token=admin)
 good('/ruoyi/auth/logout','POST',{},admin)
