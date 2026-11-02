import React,{useEffect,useState} from 'react';
import {EventFeed} from './event-feed.cjs';
const labels:Record<string,string>={TASK_CREATED:'收到工作任务',TASK_PLAN_APPENDED:'收到补充要求',UNIT_RESERVED:'已安排执行',UNIT_STARTED:'开始处理',MODEL_OUTPUT_STORED:'生成内容已保存，待审阅',TASK_CANCELLED:'任务已停止',TASK_AUTH_REVOKED:'来源或执行权限已变化',TASK_DISPATCH_PAUSED:'已暂停安排后续步骤',TASK_DISPATCH_RESUMED:'已恢复安排后续步骤',MODEL_FAILED:'本次处理失败',RESULT_ACCEPTED:'处理结果已保存',ATTEMPT_EXPIRED:'执行超时，等待恢复'};
export function ChatActivity({api,chatId}:{api:(name:string,...args:any[])=>Promise<any>;chatId:string}){
 const [items,setItems]=useState<any[]>([]),[error,setError]=useState(''),[catchingUp,setCatchingUp]=useState(false);
 useEffect(()=>{let active=true,loading=false;let feed=new EventFeed();setItems([]);setError('');setCatchingUp(false);
 async function load(){if(loading)return;loading=true;try{let full=false;for(let page=0;page<5;page++){const rows=await api('chatEvents',chatId,feed.cursor);if(!active)return;feed.accept(rows);full=rows.length===200;if(!full)break;}if(active){setItems([...feed.items].slice(-20));setCatchingUp(full);setError('');}}catch(e:any){if(active){if(['HTTP_403','HTTP_404'].includes(e.message)){feed=new EventFeed();setItems([]);}setError('活动暂时无法更新，恢复连接后会继续读取。');}}finally{loading=false;}}
 load();const timer=setInterval(()=>{if(!document.hidden)load();},2500);return()=>{active=false;clearInterval(timer);};},[chatId]);
 return <details className="chat-activity" aria-label="活动记录"><summary>活动记录{catchingUp?' · 正在补取':''}</summary>{error&&<p role="status">{error}</p>}{!items.length&&!error&&<p>暂无活动。</p>}<ol>{items.map(event=><li key={event.id}><time>{new Date(event.created_at).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}</time> {labels[event.event_type]||'工作活动已记录'}</li>)}</ol></details>;
}
