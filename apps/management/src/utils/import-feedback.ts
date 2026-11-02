import {h} from 'vue';
/** Import summaries are untrusted text. Only legacy line-break markers are normalized. */
export function importFeedback(response: unknown) {
 const body=response&&typeof response==='object'?response as Record<string,unknown>:{};
 const succeeded=body.code===200;
 const detail=typeof body.msg==='string'?body.msg.replace(/<br\s*\/?\s*>/gi,'\n'):'未收到有效的导入结果，请刷新列表核对。';
 return {succeeded,title:succeeded?'导入完成':'导入结果需核对',message:succeeded?detail:detail+'\n部分记录可能已经保存，请核对列表后处理失败记录，不要直接重复导入全部数据。'};
}

export function importFeedbackContent(message:string) {
 return h('div',{style:{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:'60vh',overflow:'auto'}},message);
}
