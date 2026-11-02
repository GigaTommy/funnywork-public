export function recoveryFailure(error:unknown,confirmed:boolean,draftAvailable:boolean){
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown;message?:unknown}:{};
 const clearSensitive=value.status===401||value.status===403||value.code==='SESSION_CHANGED'||value.message==='SESSION_CHANGED';
 if(confirmed)return {clearSensitive,message:clearSensitive?'操作已完成，但当前会话或权限已变化。请重新登录或刷新核对；无需重复提交。':'操作已完成，但后续记录读取失败。请刷新核对；无需重复提交。'};
 if(clearSensitive)return {clearSensitive,message:'会话或权限已变化，记录和材料草稿已隐藏并清空。请重新登录或核对当前授权。'};
 if(value.code==='RECOVERY_EVIDENCE_UNTRUSTED')return {clearSensitive,message:'签名来源核验未通过。请核对签发方配置、材料范围和有效期；任务仍未放行。'};
 if(value.code==='RECOVERY_CASE_CONFLICT')return {clearSensitive,message:'处置记录版本已变化，本次操作被拒绝。请刷新列表后重新选择记录。'+(draftAvailable?'重新选择原记录可恢复材料草稿。':'')};
 return {clearSensitive,message:'操作结果未确认，请刷新核对记录。'+(draftAvailable?'材料草稿已保留；结果未知的提交会复用原请求核对。':'')};
}
