import React,{useEffect,useRef,useState} from 'react';
import {readPlan,comparePlans} from './plan-diff.cjs';
const call=async(name:string,...args:any[])=>{const reply=await window.funnywork[name](...args);if(!reply.ok)throw new Error(reply.error);return reply.value;};
export function PlanHistory({taskId,currentVersion}:{taskId:string;currentVersion:number}){
 const [open,setOpen]=useState(false),[versions,setVersions]=useState<any[]>([]),[version,setVersion]=useState(1);
 const [items,setItems]=useState<any[]>([]),[edges,setEdges]=useState<any[]>([]),[meta,setMeta]=useState<any>(null);
 const [next,setNext]=useState<number|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const [comparison,setComparison]=useState<any>(null);
 const sequence=useRef(0),alive=useRef(true);
 useEffect(()=>()=>{alive.current=false;sequence.current++;},[]);
 const current=(run:number)=>alive.current&&sequence.current===run;
 function clear(){setComparison(null);setItems([]);setEdges([]);setMeta(null);setNext(null);}
 async function page(selected:number,after:number,run:number){
  const value=await call('planSnapshot',taskId,selected,after);
  if(!current(run))return;
  if(value.taskId!==taskId||value.planVersion!==selected||value.scope!=='INPUTS_AND_DEPENDENCIES'||!Array.isArray(value.items)||value.items.length>5||!Array.isArray(value.dependencies)||typeof value.hasMore!=='boolean'||(value.hasMore&&(!Number.isInteger(value.nextAfter)||value.nextAfter<=after||value.nextAfter>99)))throw new Error('INVALID_PLAN_PAGE');
  setItems(old=>after===-1?value.items:[...old,...value.items]);setEdges(old=>after===-1?value.dependencies:[...old,...value.dependencies]);setMeta(value);setNext(value.hasMore?value.nextAfter:null);
 }
 async function catalog(){const run=++sequence.current;setOpen(true);setBusy(true);setError('');clear();setVersions([]);
  try{const list=await call('plans',taskId);if(!current(run))return;
   if(!Array.isArray(list)||!list.length||list.length>100||list.some(x=>!Number.isInteger(x.version)||x.version<1||x.version>100))throw new Error('INVALID_PLAN_HISTORY');
   setVersions(list);const latest=Math.max(...list.map(x=>x.version));setVersion(latest);await page(latest,-1,run);
  }catch(e:any){if(current(run)){clear();setError('读取失败：'+e.message);}}finally{if(current(run))setBusy(false);}
 }
 async function load(selected:number,after=-1){const run=++sequence.current;setVersion(selected);setBusy(true);setError('');if(after===-1)clear();
  try{await page(selected,after,run);}catch(e:any){if(current(run)){clear();setError('读取失败：'+e.message);}}finally{if(current(run))setBusy(false);}
 }
 async function compare(){const previous=Math.max(0,...versions.filter(row=>row.version<version).map(row=>row.version));if(!previous||busy)return;
  const selected=version,run=++sequence.current;setBusy(true);setError('');clear();
  try{const before=await readPlan(call,taskId,previous,()=>current(run));const after=await readPlan(call,taskId,selected,()=>current(run));
   if(current(run))setComparison({...comparePlans(before,after),beforeVersion:previous,afterVersion:selected});
  }catch(e:any){if(current(run)){clear();setError('比较失败：'+e.message);}}finally{if(current(run))setBusy(false);}
 }
 function close(){sequence.current++;setOpen(false);setBusy(false);setError('');setVersions([]);clear();}
 return <section aria-label="计划历史">{!open?<button className="secondary" onClick={catalog}>计划历史</button>:<>
  <div className="section-head"><h3 title="步骤与依赖的历史版本">计划历史</h3><button className="quiet" onClick={close}>关闭</button></div>
  {!!versions.length&&<label>查看计划版本<select value={version} onChange={e=>load(Number(e.target.value))}>{versions.map(row=><option key={row.version} value={row.version}>版本 {row.version} · {row.input_count} 步</option>)}</select></label>}
  {currentVersion>Math.max(0,...versions.map(x=>x.version))&&!busy&&<p className="notice">计划已更新，可刷新。</p>}
  <div className="actions"><button className="secondary" disabled={busy} onClick={catalog}>刷新</button><button className="secondary" disabled={busy||!versions.some(row=>row.version<version)} onClick={compare}>比较上一版</button></div>
  {error&&<p role="alert">{error}</p>}{busy&&<p role="status">正在读取计划…</p>}
  {comparison&&<section aria-label="计划版本差异"><h4>版本 {comparison.beforeVersion} → {comparison.afterVersion}</h4><p>新增 {comparison.changes.filter((x:any)=>x.kind==='added').length} 步 · 修改 {comparison.changes.filter((x:any)=>x.kind==='changed').length} 步 · 删除 {comparison.changes.filter((x:any)=>x.kind==='removed').length} 步 · 未变化 {comparison.unchanged} 步</p>
   {comparison.changes.map((change:any)=><article className="task-step" key={(change.after||change.before).unitId}><h4>{change.kind==='added'?'新增':change.kind==='removed'?'删除':'修改'}步骤 {(change.after||change.before).ordinal+1}</h4>
    {(['before','after'] as const).map(side=>change[side]&&<div key={side}><strong>{side==='before'?'原版本':'新版本'}</strong><p>前置步骤：{change[side].predecessors.map((x:any)=>`${x.ordinal+1}（${x.completion==='OUTPUT_AVAILABLE'?'产物可用':'验证成功'}）`).join('、')||'无'}</p><pre className="preview">{change[side].input}</pre></div>)}
   </article>)}
  </section>}
  {meta&&<p>版本 {meta.planVersion} · 已加载 {items.length} / {meta.totalSteps} 步</p>}
  {items.map(item=><article className="task-step" key={item.unitId}><h4>历史步骤 {item.ordinal+1} · 创建于版本 {item.createdInVersion}</h4>
   <p className="muted">前置步骤：{edges.filter(edge=>edge.successor===item.ordinal).map(edge=>edge.predecessor+1).join('、')||'无'}</p>
   <pre className="preview">{item.input}</pre>
  </article>)}{next!==null&&<button className="secondary" disabled={busy} onClick={()=>load(version,next)}>更多步骤</button>}
 </>}</section>;
}
