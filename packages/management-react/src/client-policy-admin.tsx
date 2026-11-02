import {clientPolicySaveError} from './client-policy-feedback';
import {AgentPicker} from './agent-picker';
import {WriteButton, WriteForm} from './write-access';
import {AdminHelp} from './admin-help';
import React,{useEffect,useRef,useState} from 'react';
export function ClientPolicyAdmin({api}:{api:(name:string,...args:any[])=>Promise<any>}){
 const [snapshot,setSnapshot]=useState<any>(null),[entries,setEntries]=useState<any[]>([]),[provider,setProvider]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [original,setOriginal]=useState(''),[baseVersion,setBaseVersion]=useState(0),[needsReload,setNeedsReload]=useState(false);
 const [editingEntry,setEditingEntry]=useState<string>('');
 const pickerPanel=useRef<HTMLFieldSetElement>(null);
 useEffect(()=>{if(editingEntry)pickerPanel.current?.focus();},[editingEntry]);
 const activeEntry=entries.find(entry=>entry.id===editingEntry);
 const draft=JSON.stringify({entries,provider}),dirty=!!snapshot&&draft!==original;
 function accept(value:any){setEntries(value.policy.entries);setProvider(value.policy.identityProviderId||'');setOriginal(JSON.stringify({entries:value.policy.entries,provider:value.policy.identityProviderId||''}));setBaseVersion(value.version);}
 async function load(preserve=true){setBusy(true);setError('');try{const value=await api('clientPolicy');setSnapshot(value);if(!preserve||!dirty||needsReload)accept(value);setNeedsReload(false);return true;}catch{setError('客户端策略读取失败，已保留当前内容。');return false;}finally{setBusy(false);}}
 useEffect(()=>{void load();},[]);
 async function save(e:React.FormEvent){
  e.preventDefault();if(busy||!snapshot||needsReload)return;setBusy(true);setError('');setNotice('');
  try{
   await api('saveClientPolicy',{expectedVersion:baseVersion,signInMode:snapshot.policy.signInMode,identityProviderId:provider,entries});
   setOriginal(draft);
   if(await load(false))setNotice('应用目录已保存。此页面不改变账号认证方式，也不代表桌面客户端已应用配置。');
   else {setError('');setNeedsReload(true);setNotice('应用目录已保存，但最新状态读取失败。请刷新核对，无需再次提交。');}
  }catch(error){setError(clientPolicySaveError(error));}finally{setBusy(false);}
 }
 function update(index:number,key:string,value:string){setEntries(old=>old.map((item,i)=>i===index?{...item,[key]:value}:item));}
 return <section className="panel"><div className="section-head"><h2>客户端应用目录</h2><button disabled={busy} className="secondary" onClick={()=>void load()}>刷新策略</button></div><AdminHelp><p>员工只通过 Chat 和能力菜单使用系统。应用接入、Skills / MCP 发布、账号策略和执行能力绑定均由服务端管理员维护。</p><p>维护服务端应用目录，发布条目必须绑定本租户已发布的执行能力。账号与统一身份接入在成员管理中维护；本地应用与可执行 Skills 仍需对应运行时。</p></AdminHelp>{error&&<p className="error" role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}{snapshot&&<WriteForm onSubmit={save}><p className="notice">此目录保留旧版客户端协议字段，不用于配置当前账号登录。独立本地账号和统一身份账号由身份服务管理。</p><p className="muted">目录版本 {snapshot.version} · 客户端应用状态：尚无应用回执</p>{dirty&&<p role="status">草稿尚未保存，基于版本 {baseVersion}；刷新不会覆盖草稿。{snapshot.version!==baseVersion?' 服务端版本已变化，请核对后放弃草稿或重新编辑。':''}<button type="button" className="secondary" disabled={busy} onClick={()=>accept(snapshot)}>放弃草稿</button></p>}<div className="node-table"><table><thead><tr><th>系统或能力</th><th>分类</th><th>发布状态</th><th>执行能力</th></tr></thead><tbody>{entries.map((entry,i)=><tr key={entry.id}><td>{entry.name}</td><td>{({IM:'即时通讯',DOCUMENT:'公文',WORKFLOW:'流程',OFFICE:'办公',MAIL:'邮件',COMPUTER:'桌面',BROWSER:'浏览器',CODE:'代码',SKILL:'工作指引',MCP:'工具协议'} as Record<string,string>)[entry.category]||entry.category}</td><td><select aria-label={entry.name+'发布状态'} disabled={busy} value={entry.status} onChange={e=>update(i,'status',e.target.value)}><option value="RESERVED">未发布</option><option value="AVAILABLE">已发布</option></select></td><td><small>{entry.agentId||'尚未绑定'}</small><button type="button" className="secondary" aria-label={'选择'+entry.name+'执行能力'} disabled={busy} onClick={()=>setEditingEntry(entry.id)}>选择能力</button></td></tr>)}</tbody></table></div>{activeEntry&&<fieldset className="client-policy-picker" ref={pickerPanel} tabIndex={-1}><legend>{activeEntry.name} · 选择执行能力</legend><AgentPicker key={activeEntry.id} api={api} value={activeEntry.agentId||''} label={activeEntry.name+'执行能力'} disabled={busy} onChange={id=>setEntries(old=>old.map(entry=>entry.id===activeEntry.id?{...entry,agentId:id}:entry))}/><p className="muted">选择只更新当前草稿，保存应用目录后生效；发布状态仍需单独设置。</p><button type="button" className="secondary" disabled={busy} onClick={()=>setEditingEntry('')}>完成选择</button></fieldset>}<WriteButton disabled={busy||needsReload||!dirty}>保存应用目录</WriteButton></WriteForm>}</section>;
}
