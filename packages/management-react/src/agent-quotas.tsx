import {WriteButton, WriteForm} from './write-access';
import React, {useEffect, useRef, useState} from 'react';
type Policy={configured:boolean;maxConcurrency:number|null;effectiveLimit:number;occupied:number;observedAt:number};
export function AgentQuotas({api,zones}:{api:(name:string,...args:any[])=>Promise<any>;zones:string[]}) {
  const [agents,setAgents]=useState<any[]>([]),[agent,setAgent]=useState(''),[zone,setZone]=useState(zones[0]||'');
  const [cursor,setCursor]=useState(''),[agentsBusy,setAgentsBusy]=useState(false);
  const [policy,setPolicy]=useState<Policy|null>(null),[limit,setLimit]=useState('1');
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[agentError,setAgentError]=useState(''),[notice,setNotice]=useState('');
  const generation=useRef(0),locked=useRef(false),alive=useRef(true),agentLock=useRef(false);
  useEffect(()=>{alive.current=true;loadAgents(false);return()=>{alive.current=false;generation.current++;};},[]);
  useEffect(()=>{generation.current++;locked.current=false;setBusy(false);setPolicy(null);setLimit('1');setError('');setNotice('');if(agent&&zone)run('read');return()=>{generation.current++;};},[agent,zone]);
  async function loadAgents(more:boolean){
    if(agentLock.current)return;agentLock.current=true;setAgentsBusy(true);setAgentError('');
    try{const page=await api('agentPage',more?cursor:'','');if(alive.current){setAgents(old=>more?[...old,...page.items]:page.items);setCursor(page.nextCursor||'');if(!more)setAgent(page.items[0]?.id||'');}}
    catch{if(alive.current)setAgentError('执行能力读取失败，请重新加载。');}
    finally{agentLock.current=false;if(alive.current)setAgentsBusy(false);}
  }
  async function run(action:'read'|'save'|'clear'){
    if(locked.current||!agent||!zone)return;
    const value=Number(limit);
    if(action==='save'&&(!Number.isSafeInteger(value)||value<1||value>10000)){setError('并发上限必须为1～10000的整数。');return;}
    locked.current=true;const request=generation.current;setBusy(true);setError('');setNotice('');let confirmed=false;
    try{
      if(action==='save'){await api('setAgentPolicy',agent,zone,value);confirmed=true;}
      if(action==='clear'){await api('clearAgentPolicy',agent,zone);confirmed=true;}
      if(request!==generation.current)return;
      const current=await api('agentPolicy',agent,zone);
      if(request===generation.current){setPolicy(current);setLimit(String(current.maxConcurrency??current.effectiveLimit));if(confirmed)setNotice(action==='clear'?'已恢复继承租户限制。':'并发配额已保存。');}
    }catch{if(request===generation.current){setPolicy(null);setError(confirmed?'配置已更新，但状态读取失败，请刷新配额。':action==='read'?'配额读取失败，请刷新或检查权限。':'修改结果未确认，请刷新配额核实后再操作。');}}
    finally{if(request===generation.current){locked.current=false;setBusy(false);}}
  }
  return <section className="panel"><h2>执行能力区域并发配额</h2><p className="notice">同一执行能力在所选区域的所有任务共享额度。降低额度不会中断在途任务，系统会等待占用释放后再分配。</p>
    {agentError&&<p role="alert" className="error">{agentError}<button className="secondary" disabled={agentsBusy} onClick={()=>loadAgents(false)}>重新加载执行能力</button></p>}
    <div className="form-row"><label>执行能力<select aria-label="配额执行能力" disabled={busy} value={agent} onChange={e=>setAgent(e.target.value)}>{agents.map(a=><option key={a.id} value={a.id}>{a.name} · {a.version} · {a.capability}</option>)}</select></label><label>工作区域<select aria-label="配额区域" disabled={busy} value={zone} onChange={e=>setZone(e.target.value)}>{zones.map(z=><option key={z}>{z}</option>)}</select></label></div>
    {cursor&&<button className="secondary" disabled={agentsBusy} onClick={()=>loadAgents(true)}>加载更多执行能力</button>}
    {!agent&&!agentsBusy&&!agentError&&<p className="empty">暂无已发布的执行能力。</p>}
    <button className="secondary" disabled={busy||!agent||!zone} onClick={()=>run('read')}>刷新配额</button>
    {error&&<p role="alert" className="error">{error}</p>}{notice&&<p role="status">{notice}</p>}
    {policy&&<><p aria-label="当前配额">{policy.configured?`独立上限：${policy.maxConcurrency}`:'当前继承租户限制'} · 有效上限：{policy.effectiveLimit} · 当前占用：{policy.occupied}</p><p className="muted">观测时间：{new Date(policy.observedAt).toLocaleString()}。有效上限还受租户总额度约束，不代表此刻可用槽位；节点容量和本地资源条件仍须满足。</p><WriteForm onSubmit={e=>{e.preventDefault();run('save');}}><label>独立并发上限<input aria-label="独立并发上限" type="number" min={1} max={10000} step={1} required disabled={busy} value={limit} onChange={e=>setLimit(e.target.value)}/></label><div className="actions"><WriteButton disabled={busy}>保存并发上限</WriteButton><WriteButton type="button" className="secondary" disabled={busy||!policy.configured} onClick={()=>run('clear')}>恢复继承租户限制</WriteButton></div></WriteForm></>}
    {busy&&<p className="muted">正在更新配额状态…</p>}
  </section>;
}
