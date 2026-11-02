import {expect,it} from 'vitest';
import {gatewayOperationError} from '../../../../../packages/management-react/src/gateway-feedback';
it('distinguishes refused writes from unknown outcomes without exposing adapter secrets',()=>{
 expect(gatewayOperationError({code:'GATEWAY_VERSION_CONFLICT',status:409},'save')).toContain('本次未保存');
 expect(gatewayOperationError({code:'GATEWAY_VERSION_CONFLICT',status:409},'check')).toContain('本次检查未完成');
 expect(gatewayOperationError({code:'INVALID_GATEWAY_ENDPOINT',status:400},'save')).toContain('允许名单');
 expect(gatewayOperationError({status:403},'check')).toContain('工作区域授权');
 expect(gatewayOperationError({status:401},'save')).toContain('旧配置和草稿已清除');
 expect(gatewayOperationError({code:'SESSION_CHANGED'},'save')).not.toContain('草稿已保留');
 const unknown=gatewayOperationError(new Error('private adapter credential'),'save');
 expect(unknown).toContain('结果未确认');expect(unknown).not.toContain('private adapter credential');
});
