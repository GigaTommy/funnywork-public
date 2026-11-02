/** Explain known probe rejections without exposing upstream or credential details. */
export function modelProfileProbeError(error: unknown): string {
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown}:{};
 if(value.code==='PROBE_HOST_DENIED')return '模型服务地址未列入服务端检查允许名单，本次未发送检查请求。请由部署管理员核对该服务地址。';
 if(value.code==='PROFILE_DISABLED')return '模型配置已停用，本次未发送检查请求。请刷新配置后核对启停状态。';
 if(value.status===401)return '登录已失效，检查未完成。请重新登录。';
 if(value.status===403)return '当前账号无权检查此模型配置，请核对角色与工作区域授权。';
 return '检查未完成，请核实服务端配置、权限和网络连接。';
}

/** A stale-version rejection is a known non-write, unlike a lost network response. */
export function modelProfileSaveError(error: unknown): string {
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown}:{};
 if(value.code==='PROFILE_VERSION_CONFLICT')return '本次未保存：配置版本已变化。草稿已保留，请刷新核对最新版本后重新编辑。';
 if(value.code==='PROFILE_VERSION_EXHAUSTED')return '本次未保存：配置版本已达到上限，请由部署管理员处理。草稿已保留。';
 if(value.status===401||value.status===403)return '本次未保存：登录已失效或没有维护权限。请核对账号与工作区域授权；草稿已保留。';
 if(value.status===400||value.status===422)return '本次未保存：配置未通过校验，请核对名称、模型、地址与区域；草稿已保留。';
 return '保存结果未确认，草稿已保留。请刷新核对当前版本后再操作。';
}
