import {describe,it,expect} from 'vitest';
import type {RouteRecordRaw} from 'vue-router';
import {businessNavigation,withOrganizationTaskEntry} from '../business-navigation';
import {taskWorkbenchAccess} from '../task-workbench-access';
describe('business navigation over authorized routes',()=>{
  const routes:RouteRecordRaw[]=[{path:'',children:[{path:'/index',component:{},meta:{title:'首页'}}]},{path:'/system',meta:{title:'系统管理'},children:[{path:'user',component:{},meta:{title:'用户'}}]},{path:'/funnyworker',meta:{title:'业务'},children:[{path:'models',component:{},meta:{title:'模型'}},{path:'tasks',component:{},meta:{title:'任务'}},{path:'resources',component:{},meta:{title:'资源'}},{path:'future',component:{},meta:{title:'新模块'}}]}];
  it('puts work and models ahead of system settings without mutating route registration',()=>{
    const before=JSON.stringify(routes),nav=businessNavigation(routes);
    expect(nav.slice(0,3).map(x=>x.meta?.title)).toEqual(['运营首页','任务中心','模型中心']);
    expect(nav.at(-1)?.meta?.title).toBe('系统设置');
    expect(nav.at(-1)?.children?.map(x=>x.path)).toEqual(['/funnyworker/future','/system']);
    expect(JSON.stringify(routes)).toBe(before);
  });
  it('preserves only allowed destinations and excludes hidden routes',()=>{
    const nav=businessNavigation([{path:'/funnyworker',children:[{path:'models',component:{},meta:{title:'模型'}},{path:'tasks',hidden:true,component:{},meta:{title:'任务'}}]}]);
    expect(nav.map(x=>x.path)).toEqual(['/funnyworker/models']);
    expect(JSON.stringify(nav)).not.toContain('system/user');
  });
  it('opens organization metadata independently of owner permission without duplicate task routes',()=>{
    expect(withOrganizationTaskEntry([],[])).toEqual([]);
    const added=withOrganizationTaskEntry([],['funnyworker:task-center:read']);
    expect(added[0].name).toBe('OrganizationTaskLayout');
    expect(businessNavigation(added)[0].path).toBe('/funnyworker/tasks');
    expect(withOrganizationTaskEntry(added,['*:*:*'])).toBe(added);
  });
});
describe('workbench entry capabilities',()=>{
  it('keeps organization administrators separate from owner workbenches',()=>{
    expect(taskWorkbenchAccess(['ADMIN'],['funnyworker:task-center:read','funnyworker:tasks:read','funnyworker:annual:read'])).toEqual({organization:true,personal:false,maintenance:false,annual:false});
  });
  it('requires both business role and identity permission, even for viewers and super menu grants',()=>{
    expect(taskWorkbenchAccess(['VIEWER'],[]).personal).toBe(false);
    expect(taskWorkbenchAccess(['VIEWER'],['funnyworker:tasks:read'])).toEqual({organization:false,personal:true,maintenance:false,annual:false});
    expect(taskWorkbenchAccess([],['*:*:*'])).toEqual({organization:false,personal:false,maintenance:false,annual:false});
    expect(taskWorkbenchAccess(['ADMIN','OPERATOR'],['*:*:*'])).toEqual({organization:true,personal:true,maintenance:true,annual:true});
  });
});
