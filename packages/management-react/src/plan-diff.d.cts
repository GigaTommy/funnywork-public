export function readPlan(call:(name:string,...args:any[])=>Promise<any>,id:string,version:number,active?:()=>boolean):Promise<any>;
export function comparePlans(before:any,after:any):{changes:any[];unchanged:number};
