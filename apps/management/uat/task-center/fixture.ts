const batchReceipts=new Map<string,any>(),lostBatches=new Set<string>();
let contentPolicy={readInput:false,readArtifacts:false,version:0};
let memberAssignment={departmentId:'',version:0};
import {ref} from 'vue';
import {BusinessError} from '../../src/utils/business-feedback';
export const mode=ref('normal');
const data=[{id:'example-a',title:'整理月度经营资料',owner_id:'member-a',owner_name:'陈晓',zone:'sandbox',state:'OUTPUT_READY',capability:'report.annual.v1',created_at:1792458000000,dispatch_paused:true,department_id:'dept-a',department_name:'财务',project_id:'project-a',project_name:'年报项目',context_version:1},{id:'example-b',title:'核对版本升级问题',owner_id:'member-b',owner_name:'林明',zone:'sandbox',state:'RUNNING',capability:'repository.maintain.v1',created_at:1792457000000,dispatch_paused:false,department_id:'dept-b',department_name:'技术',project_id:'project-b',project_name:'升级项目',context_version:1}];
const departments=[{id:'dept-a',name:'财务',zone:'sandbox'},{id:'dept-b',name:'技术',zone:'sandbox'}],projects=[{id:'project-a',name:'年报项目',zone:'sandbox'},{id:'project-b',name:'升级项目',zone:'sandbox'}];
const receipts=new Map<string,any>(),lost=new Set<string>();let policy={version:0,restrictDepartments:false,departments:[],restrictProjects:false,projects:[]};
export async function businessRequest(path:string,method='GET',body?:any,key?:string){
 await new Promise(resolve=>setTimeout(resolve,mode.value==='slow'?1500:40));
 if(mode.value==='denied')throw new BusinessError('FORBIDDEN',403);
 if(mode.value==='failure')throw new BusinessError('HTTP_503',503);
 const url=new URL('http://fixture'+path);
 if(url.pathname==='/management-tasks/members')return {items:data.map(row=>({id:row.owner_id,display_name:row.owner_name})),hasMore:false};
 if(url.pathname==='/management-tasks'){
  const items=mode.value==='empty'?[]:data.filter(row=>(!url.searchParams.get('state')||row.state===url.searchParams.get('state'))&&(!url.searchParams.get('owner')||row.owner_id===url.searchParams.get('owner'))&&(!url.searchParams.get('capability')||row.capability===url.searchParams.get('capability'))&&(!url.searchParams.get('department')||row.department_id===url.searchParams.get('department'))&&(!url.searchParams.get('project')||row.project_id===url.searchParams.get('project'))&&(!url.searchParams.get('query')||row.title.includes(url.searchParams.get('query')!)));
  return {items,summary:items.map(row=>({state:row.state,count:1})),total:items.length,zones:['sandbox'],observedAt:Date.now(),coverage:'SERVER_TASKS_ONLY'};
 }
 if(url.pathname==='/management-tasks/options'||url.pathname==='/management-tasks/scope-options')return {departments,projects,zones:['sandbox'],scopeVersion:0};
 if(url.pathname==='/management-tasks/batch-preview')return {action:body.action,items:body.tasks.map((id:string)=>{const row=data.find(row=>row.id===id);return row?.state==='RUNNING'?{taskId:id,title:row.title,allowed:true,expectedToken:'a'.repeat(64),effect:'只改变后续调度；取消后实际停止仍需核对。'}:{taskId:id,allowed:false,reason:'STATE_CHANGED'};})};
 if(url.pathname==='/management-tasks/batches'){if(!batchReceipts.has(key!)){const items=body.items.map((item:any)=>{const row=data.find(row=>row.id===item.taskId)!;if(body.action==='CANCEL')row.state='CANCELLED';else row.dispatch_paused=body.action==='PAUSE';return {taskId:row.id,title:row.title,state:'DONE'};});batchReceipts.set(key!,{requestKey:key,action:body.action,items,state:'COMPLETED',physicalStopConfirmed:false});}if(mode.value==='lost-command'&&!lostBatches.has(key!)){lostBatches.add(key!);throw new BusinessError('HTTP_503',503);}return batchReceipts.get(key!);}
 if(url.pathname.startsWith('/management-tasks/batches/'))return batchReceipts.get(url.pathname.split('/').at(-1)!)||Promise.reject(new BusinessError('NOT_FOUND',404));
 if(url.pathname.startsWith('/management-tasks/content-access/')){if(method==='PUT')contentPolicy={readInput:body.readInput,readArtifacts:body.readArtifacts,version:body.expectedVersion+1};return contentPolicy;}
 if(url.pathname==='/management-tasks/attribution-members')return {items:[{id:'member-one',display_name:'陈晓',zones:['sandbox']}],nextCursor:''};
 if(url.pathname.startsWith('/management-tasks/member-contexts/')){if(method==='PUT')memberAssignment={departmentId:body.departmentId,version:body.expectedVersion+1};return memberAssignment;}
 if(url.pathname==='/management-tasks/scope-members')return {items:[{id:'admin',display_name:'测试管理员'}],nextCursor:''};
 if(url.pathname.startsWith('/management-tasks/scopes/')){if(method==='PUT')policy={...body,version:policy.version+1};return policy;}
 if(url.pathname==='/management-tasks/departments'){departments.push({id:'new-dept',...body});return {id:'new-dept'};}
 const id=url.pathname.split('/')[2];const row=data.find(row=>row.id===id);
 if(!row)throw new BusinessError('NOT_FOUND',404);
 if(url.pathname.endsWith('/workflow'))return row.capability==='report.annual.v1'?{kind:'ANNUAL_REPORT',state:'WAITING',executionState:row.state,validation:'NOT_CONFIRMED',delivery:'NOT_CONFIRMED',nextStep:'候选产出不能直接视为核验通过或正式交付；后续仍由原年报流程核验。',contentIncluded:false}:{kind:'MAINTENANCE',state:'IN_PROGRESS',validation:'NOT_CONFIRMED',delivery:'NOT_CONFIRMED',automated:true,requestedTaskIsCurrent:true,stages:[{id:'stage-one',stage:'CODE_REVIEW',state:'READY',attempts:0,currentCandidate:true}],jobs:[],nextStep:'由后端独立审查、自动验收和受控交付；出现未知执行时保留核对，不绕过原流程。',contentIncluded:false};
 if(url.pathname.endsWith('/content-capabilities'))return contentPolicy;
 if(url.pathname.endsWith('/input')){if(!contentPolicy.readInput)throw new BusinessError('FORBIDDEN',403);return {input_text:'<script>这里是原始文本，不执行</script>\n仅供隔离验收。'};}
 if(url.pathname.endsWith('/artifacts')){if(!contentPolicy.readArtifacts)throw new BusinessError('FORBIDDEN',403);return {items:[{artifact_id:'fixture-file',name:'报告原始文件.txt',version:1,byte_size:12}],hasMore:false,nextArtifact:'',nextVersion:0};}
 if(url.pathname.endsWith('/context-options'))return {departments,projects};
 if(url.pathname.endsWith('/context')){Object.assign(row,{department_id:body.departmentId,department_name:departments.find(x=>x.id===body.departmentId)?.name||'归属未确认',project_id:body.projectId,project_name:projects.find(x=>x.id===body.projectId)?.name||'归属未确认',context_version:row.context_version+1});return {version:row.context_version};}
 if(url.pathname.endsWith('/preview'))return {task:row,action:body.action,expectedToken:'a'.repeat(64),effect:body.action==='CANCEL'?'请求取消，不保证实际进程已停止。':'修改后续调度，正在执行的步骤可以继续完成。'};
 if(url.pathname.endsWith('/commands')){if(!receipts.has(key!)){if(body.action==='CANCEL')row.state='CANCELLED';else row.dispatch_paused=body.action==='PAUSE';receipts.set(key!,{submitted:true,physicalStopConfirmed:false});}if(mode.value==='lost-command'&&!lost.has(key!)){lost.add(key!);throw new BusinessError('HTTP_503',503);}return receipts.get(key!);}
 if(url.pathname.includes('/commands/'))return receipts.get(url.pathname.split('/').at(-1)!)||Promise.reject(new BusinessError('NOT_FOUND',404));
 if(url.pathname.endsWith('/events'))return {items:[{id:1,event_type:'TASK_CREATED',created_at:row.created_at}]};
 return {...row,creationAttribution:[{department_name:row.department_name,department_basis:'LOCAL_MEMBER_ASSIGNMENT',project_name:row.project_name,project_basis:'SPECIALIZED_TASK_RESOURCE',captured_at:row.created_at}],placements:{items:[{attempt_id:'attempt-1',device_id:'device-1',device_name:'测试 Mac 终端',state:'RUNNING',epoch:1,current_attempt:true,project_id:row.project_id,pool_id:'测试执行池',writer_state:'NONE'}],hasMore:false},units:[{id:'unit-1',ordinal:0,state:'READY',attempts:0}],allowedActions:['READ_METADATA'],contentIncluded:false};
}
