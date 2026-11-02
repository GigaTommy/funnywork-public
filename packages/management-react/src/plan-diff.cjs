async function readPlan(call,id,version,active=()=>true){
 const items=[],dependencies=[];let after=-1,total;
 for(let page=0;page<20;page++){
  const value=await call('planSnapshot',id,version,after);if(!active())throw new Error('STALE_PLAN_READ');
  if(value.taskId!==id||value.planVersion!==version||value.scope!=='INPUTS_AND_DEPENDENCIES'||!Number.isInteger(value.totalSteps)||value.totalSteps<0||value.totalSteps>100||!Array.isArray(value.items)||value.items.length>5||!Array.isArray(value.dependencies)||typeof value.hasMore!=='boolean'||(total!==undefined&&value.totalSteps!==total))throw new Error('INVALID_PLAN_PAGE');
  total=value.totalSteps;
  for(const item of value.items){if(!item||item.ordinal!==items.length||typeof item.unitId!=='string'||!item.unitId||typeof item.input!=='string'||item.input.length>10000||!Number.isInteger(item.createdInVersion)||item.createdInVersion<1||item.createdInVersion>version)throw new Error('INVALID_PLAN_PAGE');items.push(item);}
  dependencies.push(...value.dependencies);if(dependencies.length>1000)throw new Error('INVALID_PLAN_PAGE');
  if(!value.hasMore){if(items.length!==total)throw new Error('INCOMPLETE_PLAN');return {items,dependencies};}
  if(!value.items.length||value.nextAfter!==items.length-1||value.nextAfter<=after||items.length>=total)throw new Error('INVALID_PLAN_PAGE');
  after=value.nextAfter;
 }
 throw new Error('INCOMPLETE_PLAN');
}
function index(plan){
 const result=new Map();for(const item of plan.items){if(result.has(item.unitId))throw new Error('INVALID_PLAN');result.set(item.unitId,{...item,predecessors:[]});}
 const seen=new Set();for(const edge of plan.dependencies){
  if(!Number.isInteger(edge.predecessor)||!Number.isInteger(edge.successor)||edge.predecessor<0||edge.successor<0||edge.predecessor>=plan.items.length||edge.successor>=plan.items.length||!['SUCCEEDED','OUTPUT_AVAILABLE'].includes(edge.completion))throw new Error('INVALID_PLAN');
  const key=edge.predecessor+':'+edge.successor;if(seen.has(key))throw new Error('INVALID_PLAN');seen.add(key);
  result.get(plan.items[edge.successor].unitId).predecessors.push({unitId:plan.items[edge.predecessor].unitId,ordinal:edge.predecessor,completion:edge.completion});
 }
 for(const value of result.values())value.predecessors.sort((a,b)=>a.ordinal-b.ordinal);
 return result;
}
function comparePlans(before,after){
 const left=index(before),right=index(after),changes=[];let unchanged=0;
 for(const [id,value] of left){const next=right.get(id);if(!next)changes.push({kind:'removed',before:value});
  else if(value.input!==next.input||value.ordinal!==next.ordinal||JSON.stringify(value.predecessors)!==JSON.stringify(next.predecessors))changes.push({kind:'changed',before:value,after:next});else unchanged++;
 }
 for(const [id,value] of right)if(!left.has(id))changes.push({kind:'added',after:value});
 return {changes,unchanged};
}
module.exports={readPlan,comparePlans};
