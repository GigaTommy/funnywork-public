import React,{useEffect,useRef,useState} from 'react';
type Agent={id:string;name:string;version:string;capability:string};
/** Select a published version from scoped server pages without requiring an opaque ID. */
export function AgentPicker({api,value,onChange,capability='',disabled=false,label='执行能力'}:{api:(name:string,...args:any[])=>Promise<any>;value:string;onChange:(id:string)=>void;capability?:string;disabled?:boolean;label?:string}){
 const [rows,setRows]=useState<Agent[]>([]),[selected,setSelected]=useState<Agent>(),[query,setQuery]=useState(''),[applied,setApplied]=useState(''),[cursor,setCursor]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
 const generation=useRef(0),locked=useRef(false);
 async function load(more=false){
  if(locked.current)return;
  locked.current=true;const run=generation.current;setBusy(true);setError('');const search=more?applied:query.trim();
  try{const page=await api('agentPage',more?cursor:'',capability,search);if(run===generation.current){setRows(old=>more?[...old,...page.items]:page.items);setCursor(page.nextCursor||'');setApplied(search);}}
  catch{if(run===generation.current)setError('执行能力读取失败，请核对查看权限后重试。');}
  finally{if(run===generation.current){locked.current=false;setBusy(false);}}
 }
 useEffect(()=>{generation.current++;locked.current=false;setRows([]);setCursor('');setBusy(false);void load();return()=>{generation.current++;};},[capability]);
 const options=selected&&selected.id===value&&!rows.some(row=>row.id===value)?[selected,...rows]:rows;
 return <div className="agent-picker"><div className="form-row"><label>查找能力<input aria-label={label+'名称筛选'} disabled={disabled||busy} maxLength={100} value={query} onChange={e=>setQuery(e.target.value)} placeholder="名称或完整编号"/></label><button type="button" className="secondary" disabled={disabled||busy} onClick={()=>load()}>查询能力</button></div>
 <label>{label}<select aria-label={label} disabled={disabled||busy} value={value} onChange={e=>{const id=e.target.value;setSelected(options.find(row=>row.id===id));onChange(id);}}><option value="">请选择已发布版本</option>{value&&!options.some(row=>row.id===value)&&<option value={value}>已选版本 {value}（不在当前结果中）</option>}{options.map(row=><option key={row.id} value={row.id}>{row.name} · {row.version} · {row.capability}</option>)}</select></label>
 {error&&<p role="alert" className="error">{error}</p>}{busy&&<p role="status">正在读取执行能力…</p>}{!busy&&!error&&!rows.length&&<p className="empty">当前条件下没有已发布能力。</p>}{cursor&&<button type="button" className="secondary" disabled={disabled||busy||query.trim()!==applied} onClick={()=>load(true)}>加载更多能力</button>}{query.trim()!==applied&&<p className="muted">筛选已修改，请查询后再加载后续页面。</p>}
 </div>;
}
