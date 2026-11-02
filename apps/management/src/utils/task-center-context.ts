/** URL context contains query metadata only; every read still requires server authorization. */
export type TaskCenterContext = {query:string;owner:string;state:string;zone:string;capability:string;department:string;project:string;from:string;until:string;task:string};
const fields={query:['tc_q',160],owner:['tc_owner',64],state:['tc_state',40],zone:['tc_zone',64],capability:['tc_purpose',80],department:['tc_department',64],project:['tc_project',64],from:['tc_from',16],until:['tc_until',16],task:['tc_task',64]} as const;
export function readTaskCenterContext(query:Record<string,unknown>):TaskCenterContext {
 const result={} as TaskCenterContext;
 for(const key of Object.keys(fields) as (keyof TaskCenterContext)[]){const [name,max]=fields[key],value=query[name];if(value!=null&&(typeof value!=='string'||value.length>max))throw new Error('任务链接中的筛选条件无效，请清空筛选后重试。');result[key]=typeof value==='string'?value:'';}
 for(const key of ['from','until'] as const){const value=result[key];if(value&&!/^\d+$/.test(value)||value&&!Number.isSafeInteger(Number(value)))throw new Error('任务链接中的时间无效。');result[key]=value&&Number(value)>0?String(Number(value)):'';}
 if(Number(result.until)>0&&Number(result.from)>=Number(result.until))throw new Error('任务链接中的结束时间应晚于开始时间。');
 return result;
}
export function writeTaskCenterContext(query:Record<string,any>,context:TaskCenterContext):Record<string,any> {
 const result={...query};for(const key of Object.keys(fields) as (keyof TaskCenterContext)[]){const name=fields[key][0];delete result[name];if(context[key]&&context[key]!=='0')result[name]=context[key];}return result;
}
export function taskCenterContextKey(context:TaskCenterContext){return JSON.stringify(Object.keys(fields).map(key=>context[key as keyof TaskCenterContext]));}
