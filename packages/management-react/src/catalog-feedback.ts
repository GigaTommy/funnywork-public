/** Safe capability-catalog feedback; raw service messages may contain private details. */
export function catalogOperationError(error:unknown):string{
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown}:{};
 const messages:Record<string,string>={
  SKILL_TOO_LARGE:'工作指引超过 8000 字节限制，本次未发布。请缩短内容；当前发布草稿已保留。',
  SKILL_LIMIT:'该能力的默认指引已达到上限，本次未绑定。请先核对已有指引。',
  MCP_SCHEMA_CHANGED:'远端工具名称或参数契约不匹配，请核对已发布版本。',
  MCP_CHECK_FAILED:'连接检查失败，请核对接口地址、允许名单和服务状态。',
  MCP_CREDENTIAL_UNAVAILABLE:'服务端凭证引用不可用，请由部署管理员检查。',
  MCP_VERSION_CONFLICT:'绑定版本已变化，本次未保存。请刷新后核对，当前草稿已保留。',
  TOOL_DISABLED:'工具版本已停用，不能启用绑定或检查连接。'
 };
 if(typeof value.code==='string'&&messages[value.code])return messages[value.code];
 if(value.status===401||value.status===403||value.code==='SESSION_CHANGED')return '本次操作被拒绝，请核对登录、维护权限及工作区域授权；旧配置和草稿已清除，请重新读取。';
 if(value.status===409)return '记录已变化或版本已存在，本次未提交。请重新读取后核对；当前草稿已保留。';
 if(value.status===400||value.status===422)return '本次操作未通过校验，请核对必填项及配置；当前草稿已保留。';
 return '操作结果未确认，请核实当前权限、版本和服务端配置后刷新；当前草稿已保留。';
}
