import React,{useEffect,useRef,useState} from 'react';
const call=async(name:string,...args:any[])=>{const result=await window.funnywork[name](...args);if(!result.ok)throw new Error(result.error);return result.value;};
export function PlanAppend({task,onUpdated}:{task:any;onUpdated:(value:any)=>void}){
 const [basis,setBasis]=useState<any>(null),[text,setText]=useState(''),[predecessors,setPredecessors]=useState<number[]>([]);
 const [pending,setPending]=useState<any>(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const alive=useRef(true);const operation=useRef(0);
 useEffect(()=>{alive.current=true;return()=>{alive.current=false;operation.current++;};},[]);
 const supported=['text.stats.v1','model.read.v1'].includes(task.capability);
 function start(value:any){setBasis({id:value.taskId,version:value.planVersion,ordinal:value.units.length,capability:value.capability});setPredecessors([]);setPending(null);setError('');setNotice('');}
 async function reload(){const run=++operation.current;setBusy(true);try{const value=await call('task',task.taskId);if(!alive.current||run!==operation.current)return;onUpdated(value);start(value);}catch(e:any){if(alive.current&&run===operation.current)setError('读取失败：'+e.message);}finally{if(alive.current&&run===operation.current)setBusy(false);}}
 async function submit(e:React.FormEvent){
  e.preventDefault();if(busy)return;const run=++operation.current;setBusy(true);setError('');
  const request=pending||{id:basis.id,expectedVersion:basis.version,ordinal:basis.ordinal,text,predecessors,completion:basis.capability==='model.read.v1'?'OUTPUT_AVAILABLE':'SUCCEEDED'};
  setPending(request);
  try{const result=await call('appendPlan',request);if(!alive.current||run!==operation.current)return;
   setNotice(`已保存到计划版本 ${result.planVersion}`);setPending(null);setBasis(null);setText('');
   try{const value=await call('task',task.taskId);if(alive.current&&run===operation.current)onUpdated(value);}
   catch{if(alive.current&&run===operation.current)setNotice(`已保存到计划版本 ${result.planVersion}；进度暂未刷新。`);}
  }catch(e:any){if(!alive.current||run!==operation.current)return;
   if(['HTTP_400','HTTP_401','HTTP_403','HTTP_404','HTTP_409','HTTP_413','HTTP_422','HTTP_429'].includes(e.message)){setPending(null);setError('提交被拒绝，请刷新计划后检查步骤与权限。');}
   else setError('尚未确认提交结果。重试会核对原请求，不会另建步骤。');
  }finally{if(alive.current&&run===operation.current)setBusy(false);}
 }
 if(!supported)return null;
 return <section aria-label="追加工作步骤">{notice&&<p role="status">{notice}</p>}{!basis?<button className="secondary" disabled={busy||task.state!=='RUNNING'||task.units.length>=100} onClick={()=>start(task)}>追加步骤</button>:<form onSubmit={submit}>
  <h3>追加步骤 {basis.ordinal+1}</h3><p className="muted">版本 {basis.version}</p>
  {task.planVersion!==basis.version&&<p className="notice">计划版本已有变化，请先核对原请求或刷新计划。</p>}
  {error&&<p role="alert">{error}</p>}{pending&&<p className="muted">请求已提交，取消不撤回。</p>}
  <fieldset className="task-step" disabled={busy||!!pending}><label>追加步骤内容<textarea rows={3} maxLength={10000} required value={text} onChange={e=>setText(e.target.value)}/></label>
   <p className="muted" title={basis.capability==='model.read.v1'?'不选则独立执行；使用前置成果':'不选则独立执行；前置步骤须验证成功'}>前置步骤</p>
   <div className="step-dependencies">{Array.from({length:basis.ordinal},(_,i)=><label key={i}><input type="checkbox" checked={predecessors.includes(i)} onChange={e=>setPredecessors(old=>e.target.checked?[...old,i]:old.filter(x=>x!==i))}/>前置步骤 {i+1}</label>)}</div>
  </fieldset><div className="actions"><button disabled={busy||(!pending&&(!text.trim()||task.state!=='RUNNING'))}>{pending?'重试原请求':'保存'}</button><button type="button" className="secondary" disabled={busy||!!pending} onClick={reload}>刷新计划</button><button type="button" className="secondary" disabled={busy} onClick={()=>{setBasis(null);setPending(null);setError('');setText('');}}>取消</button></div>
 </form>}</section>;
}
