/** Do not expose adapter addresses, credentials or raw service errors. */
export function gatewayOperationError(error:unknown,operation:'save'|'check'):string{
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown}:{};
 const suffix=operation==='save'?'本次未保存，当前草稿已保留。':'本次检查未完成。';
 const reasons:Record<string,string>={
  GATEWAY_VERSION_CONFLICT:'网关版本已变化，请刷新并核对最新配置。',
  INVALID_GATEWAY_ENDPOINT:'适配地址不符合部署允许名单或地址格式，请核对以 / 结束的 HTTPS 基础地址。',
  GATEWAY_BINDING_IMMUTABLE:'已登记的地址、区域、凭证引用和契约不能更换；迁移请登记新的网关编号。',
  STATIC_GATEWAY_ID_RESERVED:'该编号由部署配置占用，请使用其他网关编号。'
 };
 if(typeof value.code==='string'&&reasons[value.code])return reasons[value.code]+suffix;
 if(value.status===401||value.status===403||value.code==='SESSION_CHANGED')return '操作被拒绝，请核对登录、维护权限和工作区域授权；旧配置和草稿已清除，请重新读取。';
 if(value.status===400||value.status===422)return '配置未通过校验，请核对必填项、地址和凭证引用格式。'+suffix;
 return operation==='save'?'保存结果未确认，请刷新核实最新配置；当前草稿已保留。':'配置检查结果未确认，请核对连接、权限和最新版本后重试。';
}
