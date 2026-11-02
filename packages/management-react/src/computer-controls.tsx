import {AdminHelp} from './admin-help';
import React,{useEffect,useRef,useState} from 'react';
type Control={attemptId:string;nodeId:string;taskId:string;unitId:string;session:string;zone:string;controlExpiresAt:number;leaseExpired:boolean;unknownActions:number;dispatchedActions:number;nodeEnabled:boolean;nodeStatus:string;adminPaused:boolean;taskState:string};
type Filter={nodeId:string;expiredOnly:boolean};
const states:Record<string,string>={RUNNING:'执行中',RECONCILING:'待业务核对',BLOCKED_AUTH:'授权已阻断',CANCELLED:'已取消',FAILED:'已失败',RECONCILED:'已核对'};
export function ComputerControls({api}:{api:(name:string,...args:any[])=>Promise<any>}){
  const [rows,setRows]=useState<Control[]>([]),[cursor,setCursor]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[observed,setObserved]=useState(0);
  const [node,setNode]=useState(''),[expired,setExpired]=useState(false),[filter,setFilter]=useState<Filter>({nodeId:'',expiredOnly:false});
  const active=useRef(true),loading=useRef(false);
  async function load(more=false,query=filter){
    if(loading.current)return;loading.current=true;setBusy(true);setError('');
    if(!more){setRows([]);setCursor('');setObserved(0);setFilter(query);}
    try{
      const page=await api('computerControls',more?cursor:'',query.nodeId,query.expiredOnly);
      if(active.current){setRows(old=>more?Array.from(new Map([...old,...page.items].map((row:Control)=>[row.attemptId,row])).values()):page.items);setCursor(page.nextCursor||'');setObserved(page.observedAt);}
    }catch{if(active.current){setRows([]);setCursor('');setObserved(0);setError('桌面占用读取失败，请检查当前权限或重新连接。');}}
    finally{loading.current=false;if(active.current)setBusy(false);}
  }
  useEffect(()=>{active.current=true;load();return()=>{active.current=false;};},[]);
  return <section className="panel nodes">
    <div className="section-head"><div><h2>待确认停止的桌面会话</h2><AdminHelp><p>仅显示授权区域内尚未释放的占用。租约到期不代表辅助程序已经退出；已发送输入也不代表业务成功。</p></AdminHelp></div><button className="secondary" disabled={busy} onClick={()=>load()}>刷新占用</button></div>
    <form onSubmit={e=>{e.preventDefault();load(false,{nodeId:node.trim(),expiredOnly:expired});}}>
      <div className="form-row"><label>节点编号<input aria-label="节点编号" value={node} disabled={busy} maxLength={64} pattern="[A-Za-z0-9_-]*" placeholder="留空查看全部授权节点" onChange={e=>setNode(e.target.value)}/></label>
      <label><span>到期范围</span><select aria-label="到期范围" value={expired?'expired':'all'} disabled={busy} onChange={e=>setExpired(e.target.value==='expired')}><option value="all">全部未释放占用</option><option value="expired">仅已到期占用</option></select></label></div><button disabled={busy} type="submit">查询占用</button>
    </form>
    {observed>0&&<p className="muted">观测时间：{new Date(observed).toLocaleString()}。翻页期间占用可能变化，刷新将重新查询。</p>}
    {error&&<p role="alert" className="error">{error}</p>}
    <div className="node-table"><table><thead><tr><th>节点 / 会话</th><th>区域 / 任务</th><th>控制期限</th><th>输入记录</th><th>节点状态</th></tr></thead><tbody>{rows.map(row=><tr key={row.attemptId}>
      <td><code>{row.nodeId}</code><small>会话 {row.session}</small></td><td>{row.zone}<small title={row.taskId}>任务 {row.taskId}</small><small>{states[row.taskState]||row.taskState}</small></td>
      <td><span className="badge">{row.leaseExpired?'已到期，待确认停止':'控制中，待确认停止'}</span><small>{new Date(row.controlExpiresAt).toLocaleString()}</small></td>
      <td>待核实输入 {row.unknownActions}<small>已发送输入 {row.dispatchedActions}</small></td>
      <td>{!row.nodeEnabled?'节点已禁用':row.adminPaused?'管理暂停':row.nodeStatus==='READY'?'上报可接单':'未接单'}<small title={row.attemptId}>执行 {row.attemptId}</small></td>
    </tr>)}</tbody></table></div>
    {!rows.length&&!busy&&!error&&<p className="empty">当前筛选范围内没有未释放的桌面占用。</p>}
    {busy&&<p role="status" className="muted">正在读取桌面占用…</p>}
    {cursor&&<button disabled={busy} className="secondary" onClick={()=>load(true)}>加载更多占用</button>}
  </section>;
}
