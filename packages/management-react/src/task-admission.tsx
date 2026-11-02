import React,{useEffect,useRef,useState} from 'react';
const call=async(name:string,...args:any[])=>{const r=await window.funnywork[name](...args);if(!r.ok)throw new Error(r.error);return r.value;};
export function TaskAdmission({task,operator,onUpdated}:{task:any;operator:boolean;onUpdated:(value:any)=>void}){
 const [busy,setBusy]=useState(false),[uncertain,setUncertain]=useState(false),[error,setError]=useState('');
 const alive=useRef(true),operation=useRef(0);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;operation.current++;};},[]);
 async function update(paused?:boolean){
  if(busy)return;const run=++operation.current;setBusy(true);setError('');
  const current=()=>alive.current&&run===operation.current;
  let failed=false;
  if(paused!==undefined){
   setUncertain(true);
   try{await call('taskAdmission',task.taskId,paused);}catch{failed=true;}
   if(!current())return;
  }
  try{
   const value=await call('task',task.taskId);if(!current())return;
   if(value.taskId!==task.taskId||typeof value.dispatchPaused!=='boolean')throw new Error('INVALID_TASK_STATE');
   onUpdated(value);setUncertain(false);
   if(failed)setError('操作响应未确认，已重新读取服务器状态，请以当前状态为准。');
  }catch{if(current()){setUncertain(true);setError('暂时无法核对服务器状态。请先刷新，再操作。');}}
  finally{if(current())setBusy(false);}
 }
 const status=task.state==='RUNNING'?(task.pauseStatus==='DRAINING'?'新步骤已暂停，等待当前步骤完成':task.pauseStatus==='PAUSED'?'新步骤已暂停':null):null;
 if(!uncertain&&!error&&!status&&(!operator||task.state!=='RUNNING'))return null;
 return <section aria-label="任务暂停与继续">{(uncertain||status)&&<p role="status">{uncertain?'暂停状态待核对':status}</p>}
  {error&&<p role="alert">{error}</p>}
  {operator&&<div className="actions"><button className="secondary" title="仅暂停新步骤，不停止已开始的操作" disabled={busy||uncertain||task.state!=='RUNNING'} onClick={()=>update(!task.dispatchPaused)}>{task.dispatchPaused?'继续任务':'暂停新步骤'}</button><button className="quiet" disabled={busy} onClick={()=>update()}>刷新</button></div>}
 </section>;
}
