#!/usr/bin/env python3
"""Check real identity/business authorization in the isolated management-shell UAT stack."""
import importlib.util
import json
from pathlib import Path
import urllib.request
import urllib.error

spec=importlib.util.spec_from_file_location('shell_uat',Path(__file__).with_name('run-management-shell-uat.py'))
uat=importlib.util.module_from_spec(spec)
spec.loader.exec_module(uat)
results=[]


def check(name, condition):
    results.append({'name':name,'passed':bool(condition)})
    if not condition: raise AssertionError(name)


def login(username):
    return uat.request('/auth/login',{'tenantId':'000000','username':username,'password':uat.PASSWORD,'clientId':uat.CLIENT,'grantType':'password'})['data']['access_token']


def business(token,path,method='GET',body=None):
    headers={'Authorization':'Bearer '+token,'X-FunnyWorker-Management':'1','Content-Type':'application/json'}
    req=urllib.request.Request('http://127.0.0.1:18765/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as response:return response.status,json.loads(response.read() or b'null')
    except urllib.error.HTTPError as error:return error.code,json.loads(error.read())


try:
    tokens={name:login(name) for name in ['shell_manager','shell_operator','shell_viewer','shell_model','shell_unbound']}
    for name,token in tokens.items():
        info=uat.request('/system/user/getInfo',token=token)['data']
        check(name+' actual identity role',bool(info.get('roles')))
        routes=uat.request('/system/menu/getRouters',token=token)['data']
        check(name+' no platform account/role menus',not any(x.get('path')=='system' for x in routes))
    manager=tokens['shell_manager'];operator=tokens['shell_operator'];viewer=tokens['shell_viewer'];model=tokens['shell_model']
    status,page=business(manager,'/management-tasks')
    check('organization metadata reads all three synthetic tasks',status==200 and page['total']==3)
    check('metadata does not contain task input', 'input_text' not in json.dumps(page))
    check('ADMIN cannot use personal tasks',business(manager,'/tasks')[0]==403)
    check('VIEWER can read personal tasks only',business(viewer,'/tasks')[0]==200 and business(viewer,'/management-tasks')[0]==403)
    check('model role reads deployments',business(model,'/model-middleware/deployments')[0]==200)
    check('model role cannot read tasks',business(model,'/management-tasks')[0]==403)
    check('unbound role fails closed',business(tokens['shell_unbound'],'/me')[0]==403)
    check('read-only organization role cannot issue commands',business(manager,'/management-tasks/shell-maintenance-task/commands','POST',{})[0]==403)
    check('input requires independent identity permission',business(manager,'/management-tasks/shell-annual-task/units/shell-annual-unit/input')[0]==403)
    check('model read permission does not permit configuration writes',business(model,'/model-middleware/pools','POST',{})[0]==403)
    check('annual workbench only opens for owner',business(manager,'/management-tasks/shell-annual-task/workflow')[1]['workbench']['canOpen'] is False and business(operator,'/management-tasks/shell-annual-task/workflow')[1]['workbench']['canOpen'] is True)
    check('annual owner detail allowed and other admin denied',business(operator,'/annual-reports/datasets/shell-dataset')[0]==200 and business(manager,'/annual-reports/datasets/shell-dataset')[0]==403)
    check('maintenance requires OPERATOR',business(operator,'/maintenance/problems/shell-problem')[0]==200 and business(manager,'/maintenance/problems/shell-problem')[0]==403)
    uat.sql('shell_business',"insert into management_task_limits values('shell_manager','demo',false,'[]',true,'[]',1)")
    try:
        check('scope excludes list and detail together',business(manager,'/management-tasks')[1]['total']==0 and business(manager,'/management-tasks/shell-annual-task')[0]==404)
    finally:uat.sql('shell_business',"delete from management_task_limits where principal_id='shell_manager'")
    # Role changes use the actual identity service, preserving all unrelated grants.
    admin=login('admin')
    role=uat.request('/system/role/list?roleKey=shell_manager',token=admin)['rows'][0]
    role_id=str(role['roleId'])
    menus=[int(x) for x in uat.sql('shell_identity',f'select menu_id from sys_role_menu where role_id={role_id}').splitlines()]
    read_menu=int(uat.sql('shell_identity',"select menu_id from sys_menu where perms='funnyworker:task-center:read'"))
    def save(menu_ids):
        uat.request('/system/role',dict(role,menuIds=menu_ids,deptIds=[],menuCheckStrictly=True,deptCheckStrictly=True),admin,method='PUT')
    save([menu for menu in menus if menu!=read_menu])
    try:
        revoked_status=business(manager,'/management-tasks')[0]
        check('identity permission removal takes effect for existing session (HTTP '+str(revoked_status)+')',revoked_status in (401,403))
    finally:save(menus)
    manager=login('shell_manager')
    check('restored identity permission restores metadata only after login',business(manager,'/management-tasks')[0]==200 and business(manager,'/tasks')[0]==403)
    uat.request('/auth/logout',{},viewer)
    check('logged-out identity token rejected',business(viewer,'/tasks')[0]==401)
    pool=dict(name='真实服务验收容量池',zone='sandbox',concurrencyLimit=2,queueLimit=10,userConcurrency=1,userQueue=2,waitMs=30000,paused=False,expectedVersion=0)
    status,created=business(admin,'/model-middleware/pools','POST',pool)
    check('authorized model pool create',status==200 and bool(created.get('id')))
    pool_id=created['id']
    check('read-only model role sees created pool',any(row['id']==pool_id for row in business(model,'/model-middleware/pools')[1]['items']))
    version=next(row['version'] for row in business(model,'/model-middleware/pools')[1]['items'] if row['id']==pool_id)
    status,_=business(admin,'/model-middleware/pools/'+pool_id,'PUT',dict(pool,expectedVersion=version,paused=True))
    check('authorized capacity update',status==200)
    check('stale model capacity version rejected',business(admin,'/model-middleware/pools/'+pool_id,'PUT',dict(pool,expectedVersion=version))[0]==409)
finally:
    uat.OUT.joinpath('authorization-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print(f'Passed {len(results)} real-stack authorization assertions; no model execution or production data.')
