import { expect, it } from 'vitest';
import { clientPolicySaveError } from '../../../../../packages/management-react/src/client-policy-feedback';
it('distinguishes a rejected stale version and unavailable binding from an unknown network outcome', () => {
 expect(clientPolicySaveError({code:'POLICY_VERSION_CONFLICT'})).toContain('本次未保存');
 expect(clientPolicySaveError({code:'AGENT_REQUIRED'})).toContain('重新选择能力');
 expect(clientPolicySaveError({status:403})).toContain('没有维护权限');
 expect(clientPolicySaveError(new TypeError('network failed'))).toContain('结果未确认');
 expect(clientPolicySaveError({code:'UNRECOGNIZED',message:'private backend detail'})).not.toContain('private backend detail');
});
