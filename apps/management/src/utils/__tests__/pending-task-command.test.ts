import {describe,it,expect} from 'vitest';
import {readPendingTaskCommand,savePendingTaskCommand,clearPendingTaskCommand} from '../pending-task-command';
const command={taskId:'task-one',key:'request-1234567890',body:{action:'CANCEL',expectedToken:'a'.repeat(64)}};
function storage(){const data=new Map<string,string>();return {getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value),removeItem:(key:string)=>data.delete(key)} as unknown as Storage;}
describe('pending administrative command recovery',()=>{
 it('restores the same request after reload and separates accounts',()=>{const saved=storage();savePendingTaskCommand('tenant-a:admin',command,saved);expect(readPendingTaskCommand('tenant-a:admin',saved)).toEqual(command);expect(readPendingTaskCommand('tenant-b:admin',saved)).toBeUndefined();});
 it('does not replace an uncertain request or clear a newer receipt',()=>{const saved=storage();savePendingTaskCommand('a',command,saved);expect(()=>savePendingTaskCommand('a',{...command,key:'other-1234567890'},saved)).toThrow();expect(()=>savePendingTaskCommand('a',{...command,body:{...command.body,action:'PAUSE'}},saved)).toThrow();clearPendingTaskCommand('a','other-1234567890',saved);expect(readPendingTaskCommand('a',saved)).toEqual(command);clearPendingTaskCommand('a',command.key,saved);expect(readPendingTaskCommand('a',saved)).toBeUndefined();});
 it('fails closed for corrupt pending state',()=>{const saved=storage();saved.setItem('funnywork:pending-task-command:a','{"taskId":"wrong"}');expect(()=>readPendingTaskCommand('a',saved)).toThrow();expect(()=>savePendingTaskCommand('a',command,saved)).toThrow();});
 it('does not allow a command without account context',()=>expect(()=>savePendingTaskCommand('',command,storage())).toThrow());
});
