/* Shared by the browser and static publication build. No crawler-specific content. */
(() => {
  'use strict';
  // Protect the last meaningful unit even in browsers without text-wrap: balance.
  function keepHeadingPhrases(lang) {
    document.querySelectorAll('main h1,main h2,main h3').forEach(heading => {
      if (heading.closest('.hero')) return;
      const lines = heading.querySelectorAll('.headline-line');
      (lines.length ? [...lines] : [heading]).forEach(line => {
        line.querySelectorAll('.heading-tail').forEach(tail => tail.replaceWith(...tail.childNodes));
        if (line.children.length) return;
        const text = line.textContent;
        const match = lang === 'en' ? /\S+\s+\S+\s*$/.exec(text) : /[\u3400-\u9fff]{2}\s*$/.exec(text);
        if (!match) return;
        const tail = document.createElement('span');
        tail.className = 'heading-tail';
        tail.textContent = match[0];
        line.replaceChildren(document.createTextNode(text.slice(0, match.index)), tail);
      });
    });
  }
  const publishedDocuments = {}; // Filled from repository license files during publication.
  const publishedMedia = {}; // Filled by the static publication build.
  window.fwMediaAssets = publishedMedia;
  window.fwApplyImage = (img, name) => {
    const asset=publishedMedia[name];
    if (!asset) return;
    img.src=asset.src;
    if (asset.srcset) { img.srcset=asset.srcset; img.sizes=img.closest('.hero')?'(max-width: 600px) calc(100vw - 56px), (max-width: 1100px) 48vw, 620px':'(max-width: 600px) calc(100vw - 40px), 850px'; }
  };
  const pages = {
    index: ["AI 工作助手：数据分析、图表与多端协同", "AI assistant for analysis and work across devices", "用 FunnyWork 在电脑上整理数据、制作图表，在手机上查看任务进展和结果。", "Use FunnyWork to organize data and create charts on desktop. Check task progress and results on your phone."],
    solutions: ["AI 工作助手的功能与配置", "AI assistant features and setup", "了解如何用 FunnyWork 处理任务，以及电脑和手机如何配合使用。", "See how to use FunnyWork for your tasks and work across desktop and phone."],
    'quarterly-review': ['AI 经营分析示例：从销售数据到季度复盘', 'AI quarterly review: from sales figures to charts', 'FunnyWork 季度复盘演示：汇总广州、深圳、佛山三地营收，比较目标与实际，生成图表并在手机查阅。明确示例数据与计算口径。', 'A FunnyWork demonstration of regional revenue analysis, target attainment charts and mobile review, with sample-data disclosure and calculation methods.'],
    security: ['安全与架构：企业部署、模型与权限', 'Security and architecture: deployment, models and permissions', '了解 FunnyWork 企业部署、模型连接、工作区权限和审批边界，以及公共服务与企业内部署的区别。', 'Learn about FunnyWork deployment, model connections, workspace permissions and approval boundaries, and how public services differ from an enterprise deployment.'],
    privacy: ["隐私与网站说明", "Privacy and website information", "FunnyWork 网站运营、隐私与数据处理、功能运行条件、演示性能与服务范围说明。", "Information about FunnyWork website operation, privacy, data processing, operating requirements, demonstration performance and scope of service."],
    guide: ['安装与使用指南', 'Installation and usage guide', '了解如何安装 FunnyWork、登录客户端、查看电脑任务和下载图表。', 'Learn how to install FunnyWork, sign in, review desktop tasks on your phone and download charts.'],
    download: ['下载与平台支持', 'Downloads and platform availability', '查看 FunnyWork 当前可用的安装包、系统要求、平台开放状态与下载核验说明。', 'Check available FunnyWork installers, system requirements, platform availability and download verification information.']
  };
  const slug = document.body.dataset.searchPage || document.body.dataset.page || 'index';
  const route = (key, lang) => key === 'index' ? (lang === 'en' ? '/index.en.html' : '/') : key === 'download' && lang === 'zh' ? '/download/' : '/' + key + (lang === 'en' ? '.en' : '') + '.html';
  const meta = (key, value, property = false) => {
    const attr = property ? 'property' : 'name';
    let el = document.head.querySelector(`meta[${attr}="${key}"]`);
    if (!el) { el = document.createElement('meta'); el.setAttribute(attr, key); document.head.append(el); }
    el.content = value;
  };
  const link = (rel, href, lang) => {
    let el = document.head.querySelector(`link[rel="${rel}"]${lang ? `[hreflang="${lang}"]` : ''}`);
    if (!el) { el = document.createElement('link'); el.rel = rel; if (lang) el.hreflang = lang; document.head.append(el); }
    el.href = href;
  };
  window.fwSetSearchMetadata = lang => {
    const en = lang === 'en', copy = pages[slug];
    if (!copy) return;
    // Publication builds set the origin explicitly. Never derive public metadata from query parameters.
    const origin = document.documentElement.dataset.siteOrigin || location.origin;
    // Both languages use the official website as their canonical origin.
    const primary = () => 'https://funnywork.funnypixelsapp.com';
    const url = primary(lang) + route(slug, lang);
    const title = 'FunnyWork — ' + copy[en ? 1 : 0], description = copy[en ? 3 : 2];
    document.title = title;
    meta('description', description);
    meta('robots', 'index,follow,max-image-preview:large');
    meta('og:type', 'website', true); meta('og:site_name', 'FunnyWork', true);
    meta('og:title', title, true); meta('og:description', description, true); meta('og:url', url, true);
    meta('og:locale', en ? 'en_US' : 'zh_CN', true);
    meta('og:image', origin + (publishedMedia['media/product/revenue-trend'+(en?'-en':'')+'.png']?.original || '/media/product/revenue-trend'+(en?'-en':'')+'.png'), true);
    meta('twitter:card', 'summary_large_image');
    link('canonical', url);
    link('alternate', primary('zh') + route(slug, 'zh'), 'zh-CN');
    link('alternate', primary('en') + route(slug, 'en'), 'en');
    link('alternate', primary('zh') + route(slug, 'zh'), 'x-default');
    document.querySelectorAll('[data-home]').forEach(a => { a.href = route('index', lang) + '?lang=' + lang + (a.dataset.home ? '#' + a.dataset.home : ''); });
    document.querySelectorAll('[data-legal]').forEach(a => { a.href = route(a.dataset.legal.replace('.html', ''), lang) + '?lang=' + lang; });
    document.querySelectorAll('[data-search-link]').forEach(a => { a.href = route(a.dataset.searchLink, lang); a.textContent = a.dataset.searchLink === 'solutions' ? (en ? 'Features & setup' : '功能与配置') : a.dataset.searchLink === 'guide' ? (en ? 'Usage guide' : '使用指南') : (en ? 'Quarterly review example' : '季度复盘示例'); });
    document.querySelectorAll('[data-case-media]').forEach(img => { img.src = '/media/product/' + img.dataset.caseMedia + (en ? '-en' : '') + '.png?v=gigi-20261019'; img.alt = en ? 'Sample regional quarterly revenue trend chart' : '示例：三地季度营收趋势'; window.fwApplyImage(img,'media/product/'+img.dataset.caseMedia+(en?'-en':'')+'.png'); });
    document.querySelectorAll('[data-download]').forEach(a => { a.href = route('download', lang) + '?lang=' + lang; });
    document.querySelectorAll('[data-page]').forEach(a => {
      const target = a.dataset.page;
      if (target === '/' || target === '/privacy.html' || target === '/security.html') a.href = route(target === '/' ? 'index' : target.slice(1, -5), lang) + '?lang=' + lang;
    });
    document.querySelectorAll('[data-license-document]').forEach(a => { const path = publishedDocuments[a.dataset.licenseDocument]; if(path) a.href = path; });
    document.querySelectorAll('[data-license]').forEach(a => { a.href = route('privacy', lang) + '?lang=' + lang + '#licensing'; a.textContent = en ? 'Licensing' : '软件许可与商业授权'; });
    document.querySelectorAll('[data-locale-link]').forEach(a => { a.href = route(slug, en ? 'zh' : 'en'); a.textContent = en ? '中文' : 'English'; a.hreflang = en ? 'zh-CN' : 'en'; });
    const node = document.getElementById('search-structured-data') || document.head.appendChild(Object.assign(document.createElement('script'), { id: 'search-structured-data', type: 'application/ld+json' }));
    const graph = [
      { '@type': 'WebSite', '@id': origin + '/#website', name: 'FunnyWork', url: origin + '/', inLanguage: ['zh-CN', 'en'] },
      { '@type': 'WebPage', '@id': url + '#webpage', url, name: title, description, inLanguage: en ? 'en' : 'zh-CN', isPartOf: { '@id': origin + '/#website' }, about: { '@id': 'https://funnywork.funnypixelsapp.com/#software' } },
      { '@type': 'SoftwareApplication', '@id': 'https://funnywork.funnypixelsapp.com/#software', name: 'FunnyWork', applicationCategory: 'BusinessApplication', operatingSystem: ['macOS 13 or later', 'Windows', 'Android 10 or later', 'iOS 17 or later'], url: primary(lang) + route('index', lang), license: new URL(publishedDocuments.LICENSE || 'https://github.com/GigaTommy/funnywork-public/blob/main/LICENSE', primary()).href, description: en ? pages.index[3] : pages.index[2] }
    ];
    node.textContent = JSON.stringify({ '@context': 'https://schema.org', '@graph': graph });
    // Each language has a crawlable static URL; query links remain backwards compatible.
    const current = new URL(location.href);
    current.pathname = route(slug, lang);
    if (current.searchParams.has('lang')) current.searchParams.set('lang', lang);
    history.replaceState(null, '', current);
    keepHeadingPhrases(lang);
  };
  const requested = new URLSearchParams(location.search).get('lang');
  window.fwSetSearchMetadata(requested === 'en' || requested === 'zh' ? requested : document.documentElement.lang === 'en' ? 'en' : 'zh');
})();
