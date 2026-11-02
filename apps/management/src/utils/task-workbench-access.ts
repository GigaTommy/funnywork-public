export function taskWorkbenchAccess(roles:string[],permissions:string[]) {
  const allowed=(key:string)=>permissions.includes('*:*:*')||permissions.includes(key);
  return {
    organization:roles.includes('ADMIN')&&allowed('funnyworker:task-center:read'),
    personal:roles.some(role=>role==='OPERATOR'||role==='VIEWER')&&allowed('funnyworker:tasks:read'),
    maintenance:roles.includes('OPERATOR')&&allowed('funnyworker:tasks:read'),
    annual:roles.some(role=>role==='OPERATOR'||role==='VIEWER')&&allowed('funnyworker:annual:read')
  };
}
