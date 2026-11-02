/** Only command identity and preview fingerprint are retained, never credentials or task content. */
export type PendingTaskCommand={taskId:string;key:string;body:{action:string;expectedToken:string}};
const prefix='funnywork:pending-task-command:';
function storageKey(actor:string){if(!actor)throw new Error('Command account is unavailable');return prefix+encodeURIComponent(actor);}
export function readPendingTaskCommand(actor:string,storage:Storage=localStorage):PendingTaskCommand|undefined{
 const raw=storage.getItem(storageKey(actor));if(raw===null)return;
 const value=JSON.parse(raw);
 if(!value||typeof value.taskId!=='string'||!/^[-A-Za-z0-9_]{1,64}$/.test(value.taskId)||typeof value.key!=='string'||!/^[-A-Za-z0-9_]{16,120}$/.test(value.key)||!value.body||!['PAUSE','RESUME','CANCEL'].includes(value.body.action)||typeof value.body.expectedToken!=='string'||! /^[a-f0-9]{64}$/.test(value.body.expectedToken))throw new Error('Invalid pending command; reconcile before submitting another command');
 return value;
}
export function savePendingTaskCommand(actor:string,value:PendingTaskCommand,storage:Storage=localStorage){
 if(storage.getItem('funnywork:pending-task-batch:'+actor)!==null)throw new Error('An earlier batch still needs reconciliation');
 const existing=readPendingTaskCommand(actor,storage);if(existing&&(existing.key!==value.key||existing.taskId!==value.taskId||existing.body.action!==value.body.action||existing.body.expectedToken!==value.body.expectedToken))throw new Error('An earlier command still needs reconciliation');
 storage.setItem(storageKey(actor),JSON.stringify(value));
}
export function clearPendingTaskCommand(actor:string,key:string,storage:Storage=localStorage){if(readPendingTaskCommand(actor,storage)?.key===key)storage.removeItem(storageKey(actor));}
