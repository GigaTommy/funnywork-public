import { describe, expect, it, vi, afterEach } from 'vitest';
import { BusinessError, businessErrorMessage, saveAndRefresh } from '../business-feedback';
import { businessRequest } from '../funnyworker-api';
const session=vi.hoisted(()=>({token:'test-token'}));
vi.mock('@/utils/auth',()=>({getToken:()=>session.token}));
afterEach(()=>{vi.unstubAllGlobals();session.token='test-token';});
describe('write feedback',()=>{
 it('preserves failed writes and does not refresh or repeat them',async()=>{
  const write=vi.fn().mockRejectedValue(new BusinessError('FORBIDDEN',403));
  const refresh=vi.fn();
  await expect(saveAndRefresh(write,refresh)).rejects.toMatchObject({status:403});
  expect(refresh).not.toHaveBeenCalled();expect(write).toHaveBeenCalledTimes(1);
 });
 it('distinguishes committed writes from refresh failures',async()=>{
  const write=vi.fn().mockResolvedValue(undefined);
  const message=await saveAndRefresh(write,async()=>{throw new Error('offline');});
  expect(message).toContain('无需重复提交');expect(write).toHaveBeenCalledTimes(1);
 });
 it('confirms success only after both steps',async()=>{
  const order:string[]=[];
  expect(await saveAndRefresh(async()=>{order.push('write');},async()=>{order.push('read');})).toBe('操作已完成。');
  expect(order).toEqual(['write','read']);
 });
 it('does not render arbitrary exception messages',()=>{
  expect(businessErrorMessage(new Error('secret'))).not.toContain('secret');
  expect(businessErrorMessage(new BusinessError('STATE_CONFLICT',409))).toContain('冲突');
 });
 it('distinguishes a self-authorization refusal from stale data',()=>{
  expect(businessErrorMessage(new BusinessError('SELF_SCOPE_CHANGE',409))).toBe('不能修改自己的业务授权，请选择其他账号。');
  expect(businessErrorMessage(new BusinessError('STATE_CONFLICT',409))).toContain('冲突');
 });
});
describe('business requests',()=>{
 it('retains structured permission errors',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({code:'BUSINESS_SCOPE_REQUIRED',message:'internal'}),{status:403})));
  await expect(businessRequest('/me')).rejects.toMatchObject({code:'BUSINESS_SCOPE_REQUIRED',status:403});
 });
 it('handles non-JSON proxy failures',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('<html>unavailable</html>',{status:502})));
  await expect(businessRequest('/me')).rejects.toMatchObject({code:'HTTP_502',status:502});
 });
 it('rejects responses from a previous session',async()=>{
  vi.stubGlobal('fetch',vi.fn().mockImplementation(async()=>{session.token='replacement';return new Response('{}');}));
  await expect(businessRequest('/me')).rejects.toMatchObject({code:'SESSION_CHANGED'});
 });
 it('does not send a request without credentials',async()=>{
  session.token='';const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
  await expect(businessRequest('/me')).rejects.toMatchObject({status:401});expect(fetch).not.toHaveBeenCalled();
 });
});

it('clears protected data only on authentication, authorization or session changes', async()=>{
 const {businessAccessLost}=await import('../business-feedback');
 expect(businessAccessLost(new BusinessError('FORBIDDEN',403))).toBe(true);
 expect(businessAccessLost(new BusinessError('UNAUTHENTICATED',401))).toBe(true);
 expect(businessAccessLost(new BusinessError('SESSION_CHANGED'))).toBe(true);
 expect(businessAccessLost(new BusinessError('CONFLICT',409))).toBe(false);
 expect(businessAccessLost(new BusinessError('UNAVAILABLE',503))).toBe(false);
 expect(businessAccessLost(new Error('network failed'))).toBe(false);
});
