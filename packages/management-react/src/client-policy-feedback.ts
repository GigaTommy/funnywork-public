/** Only interpret stable error codes; never display raw server messages. */
export function clientPolicySaveError(error: unknown): string {
 const value=error&&typeof error==='object'?error as {code?:unknown;status?:unknown}:{};
 if(value.code==='POLICY_VERSION_CONFLICT')return '目录版本已变化，本次未保存。请刷新核对最新版本；草稿会保留，可放弃后重新编辑。';
 if(value.code==='AGENT_REQUIRED')return '本次未保存：已发布条目必须绑定当前租户的已发布执行能力。请重新选择能力或将条目设为未发布。';
 if(value.code==='DUPLICATE_ENTRY')return '本次未保存：目录条目重复。请核对目录后重新提交。';
 if(value.status===401||value.status===403)return '本次未保存：登录已失效或没有维护权限。请重新登录或联系管理员；草稿已保留。';
 return '保存结果未确认，草稿已保留。请刷新核对当前版本后处理。';
}
