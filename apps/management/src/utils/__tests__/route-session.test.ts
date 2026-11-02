import { expect, it } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import { clearSessionRoutes } from '../route-session';

it('removes old privileged routes and descendants before a new account installs its routes', () => {
 const component={template:'<div />'};
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/login',name:'login',component},{path:'/',name:'home',component}]});
 const names=new Set(router.getRoutes().map(route=>route.name!));
 router.addRoute({path:'/system',name:'system',component,children:[{path:'user',name:'user-admin',component}]});
 expect(router.hasRoute('user-admin')).toBe(true);
 clearSessionRoutes(router,names);
 expect(router.hasRoute('system')).toBe(false);expect(router.hasRoute('user-admin')).toBe(false);
 expect(router.hasRoute('login')).toBe(true);expect(router.hasRoute('home')).toBe(true);
 router.addRoute({path:'/tasks',name:'tasks',component});
 expect(router.hasRoute('tasks')).toBe(true);expect(router.hasRoute('user-admin')).toBe(false);
});
