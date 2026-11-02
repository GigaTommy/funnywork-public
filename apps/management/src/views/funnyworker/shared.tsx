import React,{useEffect,useRef,useState} from 'react';
import {WriteButton,WriteForm} from '../../../../../packages/management-react/src/write-access';
import {businessErrorMessage} from '@/utils/business-feedback';
import {AdminHelp} from '../../../../../packages/management-react/src/admin-help';
import {businessApi as api} from '@/utils/funnyworker-api';
export function Overview(){
 const [data,setData]=useState<any>(null),[error,setError]=useState('');
 useEffect(()=>{let alive=true;async function load(){try{const value=await api('metrics');if(alive){setData(value);setError('');}}catch{if(alive){setData(null);setError('无法读取调度状态，请核对身份与服务连接。');}}}load();const timer=setInterval(()=>{if(!document.hidden)load();},10000);return()=>{alive=false;clearInterval(timer);};},[]);
 const states:Record<string,string>={READY:'等待执行',RESERVED:'已分配',RUNNING:'执行中',SUCCEEDED:'已完成',FAILED:'失败',CANCELLED:'已取消',WAITING_DEPENDENCY:'等待前置',BLOCKED_DEPENDENCY:'前置失败'};
 const total=(field:string)=>data.zones.reduce((sum:number,zone:any)=>sum+Number(zone.nodes[field]||0),0);
 return <section className="panel overview-panel"><div className="section-head"><h2>运行概览</h2>{data&&<span className="muted">{new Date(data.observedAt).toLocaleTimeString()} 更新 · 10 秒刷新</span>}</div>{error&&<p className="error" role="alert">{error}</p>}{data?<><div className="overview-stats"><div><small>可接单节点</small><strong>{total('ready_nodes')}</strong></div><div><small>节点总容量</small><strong>{total('ready_slots')}</strong></div><div><small>并发上限</small><strong>{data.maxConcurrency}</strong></div><div><small>最长等待</small><strong>{Math.round(Math.max(0,...data.zones.map((zone:any)=>zone.oldestReadyTaskAgeMs))/1000)}<span> 秒</span></strong></div></div><div className="section-head overview-section"><h2>区域调度</h2><AdminHelp><p>容量为可接单节点的总容量，剩余槽位请查看节点页面。状态为最近一次查询结果。</p></AdminHelp></div><div className="node-table"><table><thead><tr><th>区域</th><th>可接单节点</th><th>总容量</th><th>最长等待</th></tr></thead><tbody>{data.zones.map((zone:any)=><tr key={zone.zone}><td><strong>{zone.zone}</strong></td><td>{zone.nodes.ready_nodes}</td><td>{zone.nodes.ready_slots}</td><td>{Math.round(zone.oldestReadyTaskAgeMs/1000)} 秒</td></tr>)}</tbody></table></div><div className="section-head overview-section"><h2>执行状态</h2></div><div className="node-table"><table><thead><tr><th>区域</th><th>状态</th><th>执行单元</th></tr></thead><tbody>{data.zones.flatMap((zone:any)=>zone.units.map((unit:any)=><tr key={zone.zone+unit.state}><td>{zone.zone}</td><td><span className={'badge status-'+unit.state}>{states[unit.state]||unit.state}</span></td><td>{unit.amount}</td></tr>))}</tbody></table></div>{!data.zones.some((zone:any)=>zone.units.length)&&<div className="admin-empty-inline">暂无执行任务</div>}</>:!error&&<p>正在读取…</p>}</section>;

}
export function CapabilityInventory({model}:{model:boolean}){
 const [rows,setRows]=useState<any[]>([]),[cursor,setCursor]=useState(''),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const [capability,setCapability]=useState(model?'model.read.v1':''),[query,setQuery]=useState(''),[applied,setApplied]=useState({capability:model?'model.read.v1':'',query:''});
 const alive=useRef(true),locked=useRef(false);
 const kinds:Record<string,string>={'text.stats.v1':'文本统计','model.read.v1':'模型与只读工具','connector.write.v1':'业务连接器','browser.write.v1':'浏览器操作','computer.write.v1':'桌面操作','repository.maintain.v1':'仓库维护'};
 async function load(more=false){
  if(locked.current)return;locked.current=true;setBusy(true);setError('');
  const filter=more?applied:{capability,query:query.trim()};
  try{const page=await api('agentPage',more?cursor:'',filter.capability,filter.query);if(alive.current){setRows(old=>more?[...old,...page.items]:page.items);setCursor(page.nextCursor||'');setApplied(filter);}}
  catch{if(alive.current)setError('执行能力读取失败，请核对权限后重试。');}
  finally{locked.current=false;if(alive.current)setBusy(false);}
 }
 useEffect(()=>{alive.current=true;void load();return()=>{alive.current=false;};},[]);
 const [name,setName]=useState(''),[version,setVersion]=useState('1.0.0'),[newKind,setNewKind]=useState('text.stats.v1'),[notice,setNotice]=useState('');
 async function publish(e:React.FormEvent){
  e.preventDefault();if(locked.current)return;locked.current=true;setBusy(true);setError('');setNotice('');
  try{const value=await api('publishAgent',{name,version,capability:newKind});if(alive.current){setNotice(`已发布 ${value.name} · ${value.version}。请查询 / 刷新查看目录；节点配置和任务授权需分别完成。`);setName('');}}
  catch(error){if(alive.current)setError(businessErrorMessage(error)+' 当前发布草稿已保留。');}
  finally{locked.current=false;if(alive.current)setBusy(false);}
 }
 const changed=capability!==applied.capability||query.trim()!==applied.query;
 return <section className="panel"><div className="section-head"><h2>执行能力目录</h2><button className="secondary" disabled={busy} onClick={()=>load()}>查询 / 刷新</button></div>
 <p className="notice">显示当前租户已发布的执行能力版本。发布不代表已配置工具、已授权区域或执行节点可用；任务创建和执行仍分别校验。</p>
 <div className="form-row"><label>能力类型<select aria-label="能力类型" value={capability} disabled={busy||model} onChange={e=>setCapability(e.target.value)}><option value="">全部类型</option>{Object.entries(kinds).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label><label>名称或编号<input maxLength={100} disabled={busy} value={query} onChange={e=>setQuery(e.target.value)} placeholder="名称或完整编号"/></label></div>
 {error&&<p className="error" role="alert">{error}</p>}
 <div className="node-table"><table><thead><tr><th>名称 / 版本</th><th>类型</th><th>发布状态</th><th>编号</th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td><strong>{row.name}</strong><small>{row.version}</small></td><td>{kinds[row.capability]||row.capability}</td><td>已发布</td><td>{row.id}</td></tr>)}</tbody></table></div>
 {!rows.length&&!busy&&!error&&<p className="empty">当前条件下没有已发布能力。</p>}{busy&&<p role="status">正在读取能力目录…</p>}{changed&&<p className="muted">条件已修改，请查询后再加载后续页面。</p>}{cursor&&<button disabled={busy||changed} onClick={()=>load(true)}>加载更多</button>}
 {notice&&<p role="status">{notice}</p>}<h3>发布执行能力版本</h3><p className="muted">名称使用小写字母、数字和短横线；版本发布后保留，更新需使用新版本号。</p>
 <WriteForm onSubmit={publish}><label>发布名称<input required pattern="[a-z0-9-]{1,100}" maxLength={100} disabled={busy} value={name} onChange={e=>setName(e.target.value)}/></label><label>发布版本<input required pattern="[0-9]+\.[0-9]+\.[0-9]+" maxLength={40} disabled={busy} value={version} onChange={e=>setVersion(e.target.value)}/></label><label>发布能力类型<select disabled={busy} value={newKind} onChange={e=>setNewKind(e.target.value)}>{Object.entries(kinds).map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label><WriteButton disabled={busy||!name}>发布版本</WriteButton></WriteForm></section>;
}
