import {describe,it,expect} from 'vitest';
import {renderToString} from 'vue/server-renderer';
import {importFeedback,importFeedbackContent} from '../import-feedback';
describe('user import feedback',()=>{
 it('escapes hostile account markup in the actual dialog content',async()=>{const html=await renderToString(importFeedbackContent('<img src=x onerror=alert(1)>'));expect(html).not.toContain('<img');expect(html).toContain('&lt;img');});
 it('retains untrusted markup as literal text while normalizing legacy line breaks',()=>{const value=importFeedback({code:200,msg:'完成<br/>账号 <img src=x onerror=alert(1)> <br>下一行'});expect(value.message).toBe('完成\n账号 <img src=x onerror=alert(1)> \n下一行');expect(value.succeeded).toBe(true);});
 it('does not treat an HTTP success carrying a business failure as completed',()=>{const value=importFeedback({code:500,msg:'成功 2 条，失败 1 条'});expect(value.succeeded).toBe(false);expect(value.message).toContain('部分记录可能已经保存');});
 it('treats malformed responses as unconfirmed',()=>{expect(importFeedback(null).succeeded).toBe(false);expect(importFeedback({code:'200'}).message).toContain('未收到有效');});
});
