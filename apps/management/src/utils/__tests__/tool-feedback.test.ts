import {expect,it} from 'vitest';
import {toolPublicationError} from '../../../../../packages/management-react/src/tool-feedback';
it('distinguishes structured publication conflicts and authorization refusals from unknown outcomes',()=>{
 expect(toolPublicationError({code:'TEMPLATE_VERSION_CONFLICT',status:409})).toContain('本次未发布');
 expect(toolPublicationError({status:403})).toContain('区域授权');
 expect(toolPublicationError(new TypeError('private credential'))).toContain('结果未确认');
 expect(toolPublicationError(new Error('private credential'))).not.toContain('private credential');
 expect(toolPublicationError({message:'HTTP_409'})).toContain('选择新版本');
});
