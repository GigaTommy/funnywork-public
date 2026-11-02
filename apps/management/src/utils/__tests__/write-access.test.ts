import {expect,it} from 'vitest';
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {WriteAccess,WriteButton} from '../../../../../packages/management-react/src/write-access';
it('disables writes regardless of button wording while preserving ordinary read buttons',()=>{
 const html=renderToStaticMarkup(React.createElement(WriteAccess.Provider,{value:false},
  React.createElement(React.Fragment,null,
   React.createElement(WriteButton,null,'解除绑定'),React.createElement(WriteButton,null,'恢复继承租户限制'),React.createElement('button',null,'刷新'))));
 expect(html.match(/disabled=""/g)).toHaveLength(2);
 expect(html).toContain('<button>刷新</button>');
});
it('retains busy state even when write permission is present',()=>{
 const html=renderToStaticMarkup(React.createElement(WriteAccess.Provider,{value:true},React.createElement(WriteButton,{disabled:true},'保存')));
 expect(html).toContain('disabled=""');
});
