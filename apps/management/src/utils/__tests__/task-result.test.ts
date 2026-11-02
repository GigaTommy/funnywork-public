import {describe,it,expect} from 'vitest';
import {describeTaskResult,decodeTaskText} from '../task-result';
describe('task result presentation',()=>{
 it('keeps model output explicitly unverified',()=>{const view=describeTaskResult({kind:'MODEL_OUTPUT',artifactId:'a',reviewState:'UNREVIEWED'});expect(view.kind).toBe('model');expect(view.note).toContain('尚未经过业务确认');});
 it('describes actual text statistics including zero',()=>{expect(describeTaskResult({codePoints:0,lines:0}).fields).toEqual([['字符数','0'],['行数','0']]);expect(describeTaskResult({codePoints:-1,lines:0}).kind).toBe('structured');});
 it('does not label an unknown receipt as verified',()=>{expect(describeTaskResult({businessVerified:false,state:'UNKNOWN'}).fields).toEqual([['核对状态','结果待核对']]);expect(describeTaskResult({businessVerified:false}).note).not.toContain('已通过');});
 it('preserves Unicode and literal markup as text',()=>{const text='中文 🐾 <script>literal</script>';const bytes=new TextEncoder().encode(text);expect(decodeTaskText(btoa(String.fromCharCode(...bytes)))).toBe(text);});
 it('rejects malformed text and oversized payloads',()=>{expect(()=>decodeTaskText('/w==')).toThrow();expect(()=>decodeTaskText('!')).toThrow();expect(()=>decodeTaskText('a'.repeat(349529))).toThrow();});
 it('keeps unknown structures in the technical fallback',()=>{expect(describeTaskResult({unexpected:{data:1}}).kind).toBe('structured');expect(describeTaskResult('plain text').kind).toBe('text');});
});
