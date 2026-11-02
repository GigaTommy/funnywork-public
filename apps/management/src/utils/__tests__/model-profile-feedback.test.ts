import { expect, it } from 'vitest';
import { modelProfileProbeError, modelProfileSaveError } from '../../../../../packages/management-react/src/model-profile-feedback';
it('distinguishes a deployment host rejection from authorization and an unknown request outcome', () => {
 expect(modelProfileProbeError({code:'PROBE_HOST_DENIED',status:403})).toContain('本次未发送检查请求');
 expect(modelProfileProbeError({code:'PROBE_HOST_DENIED',status:403})).toContain('允许名单');
 expect(modelProfileProbeError({code:'PROFILE_DISABLED',status:409})).toContain('已停用');
 expect(modelProfileProbeError({status:403})).toContain('工作区域授权');
 expect(modelProfileProbeError({status:401})).toContain('重新登录');
 expect(modelProfileProbeError(new TypeError('private endpoint detail'))).not.toContain('private endpoint detail');
 expect(modelProfileProbeError({code:'UNKNOWN',message:'secret credential'})).not.toContain('secret credential');
});
it('distinguishes a rejected stale draft from an unknown save outcome while retaining the draft', () => {
 expect(modelProfileSaveError({code:'PROFILE_VERSION_CONFLICT',status:409})).toContain('本次未保存');
 expect(modelProfileSaveError({code:'PROFILE_VERSION_CONFLICT',status:409})).toContain('草稿已保留');
 expect(modelProfileSaveError({status:403})).toContain('没有维护权限');
 expect(modelProfileSaveError({status:422})).toContain('未通过校验');
 expect(modelProfileSaveError(new TypeError('network failed'))).toContain('结果未确认');
 expect(modelProfileSaveError({code:'UNKNOWN',message:'secret credential'})).not.toContain('secret credential');
});
