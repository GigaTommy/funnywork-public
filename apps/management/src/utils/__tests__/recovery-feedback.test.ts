import {expect,it} from 'vitest';
import {recoveryFailure} from '../../../../../packages/management-react/src/recovery-feedback';
it('keeps confirmed writes accurate while clearing sensitive state after authorization loss',()=>{
 const refresh=recoveryFailure(new Error('private evidence'),true,false);
 expect(refresh.message).toContain('操作已完成');expect(refresh.message).toContain('无需重复提交');expect(refresh.clearSensitive).toBe(false);
 const revoked=recoveryFailure({status:403},true,true);
 expect(revoked.clearSensitive).toBe(true);expect(revoked.message).toContain('操作已完成');
 expect(recoveryFailure({status:401},false,true).message).toContain('清空');
 expect(recoveryFailure(new Error('SESSION_CHANGED'),false,true).clearSensitive).toBe(true);
 const unknown=recoveryFailure(new Error('private evidence'),false,true);
 expect(unknown.message).toContain('草稿已保留');expect(unknown.message).not.toContain('private evidence');
});

it('reports an explicit signature refusal without exposing material or claiming an unknown write',()=>{
 const result=recoveryFailure({code:'RECOVERY_EVIDENCE_UNTRUSTED',status:400,message:'private envelope'},false,true);
 expect(result.clearSensitive).toBe(false);expect(result.message).toContain('核验未通过');expect(result.message).toContain('未放行');
 expect(result.message).not.toContain('结果未确认');expect(result.message).not.toContain('private envelope');
});
