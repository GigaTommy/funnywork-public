#!/usr/bin/env python3
"""Integration check through the public gateway; removes its temporary user and role."""
import json, pathlib, secrets, urllib.request, urllib.error
root=pathlib.Path(__file__).resolve().parents[2]
credentials=json.loads((root/'.local/ruoyi/credentials.json').read_text())
base='http://127.0.0.1:8765/ruoyi'; client='e5cd7e4891bf95d1d19206ce24a7b32e'
def call(path,method='GET',data=None,token=None):
 headers={'Content-Type':'application/json','clientid':client}
 if token:headers['Authorization']='Bearer '+token
 request=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
 try:
  with urllib.request.urlopen(request,timeout=30) as response:return json.load(response)
 except urllib.error.HTTPError as error:return json.loads(error.read())
def ok(value):assert value['code']==200,value.get('msg');return value
anonymous=call('/system/user/list');assert anonymous['code']==401,anonymous
admin=ok(call('/auth/login','POST',{'tenantId':'000000','username':credentials['username'],'password':credentials['password'],'clientId':client,'grantType':'password'}))['data']['access_token']
for path in ['/system/user/list','/system/role/list','/system/dept/list','/system/menu/list','/system/menu/getRouters']:ok(call(path,token=admin))
key='verify_'+secrets.token_hex(5);role_id=None;user_id=None;employee=None
try:
 ok(call('/system/role','POST',{'roleName':'权限验收','roleKey':key,'roleSort':99,'status':'0','menuCheckStrictly':True,'deptCheckStrictly':True,'dataScope':'5','menuIds':[100],'deptIds':[]},admin))
 role_id=ok(call('/system/role/list?roleKey='+key,token=admin))['rows'][-1]['roleId']
 password=secrets.token_urlsafe(16)
 ok(call('/system/user','POST',{'userName':key,'nickName':'权限验收','deptId':103,'password':password,'status':'0','sex':'0','roleIds':[role_id],'postIds':[]},admin))
 user_id=ok(call('/system/user/list?userName='+key,token=admin))['rows'][0]['userId']
 employee=ok(call('/auth/login','POST',{'tenantId':'000000','username':key,'password':password,'clientId':client,'grantType':'password'}))['data']['access_token']
 limited=ok(call('/system/user/list',token=employee))
 assert all(str(row['userId'])==str(user_id) for row in limited['rows']), 'Personal data scope exposed another user'
 denied=call('/system/role/list',token=employee);assert denied['code']==403,denied
 changed=call('/system/user','POST',{'userName':'unauthorized','nickName':'unauthorized','deptId':103,'password':password,'status':'0','roleIds':[],'postIds':[]},employee);assert changed['code']==403,changed
 ok(call('/auth/logout','POST',{},employee));assert call('/system/user/list',token=employee)['code']==401
 print('Passed: administrator login, management lists, user/role creation, personal data scope, forbidden role access and user creation, logout revocation.')
finally:
 if user_id:ok(call('/system/user/'+str(user_id),'DELETE',token=admin))
 if role_id:ok(call('/system/role/'+str(role_id),'DELETE',token=admin))
 ok(call('/auth/logout','POST',{},admin))
