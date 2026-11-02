// Same-origin administration session; credentials never enter browser storage.
let credential = '';
let generation = 0;
const part = (value:any) => encodeURIComponent(String(value ?? ''));
async function request(path:string, method='GET', body?:any) {
  const run=generation;
  const response=await fetch('/api/v1'+path,{method,redirect:'error',cache:'no-store',credentials:'omit',headers:{Authorization:'Bearer '+credential,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  if(run!==generation)throw new Error('SESSION_CHANGED');
  if(!response.ok)throw new Error('HTTP_'+response.status);
  const content=response.status===204?'':await response.text();
  const value=content?JSON.parse(content):null;
  if(run!==generation)throw new Error('SESSION_CHANGED');
  return value;
}
export async function webApi(name:string,...args:any[]):Promise<any>{
  const [a,b,c,d]=args;
  if(name==='disconnect'){try{if(credential)await request('/auth/logout','POST',{});}finally{generation++;credential='';}return;}
  if(name==='connect'){
    const run=++generation;credential=b;
    try{const me=await request('/me');if(me.kind!=='USER'||!me.roles.includes('ADMIN'))throw new Error('HTTP_403');return me;}
    catch(e){if(run===generation)credential='';throw e;}
  }
  if(!credential)throw new Error('NOT_CONNECTED');
  const callGeneration=generation;
  switch(name){
    case 'officeStatus':return request('/office-capabilities?agentId='+part(a)+'&zone='+part(b));
    case 'configureOffice':return request('/office-capabilities','POST',a);
    case 'mcpBindings':return request('/mcp-bindings?zone='+part(a)+'&after='+part(b));
    case 'saveMcpBinding':return request('/mcp-bindings','PUT',a);
    case 'skills':return request('/skills?zone='+part(a)+'&after='+part(b));
    case 'publishSkill':return request('/skills','POST',a);
    case 'disableSkill':return request('/skills/'+part(a)+'/disable','POST',{});
    case 'agentSkills':return request('/agent-definitions/'+part(a)+'/skills?zone='+part(b));
    case 'bindSkill':return request('/agent-definitions/'+part(a)+'/skills/'+part(b),c?'PUT':'DELETE');
    case 'managedGateways':return request('/managed-gateways?zone='+part(a)+'&after='+part(b));
    case 'saveManagedGateway':return request('/managed-gateways','PUT',a);
    case 'modelConnection':return request('/model-profiles/'+part(a)+'/connection');
    case 'saveModelConnection':return request('/model-profiles/'+part(a)+'/connection','PUT',b);
    case 'probeModel':return request('/model-profiles/'+part(a)+'/probe?inference='+part(b===true),'POST',{});
    case 'modelHistory':return request('/model-profiles/'+part(a)+'/history?before='+part(b||0));
    case 'restoreModelProfile':return request('/model-profiles/'+part(a)+'/restore','POST',b);
    case 'modelProfiles':return request('/model-profiles?zone='+part(a)+'&after='+part(b));
    case 'saveModelProfile':return request('/model-profiles'+(a?'/'+part(a):''),a?'PUT':'POST',b);
    case 'setPassword':return request('/principals/'+part(a.principalId)+'/password','PUT',{username:a.username,password:a.password});
    case 'createUser':return request('/users','POST',a);
    case 'linkIdentity':return request('/identity-links','PUT',a);
    case 'revokeIdentity':return request('/identity-links/revoke','POST',a);
    case 'clientPolicy':return request('/client-policy');
    case 'saveClientPolicy':return request('/client-policy','PUT',a);
    case 'metrics':return request('/tenant/scheduling-metrics');
    case 'nodes':return request('/nodes?limit=50&after='+part(a));
    case 'nodeAdmission':return request('/nodes/'+part(a)+'/admission','PUT',{paused:b});
    case 'agentPage':return request('/agent-definitions/page?limit=50&after='+part(a)+'&capability='+part(b)+'&query='+part(c));
    case 'agentPolicy':return request('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b));
    case 'setAgentPolicy':return request('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b),'PUT',{maxConcurrency:c});
    case 'clearAgentPolicy':return request('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b),'DELETE');
    case 'computerControls':return request('/computer-controls?limit=50&after='+part(a)+'&nodeId='+part(b)+'&expiredOnly='+Boolean(c));
    case 'toolTemplates':return request('/tool-templates');
    case 'publishTemplate':return request('/tool-templates/'+part(a)+'/publish','POST',{version:b,zone:c});
    case 'agentTools':return request('/agent-definitions/'+part(a)+'/tools?zone='+part(b)+'&limit=50&after='+part(c));
    case 'disableTool':return request('/tool-versions/'+part(a)+'/disable','POST',{});
    case 'setAgentTool':return request('/agent-definitions/'+part(a)+'/tools/'+part(b),c?'PUT':'DELETE');
    case 'documentBindings':return request('/tasks/'+part(a)+'/documents');
    case 'bindDocument':return request('/tasks/'+part(a)+'/documents/'+part(b),'PUT',{sourceId:c,deploymentHash:d});
    case 'revokeDocument':return request('/tasks/'+part(a)+'/documents/'+part(b),'DELETE');
    case 'recoveryCases':return request('/recovery/cases?limit=50&zone='+part(a)+'&after='+part(b));
    case 'discoverRecovery':return request('/recovery/cases/discover?zone='+part(a),'POST',{});
    case 'claimRecovery':return request('/recovery/cases/'+part(a)+'/claim','POST',{expectedVersion:b});
    case 'recoveryEvidence':return request('/recovery/cases/'+part(a)+'/evidence?limit=50&after='+part(b));
    case 'recoveryEvidenceDetail':return request('/recovery/cases/'+part(a)+'/evidence/'+part(b));
    case 'verifyRecoveryEvidence':return request('/recovery/cases/'+part(a)+'/evidence/'+part(b)+'/verify','POST',{});
    case 'submitRecoveryEvidence':{
      const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(a.content));
      if(callGeneration!==generation)throw new Error('SESSION_CHANGED');
      const sha256=Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('');
      return request('/recovery/cases/'+part(a.id)+'/evidence','POST',{expectedVersion:a.expectedVersion,requestKey:a.requestKey,kind:a.kind,content:a.content,sha256});
    }
    default:throw new Error('UNKNOWN_OPERATION');
  }
}
