import {describe,it,expect} from 'vitest';
import {readTaskCenterContext,writeTaskCenterContext,taskCenterContextKey} from '../task-center-context';
describe('task center URL context',()=>{
 it('round trips filters and an open task without overwriting personal/other route fields',()=>{const source={taskId:'personal-task',state:'RUNNING',other:'kept',tc_q:'年报 & 费用',tc_department:'finance',tc_project:'project-a',tc_from:'1',tc_until:'200',tc_task:'organization-task'};const context=readTaskCenterContext(source);expect(writeTaskCenterContext(source,context)).toEqual(source);expect(context.task).toBe('organization-task');expect(context.state).toBe('');});
 it('removes only organization context on reset and treats zero time as absent',()=>{const original={taskId:'mine',tc_task:'detail',tc_zone:'a',tc_from:'0'};expect(writeTaskCenterContext(original,readTaskCenterContext({}))).toEqual({taskId:'mine'});expect(readTaskCenterContext(original).from).toBe('');});
 it('rejects ambiguous, oversized and invalid time links instead of widening the query',()=>{for(const query of [{tc_owner:['one','two']},{tc_q:'x'.repeat(161)},{tc_from:'-1'},{tc_until:'NaN'},{tc_from:'200',tc_until:'100'},{tc_from:'9007199254740992'}])expect(()=>readTaskCenterContext(query)).toThrow();});
 it('has a stable key independent of unrelated parameters and object ordering',()=>{expect(taskCenterContextKey(readTaskCenterContext({tc_zone:'a',tab:'mine'}))).toBe(taskCenterContextKey(readTaskCenterContext({irrelevant:'value',tc_zone:'a'})));});
});
