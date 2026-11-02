/** Describe supported result contracts without claiming generated output is verified business success. */
export function describeTaskResult(value: unknown) {
 const result=value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
 if(result.kind==='ANNUAL_REPORT')return {kind:'annual',title:'年度经营报告',note:result.businessVerified===true?'后端独立核验通过，已自动交付。':'候选未通过核验，未发布正式文件。',fields:[['交付状态',result.outcome==='DELIVERED'?'已交付':'核验未通过']]};
 if(result.kind==='REPOSITORY_MAINTENANCE')return {kind:'maintenance',title:'仓库维护',note:'在问题详情中查看候选、检查及最终交付状态。',fields:[['执行结果',result.outcome==='CANDIDATE_READY'?'候选已提交':String(result.outcome||'需处理')]]};
 if(result.kind==='MODEL_OUTPUT'&&typeof result.artifactId==='string')return {kind:'model',title:'模型产出',note:'内容已生成，尚未经过业务确认。',fields:[]};
 if(Number.isSafeInteger(result.codePoints)&&Number.isSafeInteger(result.lines)&&Number(result.codePoints)>=0&&Number(result.lines)>=0)return {kind:'stats',title:'文本统计',note:'字符数按 Unicode 码点统计。',fields:[['字符数',String(result.codePoints)],['行数',String(result.lines)]]};
 if(result.reconciled===true||typeof result.businessVerified==='boolean'){
  const labels:Record<string,string>={CONFIRMED:'已确认',REJECTED:'已拒绝',UNKNOWN:'结果待核对',APPLIED:'已应用',NOT_APPLIED:'未应用'};
  const state=typeof result.state==='string'?result.state:'';
  return {kind:'receipt',title:'执行核对结果',note:result.businessVerified===true?'已通过业务凭据核对。':'请结合核对状态与关联恢复记录确认结果。',fields:state?[['核对状态',labels[state]||state]]:[]};
 }
 if(typeof value==='string')return {kind:'text',title:'结果正文',note:'',fields:[]};
 return {kind:'structured',title:'结构化结果',note:'此结果尚无专用展示格式，可展开技术明细查看完整内容。',fields:[]};
}

/** Model output is UTF-8 text. Reject malformed or oversized content rather than showing corrupted text. */
export function decodeTaskText(base64:unknown):string {
 if(typeof base64!=='string'||base64.length>349528)throw new Error('Invalid result content');
 const bytes=Uint8Array.from(atob(base64),c=>c.charCodeAt(0));
 if(bytes.length>262144)throw new Error('Result too large');
 return new TextDecoder('utf-8',{fatal:true}).decode(bytes);
}
