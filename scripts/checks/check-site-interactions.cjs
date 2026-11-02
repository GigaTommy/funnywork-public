#!/usr/bin/env node
// Source-level regressions; actual responsive layout and playback are checked in a browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const { JSDOM } = createRequire(path.join(root, 'apps/desktop/package.json'))('jsdom');
const site = path.join(root, 'designs/funnywork-site');
const script = fs.readFileSync(path.join(site, 'site.js'), 'utf8');
let checks = 0;

async function run() {
  for (const host of ['regional.example.invalid', 'funnywork.funnypixelsapp.com']) {
    for (const page of ['index.html', 'privacy.html', 'security.html']) {
      for (const locale of ['zh', 'en']) {
        const dom = new JSDOM(fs.readFileSync(path.join(site, page), 'utf8'), {
          url: `https://${host}/${page}?lang=${locale}`, runScripts: 'outside-only',
        });
        const { window: w } = dom;
        const d = w.document;
        let loads = 0, networkCalls = 0, plays = 0;
        w.fetch = () => { networkCalls++; throw Error('Unexpected network request'); };
        w.IntersectionObserver = class { observe() {} };
        w.HTMLMediaElement.prototype.pause = () => {};
        w.HTMLMediaElement.prototype.play = async () => { plays++; };
        w.HTMLElement.prototype.scrollIntoView = () => {};
        w.matchMedia = () => ({ matches: false });
        w.HTMLMediaElement.prototype.load = () => { loads++; };
        w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
        w.HTMLDialogElement.prototype.close = function () { this.open = false; };
        w.eval(script);
        for (const lang of [locale, locale === 'en' ? 'zh' : 'en', locale]) {
          d.querySelector(`[data-lang="${lang}"]`).click();
          assert.equal(d.documentElement.lang, lang === 'en' ? 'en' : 'zh-CN');
          assert.equal(d.querySelector('.brand').textContent.trim().replace(/\.$/, ''), 'FunnyWork');
          assert(!/FunnyWorker|暖橙官网预览|Warm orange preview|UAT/.test(d.body.textContent));
          const repository = d.querySelector('footer a[href="https://github.com/GigaTommy/funnywork-public"]');
          assert(repository);
          assert.equal(repository.textContent, 'GitHub · GigaTommy/funnywork-public');
          assert.equal(repository.target, '_blank');
          assert(repository.relList.contains('noopener') && repository.relList.contains('noreferrer'));
          assert(!/beian\.miit\.gov\.cn|ICP备/.test(d.querySelector('footer').outerHTML));
          for (const a of d.querySelectorAll('[data-legal],[data-home]')) {
            assert.equal(new URL(a.href).host, host, 'Navigation must stay on the current regional host');
            assert.equal(new URL(a.href).searchParams.get('lang'), lang);
          }
          if (lang === 'en') {
            for (const el of d.querySelectorAll('[data-t],[alt],[aria-label]')) {
              assert(!/[\u4e00-\u9fff]/.test((el.hasAttribute('data-t') ? el.textContent : '') + (el.getAttribute('alt') || '') + (el.getAttribute('aria-label') || '')), el.outerHTML);
            }
          }
          if (page === 'index.html') {
            assert.equal(d.querySelectorAll('img[src="assets/gigi-avatar.jpg"]').length,1,'Portrait belongs only in the support contact');
            assert.equal(d.querySelector('[data-t="salesPerson"]').textContent,lang==='en'?'Gigi · Customer Support':'珠珠 · 客服');
            assert(!/Gigi|珠珠/.test(d.querySelector('.case-section').textContent),'Case copy should focus on the workflow rather than repeat names');
            if(lang==='en')assert(d.querySelector('.output-copy h3').textContent.includes('organizing Reports'),'Heading clauses must preserve word spacing');
            const suffix = lang === 'en' ? '-en' : '';
            for (const a of d.querySelectorAll('a[data-image]')) {
              assert.equal(a.href, a.querySelector('img').src);
              assert(fs.existsSync(path.join(site, new URL(a.href).pathname.slice(1))));
            }
            for (const key of ['targets', 'revenue']) {
              d.querySelector(`[data-chart="${key}"]`).click();
              const expected = `${key === 'targets' ? 'target-completion' : 'revenue-trend'}${suffix}.png?v=gigi-20261019`;
              assert(d.getElementById('output-chart').src.endsWith(expected));
              const a = d.getElementById('output-link');
              a.click();
              assert(d.getElementById('image-dialog').open);
              assert.equal(d.getElementById('dialog-image').src, a.href);
              d.querySelector('[data-close="image-dialog"]').click();
              assert(!d.getElementById('image-dialog').open);
            }
            assert(d.querySelector('video source').src.endsWith(`iphone-chart-preview${suffix}.mp4?v=gigi-20261019`));
            assert(d.querySelector('video').poster.endsWith(`iphone-chart-reading${suffix}.png?v=gigi-20261019`));
            assert(d.querySelector('.tag').textContent.includes(lang === 'en' ? 'Sample data' : '示例数据'));
            const fallback=d.getElementById('video-fallback'), source=d.querySelector('video source');
            source.dispatchEvent(new w.Event('error'));assert(!fallback.hidden,'A failed video source must offer a way to continue');
            assert(d.getElementById('video-direct').href.endsWith(`iphone-chart-preview${suffix}.mp4?v=gigi-20261019`));
            d.querySelector('[data-chart="revenue"]').click();assert(!fallback.hidden,'Switching charts must not hide a video failure');
            const loadsBefore=loads,playsBefore=plays;d.getElementById('retry-video').click();
            assert(loads>loadsBefore&&plays>playsBefore,'Retry must reload and play the video');
            d.querySelector('video').dispatchEvent(new w.Event('error'));assert(!fallback.hidden);
            d.querySelector('video').dispatchEvent(new w.Event('playing'));assert(fallback.hidden);
            d.getElementById('watch-demo').click();assert.equal(w.location.hash,'#mobile');assert(plays>playsBefore+1,'Hero demo action must start playback');

          }
          checks++;
        }
        if (page === 'index.html') {
          assert(loads > 0, 'Changing language must reload the corresponding video');
          d.querySelector('[data-chart="targets"]').click();
          d.querySelector(`[data-lang="${locale === 'en' ? 'zh' : 'en'}"]`).click();
          assert.equal(d.querySelector('[data-chart="targets"]').getAttribute('aria-pressed'), 'true');
          const form = d.getElementById('inquiry-form');
          const company = d.getElementById('company'), email = d.getElementById('email'), scenario = d.getElementById('scenario');
          company.value = '   '; scenario.value = '   ';
          form.requestSubmit();
          assert(!d.getElementById('inquiry-dialog').open, 'Whitespace must not pass required-field validation');
          assert(scenario.validity.customError);
          d.querySelector(`[data-lang="${d.documentElement.lang === 'en' ? 'zh' : 'en'}"]`).click();
          assert(!scenario.validity.customError, 'Language changes must clear stale validation text');
          assert(!company.required, 'Name or team must be optional');
          scenario.value = '季度销售复盘';
          form.requestSubmit();
          assert(d.getElementById('inquiry-dialog').open, 'Feedback must work without a name or team');
          d.querySelector('[data-close="inquiry-dialog"]').click();
          assert.equal(new URL(form.action).protocol, 'mailto:', 'Without JavaScript, form data must not be submitted to the website');
          company.value = '南方业务团队'; company.dispatchEvent(new w.Event('input'));
          email.value = 'invalid';
          assert(!email.checkValidity());
          email.value = 'contact@example.com';
          scenario.value = '<img src=x onerror=alert(1)>季度复盘';
          form.requestSubmit();
          assert(d.getElementById('inquiry-dialog').open);
          assert(d.getElementById('draft-body').value.includes(scenario.value));
          assert(!d.getElementById('draft-body').querySelector('img'), 'Inquiry data must render as plain text');
          const mailto = new URL(d.getElementById('draft-mailto').href);
          assert.equal(mailto.protocol, 'mailto:');
          assert.equal(mailto.pathname, 'support@funnypixelsapp.com');
          assert(mailto.searchParams.get('body').includes('contact@example.com'));
          assert.equal(networkCalls, 0, 'Previewing an inquiry must not upload form contents');
          assert(!Object.values(w.localStorage).some(value => value.includes('contact@example.com')), 'Inquiry details must not persist');
          d.querySelector('[data-close="inquiry-dialog"]').click();
          let copied;
          Object.defineProperty(w.navigator, 'clipboard', { configurable: true, value: { writeText: async text => { copied = text; } } });
          d.getElementById('copy-email').click();
          await new Promise(resolve => setImmediate(resolve));
          assert.equal(copied, 'support@funnypixelsapp.com');
          d.getElementById('copy-draft').click();
          await new Promise(resolve => setImmediate(resolve));
          assert.equal(copied,d.getElementById('draft-body').value);
          assert(d.getElementById('copy-status').textContent);
          Object.defineProperty(w.navigator, 'clipboard', { configurable: true, value: { writeText: async () => { throw Error('Unavailable'); } } });
          d.getElementById('copy-email').click();
          await new Promise(resolve => setImmediate(resolve));
          assert(d.getElementById('copy-status').textContent.includes('support@funnypixelsapp.com'));
          d.getElementById('copy-draft').click();
          await new Promise(resolve => setImmediate(resolve));
          const draft=d.getElementById('draft-body');
          assert.equal(draft.selectionStart,0);assert.equal(draft.selectionEnd,draft.value.length,'Clipboard failure must leave a selectable draft');
          scenario.value='季度销售数据汇总与经营复盘。'.repeat(100);
          form.requestSubmit();
          assert(d.getElementById('draft-status').textContent);
          assert(!new URL(d.getElementById('draft-mailto').href).searchParams.has('body'),'Long drafts must use the copy/paste path instead of an oversized mailto URL');
          d.querySelector('[data-close="inquiry-dialog"]').click();
          const dialog=d.getElementById('image-dialog');dialog.getBoundingClientRect=()=>({left:20,right:120,top:20,bottom:120});dialog.showModal();
          dialog.dispatchEvent(new w.MouseEvent('click',{clientX:30,clientY:30,bubbles:true}));assert(dialog.open,'Clicking dialog padding must not dismiss it');
          dialog.dispatchEvent(new w.MouseEvent('click',{clientX:10,clientY:10,bubbles:true}));assert(!dialog.open,'Clicking outside the dialog must dismiss it');
          const video=d.querySelector('video');
          const key='fw-orange-video-'+(video.dataset.locale||'zh');w.localStorage.setItem(key,'3');video.currentTime=0;
          video.dispatchEvent(new w.Event('timeupdate'));assert.equal(w.localStorage.getItem(key),'3','Loading a source must not erase saved playback progress');
        }
        w.close();
      }
    }
  }
  console.log(`${checks} page/language round trips passed; host navigation, media, dialogs, inquiry validation/privacy, long-draft copying, dialog dismissal and video resume passed.`);
}
run().catch(error => { console.error(error); process.exitCode = 1; });
