import React from 'react';
export function AdminHelp({children}:{children:React.ReactNode}){
 return <details className="admin-help"><summary>使用说明</summary><div>{children}</div></details>;
}
export function AdminTabs({items}:{items:{label:string;content:React.ReactNode}[]}){
 const [selected,setSelected]=React.useState(0);
 return <><div className="admin-tabs" role="tablist" aria-label="管理分类">{items.map((item,index)=><button key={item.label} id={'admin-tab-'+index} role="tab" aria-selected={selected===index} aria-controls="admin-tab-panel" onClick={()=>setSelected(index)}>{item.label}</button>)}</div><div id="admin-tab-panel" role="tabpanel" aria-labelledby={'admin-tab-'+selected} key={selected}>{items[selected].content}</div></>;
}
