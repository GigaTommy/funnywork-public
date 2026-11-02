import { expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
const calls=vi.hoisted(()=>({logout:vi.fn(),remove:vi.fn(),routes:vi.fn(),menus:vi.fn(),tabs:vi.fn()}));
vi.mock('@/api/login',()=>({login:vi.fn(),logout:calls.logout,getInfo:vi.fn()}));
vi.mock('@/utils/auth',()=>({getToken:()=> 'old-session',setToken:vi.fn(),removeToken:calls.remove}));
vi.mock('@/router',()=>({resetRouter:calls.routes}));
vi.mock('@/store/modules/permission',()=>({usePermissionStore:()=>({resetSession:calls.menus})}));
vi.mock('@/store/modules/tagsView',()=>({useTagsViewStore:()=>({resetSession:calls.tabs})}));
import { useUserStore } from '@/store/modules/user';
it('finishes local logout and removes account routes, menus and caches after a network failure',async()=>{
 setActivePinia(createPinia());calls.logout.mockRejectedValueOnce(new TypeError('Network unavailable'));
 const user=useUserStore();user.roles=['admin'];user.permissions=['*:*:*'];user.nickname='Previous account';user.userId='1';user.tenantId='000000';
 await expect(user.logout()).resolves.toBeUndefined();
 expect(user.token).toBe('');expect(user.roles).toEqual([]);expect(user.permissions).toEqual([]);expect(user.nickname).toBe('');expect(user.userId).toBe('');expect(user.tenantId).toBe('');
 for(const fn of [calls.remove,calls.routes,calls.menus,calls.tabs])expect(fn).toHaveBeenCalledOnce();
});
