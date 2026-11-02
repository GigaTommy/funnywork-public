import { getToken } from '@/utils/auth';
import { BusinessError } from './business-feedback';
const part = (value:any) => encodeURIComponent(String(value ?? ''));
export async function businessRequest(path:string, method='GET', body?:any, requestKey?:string) {
  const credential=getToken();
  if(!credential)throw new BusinessError('NOT_CONNECTED',401);
  const response=await fetch('/api/v1'+path,{method,redirect:'error',cache:'no-store',credentials:'omit',headers:{Authorization:'Bearer '+credential,'X-FunnyWorker-Management':'1','Content-Type':'application/json',...(requestKey?{'Idempotency-Key':requestKey}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  if(credential!==getToken())throw new BusinessError('SESSION_CHANGED');
  if(!response.ok){
    const detail=await response.json().catch(()=>null);
    throw new BusinessError(typeof detail?.code==='string'?detail.code:'HTTP_'+response.status,response.status);
  }
  const content=response.status===204?'':await response.text();
  if(credential!==getToken())throw new BusinessError('SESSION_CHANGED');
  return content?JSON.parse(content):null;
}
export async function businessApi(name:string,...args:any[]):Promise<any>{
 const [a,b,c,d]=args; const callGeneration=getToken();
  switch(name){
    case 'officeStatus':return businessRequest('/office-capabilities?agentId='+part(a)+'&zone='+part(b));
    case 'configureOffice':return businessRequest('/office-capabilities','POST',a);
    case 'mcpBindings':return businessRequest('/mcp-bindings?zone='+part(a)+'&after='+part(b));
    case 'mcpCandidates':return businessRequest('/mcp-bindings/candidates?zone='+part(a)+'&after='+part(b));
    case 'checkMcpBinding':return businessRequest('/mcp-bindings/'+part(a)+'/check?version='+part(b),'POST',{});
    case 'saveMcpBinding':return businessRequest('/mcp-bindings','PUT',a);
    case 'skills':return businessRequest('/skills?zone='+part(a)+'&after='+part(b));
    case 'publishSkill':return businessRequest('/skills','POST',a);
    case 'disableSkill':return businessRequest('/skills/'+part(a)+'/disable','POST',{});
    case 'agentSkills':return businessRequest('/agent-definitions/'+part(a)+'/skills?zone='+part(b));
    case 'bindSkill':return businessRequest('/agent-definitions/'+part(a)+'/skills/'+part(b),c?'PUT':'DELETE');
    case 'managedGateways':return businessRequest('/managed-gateways?zone='+part(a)+'&after='+part(b));
    case 'gatewayContracts':return businessRequest('/managed-gateways/contracts?zone='+part(a)+'&after='+part(b));
    case 'checkManagedGateway':return businessRequest('/managed-gateways/'+part(a)+'/check?version='+part(b),'POST',{});
    case 'saveManagedGateway':return businessRequest('/managed-gateways','PUT',a);
    case 'modelConnection':return businessRequest('/model-profiles/'+part(a)+'/connection');
    case 'saveModelConnection':return businessRequest('/model-profiles/'+part(a)+'/connection','PUT',b);
    case 'probeModel':return businessRequest('/model-profiles/'+part(a)+'/probe?inference='+part(b===true),'POST',{});
    case 'modelHistory':return businessRequest('/model-profiles/'+part(a)+'/history?before='+part(b||0));
    case 'restoreModelProfile':return businessRequest('/model-profiles/'+part(a)+'/restore','POST',b);
    case 'modelProfiles':return businessRequest('/model-profiles?zone='+part(a)+'&after='+part(b));
    case 'saveModelProfile':return businessRequest('/model-profiles'+(a?'/'+part(a):''),a?'PUT':'POST',b);
    case 'setPassword':return businessRequest('/principals/'+part(a.principalId)+'/password','PUT',{username:a.username,password:a.password});
    case 'createUser':return businessRequest('/users','POST',a);
    case 'linkIdentity':return businessRequest('/identity-links','PUT',a);
    case 'revokeIdentity':return businessRequest('/identity-links/revoke','POST',a);
    case 'clientPolicy':return businessRequest('/client-policy');
    case 'saveClientPolicy':return businessRequest('/client-policy','PUT',a);
    case 'metrics':return businessRequest('/tenant/scheduling-metrics');
    case 'nodes':return businessRequest('/nodes?limit=50&after='+part(a));
    case 'nodeAdmission':return businessRequest('/nodes/'+part(a)+'/admission','PUT',{paused:b});
    case 'publishAgent':return businessRequest('/agent-definitions','POST',a);
    case 'agentPage':return businessRequest('/agent-definitions/page?limit=50&after='+part(a)+'&capability='+part(b)+'&query='+part(c));
    case 'agentPolicy':return businessRequest('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b));
    case 'setAgentPolicy':return businessRequest('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b),'PUT',{maxConcurrency:c});
    case 'clearAgentPolicy':return businessRequest('/agent-definitions/'+part(a)+'/scheduling-policy?zone='+part(b),'DELETE');
    case 'computerControls':return businessRequest('/computer-controls?limit=50&after='+part(a)+'&nodeId='+part(b)+'&expiredOnly='+Boolean(c));
    case 'toolTemplates':return businessRequest('/tool-templates');
    case 'publishTemplate':return businessRequest('/tool-templates/'+part(a)+'/publish','POST',{version:b,zone:c});
    case 'agentTools':return businessRequest('/agent-definitions/'+part(a)+'/tools?zone='+part(b)+'&limit=50&after='+part(c));
    case 'disableTool':return businessRequest('/tool-versions/'+part(a)+'/disable','POST',{});
    case 'setAgentTool':return businessRequest('/agent-definitions/'+part(a)+'/tools/'+part(b),c?'PUT':'DELETE');
    case 'documentBindings':return businessRequest('/tasks/'+part(a)+'/documents');
    case 'documentToolCandidates':return businessRequest('/tasks/'+part(a)+'/documents/tool-candidates?after='+part(b));
    case 'documentSourceCandidates':return businessRequest('/tasks/'+part(a)+'/documents/source-candidates?afterSource='+part(b)+'&afterHash='+part(c));
    case 'bindDocument':return businessRequest('/tasks/'+part(a)+'/documents/'+part(b),'PUT',{sourceId:c,deploymentHash:d});
    case 'revokeDocument':return businessRequest('/tasks/'+part(a)+'/documents/'+part(b),'DELETE');
    case 'recoveryCases':return businessRequest('/recovery/cases?limit=50&zone='+part(a)+'&after='+part(b)+'&taskId='+part(c));
    case 'discoverRecovery':return businessRequest('/recovery/cases/discover?zone='+part(a),'POST',{});
    case 'claimRecovery':return businessRequest('/recovery/cases/'+part(a)+'/claim','POST',{expectedVersion:b});
    case 'recoveryEvidence':return businessRequest('/recovery/cases/'+part(a)+'/evidence?limit=50&after='+part(b));
    case 'recoveryEvidenceDetail':return businessRequest('/recovery/cases/'+part(a)+'/evidence/'+part(b));
    case 'verifyRecoveryEvidence':return businessRequest('/recovery/cases/'+part(a)+'/evidence/'+part(b)+'/verify','POST',{});
    case 'submitRecoveryEvidence':{
      const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(a.content));
      if(callGeneration!==getToken())throw new Error('SESSION_CHANGED');
      const sha256=Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('');
      return businessRequest('/recovery/cases/'+part(a.id)+'/evidence','POST',{expectedVersion:a.expectedVersion,requestKey:a.requestKey,kind:a.kind,content:a.content,sha256});
    }
    default:throw new Error('UNKNOWN_OPERATION');
  }
}
