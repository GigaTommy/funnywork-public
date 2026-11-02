import {WriteButton, WriteForm} from './write-access';
import {AdminHelp} from './admin-help';
import React,{useEffect,useRef,useState} from 'react';
export function DocumentBindings({api,taskId=''}:{taskId?:string;api:(name:string,...args:any[])=>Promise<any>}){
 const [task,setTask]=useState(taskId),[snapshot,setSnapshot]=useState<any>(null),[tool,setTool]=useState(''),[source,setSource]=useState(''),[digest,setDigest]=useState('');
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const alive=useRef(true),locked=useRef(false);
 const [tools,setTools]=useState<any[]>([]),[sources,setSources]=useState<any[]>([]),[toolCursor,setToolCursor]=useState(''),[sourceCursor,setSourceCursor]=useState({source:'',hash:''}),[optionError,setOptionError]=useState(''),[manual,setManual]=useState(false);
 async function options(id:string,kind:'tools'|'sources',more=false){
  try{
   const page=await api(kind==='tools'?'documentToolCandidates':'documentSourceCandidates',id,more?(kind==='tools'?toolCursor:sourceCursor.source):'',more?sourceCursor.hash:'');
   if(!alive.current)return;
   if(kind==='tools'){setTools(old=>more?[...old,...page.items]:page.items);setToolCursor(page.nextCursor||'');}
   else{setSources(old=>more?[...old,...page.items]:page.items);setSourceCursor({source:page.nextSource||'',hash:page.nextHash||''});}
  }catch{if(alive.current)setOptionError('候选清单读取失败，请重新查询任务；已有绑定结果未受影响。');}
 }
 async function moreOptions(kind:'tools'|'sources'){
  if(locked.current||!snapshot)return;locked.current=true;setBusy(true);setOptionError('');
  try{await options(snapshot.taskId,kind,true);}finally{locked.current=false;if(alive.current)setBusy(false);}
 }

 useEffect(()=>{alive.current=true;if(taskId)void run();return()=>{alive.current=false;};},[]);
 async function run(action?:'bind'|'revoke',toolId=tool){
  if(locked.current)return;locked.current=true;setBusy(true);setError('');setNotice('');
  const id=action?snapshot.taskId:task.trim();let failed=false,committed=false;
  try{
   if(action){try{await api(action==='bind'?'bindDocument':'revokeDocument',id,toolId,source,digest);committed=true;}catch{failed=true;}}
   if(!alive.current)return;
   const value=await api('documentBindings',id);if(!alive.current)return;
   if(value.taskId!==id||!Array.isArray(value.items))throw new Error('INVALID_BINDINGS');
   setSnapshot(value);
   if(!action){setOptionError('');setTools([]);setSources([]);setToolCursor('');setSourceCursor({source:'',hash:''});await Promise.all([options(id,'tools'),options(id,'sources')]);}
   if(action){
    const row=value.items.find((item:any)=>item.toolVersionId===toolId);
    const confirmed=action==='bind'?row?.bindingEnabled&&row.sourceId===source&&row.deploymentHash===digest:row&&!row.bindingEnabled;
    if(confirmed){setNotice(action==='bind'?'已核对来源绑定。':'已核对绑定撤销；已读取的内容不会因此撤回。');setTool('');await options(id,'tools');}
    else setError(failed?'请求未确认，已读取当前绑定，请核对后再操作。':'当前绑定与请求不一致，请核对状态。');
   }
  }catch{if(alive.current){if(!action)setSnapshot(null);setError(committed?'操作已完成，但绑定状态读取失败，请重新查询核对；无需重复提交。':'绑定状态读取失败，请检查任务编号和当前权限后重新查询。');}}
  finally{locked.current=false;if(alive.current)setBusy(false);}
 }
 return <section className="panel nodes"><h2>任务知识来源</h2><p className="notice">按任务核对文档或知识集合绑定。这里不显示任务正文和来源凭证；来源系统仍会独立验证执行身份。</p>
  <form onSubmit={e=>{e.preventDefault();run();}}><label>查询任务编号<input required maxLength={64} pattern="[A-Za-z0-9-]+" value={task} disabled={busy} onChange={e=>{setTask(e.target.value);setSnapshot(null);setTool('');setSource('');setDigest('');setTools([]);setSources([]);setNotice('');setError('');}}/></label><button disabled={busy||!task.trim()}>查询来源绑定</button></form>
  {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
  {snapshot&&<><p>区域：{snapshot.zone} · 任务状态：{snapshot.taskState}</p><p>执行身份：<code>{snapshot.executionPrincipalId}</code></p>
   <WriteForm onSubmit={e=>{e.preventDefault();run('bind');}}><h3>绑定已部署来源</h3><AdminHelp><p>工具版本须已授予该任务；来源编号和部署摘要须与节点部署清单一致。绑定后不可替换，撤销后不可恢复同一绑定。新增绑定不会重跑已完成的步骤。</p></AdminHelp>
    <p className="muted">来源清单来自同区域节点对当前执行身份的部署上报，不代表节点在线或来源访问已验证。</p>{optionError&&<p role="alert" className="error">{optionError}</p>}<fieldset disabled={busy||snapshot.taskState!=='RUNNING'}><label>已授权的只读工具<select required value={tool} onChange={e=>setTool(e.target.value)}><option value="">请选择工具版本</option>{tool&&!tools.some(item=>item.id===tool)&&<option value={tool}>已选版本 {tool}</option>}{tools.map(item=><option key={item.id} value={item.id}>{item.name} · {item.version}</option>)}</select></label>{toolCursor&&<button type="button" onClick={()=>moreOptions('tools')}>加载更多工具</button>}
    <label><input type="checkbox" checked={manual} onChange={e=>{setManual(e.target.checked);setSource('');setDigest('');}}/>手动输入部署信息</label>
    {manual?<><p className="muted">用于尚未上报的部署；绑定后仍需匹配节点清单才能调度。</p><label>来源或集合编号<input required maxLength={120} pattern="[A-Za-z0-9._-]+" value={source} onChange={e=>setSource(e.target.value)}/></label><label>部署摘要<input required minLength={64} maxLength={64} pattern="[a-f0-9]{64}" value={digest} onChange={e=>setDigest(e.target.value)}/></label></>:<><label>已上报的来源版本<select required value={source&&digest?source+':'+digest:''} onChange={e=>{const [id,hash]=e.target.value.split(':');setSource(id||'');setDigest(hash||'');}}><option value="">请选择来源与部署版本</option>{sources.map(item=><option key={item.source_id+':'+item.deployment_hash} value={item.source_id+':'+item.deployment_hash}>{item.source_id} · {item.deployment_hash.slice(0,12)} · {item.node_count} 个节点</option>)}</select></label>{digest&&<p className="wrap-anywhere">完整部署摘要：{digest}</p>}{sourceCursor.source&&<button type="button" onClick={()=>moreOptions('sources')}>加载更多来源</button>}{!busy&&!optionError&&!sources.length&&<p className="muted">尚无匹配此执行身份和区域的节点来源上报。</p>}</>}
    <WriteButton>保存来源绑定</WriteButton></fieldset>
   </WriteForm><h3>已登记绑定 · {snapshot.items.length}</h3><p className="muted">各项状态只反映平台配置，不代表来源可达、凭证有效或当前任务可执行。</p>
   {snapshot.items.map((row:any)=><article className="task-step" key={row.toolVersionId}><h4>{row.name} · {row.version}</h4><p>工具版本：<code>{row.toolVersionId}</code></p><p>来源：{row.sourceId}</p><p className="wrap-anywhere">部署摘要：<code>{row.deploymentHash}</code></p><p>{row.bindingEnabled?'绑定未撤销':'绑定已撤销'} · {row.toolEnabled?'工具启用':'工具停用'} · {row.taskGranted?'任务已获授工具':'任务未获授工具'}</p><WriteButton className="secondary" disabled={busy||!row.bindingEnabled} onClick={()=>run('revoke',row.toolVersionId)}>撤销此来源绑定</WriteButton></article>)}
  </>}
 </section>;
}
