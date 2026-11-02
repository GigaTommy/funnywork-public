import {expect,it} from 'vitest';
import {prepareRecoverySubmission} from '../../../../../packages/management-react/src/recovery-submission';

it('replays uncertain submissions but uses refreshed versions after explicit rejection',()=>{
 const old={id:'case-a',expectedVersion:1,requestKey:'original-key',kind:'OPERATOR_NOTE',content:'retained draft'};
 const refreshed={id:old.id,expectedVersion:2,kind:old.kind,content:old.content};
 expect(prepareRecoverySubmission(old,refreshed,()=>{throw new Error('must replay');})).toBe(old);
 expect(prepareRecoverySubmission({...old,refused:true},refreshed,()=> 'fresh-key')).toEqual({...refreshed,requestKey:'fresh-key'});
 expect(prepareRecoverySubmission(old,{...refreshed,content:'edited draft'},()=> 'edited-key').requestKey).toBe('edited-key');
 expect(prepareRecoverySubmission(old,{...refreshed,id:'case-b'},()=> 'other-key').requestKey).toBe('other-key');
 expect(prepareRecoverySubmission({...refreshed,expectedVersion:1},refreshed,()=> 'draft-key')).toEqual({...refreshed,requestKey:'draft-key'});
});
