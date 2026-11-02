import {readPendingTaskCommand} from './pending-task-command';
export type PendingTaskBatch={key:string;body:{action:'PAUSE'|'RESUME'|'CANCEL';items:{taskId:string;expectedToken:string}[]}};
const prefix='funnywork:pending-task-batch:';
function parse(value:unknown):PendingTaskBatch {
 const item=value as PendingTaskBatch;
 if(!item||typeof item.key!=='string'||!/^[A-Za-z0-9._:-]{16,120}$/.test(item.key)||!item.body||!['PAUSE','RESUME','CANCEL'].includes(item.body.action)||!Array.isArray(item.body.items)||item.body.items.length<1||item.body.items.length>50)throw new Error('批量待核对记录无效');
 const ids=new Set<string>();for(const row of item.body.items){if(!row||typeof row.taskId!=='string'||!row.taskId||row.taskId.length>64||ids.has(row.taskId)||typeof row.expectedToken!=='string'||!/^[a-f0-9]{64}$/.test(row.expectedToken))throw new Error('批量待核对记录无效');ids.add(row.taskId);}
 return {key:item.key,body:{action:item.body.action,items:item.body.items.map(row=>({taskId:row.taskId,expectedToken:row.expectedToken}))}};
}
export function readPendingTaskBatch(actor:string,storage:Storage=localStorage){if(!actor)throw new Error('缺少账号上下文');const raw=storage.getItem(prefix+actor);return raw===null?undefined:parse(JSON.parse(raw));}
export function savePendingTaskBatch(actor:string,value:PendingTaskBatch,storage:Storage=localStorage){if(readPendingTaskCommand(actor,storage)!==undefined)throw new Error('请先核对单任务请求');const next=parse(value),previous=readPendingTaskBatch(actor,storage);if(previous&&JSON.stringify(previous)!==JSON.stringify(next))throw new Error('不能替换待核对的原批量请求');storage.setItem(prefix+actor,JSON.stringify(next));}
export function clearPendingTaskBatch(actor:string,key:string,storage:Storage=localStorage){const previous=readPendingTaskBatch(actor,storage);if(previous?.key===key)storage.removeItem(prefix+actor);}
