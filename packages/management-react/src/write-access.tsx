import React, {createContext, useContext} from 'react';
/** The standalone legacy admin retains its existing server-enforced behavior. */
export const WriteAccess = createContext(true);
export function WriteButton(props: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const allowed = useContext(WriteAccess);
  return <button {...props} disabled={!allowed || props.disabled} title={allowed ? props.title : '当前账号仅可查看，请联系管理员配置操作权限。'} />;
}
export function WriteForm(props: React.FormHTMLAttributes<HTMLFormElement>) {
  const allowed = useContext(WriteAccess);
  return <form {...props} onSubmit={event => {
    if (!allowed) { event.preventDefault(); return; }
    props.onSubmit?.(event);
  }} />;
}
