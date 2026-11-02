/** Known publication refusals are distinct from a lost response. Never expose raw errors. */
export function toolPublicationError(error: unknown): string {
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown;message?:unknown}:{};
 if(value.code==='TEMPLATE_VERSION_CONFLICT'||value.status===409||value.message==='HTTP_409')return '此版本与已有配置冲突或已停用，本次未发布。请选择新版本；当前发布草稿已保留。';
 if(value.status===401||value.status===403)return '本次未发布：登录已失效或没有发布权限。请核对账号和区域授权；当前发布草稿已保留。';
 if(value.status===400||value.status===422)return '本次未发布：模板、版本或区域未通过校验。当前发布草稿已保留。';
 return '发布结果未确认。当前发布草稿已保留，可用相同模板、版本和区域重试核实。';
}
