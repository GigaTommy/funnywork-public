import React from 'react';
export type Step={text:string;predecessors:number[]};
export function TaskSteps({steps,onChange,busy,model}:{steps:Step[];onChange:(steps:Step[])=>void;busy:boolean;model:boolean}){
  return <section aria-label="工作步骤">
    {steps.map((step,index)=><fieldset className="task-step" key={index} disabled={busy}><legend>步骤 {index+1}</legend>
      <label>步骤 {index+1} 内容<textarea rows={3} maxLength={10000} required value={step.text} onChange={e=>onChange(steps.map((old,i)=>i===index?{...old,text:e.target.value}:old))}/></label>
      {index>0&&<div><p className="muted" title={model?'不选则独立执行；使用前置成果，前置失败则阻断':'不选则独立执行；前置步骤通过后执行，失败则阻断'}>前置步骤</p><div className="step-dependencies">{steps.slice(0,index).map((_,previous)=><label key={previous}><input type="checkbox" aria-label={`步骤 ${index+1} 等待步骤 ${previous+1}`} checked={step.predecessors.includes(previous)} onChange={e=>onChange(steps.map((old,i)=>i===index?{...old,predecessors:e.target.checked?[...old.predecessors,previous].sort((a,b)=>a-b):old.predecessors.filter(x=>x!==previous)}:old))}/>步骤 {previous+1}</label>)}</div></div>}
    </fieldset>)}<div className="actions"><button type="button" className="secondary" disabled={busy||steps.length>=100} onClick={()=>onChange([...steps,{text:'',predecessors:[steps.length-1]}])}>添加步骤</button><button type="button" className="secondary" disabled={busy||steps.length<=2} onClick={()=>onChange(steps.slice(0,-1))}>移除最后一步</button></div>
  </section>;
}
