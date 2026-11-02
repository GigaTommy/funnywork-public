import type { RouteRecordRaw } from 'vue-router';

/** Organization metadata permission can open tasks without granting the owner task API. */
export function withOrganizationTaskEntry(routes:RouteRecordRaw[],permissions:string[]):RouteRecordRaw[] {
  if(!permissions.includes('funnyworker:task-center:read')&&!permissions.includes('*:*:*'))return routes;
  const hasTask=(nodes:RouteRecordRaw[],base=''):boolean=>nodes.some(node=>{
    const path=node.path.startsWith('/')?node.path:(base+'/'+node.path).replace(/\/+/g,'/');
    return path==='/funnyworker/tasks'||!!node.children&&hasTask(node.children,path);
  });
  if(hasTask(routes))return routes;
  // The identity route wire format uses component names until filterAsyncRouter resolves them.
  return [...routes,{path:'/funnyworker',name:'OrganizationTaskLayout',component:'Layout' as any,children:[{path:'tasks',name:'OrganizationTasks',component:'funnyworker/tasks' as any,meta:{title:'任务中心',icon:'dashboard'}}]}];
}

/** Presentation of routes already authorized by the identity service; never grants a route. */
export function businessNavigation(routes: RouteRecordRaw[]): RouteRecordRaw[] {
  const primary = ['/funnyworker/tasks', '/funnyworker/models', '/funnyworker/usage', '/funnyworker/history'];
  const groups = [
    {key:'execution',title:'执行与能力',paths:['resources','nodes','agents','office','computer','tools','documents','gateways','packages','integrations','recovery']},
    {key:'members',title:'成员与组织',paths:['desktop','authorization']},
    {key:'governance',title:'策略与审计',paths:['overview','client','scheduling','audit']},
    {key:'workbenches',title:'专项工作台',paths:['annual']}
  ];
  const names:Record<string,string> = {'/index':'运营首页','/funnyworker/tasks':'任务中心','/funnyworker/models':'模型中心','/funnyworker/usage':'用量与成本','/funnyworker/history':'会话中心'};
  const business:RouteRecordRaw[] = [], system:RouteRecordRaw[] = [], home:RouteRecordRaw[] = [];
  function visit(route:RouteRecordRaw,base='') {
    if(route.hidden)return;
    const path=/^(?:https?:\/\/|\/)/.test(route.path)?route.path:(base+'/'+route.path).replace(/\/+/g,'/');
    const node={...route,path,meta:route.meta?{...route.meta,title:names[path]||route.meta.title}:undefined};
    if(path==='/index'){home.push(node);return;}
    if(path==='/funnyworker'||path==='/'||path===''){route.children?.forEach(child=>visit(child,path==='/'?'':path));return;}
    if(path.startsWith('/funnyworker/'))business.push(node);else if(route.meta)system.push(node);
  }
  routes.forEach(route=>visit(route));
  const remaining=new Map(business.map(route=>[route.path,route]));
  const result=[...home];
  for(const path of primary){const node=remaining.get(path);if(node){result.push(node);remaining.delete(path);}}
  for(const group of groups){
    const children=group.paths.flatMap(path=>{const full='/funnyworker/'+path,node=remaining.get(full);remaining.delete(full);return node?[node]:[];});
    if(children.length)result.push({path:'/_navigation/'+group.key,alwaysShow:true,meta:{title:group.title,icon:'dashboard'},children});
  }
  // Unknown authorized modules remain accessible in settings rather than being silently dropped.
  const settings=[...remaining.values(),...system];
  if(settings.length)result.push({path:'/_navigation/settings',alwaysShow:true,meta:{title:'系统设置',icon:'system'},children:settings});
  return result;
}
