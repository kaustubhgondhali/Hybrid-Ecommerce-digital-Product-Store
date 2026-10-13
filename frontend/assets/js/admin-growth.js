/* Admin sections for SEO, content pages, coupons and reviews. Uses the helpers exported by admin.js. */
(function () {
  'use strict';
  const { el, mount, money } = UI;
  const K = window.AdminKit;
  const { API, SF, can, view, head, table, onSubmit, input, selectEl, intOrNull, stat, handleAuthError } = K;

  const CHECK_ICON = { PASS: '\u2714', WARN: '!', FAIL: '\u2716' };
  const scoreClass = (s) => (s >= 80 ? 'green' : s >= 50 ? 'amber' : 'red');
  const scoreBadge = (s) => el('span', { class: 'badge ' + scoreClass(s), text: String(s) });

  function tabs(base, items, active) {
    return el('nav', { class: 'tabs mb' }, items.map(([key, label]) =>
      el('a', { href: '#/' + base + '/' + key, class: key === active ? 'active' : '', text: label })));
  }

  function counter(field, ideal, max) {
    const out = el('span', { class: 'char-count' });
    const update = () => {
      const n = field.value.trim().length;
      out.textContent = n + ' / ' + ideal;
      out.className = 'char-count ' + (n === 0 ? '' : n > max ? 'red' : n >= ideal * 0.6 ? 'green' : 'amber');
    };
    field.addEventListener('input', update);
    update();
    return out;
  }

  function checkList(checks) {
    return el('ul', { class: 'seo-checks' }, checks.map((c) => el('li', { class: 'seo-check ' + c.status.toLowerCase() },
      el('span', { class: 'icon', 'aria-hidden': 'true', text: CHECK_ICON[c.status] || '' }),
      el('div', null, el('strong', { text: c.label }), el('div', { class: 'muted small', text: c.message })))));
  }

  function serpPreview(serp) {
    return el('div', { class: 'serp', 'aria-label': 'Google search result preview' },
      el('div', { class: 'serp-url', text: serp.url }),
      el('div', { class: 'serp-title', text: serp.title }),
      el('div', { class: 'serp-desc', text: serp.description || 'No description \u2013 Google will pick text from the page.' }),
      serp.titleTruncated || serp.descriptionTruncated
        ? el('div', { class: 'muted small mt', text: 'Google will cut off the ' + [serp.titleTruncated ? 'title' : null, serp.descriptionTruncated ? 'description' : null].filter(Boolean).join(' and ') + ' (\u2026).' })
        : null);
  }

  function debounce(fn, ms) {
    let t = null;
    return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
  }

  // ================================================================== SEO

  const SEO_TABS = [['overview', 'Overview'], ['products', 'Products'], ['settings', 'Site settings'],
    ['redirects', 'Redirects'], ['not-found', '404 monitor'], ['sitemap', 'Sitemap']];

  async function seoRoute(parts) {
    const tab = SEO_TABS.some(([k]) => k === parts[0]) ? parts[0] : 'overview';
    if (tab === 'products' && parts[1]) return seoProductView(parts[1]);
    const fn = { overview: seoOverview, products: seoProducts, settings: seoSettings, redirects: seoRedirects, 'not-found': seoNotFound, sitemap: seoSitemap }[tab];
    const body = await fn();
    mount(view(), head('SEO'), tabs('seo', SEO_TABS, tab), body);
  }

  async function seoOverview() {
    const o = await Api.get(API + '/seo/overview');
    return [
      el('div', { class: 'stats' },
        el('div', { class: 'stat score-stat ' + scoreClass(o.siteScore) }, el('div', { class: 'value', text: o.siteScore + '/100' }), el('div', { class: 'label', text: 'Site health' })),
        stat('Average product score', o.productsAnalyzed ? o.averageProductScore + '/100' : '\u2014'),
        stat('Products needing work', o.productsNeedingWork + ' of ' + o.productsAnalyzed),
        stat('URLs in sitemap', o.sitemapUrls),
        stat('Redirects', o.redirects),
        stat('404 URLs seen', o.notFoundUrls, o.notFoundUrls ? 'warn' : '')),
      el('div', { class: 'split' },
        el('div', { class: 'card' }, el('h2', { text: 'Site audit' }), checkList(o.checks)),
        el('div', { class: 'card' }, el('h2', { text: 'Fix these products first' }),
          o.worstProducts.length ? table(['Product', 'Score', 'Top issue'], o.worstProducts.map((p) => el('tr', null,
            el('td', null, el('a', { href: '#/seo/products/' + p.id, text: p.title })),
            el('td', null, scoreBadge(p.score)),
            el('td', { class: 'small', text: p.topIssue || '' })))) : el('p', { class: 'muted', text: 'Every product is in good shape.' })))
    ];
  }

  async function seoProducts() {
    const rows = await Api.get(API + '/seo/products');
    const filter = el('input', { type: 'search', placeholder: 'Filter products', 'aria-label': 'Filter products' });
    const body = el('div');
    const render = () => {
      const q = filter.value.trim().toLowerCase();
      const list = rows.filter((r) => !q || r.title.toLowerCase().includes(q) || r.slug.includes(q));
      mount(body, table(['Product', 'Status', 'Score', 'Issues', 'Top issue'], list.map((r) => {
        const tr = el('tr', { class: 'clickable' },
          el('td', null, el('a', { href: '#/seo/products/' + r.id, text: r.title }), el('div', { class: 'muted small', text: '/' + SF + '/p/' + r.slug })),
          el('td', null, UI.badge(r.status)),
          el('td', null, scoreBadge(r.score), ' ', el('span', { class: 'muted small', text: r.grade })),
          el('td', { class: 'num', text: String(r.problems) }),
          el('td', { class: 'small', text: r.topIssue || '' }));
        tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/seo/products/' + r.id; });
        return tr;
      }), 'No products yet.'));
    };
    filter.addEventListener('input', render);
    render();
    return el('div', { class: 'card' }, el('div', { class: 'toolbar mb' }, filter,
      el('span', { class: 'muted small', text: 'Sorted worst first. Archived products are not listed.' })), body);
  }

  async function seoProductView(id) {
    const p = await Api.get(API + '/seo/products/' + id);
    const writable = can('CATALOG_WRITE');
    const slug = input('slug', { value: p.slug, maxlength: '160', disabled: !writable });
    const metaTitle = input('metaTitle', { value: p.metaTitle || '', maxlength: '200', placeholder: p.title, class: 'wide', disabled: !writable });
    const metaDescription = el('textarea', { name: 'metaDescription', rows: '3', maxlength: '320', disabled: !writable, placeholder: 'Leave empty to use the product summary' });
    metaDescription.value = p.metaDescription || '';
    const focusKeyword = input('focusKeyword', { value: p.focusKeyword || '', maxlength: '100', placeholder: 'e.g. wireless earbuds', disabled: !writable });
    const noindex = input('noindex', { type: 'checkbox', checked: p.noindex, disabled: !writable });

    const scoreBox = el('div', { class: 'seo-score' });
    const serpBox = el('div');
    const checksBox = el('div');
    const renderAnalysis = (a) => {
      mount(scoreBox, el('div', { class: 'score-ring ' + scoreClass(a.score) }, el('span', { text: String(a.score) })),
        el('div', null, el('strong', { text: a.grade }), el('div', { class: 'muted small', text: 'SEO score for this product' })));
      mount(serpBox, serpPreview(a.serp));
      mount(checksBox, checkList(a.checks));
    };
    renderAnalysis(p.analysis);

    const analyze = debounce(async () => {
      try {
        const r = await Api.post(API + '/seo/products/' + id + '/analyze', {
          slug: slug.value.trim(), metaTitle: metaTitle.value.trim(), metaDescription: metaDescription.value.trim(),
          focusKeyword: focusKeyword.value.trim(), noindex: noindex.checked
        });
        renderAnalysis(r.analysis);
      } catch (e) { if (!handleAuthError(e)) UI.toast(UI.errorMessage(e), 'error'); }
    }, 400);
    [slug, metaTitle, metaDescription, focusKeyword].forEach((f) => f.addEventListener('input', analyze));
    noindex.addEventListener('change', analyze);

    const form = el('form', { class: 'card' }, el('h2', { text: 'Search appearance' }),
      UI.field('Focus keyword', focusKeyword, 'The main phrase shoppers type into Google to find this product.'),
      el('div', { class: 'field' }, el('label', { text: 'SEO title' }), metaTitle, counter(metaTitle, 60, 65),
        el('div', { class: 'hint', text: 'Shown as the blue link in Google. Aim for 30\u201360 characters with the keyword near the start.' })),
      el('div', { class: 'field' }, el('label', { text: 'Meta description' }), metaDescription, counter(metaDescription, 160, 165),
        el('div', { class: 'hint', text: 'The snippet under the link. Aim for 120\u2013160 characters and a reason to click.' })),
      UI.field('URL slug', slug, p.status === 'PUBLISHED' ? 'Changing it adds a 301 redirect from the old URL automatically.' : '/' + SF + '/p/\u2026'),
      el('label', { class: 'check mb' }, noindex, 'Hide this product from search engines (noindex)'),
      writable ? el('button', { class: 'btn', type: 'submit', text: 'Save SEO' }) : null);
    onSubmit(form, async () => {
      const saved = await Api.put(API + '/seo/products/' + id, {
        slug: slug.value.trim(), metaTitle: metaTitle.value.trim(), metaDescription: metaDescription.value.trim(),
        focusKeyword: focusKeyword.value.trim(), noindex: noindex.checked
      });
      renderAnalysis(saved.analysis);
      UI.toast('SEO saved', 'success');
    });

    const images = el('div', { class: 'card' }, el('h2', { text: 'Image alt text' }),
      p.images.length ? el('div', { class: 'image-list' }, p.images.map((img) => {
        const alt = input('alt', { value: img.alt || '', maxlength: '250', placeholder: 'Describe the image', disabled: !writable, 'aria-label': 'Alt text' });
        alt.addEventListener('change', async () => {
          try {
            await Api.patch(API + '/products/' + id + '/images/' + img.id, { alt: alt.value.trim() });
            UI.toast('Alt text saved', 'success');
            analyze();
          } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        });
        return el('figure', null, el('img', { src: img.url, alt: img.alt || '', loading: 'lazy', decoding: 'async' }), el('figcaption', null, alt));
      })) : el('p', { class: 'muted', text: 'This product has no images yet.' }));

    mount(view(),
      el('p', { class: 'small' }, el('a', { href: '#/seo/products', text: '\u2190 SEO products' }), ' \u00B7 ',
        el('a', { href: '#/products/' + id, text: 'Edit product details' }),
        p.status === 'PUBLISHED' ? [' \u00B7 ', el('a', { href: p.url, target: '_blank', rel: 'noopener', text: 'View live page' })] : null),
      head(p.title, UI.badge(p.status)),
      el('div', { class: 'split seo-editor' },
        el('div', null, form, images),
        el('div', null,
          el('div', { class: 'card' }, scoreBox),
          el('div', { class: 'card' }, el('h3', { text: 'Google preview' }), serpBox),
          el('div', { class: 'card' }, el('h3', { text: 'Checks' }), checksBox))));
  }

  async function seoSettings() {
    const s = await Api.get(API + '/seo/settings');
    const writable = can('SETTINGS_WRITE');
    const robots = el('textarea', { name: 'robotsExtra', rows: '4', disabled: !writable, placeholder: 'Disallow: /some-private-path' });
    robots.value = s.robotsExtra || '';
    const desc = el('textarea', { name: 'seoDescription', rows: '3', maxlength: '320', disabled: !writable });
    desc.value = s.seoDescription || '';
    const title = input('seoTitle', { value: s.seoTitle || '', maxlength: '200', class: 'wide', disabled: !writable });
    const form = el('form', { class: 'card' }, el('h2', { text: 'Home page & verification' }),
      el('div', { class: 'field' }, el('label', { text: 'Home page title' }), title, counter(title, 60, 65)),
      el('div', { class: 'field' }, el('label', { text: 'Home page description' }), desc, counter(desc, 160, 165)),
      el('div', { class: 'row' },
        UI.field('Google Search Console code', input('googleVerification', { value: s.googleVerification || '', maxlength: '300', disabled: !writable }),
          'Paste the full <meta> tag or just the content value.'),
        UI.field('Bing Webmaster code', input('bingVerification', { value: s.bingVerification || '', maxlength: '300', disabled: !writable }))),
      UI.field('Extra robots.txt rules', robots, 'One Allow:/Disallow: rule per line. Admin, cart, checkout and account pages are always blocked.'),
      writable ? el('button', { class: 'btn', type: 'submit', text: 'Save' }) : null);
    onSubmit(form, async (v) => {
      await Api.put(API + '/seo/settings', { seoTitle: v.seoTitle, seoDescription: v.seoDescription,
        googleVerification: v.googleVerification, bingVerification: v.bingVerification, robotsExtra: v.robotsExtra });
      UI.toast('SEO settings saved', 'success');
      seoRoute(['settings']);
    });
    return el('div', { class: 'split' }, form,
      el('div', null,
        el('div', { class: 'card' }, el('h3', { text: 'Home page in Google' }), serpPreview(s.homePreview)),
        el('div', { class: 'card' }, el('h3', { text: 'Files for search engines' }),
          el('p', null, el('a', { href: s.sitemapUrl, target: '_blank', rel: 'noopener', text: s.sitemapUrl })),
          el('p', null, el('a', { href: s.robotsUrl, target: '_blank', rel: 'noopener', text: s.robotsUrl })),
          el('p', { class: 'muted small', text: 'Submit the sitemap in Google Search Console and Bing Webmaster Tools. Site address: ' + (s.baseUrl || 'not configured') + '.' }))));
  }

  function redirectForm(prefillFrom) {
    const form = el('form', { class: 'inline-form' },
      UI.field('From path', input('fromPath', { required: true, maxlength: '500', placeholder: '/' + SF + '/p/old-name', value: prefillFrom || '', class: 'wide' })),
      UI.field('To path or URL', input('toPath', { required: true, maxlength: '500', placeholder: '/' + SF + '/p/new-name', class: 'wide' })),
      UI.field('Type', selectEl('statusCode', [['301', '301 permanent'], ['302', '302 temporary']], '301')),
      el('button', { class: 'btn', type: 'submit', text: 'Add redirect' }));
    onSubmit(form, async (v) => {
      await Api.post(API + '/seo/redirects', { fromPath: v.fromPath, toPath: v.toPath, statusCode: intOrNull(v.statusCode) });
      UI.toast('Redirect added', 'success');
      location.hash = '#/seo/redirects';
      seoRoute(['redirects']);
    });
    return form;
  }

  async function seoRedirects() {
    const list = await Api.get(API + '/seo/redirects');
    const writable = can('SETTINGS_WRITE');
    const rows = list.map((r) => {
      const to = input('toPath', { value: r.toPath, maxlength: '500', class: 'wide', disabled: !writable });
      const code = selectEl('statusCode', [['301', '301'], ['302', '302']], String(r.statusCode), { disabled: !writable });
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Delete' });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try { await Api.put(API + '/seo/redirects/' + r.id, { toPath: to.value.trim(), statusCode: intOrNull(code.value) }); UI.toast('Redirect saved', 'success'); }
        catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Delete the redirect from ' + r.fromPath + '?')) return;
        try { await Api.del(API + '/seo/redirects/' + r.id); seoRoute(['redirects']); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', null, el('code', { text: r.fromPath }), r.autoCreated ? el('div', { class: 'muted small', text: 'Added automatically after a URL change' }) : null),
        el('td', null, to), el('td', null, code), el('td', { class: 'num', text: String(r.hits) }),
        el('td', { class: 'small', text: r.lastHitAt ? UI.date(r.lastHitAt) : 'never' }),
        el('td', null, writable ? el('div', { class: 'actions' }, save, del) : null));
    });
    return [writable ? el('div', { class: 'card' }, el('h2', { text: 'New redirect' }), redirectForm(UI.param('from'))) : null,
      el('div', { class: 'card' }, table(['From', 'To', 'Type', el('th', { class: 'num', text: 'Hits' }), 'Last used', ''], rows,
        'No redirects yet. When you rename a published product or category, a 301 redirect is added here automatically.'))];
  }

  async function seoNotFound() {
    const list = await Api.get(API + '/seo/not-found');
    const writable = can('SETTINGS_WRITE');
    const formBox = el('div');
    const rows = list.map((n) => {
      const fix = el('button', { class: 'btn small', type: 'button', text: 'Redirect' });
      const dismiss = el('button', { class: 'btn ghost small', type: 'button', text: 'Dismiss' });
      fix.addEventListener('click', () => {
        mount(formBox, el('div', { class: 'card' }, el('h2', { text: 'Redirect ' + n.path }), redirectForm(n.path)));
        formBox.scrollIntoView({ behavior: 'smooth' });
      });
      dismiss.addEventListener('click', () => UI.busy(dismiss, async () => {
        try { await Api.del(API + '/seo/not-found/' + n.id); seoRoute(['not-found']); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', null, el('code', { text: n.path })), el('td', { class: 'num', text: String(n.hits) }),
        el('td', { class: 'small', text: UI.date(n.firstSeen) }), el('td', { class: 'small', text: UI.date(n.lastSeen) }),
        el('td', null, writable ? el('div', { class: 'actions' }, fix, dismiss) : null));
    });
    const clear = list.length && writable ? el('button', { class: 'btn ghost small', type: 'button', text: 'Clear all' }) : null;
    if (clear) {
      clear.addEventListener('click', () => UI.busy(clear, async () => {
        if (!confirm('Clear the whole 404 list?')) return;
        try { await Api.del(API + '/seo/not-found'); seoRoute(['not-found']); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
    }
    return [el('p', { class: 'muted', text: 'Store URLs that visitors or search engines requested but that do not exist. Redirect the popular ones to a relevant page so you keep the traffic.' }),
      formBox,
      el('div', { class: 'card' }, el('div', { class: 'toolbar mb' }, el('span', { class: 'spacer' }), clear),
        table(['Path', el('th', { class: 'num', text: 'Hits' }), 'First seen', 'Last seen', ''], rows, 'No missing pages recorded. \uD83C\uDF89'))];
  }

  async function seoSitemap() {
    const list = await Api.get(API + '/seo/sitemap');
    return el('div', { class: 'card' },
      el('p', { class: 'muted', text: list.length + ' URLs from this storefront are in /sitemap.xml. Drafts, archived and noindex products are left out.' }),
      table(['URL', 'Last modified', el('th', { class: 'num', text: 'Images' })], list.map((e) => el('tr', null,
        el('td', null, el('a', { href: e.url, target: '_blank', rel: 'noopener', text: e.url })),
        el('td', { class: 'small', text: e.lastModified ? UI.date(e.lastModified, false) : '' }),
        el('td', { class: 'num', text: String((e.images || []).length) }))), 'The sitemap is empty.'));
  }

  // ================================================================== content pages

  async function pagesRoute(parts) {
    if (parts[0]) return pageEditView(parts[0]);
    const list = await Api.get(API + '/content-pages');
    const writable = can('SETTINGS_WRITE');
    mount(view(), head('Pages', writable ? el('a', { class: 'btn', href: '#/pages/new', text: 'New page' }) : null),
      el('p', { class: 'muted', text: 'Policy and information pages linked from the store footer. Templates were created for you \u2013 review them and replace the placeholders before going live.' }),
      el('div', { class: 'card' }, table(['Title', 'URL', 'Status', 'Footer', 'Updated'], list.map((p) => {
        const tr = el('tr', { class: 'clickable' },
          el('td', null, el('a', { href: '#/pages/' + p.id, text: p.title })),
          el('td', { class: 'small' }, el('code', { text: p.url })),
          el('td', null, UI.badge(p.published ? 'PUBLISHED' : 'DRAFT')),
          el('td', { text: p.showInFooter ? 'Yes' : '' }),
          el('td', { class: 'small', text: UI.date(p.updatedAt) }));
        tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/pages/' + p.id; });
        return tr;
      }), 'No pages yet.')));
  }

  const COMPARE_TEMPLATE = [
    '## Quick comparison',
    '| Feature | Product A | Product B |',
    '|---|---|---|',
    '| Price | \u20b9 | \u20b9 |',
    '| Best for | | |',
    '| Key spec | | |',
    '| Where to buy | [View deal](/' + SF + '/p/product-a) | [View deal](/' + SF + '/p/product-b) |'
  ].join('\n');

  function insertAtCursor(textarea, text) {
    const start = textarea.selectionStart ?? textarea.value.length;
    const end = textarea.selectionEnd ?? start;
    const before = textarea.value.slice(0, start);
    const block = (before && !before.endsWith('\n\n') ? (before.endsWith('\n') ? '\n' : '\n\n') : '') + text + '\n';
    textarea.value = before + block + textarea.value.slice(end);
    textarea.focus();
    textarea.selectionStart = textarea.selectionEnd = start + block.length;
  }

  async function pageEditView(id) {
    const isNew = id === 'new';
    const p = isNew ? { title: '', slug: '', body: '', metaTitle: '', metaDescription: '', published: false, showInFooter: true, sortOrder: 50 }
      : await Api.get(API + '/content-pages/' + id);
    const writable = can('SETTINGS_WRITE');
    const body = el('textarea', { name: 'body', rows: '22', maxlength: '60000', disabled: !writable, class: 'mono' });
    body.value = p.body || '';
    const metaTitle = input('metaTitle', { value: p.metaTitle || '', maxlength: '200', class: 'wide', disabled: !writable });
    const metaDescription = el('textarea', { name: 'metaDescription', rows: '2', maxlength: '320', disabled: !writable });
    metaDescription.value = p.metaDescription || '';
    const form = el('form', { class: 'card' },
      el('div', { class: 'row' },
        UI.field('Title', input('title', { value: p.title, required: true, maxlength: '200', disabled: !writable })),
        UI.field('URL slug', input('slug', { value: p.slug, maxlength: '120', disabled: !writable }), '/' + SF + '/page/\u2026')),
      UI.field('Content', body, 'Plain text with simple formatting: "# " or "## " headings, "- " bullet lists, "> " highlighted notes, [link text](https://example.com) and comparison tables: one row per line, cells separated by "|", e.g. "| Feature | Phone A | Phone B |" then "| Battery | 5000 mAh | 4500 mAh |". The first row is the header.'),
      writable ? el('div', { class: 'actions' }, el('button', {
        class: 'btn ghost small', type: 'button', text: 'Insert comparison table',
        on: { click: () => insertAtCursor(body, COMPARE_TEMPLATE) }
      })) : null,
      el('div', { class: 'row' },
        el('div', { class: 'field' }, el('label', { text: 'Search title (optional)' }), metaTitle, counter(metaTitle, 60, 65)),
        el('div', { class: 'field' }, el('label', { text: 'Search description (optional)' }), metaDescription, counter(metaDescription, 160, 165))),
      el('div', { class: 'row' },
        el('label', { class: 'check' }, input('published', { type: 'checkbox', checked: p.published, disabled: !writable }), 'Published'),
        el('label', { class: 'check' }, input('showInFooter', { type: 'checkbox', checked: p.showInFooter, disabled: !writable }), 'Link in footer'),
        UI.field('Footer order', input('sortOrder', { type: 'number', value: String(p.sortOrder), class: 'num-input', disabled: !writable }))),
      writable ? el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: isNew ? 'Create page' : 'Save page' }),
        isNew ? null : el('button', { class: 'btn ghost danger-text', type: 'button', text: 'Delete page', on: { click: () => removePage(p) } })) : null);
    onSubmit(form, async (v) => {
      const payload = { title: v.title, slug: v.slug, body: body.value, metaTitle: metaTitle.value.trim(), metaDescription: metaDescription.value.trim(),
        published: Boolean(v.published), showInFooter: Boolean(v.showInFooter), sortOrder: intOrNull(v.sortOrder) };
      const saved = isNew ? await Api.post(API + '/content-pages', payload) : await Api.put(API + '/content-pages/' + id, payload);
      UI.toast('Page saved', 'success');
      location.hash = '#/pages/' + saved.id;
      if (!isNew) pageEditView(saved.id);
    });
    mount(view(), el('p', { class: 'small' }, el('a', { href: '#/pages', text: '\u2190 Pages' }),
      !isNew && p.published ? [' \u00B7 ', el('a', { href: p.url, target: '_blank', rel: 'noopener', text: 'View page' })] : null),
      head(isNew ? 'New page' : p.title), form);
  }

  async function removePage(p) {
    if (!confirm('Delete "' + p.title + '"? Links to it will show "page not found".')) return;
    try {
      await Api.del(API + '/content-pages/' + p.id);
      location.hash = '#/pages';
    } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
  }

  // ================================================================== coupons

  const toLocal = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  };
  const fromLocal = (v) => (v ? new Date(v).toISOString() : null);

  function couponValue(c) {
    return c.discountType === 'PERCENT'
      ? (c.discountValue / 100) + '% off' + (c.maxDiscountMinor ? ' (max ' + money(c.maxDiscountMinor) + ')' : '')
      : money(c.discountValue) + ' off';
  }

  async function couponsView() {
    const list = await Api.get(API + '/coupons');
    const writable = can('SETTINGS_WRITE');
    const rows = list.map((c) => {
      const active = input('active', { type: 'checkbox', checked: c.active, disabled: !writable, 'aria-label': 'Active' });
      active.addEventListener('change', async () => {
        try { await Api.put(API + '/coupons/' + c.id, { active: active.checked }); UI.toast(active.checked ? 'Coupon activated' : 'Coupon paused', 'success'); }
        catch (e) { active.checked = !active.checked; UI.toast(UI.errorMessage(e), 'error'); }
      });
      const now = Date.now();
      const state = !c.active ? 'DISABLED' : c.endsAt && new Date(c.endsAt).getTime() < now ? 'EXPIRED'
        : c.startsAt && new Date(c.startsAt).getTime() > now ? 'PENDING' : c.usageLimit && c.timesUsed >= c.usageLimit ? 'EXPIRED' : 'ACTIVE';
      return el('tr', null,
        el('td', null, el('code', { text: c.code }), c.description ? el('div', { class: 'muted small', text: c.description }) : null),
        el('td', { text: couponValue(c) }),
        el('td', { text: c.minSubtotalMinor ? money(c.minSubtotalMinor) : '\u2014' }),
        el('td', { class: 'num', text: c.timesUsed + (c.usageLimit ? ' / ' + c.usageLimit : '') }),
        el('td', { class: 'small', text: (c.startsAt ? UI.date(c.startsAt, false) : 'now') + ' \u2192 ' + (c.endsAt ? UI.date(c.endsAt, false) : 'no end') }),
        el('td', null, UI.badge(state, state === 'PENDING' ? 'Scheduled' : null)),
        el('td', null, active));
    });

    const type = selectEl('discountType', [['PERCENT', 'Percentage'], ['FIXED', 'Fixed amount (\u20B9)']], 'PERCENT');
    const form = el('form', null,
      el('div', { class: 'row' },
        UI.field('Code', input('code', { required: true, maxlength: '40', placeholder: 'WELCOME10', class: 'upper' })),
        UI.field('Description (internal)', input('description', { maxlength: '200' }))),
      el('div', { class: 'row' },
        UI.field('Discount type', type),
        UI.field('Value', input('discountValue', { required: true, inputmode: 'decimal', placeholder: '10' }), 'Percent (e.g. 10) or rupees (e.g. 150).'),
        UI.field('Max discount (\u20B9, optional)', input('maxDiscount', { inputmode: 'decimal' }), 'Caps percentage coupons.')),
      el('div', { class: 'row' },
        UI.field('Minimum order (\u20B9)', input('minSubtotal', { inputmode: 'decimal', placeholder: '0' })),
        UI.field('Total uses (optional)', input('usageLimit', { type: 'number', min: '1' })),
        UI.field('Uses per customer', input('perCustomerLimit', { type: 'number', min: '0', value: '1' }), '0 = unlimited')),
      el('div', { class: 'row' },
        UI.field('Starts (optional)', input('startsAt', { type: 'datetime-local' })),
        UI.field('Ends (optional)', input('endsAt', { type: 'datetime-local' }))),
      el('button', { class: 'btn', type: 'submit', text: 'Create coupon' }));
    onSubmit(form, async (v) => {
      const raw = Number(String(v.discountValue).replace(/,/g, ''));
      if (!Number.isFinite(raw) || raw <= 0) throw new Error('Enter a valid discount value');
      await Api.post(API + '/coupons', {
        code: v.code.toUpperCase(), description: v.description, discountType: v.discountType,
        discountValue: v.discountType === 'PERCENT' ? Math.round(raw * 100) : UI.toMinor(raw),
        maxDiscountMinor: v.maxDiscount ? UI.toMinor(v.maxDiscount) : null, minSubtotalMinor: v.minSubtotal ? UI.toMinor(v.minSubtotal) : 0,
        usageLimit: intOrNull(v.usageLimit), perCustomerLimit: intOrNull(v.perCustomerLimit),
        startsAt: fromLocal(v.startsAt), endsAt: fromLocal(v.endsAt), active: true
      });
      UI.toast('Coupon created', 'success');
      couponsView();
    });

    mount(view(), head('Coupons'),
      writable ? el('div', { class: 'card' }, el('h2', { text: 'New coupon' }), form) : null,
      el('div', { class: 'card' }, table(['Code', 'Discount', 'Min. order', el('th', { class: 'num', text: 'Used' }), 'Valid', 'Status', 'Active'], rows,
        'No coupons yet.')),
      el('p', { class: 'muted small', text: 'Discounts are applied before GST and shipping. A coupon use is counted when the order is paid (or when a cash-on-delivery order is delivered); cancelled and expired orders give the use back.' }));
  }

  // ================================================================== reviews

  const reviewFilter = { status: 'PENDING', page: 0 };
  const REVIEW_SOURCES = [['WHATSAPP', 'WhatsApp message'], ['EMAIL', 'Email'], ['PHONE', 'Phone call'],
    ['IN_PERSON', 'In person'], ['OTHER', 'Other (e.g. social media message)']];

  async function refreshReviewBadge() {
    if (!can('CATALOG_READ')) return;
    try {
      K.navBadge('reviews', (await Api.get(API + '/reviews/pending-count')).pending);
    } catch (e) { /* the badge is only a reminder */ }
  }

  document.addEventListener('admin:ready', () => {
    refreshReviewBadge();
    // Customers can post reviews while the panel is open; a light check keeps the menu count current.
    setInterval(() => { if (!document.hidden) refreshReviewBadge(); }, 120000);
  });

  /** Search box plus a list of matching products from this storefront. */
  function productPicker() {
    const search = el('input', { type: 'search', placeholder: 'Type to search your products', 'aria-label': 'Search products' });
    const select = el('select', { name: 'productId', required: true, 'aria-label': 'Product' });
    let timer = null;
    const load = async () => {
      const q = search.value.trim();
      try {
        const res = await Api.get(API + '/products?size=50' + (q ? '&q=' + encodeURIComponent(q) : ''));
        mount(select, res.items.length
          ? res.items.map((p) => el('option', { value: String(p.id), text: p.title + (p.status === 'PUBLISHED' ? '' : ' (' + UI.label(p.status).toLowerCase() + ')') }))
          : el('option', { value: '', text: q ? 'No products match “' + q + '”' : 'No products yet' }));
      } catch (e) {
        mount(select, el('option', { value: '', text: 'Could not load products' }));
      }
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 300); });
    search.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(timer); load(); } });
    load();
    return el('div', { class: 'product-picker' }, search, select);
  }

  function addReviewForm() {
    const rating = selectEl('rating', [[5, '★★★★★ 5 stars'], [4, '★★★★ 4 stars'], [3, '★★★ 3 stars'],
      [2, '★★ 2 stars'], [1, '★ 1 star']], 5);
    const form = el('form', null,
      UI.field('Product', productPicker()),
      el('div', { class: 'row' },
        UI.field('Customer name', input('authorName', { required: true, maxlength: '120', autocomplete: 'off', placeholder: 'Priya Sharma' }),
          'Shown as first name and initial, e.g. “Priya S.”'),
        UI.field('Rating', rating),
        UI.field('Where did they share it?', selectEl('source', REVIEW_SOURCES, 'WHATSAPP'))),
      UI.field('Title (optional)', input('title', { maxlength: '120', placeholder: 'Great value for money' })),
      UI.field('What the customer said', el('textarea', { name: 'body', rows: '4', maxlength: '3000', required: true }),
        'Use their own words. You can fix spelling, but don’t change the meaning.'),
      el('label', { class: 'check mb' }, input('publish', { type: 'checkbox', checked: true }), ' Show on the website now (untick to keep it waiting for approval)'),
      el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: 'Add review' })),
      el('p', { class: 'muted small mt' }, 'Only add real feedback from real customers, and keep their original message. ',
        'On the website the review is labelled with where it came from (for example “Shared with us on WhatsApp”) ',
        'and is never marked as a verified purchase. Indian consumer rules treat made-up reviews as an unfair trade practice.'));
    return onSubmit(form, async (v) => {
      const saved = await Api.post(API + '/reviews', {
        productId: Number(v.productId), authorName: v.authorName, rating: Number(v.rating), title: v.title,
        body: v.body, source: v.source, publish: v.publish
      });
      UI.toast(saved.status === 'APPROVED' ? 'Review added and live on the website' : 'Review added to the approval queue', 'success');
      reviewFilter.status = saved.status === 'APPROVED' ? 'APPROVED' : 'PENDING';
      reviewFilter.page = 0;
      reviewsView();
    });
  }

  async function reviewsView() {
    const result = await Api.get(API + '/reviews?page=' + reviewFilter.page + (reviewFilter.status ? '&status=' + reviewFilter.status : ''));
    const writable = can('CATALOG_WRITE');
    refreshReviewBadge();
    const filter = selectEl('status', [['PENDING', 'Waiting for approval'], ['APPROVED', 'Approved'], ['REJECTED', 'Rejected'], ['', 'All reviews']], reviewFilter.status);
    filter.addEventListener('change', () => { reviewFilter.status = filter.value; reviewFilter.page = 0; reviewsView(); });

    const cards = result.items.map((r) => {
      const reply = el('textarea', { rows: '2', maxlength: '2000', placeholder: 'Public reply (optional)', disabled: !writable });
      reply.value = r.adminReply || '';
      const act = (status, label, cls) => {
        const b = el('button', { class: 'btn small ' + (cls || ''), type: 'button', text: label });
        b.addEventListener('click', () => UI.busy(b, async () => {
          try {
            await Api.patch(API + '/reviews/' + r.id, { status, adminReply: reply.value.trim() });
            UI.toast(status === r.status ? 'Reply saved' : status === 'APPROVED' ? 'Review approved — it is now on the website' : 'Review hidden from the website', 'success');
            reviewsView();
          } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        return b;
      };
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Delete' });
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Delete this review permanently?')) return;
        try { await Api.del(API + '/reviews/' + r.id); reviewsView(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      const fromTeam = r.source && r.source !== 'WEBSITE';
      return el('div', { class: 'card review-admin' },
        el('div', { class: 'actions' }, el('span', { class: 'stars', text: '★'.repeat(r.rating) + '☆'.repeat(5 - r.rating) }),
          r.title ? el('strong', { text: r.title }) : null, el('span', { class: 'spacer' }),
          fromTeam ? el('span', { class: 'badge purple', text: 'Added by team' }) : null,
          UI.badge(r.status, r.status === 'PENDING' ? 'Waiting' : null)),
        el('div', { class: 'muted small' }, r.authorName, r.verifiedPurchase ? ' · verified purchase' : '',
          fromTeam ? ' · ' + r.sourceLabel + (r.addedBy ? ' (added by ' + r.addedBy + ')' : '') : ' · written on the website',
          ' · ', UI.date(r.createdAt), ' · ',
          el('a', { href: '#/products/' + r.productId, text: r.productTitle })),
        r.body ? el('p', { class: 'review-body', text: r.body }) : null,
        writable ? [reply, el('div', { class: 'actions mt' },
          r.status !== 'APPROVED' ? act('APPROVED', 'Approve & publish') : act('APPROVED', 'Save reply', 'secondary'),
          r.status !== 'REJECTED' ? act('REJECTED', r.status === 'APPROVED' ? 'Unpublish' : 'Reject', 'secondary') : null, del)]
          : r.adminReply ? el('p', { class: 'note small', text: 'Reply: ' + r.adminReply }) : null);
    });

    const emptyText = reviewFilter.status === 'PENDING'
      ? 'No reviews are waiting for approval. New reviews from customers appear here first and go live only when you approve them.'
      : 'No reviews here.';
    mount(view(), head('Reviews', filter),
      writable ? el('details', { class: 'card add-review' },
        el('summary', null, el('strong', { text: 'Add a review' }),
          el('span', { class: 'muted small', text: ' — feedback a customer sent you by WhatsApp, email, phone or in person' })),
        addReviewForm()) : null,
      cards.length ? cards : el('div', { class: 'card empty', text: emptyText }),
      K.pager(result, (pg) => { reviewFilter.page = pg; reviewsView(); }),
      el('p', { class: 'muted small' }, 'Approve honest reviews even when they are negative — a polite reply usually builds more trust than hiding them. ',
        'Customer reviews skip this queue only if “Publish customer reviews immediately” is ticked in Settings.'));
  }

  // ================================================================== system health

  let showResolved = false;

  async function systemView() {
    const h = await Api.get(API + '/system' + (showResolved ? '?resolved=true' : ''));
    const up = (secs) => {
      const d = Math.floor(secs / 86400), hr = Math.floor((secs % 86400) / 3600), m = Math.floor((secs % 3600) / 60);
      return (d ? d + 'd ' : '') + hr + 'h ' + m + 'm';
    };
    const item = (label, value, ok) => el('div', { class: 'summary-row' }, el('span', { text: label }),
      el('span', null, ok === undefined ? null : el('span', { class: 'dot ' + (ok ? 'ok' : 'bad'), 'aria-hidden': 'true' }), ' ', value));
    const storagePct = h.storageTotalMb ? Math.round(100 * (1 - h.storageFreeMb / h.storageTotalMb)) : null;

    const toggle = el('label', { class: 'check' }, el('input', { type: 'checkbox', checked: showResolved,
      on: { change: (e) => { showResolved = e.target.checked; systemView(); } } }), 'Show resolved');
    const errorRows = h.errors.map((e) => {
      const btn = el('button', { class: 'btn ghost small', type: 'button', text: e.resolved ? 'Reopen' : 'Mark resolved' });
      btn.addEventListener('click', () => UI.busy(btn, async () => {
        try { await Api.patch(API + '/system/errors/' + e.id, { resolved: !e.resolved }); systemView(); } catch (err) { UI.toast(UI.errorMessage(err), 'error'); }
      }));
      const details = el('details', null, el('summary', { class: 'small', text: 'Stack trace' }), el('pre', { class: 'stack', text: e.stack || '' }));
      return el('div', { class: 'error-entry' },
        el('div', { class: 'actions' }, el('strong', { text: e.errorType.split('.').pop() }),
          el('code', { class: 'small', text: (e.method || '') + ' ' + (e.path || '') }), el('span', { class: 'spacer' }),
          el('span', { class: 'badge ' + (e.occurrences > 10 ? 'red' : 'amber'), text: e.occurrences + '\u00D7' }), btn),
        e.message ? el('div', { class: 'small', text: e.message }) : null,
        el('div', { class: 'muted small', text: 'First ' + UI.date(e.firstSeen) + ' \u00B7 last ' + UI.date(e.lastSeen) }),
        details);
    });

    mount(view(), head('System health'),
      el('div', { class: 'stats' },
        stat('Database', h.databaseUp ? 'Online' : 'Down', h.databaseUp ? '' : 'bad'),
        stat('Unresolved errors', h.unresolvedErrors, h.unresolvedErrors ? 'warn' : ''),
        stat('Emails waiting to retry', h.emailsPending, h.emailsPending ? 'warn' : ''),
        stat('Emails failed', h.emailsFailed, h.emailsFailed ? 'bad' : ''),
        stat('Uptime', up(h.uptimeSeconds))),
      el('div', { class: 'split' },
        el('div', { class: 'card' }, el('h2', { text: 'Errors' }), el('div', { class: 'toolbar mb' }, el('p', { class: 'muted small', text: 'Unexpected server errors, grouped. Customers only saw "Something went wrong".' }), el('span', { class: 'spacer' }), toggle),
          errorRows.length ? errorRows : el('p', { class: 'muted', text: showResolved ? 'No resolved errors.' : 'No unresolved errors. \u2714' })),
        el('div', { class: 'card' }, el('h2', { text: 'Configuration' }),
          item('Database', h.database || 'unknown', h.databaseUp),
          item('Schema version', h.schemaVersion || '\u2014'),
          item('Payments', h.demoPayments ? 'Demo mode (no real money)' : 'Live provider', !h.demoPayments),
          item('Site address uses HTTPS', h.httpsBaseUrl ? 'Yes' : 'No', h.httpsBaseUrl),
          item('Sign-in sessions', h.sessionStore, h.sessionStore.startsWith('Database')),
          item('Product search', h.searchMode),
          item('Emails skipped (SMTP off)', String(h.emailsSkipped), h.emailsSkipped === 0),
          item('Upload storage free', h.storageFreeMb == null ? 'folder missing' : Math.round(h.storageFreeMb / 1024 * 10) / 10 + ' GB' + (storagePct != null ? ' (' + storagePct + '% used)' : ''),
            h.storageFreeMb != null && h.storageFreeMb > 2048),
          item('Java', h.javaVersion),
          item('Memory', h.heapUsedMb + ' MB of ' + h.heapMaxMb + ' MB'),
          item('Started', UI.date(h.startedAt)),
          el('p', { class: 'muted small mt', text: 'Back up the database and the upload folder daily \u2013 see scripts/backup.ps1 and scripts/backup.sh.' }))));
  }

  K.register('seo', 'SEO', 'CATALOG_READ', seoRoute, 'inventory');
  // ================================================================== security analyzer

  async function securityView() {
    const r = await Api.get(API + '/security');
    const mfaParts = String(r.summary.staffWithMfa || '').split(' / ');
    const mfaOk = mfaParts.length === 2 && mfaParts[0] === mfaParts[1] && mfaParts[1] !== '0';
    const summaryBits = [
      ['Site uses HTTPS', r.summary.httpsBaseUrl ? 'Yes' : 'No', !!r.summary.httpsBaseUrl],
      ['Secure cookies', r.summary.cookieSecure ? 'On' : 'Off', !!r.summary.cookieSecure],
      ['HSTS', r.summary.hstsEnabled ? 'On' : 'Off', !!r.summary.hstsEnabled],
      ['Rate limiting', r.summary.rateLimitEnabled ? 'On' : 'Off', !!r.summary.rateLimitEnabled],
      ['Staff MFA', r.summary.staffWithMfa, mfaOk],
      ['Email verification', r.summary.emailVerificationRequired ? 'Required' : 'Optional', !!r.summary.emailVerificationRequired],
      ['Payments', r.summary.demoPayments ? 'Demo mode' : 'Live provider', !r.summary.demoPayments],
      ['Profile', r.summary.prodProfile ? 'prod' : 'dev / docker', true]
    ];

    const next = r.nextSteps.length
      ? el('ol', { class: 'security-next' }, r.nextSteps.map((s) => el('li', { class: s.status.toLowerCase() },
          el('strong', { text: s.label }),
          el('div', { class: 'muted small', text: s.fix || '' }))))
      : el('p', { class: 'muted', text: 'No open findings. Keep reviewing this page after every deploy.' });

    const groups = r.categories.map((cat) => el('div', { class: 'card' },
      el('h2', { text: cat }),
      checkList((r.byCategory[cat] || []).map((c) => ({
        status: c.status,
        label: c.label,
        message: c.message + (c.fix && c.status !== 'PASS' ? ' Fix: ' + c.fix : '')
      })))));

    mount(view(), head('Security'),
      el('p', { class: 'muted mb', text: 'Defensive check of this storefront\u2019s configuration and staff hygiene. It does not attack the site or scan other people\u2019s servers.' }),
      el('div', { class: 'stats' },
        el('div', { class: 'stat score-stat ' + scoreClass(r.score) },
          el('div', { class: 'value', text: r.score + '/100' }),
          el('div', { class: 'label', text: r.grade })),
        stat('Open findings', r.problems, r.problems ? 'warn' : ''),
        stat('Critical', r.critical, r.critical ? 'bad' : ''),
        stat('Checks run', r.checks.length)),
      el('div', { class: 'split' },
        el('div', { class: 'card' }, el('h2', { text: 'Fix these first' }), next,
          el('p', { class: 'muted small mt', text: 'Critical items should be fixed before launch. Warnings are strongly recommended for production.' })),
        el('div', { class: 'card' }, el('h2', { text: 'At a glance' }),
          summaryBits.map(([label, value, ok]) => el('div', { class: 'summary-row' },
            el('span', { text: label }),
            el('span', null, el('span', { class: 'dot ' + (ok ? 'ok' : 'bad'), 'aria-hidden': 'true' }), ' ', value))))),
      el('div', { class: 'security-groups' }, groups));
  }

  K.register('system', 'System health', 'AUDIT_READ', systemView, 'audit');
  K.register('security', 'Security', 'AUDIT_READ', securityView, 'system');
  K.register('reviews', 'Reviews', 'CATALOG_READ', reviewsView, 'seo');
  K.register('coupons', 'Coupons', 'ORDERS_READ', couponsView, 'entitlements');
  K.register('pages', 'Pages', 'DASHBOARD_READ', pagesRoute, 'affiliate');

  // ================================================================== affiliate product research

  const RESEARCH_TABS = [['run', 'Run research'], ['platforms', 'Affiliate platforms']];
  const ACCESS_LABEL = { DIRECT: ['Direct', 'green'], NETWORK: ['Network', 'blue'], VIA_NETWORK: ['Via network', 'amber'] };
  const accessBadge = (a) => {
    const [text, color] = ACCESS_LABEL[a] || [a || '', ''];
    return el('span', { class: 'badge ' + color, text });
  };

  async function researchRoute(parts) {
    const tab = RESEARCH_TABS.some(([k]) => k === parts[0]) ? parts[0] : 'run';
    const meta = await Api.get(API + '/research');
    if (tab === 'platforms') {
      mount(view(), head('Product research'), tabs('research', RESEARCH_TABS, tab),
        el('p', { class: 'muted mb', text: meta.howItWorks }),
        el('div', { class: 'stats' },
          stat('Affiliate platforms', meta.platforms.length),
          stat('Playbook categories', meta.categories.length),
          stat('Catalog products', meta.catalogProducts),
          stat('Connected merchants', meta.connectedMerchants)),
        el('div', { class: 'card' }, el('h2', { text: 'Only platforms with affiliate programs' }),
          table(['Platform', 'How to join', 'Eligibility', 'Region', 'Join'], meta.platforms.map((p) => el('tr', null,
            el('td', null, el('strong', { text: p.name }), el('div', { class: 'muted small', text: p.program })),
            el('td', null, accessBadge(p.access)),
            el('td', { class: 'small', text: p.requirement || '' }),
            el('td', { text: p.region }),
            el('td', null, el('a', { href: p.joinUrl, target: '_blank', rel: 'noopener', text: 'Open' })))),
            'No platforms for this storefront.')));
      return;
    }
    await researchRunView(meta);
  }

  async function researchRunView(meta) {
    const niche = el('input', { name: 'niche', maxlength: '120', placeholder: 'e.g. wireless earbuds, Python course, sunscreen',
      'aria-label': 'Niche or focus' });
    const notes = el('textarea', { name: 'researchNotes', rows: '8', maxlength: '20000',
      placeholder: 'Paste Google review excerpts, “best of” roundups, Reddit/forum notes, or YouTube summaries you already researched. We do not scrape Google.',
      'aria-label': 'Research notes' });
    const catBox = el('div', { class: 'chip-row' });
    const selected = new Set();
    meta.categories.forEach((c) => {
      const chip = el('button', { type: 'button', class: 'chip', text: c.name, title: c.blurb });
      chip.addEventListener('click', () => {
        if (selected.has(c.id)) { selected.delete(c.id); chip.classList.remove('active'); }
        else { selected.add(c.id); chip.classList.add('active'); }
      });
      catBox.appendChild(chip);
    });
    const runBtn = el('button', { class: 'btn', type: 'submit', text: 'Analyze opportunities' });
    const msg = el('div');
    const results = el('div', { class: 'mt' });
    const form = el('form', { class: 'card' },
      el('h2', { text: 'Research brief' }),
      el('p', { class: 'muted small', text: 'Fast, on-server analysis. Affiliate platforms only — suggestions skip merchants without a partner program.' }),
      msg,
      UI.field('What should we research?', niche, 'Niche, audience, or product type.'),
      UI.field('Google research & reviews (optional)', notes,
        'Copy/paste what you found on Google. Keywords, praise, complaints, and price cues sharpen the scores.'),
      el('div', { class: 'mb' }, el('label', { class: 'label', text: 'Category focus (optional)' }), catBox,
        el('p', { class: 'muted small', text: 'Leave empty to scan every playbook category for this storefront.' })),
      runBtn);

    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(runBtn, async () => {
        try {
          const report = await Api.post(API + '/research/analyze', {
            niche: niche.value.trim(),
            researchNotes: notes.value.trim(),
            categoryFocus: [...selected],
            maxPerCategory: 4
          });
          mount(msg, el('div', { class: 'alert success',
            text: 'Analyzed in ' + report.analyzedInMs + ' ms · opportunity ' + report.opportunityScore + '/100 (' + report.grade + ')' }));
          mount(results, renderResearchReport(report));
        } catch (err) {
          mount(msg, UI.errorAlert(err));
          mount(results);
        }
      });
    });

    mount(view(), head('Product research'), tabs('research', RESEARCH_TABS, 'run'),
      el('p', { class: 'muted mb', text: meta.howItWorks }),
      form, results);
  }

  function renderResearchReport(r) {
    const signalList = r.signals.length
      ? el('ul', { class: 'seo-checks' }, r.signals.map((s) => el('li', { class: 'seo-check pass' },
          el('span', { class: 'icon', 'aria-hidden': 'true', text: '·' }),
          el('div', null, el('strong', { text: s.type + ' · ' + s.text }),
            el('div', { class: 'muted small', text: s.detail })))))
      : el('p', { class: 'muted', text: 'No extra signals yet.' });

    const actions = el('ol', { class: 'security-next' }, r.actions.map((a) => el('li', null, el('div', { text: a }))));

    const platformRows = table(['Platform', 'Fit', 'How to join', 'Eligibility', 'Status'], r.platforms.map((p) => el('tr', null,
      el('td', null, el('strong', { text: p.name }), el('div', { class: 'muted small', text: p.program })),
      el('td', null, scoreBadge(p.fitScore)),
      el('td', null, accessBadge(p.access)),
      el('td', { class: 'small', text: p.requirement || '' }),
      el('td', null, p.merchantConnected
        ? el('span', { class: 'badge green', text: 'Connected' })
        : el('a', { class: 'small', href: p.joinUrl, target: '_blank', rel: 'noopener', text: 'Join program' })))),
      'No affiliate platforms for this storefront.');

    const searchRows = table(['Ask Google', 'Why', 'Open'], r.nextSearches.map((s) => el('tr', null,
      el('td', null, el('code', { class: 'small', text: s.query })),
      el('td', { class: 'small', text: s.purpose }),
      el('td', null, el('a', { href: s.googleUrl, target: '_blank', rel: 'noopener', text: 'Search' })))),
      'No searches suggested.');

    const catBlocks = r.categories.map((c) => el('div', { class: 'card' },
      el('div', { class: 'actions' },
        el('h2', { text: c.categoryName }),
        el('span', { class: 'spacer' }),
        scoreBadge(c.opportunityScore),
        el('span', { class: 'muted small', text: ' opportunity' })),
      el('p', { class: 'muted small', text: c.blurb }),
      el('p', { class: 'small mb', text: 'Source via: ' + (c.platforms || []).join(', ') }),
      table(['Suggested product', 'Fit', 'Priority', 'Catalog', 'Why'], c.products.map((p) => el('tr', null,
        el('td', null, el('strong', { text: p.title }),
          p.tags && p.tags.length ? el('div', { class: 'muted small', text: p.tags.join(' · ') }) : null),
        el('td', null, scoreBadge(p.fitScore)),
        el('td', null, el('span', { class: 'badge ' + (p.priority === 'HIGH' ? 'green' : p.priority === 'MEDIUM' ? 'amber' : ''),
          text: p.priority })),
        el('td', { class: 'small', text: p.alreadyInCatalog ? ('Have: ' + (p.matchedCatalogTitle || 'yes')) : 'Not listed yet' }),
        el('td', { class: 'small', text: p.why }))), 'No products in this band.'),
      c.searches && c.searches.length
        ? el('div', { class: 'mt' }, el('h3', { class: 'small', text: 'What to search next' }),
            el('ul', { class: 'plain' }, c.searches.slice(0, 3).map((s) =>
              el('li', null, el('a', { href: s.googleUrl, target: '_blank', rel: 'noopener', text: s.query }),
                el('span', { class: 'muted small', text: ' — ' + s.purpose })))))
        : null));

    return [
      el('div', { class: 'stats' },
        el('div', { class: 'stat score-stat ' + scoreClass(r.opportunityScore) },
          el('div', { class: 'value', text: r.opportunityScore + '/100' }),
          el('div', { class: 'label', text: r.grade })),
        stat('Categories ranked', r.categories.length),
        stat('Platforms scored', r.platforms.length),
        stat('Next Google searches', r.nextSearches.length),
        stat('Analysis time', r.analyzedInMs + ' ms')),
      el('div', { class: 'card mb' }, el('p', { text: r.summary })),
      el('div', { class: 'split' },
        el('div', { class: 'card' }, el('h2', { text: 'Do this next' }), actions),
        el('div', { class: 'card' }, el('h2', { text: 'Signals from your notes' }), signalList)),
      el('div', { class: 'card' }, el('h2', { text: 'Ask Google these queries' }),
        el('p', { class: 'muted small', text: 'Copy these into Google yourself. The tool never scrapes search results.' }),
        searchRows),
      el('div', { class: 'card' }, el('h2', { text: 'Affiliate platforms (fit)' }), platformRows),
      el('h2', { class: 'mt', text: 'Products by category' }),
      el('div', { class: 'research-cats' }, catBlocks)
    ];
  }

  K.register('research', 'Research', 'AFFILIATE_READ', researchRoute, 'affiliate');
})();
