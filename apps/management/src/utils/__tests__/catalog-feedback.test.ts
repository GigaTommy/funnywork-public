import {expect,it} from 'vitest';
import {catalogOperationError} from '../../../../../packages/management-react/src/catalog-feedback';
it('explains byte-limit and scope refusals without disguising unknown outcomes or exposing secrets',()=>{
 expect(catalogOperationError({code:'SKILL_TOO_LARGE',status:413})).toContain('本次未发布');
 expect(catalogOperationError({code:'SKILL_TOO_LARGE',status:413})).toContain('8000');
 expect(catalogOperationError({status:403})).toContain('工作区域授权');
 expect(catalogOperationError({status:401})).toContain('旧配置和草稿已清除');
 expect(catalogOperationError({code:'SESSION_CHANGED'})).not.toContain('草稿已保留');
 expect(catalogOperationError({code:'MCP_VERSION_CONFLICT',status:409})).toContain('本次未保存');
 expect(catalogOperationError({code:'DATA_CONFLICT',status:409})).toContain('本次未提交');
 expect(catalogOperationError({status:409})).not.toContain('结果未确认');
 expect(catalogOperationError(new Error('private credential'))).toContain('结果未确认');
 expect(catalogOperationError(new Error('private credential'))).not.toContain('private credential');
});
