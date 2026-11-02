import {describe,it,expect} from 'vitest';
import {readPendingTaskBatch,savePendingTaskBatch,clearPendingTaskBatch} from '../pending-task-batch';
import {savePendingTaskCommand} from '../pending-task-command';
function storage(){const data=new Map<string,string>();return {getItem:(key:string)=>data.get(key)??null,setItem:(key:string,value:string)=>data.set(key,value),removeItem:(key:string)=>data.delete(key)} as unknown as Storage;}
const batch={key:'batch-request-12345',body:{action:'PAUSE' as const,items:[{taskId:'task-a',expectedToken:'a'.repeat(64)}]}};
describe('pending batch recovery',()=>{
 it('restores exactly the confirmed set and isolates accounts',()=>{const saved=storage();savePendingTaskBatch('tenant:admin',batch,saved);expect(readPendingTaskBatch('tenant:admin',saved)).toEqual(batch);expect(readPendingTaskBatch('tenant:other',saved)).toBeUndefined();});
 it('does not replace a pending batch or clear a different request',()=>{const saved=storage();savePendingTaskBatch('a',batch,saved);expect(()=>savePendingTaskBatch('a',{...batch,body:{...batch.body,items:[...batch.body.items,{taskId:'task-b',expectedToken:'b'.repeat(64)}]}},saved)).toThrow();clearPendingTaskBatch('a','other',saved);expect(readPendingTaskBatch('a',saved)).toEqual(batch);clearPendingTaskBatch('a',batch.key,saved);expect(readPendingTaskBatch('a',saved)).toBeUndefined();});
 it('blocks new single commands while a batch remains and vice versa',()=>{const saved=storage(),command={taskId:'task-a',key:'command-key',body:{action:'PAUSE',expectedToken:'a'.repeat(64)}};savePendingTaskBatch('a',batch,saved);expect(()=>savePendingTaskCommand('a',command,saved)).toThrow();const other=storage();savePendingTaskCommand('a',command,other);expect(()=>savePendingTaskBatch('a',batch,other)).toThrow();});
 it('preserves single-command reconciliation for the actual JSON account key',()=>{
  const actor=JSON.stringify(['tenant-a','admin-a']),saved=storage();
  const command={taskId:'task-a',key:'command-request-12345',body:{action:'PAUSE',expectedToken:'a'.repeat(64)}};
  savePendingTaskCommand(actor,command,saved);
  expect(()=>savePendingTaskBatch(actor,batch,saved)).toThrow();
  expect(readPendingTaskBatch(actor,saved)).toBeUndefined();
 });
 it('fails closed for corrupt, duplicate, oversized or missing-account records',()=>{const saved=storage();saved.setItem('funnywork:pending-task-batch:a','{}');expect(()=>readPendingTaskBatch('a',saved)).toThrow();expect(()=>savePendingTaskBatch('a',batch,saved)).toThrow();expect(()=>readPendingTaskBatch('',saved)).toThrow();for(const items of [[...batch.body.items,...batch.body.items],Array.from({length:51},(_,index)=>({taskId:String(index),expectedToken:'a'.repeat(64)}))])expect(()=>savePendingTaskBatch('b',{...batch,body:{...batch.body,items}},saved)).toThrow();});
});
