import DOMPurify from 'dompurify';

/** Sanitize persisted editor HTML, retaining formatting, images and supported video embeds. */
export function sanitizeRichText(html: string): string {
  const fragment = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p',
      'br',
      'strong',
      'b',
      'em',
      'i',
      'u',
      's',
      'strike',
      'sub',
      'sup',
      'blockquote',
      'pre',
      'code',
      'ol',
      'ul',
      'li',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'span',
      'div',
      'a',
      'img',
      'iframe',
      'hr',
      'table',
      'thead',
      'tbody',
      'tfoot',
      'tr',
      'th',
      'td',
      'caption',
      'colgroup',
      'col'
    ],
    RETURN_DOM_FRAGMENT: true,
    FORBID_ATTR: ['id', 'name', 'contenteditable', 'autofocus', 'tabindex']
  });
  for (const element of fragment.querySelectorAll('[class]')) {
    const classes = [...element.classList].filter((name) =>
      /^(?:ql-align-(?:center|right|justify)|ql-direction-rtl|ql-indent-[1-8]|ql-size-(?:small|large|huge)|ql-font-(?:serif|monospace)|ql-code-block(?:-container)?|ql-syntax|ql-video|ql-ui)$/.test(
        name
      )
    );
    if (classes.length) element.setAttribute('class', classes.join(' '));
    else element.removeAttribute('class');
  }
  for (const element of fragment.querySelectorAll<HTMLElement>('[style]')) {
    const formatted = document.createElement('span');
    for (const property of [
      'color',
      'background-color',
      'font-family',
      'font-size',
      'font-weight',
      'font-style',
      'text-decoration',
      'text-align',
      'direction'
    ]) {
      formatted.style.setProperty(property, element.style.getPropertyValue(property));
    }
    if (formatted.style.cssText) element.setAttribute('style', formatted.style.cssText);
    else element.removeAttribute('style');
  }
  for (const frame of fragment.querySelectorAll('iframe')) {
    try {
      const source = new URL(frame.getAttribute('src') || '');
      const allowed =
        source.protocol === 'https:' &&
        !source.username &&
        !source.password &&
        !source.port &&
        ((source.hostname === 'www.youtube.com' && source.pathname.startsWith('/embed/')) ||
          (source.hostname === 'player.vimeo.com' && source.pathname.startsWith('/video/')));
      if (!allowed) {
        frame.remove();
        continue;
      }
      frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation');
      frame.setAttribute('referrerpolicy', 'no-referrer');
    } catch {
      frame.remove();
    }
  }
  const container = document.createElement('div');
  container.append(fragment);
  return container.innerHTML;
}
