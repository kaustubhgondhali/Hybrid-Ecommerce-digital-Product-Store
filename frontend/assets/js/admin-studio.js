/* Admin: content studio. Blog posts, Web Stories, newsletters, social posts and posters made from product data with
   templates — free, no AI service. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, can, view, head, table, selectEl } = K;
  const S = API + '/studio';

  const BLOG_LABELS = { REVIEW: 'Review', WORTH_IT: 'Is it worth it?', FAQ: 'FAQs', BUYING_TIPS: 'How to choose', COMPARISON: 'Comparison', ROUNDUP: 'Best-of roundup' };
  const EMAIL_LABELS = { LAUNCH: 'Product launch', NEW_ARRIVALS: 'New arrivals', TOP_DEALS: 'Top deals', COLLECTION: 'Collection' };
  const FORMATS = [['SQUARE', 'Instagram post 1080×1080'], ['PORTRAIT', 'Instagram portrait 1080×1350'], ['STORY', 'Story / Reel / Status 1080×1920'],
    ['PIN', 'Pinterest pin 1000×1500'], ['BANNER', 'Facebook / LinkedIn / X 1200×628'], ['HERO', 'Website banner 1920×640']];
  const THEMES = [['LIGHT', 'Light'], ['DARK', 'Dark'], ['SALE', 'Sale (bold red)']];

  function tabs(active) {
    return el('nav', { class: 'tabs mb' }, [['', 'One product'], ['collections', 'Several products'], ['automation', 'Automation'], ['history', 'History']]
      .map(([key, label]) => el('a', { href: '#/studio' + (key ? '/' + key : ''), class: key === active ? 'active' : '', text: label })));
  }

  function intro() {
    return el('div', { class: 'card' }, el('p', { class: 'muted', text: 'Pick a published product and the studio writes blog posts, a Google Web Story, '
      + 'newsletters, social posts and posters from its details, price and photos. Everything is made from templates on your own server — '
      + 'no AI service and no cost. Click “Another version” for different wording.' }));
  }

  async function studioRoute(parts) {
    if (parts[0] === 'collections') return collectionsView();
    if (parts[0] === 'automation') return automationView();
    if (parts[0] === 'history') return historyView();
    if (parts[0] === 'product' && parts[1]) return productView(parts[1], 0);
    return pickView();
  }

  // ------------------------------------------------------------------ product search

  function productSearch(onPick, hint) {
    const search = el('input', { type: 'search', placeholder: 'Search published products…', 'aria-label': 'Search products' });
    const results = el('div', { class: 'product-checklist picker-results' });
    let timer;
    const load = () => {
      Api.get(API + '/products?size=20&status=PUBLISHED' + (search.value.trim() ? '&q=' + encodeURIComponent(search.value.trim()) : '')).then((res) => {
        mount(results, res.items.length ? res.items.map((p) => el('button', { class: 'product-option check', type: 'button', on: { click: () => onPick(p) } },
          p.imageUrl ? el('img', { src: p.imageUrl, alt: '' }) : null,
          el('span', null, p.title))) : el('p', { class: 'muted small', text: 'No published products match.' }));
      }).catch((e) => mount(results, UI.errorAlert(e)));
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 200); });
    load();
    return el('div', { class: 'studio-search' }, hint ? el('p', { class: 'muted small', text: hint }) : null, search, results);
  }

  function pickView() {
    mount(view(), head('Content studio'), tabs(''), intro(),
      el('div', { class: 'card' }, el('h2', { text: 'Choose a product' }),
        productSearch((p) => { location.hash = '#/studio/product/' + p.id; })));
  }

  // ------------------------------------------------------------------ helpers

  function copyButton(getText) {
    return el('button', { class: 'btn small secondary', type: 'button', text: 'Copy', on: { click: async (e) => {
      try {
        await navigator.clipboard.writeText(getText());
        UI.toast('Copied', 'success');
      } catch (err) {
        e.target.closest('.studio-item').querySelector('textarea')?.select();
        UI.toast('Press Ctrl+C to copy', 'info');
      }
    } } });
  }

  function textBox(text, rows) {
    const t = el('textarea', { rows: String(rows || 8), class: 'mono studio-text' });
    t.value = text;
    return t;
  }

  async function create(btn, url, payload, what) {
    await UI.busy(btn, async () => {
      try {
        const c = await Api.post(url, payload);
        UI.toast(what + ' created', 'success');
        btn.parentElement.after(el('p', { class: 'small studio-created' }, UI.badge(c.status), ' ',
          el('a', { href: c.adminPath, text: 'Open “' + c.title + '”' })));
      } catch (e) {
        if (!K.handleAuthError(e)) UI.toast(UI.errorMessage(e), 'error');
      }
    });
  }

  function blogCard(d, payload) {
    const body = textBox(d.body, 14);
    const details = el('details', null, el('summary', { text: 'Show the article text' }), body,
      el('p', { class: 'muted small', text: 'You can edit everything after saving — it opens in Buying guides.' }));
    const actions = el('div', { class: 'actions' });
    if (can('CATALOG_WRITE')) {
      actions.append(
        el('button', { class: 'btn small', type: 'button', text: 'Save as draft', on: { click: (e) => create(e.target, S + '/blogs', Object.assign({ publish: false }, payload), 'Draft') } }),
        el('button', { class: 'btn small secondary', type: 'button', text: 'Publish now', on: { click: (e) => create(e.target, S + '/blogs', Object.assign({ publish: true }, payload), 'Article') } }));
    }
    actions.append(copyButton(() => body.value));
    return el('div', { class: 'studio-item' },
      el('div', { class: 'studio-item-head' }, el('span', { class: 'badge', text: BLOG_LABELS[d.kind] || d.kind }), el('strong', { text: d.title })),
      el('p', { class: 'muted small', text: d.summary }), details, actions);
  }

  function emailCard(d, payload) {
    const body = textBox(d.body, 12);
    const actions = el('div', { class: 'actions' });
    if (payload && can('MARKETING_SEND')) {
      actions.append(el('button', { class: 'btn small', type: 'button', text: 'Save as campaign draft',
        on: { click: (e) => create(e.target, S + '/newsletters', payload, 'Campaign draft') } }));
    }
    actions.append(copyButton(() => 'Subject: ' + d.subject + '\n\n' + body.value));
    return el('div', { class: 'studio-item' },
      el('div', { class: 'studio-item-head' }, el('span', { class: 'badge', text: EMAIL_LABELS[d.kind] || d.kind }), el('strong', { text: d.subject })),
      el('details', null, el('summary', { text: 'Show the email' }), body,
        el('p', { class: 'muted small', text: '{name}, {store} and {link} are filled in for each subscriber. Drafts are never sent until you send them from Customers → Campaigns.' })),
      actions);
  }

  function socialCard(p) {
    const text = textBox(p.text, Math.min(12, Math.max(4, p.text.split('\n').length + 1)));
    const count = el('span', { class: 'muted small' });
    const update = () => {
      const n = text.value.length;
      count.textContent = n + ' / ' + p.maxLength + (n > p.maxLength ? ' — too long' : '');
      count.classList.toggle('danger-text', n > p.maxLength);
    };
    text.addEventListener('input', update);
    update();
    return el('div', { class: 'studio-item' },
      el('div', { class: 'studio-item-head' }, el('strong', { text: p.label }), el('span', { class: 'spacer' }), count),
      text,
      el('div', { class: 'actions' }, copyButton(() => text.value),
        p.shareUrl ? el('a', { class: 'btn small ghost', href: p.shareUrl, target: '_blank', rel: 'noopener', text: 'Open share window' }) : null));
  }

  function posterGrid(urlFor) {
    const theme = selectEl('theme', THEMES, 'LIGHT');
    const grid = el('div', { class: 'studio-posters' });
    const draw = () => mount(grid, FORMATS.map(([f, label]) => {
      const src = urlFor(f, theme.value);
      return el('figure', { class: 'studio-poster' },
        el('a', { href: src, target: '_blank', rel: 'noopener' }, el('img', { src, alt: label, loading: 'lazy' })),
        el('figcaption', null, el('span', { class: 'small', text: label }),
          el('a', { class: 'btn small secondary', href: src, download: f.toLowerCase() + '-' + theme.value.toLowerCase() + '.png', text: 'Download' })));
    }));
    theme.addEventListener('change', draw);
    draw();
    return el('div', null, el('div', { class: 'row' }, UI.field('Style', theme)), grid);
  }

  // ------------------------------------------------------------------ one product

  async function productView(id, variant) {
    const d = await Api.get(S + '/products/' + id + '?variant=' + variant);
    const p = d.product;
    const another = el('button', { class: 'btn secondary', type: 'button', text: 'Another version', on: { click: () => productView(id, variant + 1) } });

    const storyPages = el('ol', { class: 'studio-story' }, d.story.pages.map((s) => el('li', null, el('strong', { text: s.headline }), el('div', { class: 'small', text: s.body || '' }))));
    const storyActions = el('div', { class: 'actions' });
    if (!d.storyReady) {
      storyActions.append(el('p', { class: 'muted small', text: 'Add a photo to this product to make a story.' }));
    } else if (can('CATALOG_WRITE')) {
      storyActions.append(
        el('button', { class: 'btn small', type: 'button', text: 'Create story draft', on: { click: (e) => create(e.target, S + '/stories', { productId: p.id, variant, publish: false }, 'Story') } }),
        el('button', { class: 'btn small secondary', type: 'button', text: 'Publish story', on: { click: (e) => create(e.target, S + '/stories', { productId: p.id, variant, publish: true }, 'Story') } }));
    }

    mount(view(), el('p', { class: 'small' }, el('a', { href: '#/studio', text: '← Content studio' }), ' · ',
      el('a', { href: p.productUrl, target: '_blank', rel: 'noopener', text: 'View product' })),
      head(p.title, another),
      el('p', { class: 'muted small', text: 'Version ' + (variant + 1) + ' · facts come from the live product page: summary, highlights, pros and cons, specs and price. '
        + 'Fill those in for richer content.' }),
      el('section', { class: 'card' }, el('h2', { text: 'Blog posts' }),
        d.blogs.map((b) => blogCard(b, { productId: p.id, kind: b.kind, variant }))),
      el('section', { class: 'card' }, el('h2', { text: 'Google Web Story' }),
        el('p', { class: 'muted small', text: d.story.title + ' — ' + d.story.pages.length + ' pages, using this product’s photos. Each page links to the product.' }),
        storyPages, storyActions),
      el('section', { class: 'card' }, el('h2', { text: 'Newsletters' }),
        d.emails.map((m) => emailCard(m, m.kind === 'LAUNCH' ? { productIds: [p.id], kind: m.kind, variant } : null))),
      el('section', { class: 'card' }, el('h2', { text: 'Social media & WhatsApp' }),
        el('p', { class: 'muted small', text: 'Copy a caption, or open the network’s own share window (free, no account linking). For Instagram, download a poster below and paste the caption.' }),
        el('div', { class: 'studio-social' }, d.social.map(socialCard))),
      el('section', { class: 'card' }, el('h2', { text: 'Posters & banners' }),
        posterGrid((f, t) => S + '/posters/' + p.id + '?format=' + f + '&theme=' + t)));
  }

  // ------------------------------------------------------------------ several products

  function collectionsView() {
    let picked = [];
    let variant = 0;
    const chosen = el('div', { class: 'chosen-products' });
    const topic = K.input('topic', { maxlength: '60', placeholder: 'e.g. wireless earbuds, Diwali gifts (optional)' });
    const out = el('div');
    const go = el('button', { class: 'btn', type: 'button', text: 'Generate', disabled: true });
    const drawChosen = () => {
      mount(chosen, picked.map((p) => el('span', { class: 'chip' }, p.title,
        el('button', { class: 'chip-remove', type: 'button', 'aria-label': 'Remove ' + p.title, text: '×',
          on: { click: () => { picked = picked.filter((x) => x.id !== p.id); drawChosen(); } } }))));
      go.disabled = picked.length < 2;
    };
    const generate = async () => {
      mount(out, el('p', { class: 'muted', text: 'Writing…' }));
      try {
        const ids = picked.map((p) => p.id);
        const d = await Api.post(S + '/collections/preview', { productIds: ids, topic: topic.value, variant });
        const t = topic.value.trim();
        const headline = K.input('headline', { maxlength: '80', placeholder: 'Headline (optional), e.g. Diwali sale' });
        const subline = K.input('subline', { maxlength: '100', placeholder: 'Second line (optional)' });
        const posterBox = el('div');
        const drawPosters = () => mount(posterBox, posterGrid((f, th) => S + '/posters/collection?ids=' + ids.join(',') + '&format=' + f + '&theme=' + th
          + (headline.value.trim() ? '&headline=' + encodeURIComponent(headline.value.trim()) : '')
          + (subline.value.trim() ? '&subline=' + encodeURIComponent(subline.value.trim()) : '')));
        drawPosters();
        mount(out,
          el('div', { class: 'actions' }, el('button', { class: 'btn secondary', type: 'button', text: 'Another version', on: { click: () => { variant++; generate(); } } })),
          el('section', { class: 'card' }, el('h2', { text: 'Blog posts' }),
            d.comparison ? blogCard(d.comparison, { productIds: ids, kind: 'COMPARISON', topic: t, variant }) : el('p', { class: 'muted small', text: 'Choose 2 to 4 products for a comparison.' }),
            d.roundup ? blogCard(d.roundup, { productIds: ids, kind: 'ROUNDUP', topic: t, variant }) : el('p', { class: 'muted small', text: 'Choose 3 or more products for a “best of” roundup.' })),
          el('section', { class: 'card' }, el('h2', { text: 'Newsletters' }),
            d.emails.map((m) => emailCard(m, { productIds: ids, kind: m.kind, topic: t, variant }))),
          el('section', { class: 'card' }, el('h2', { text: 'Sale & collection banners' }),
            el('div', { class: 'row' }, UI.field('Headline', headline), UI.field('Second line', subline)),
            el('div', { class: 'actions' }, el('button', { class: 'btn small secondary', type: 'button', text: 'Update banners', on: { click: drawPosters } })),
            posterBox));
      } catch (e) {
        if (!K.handleAuthError(e)) mount(out, UI.errorAlert(e));
      }
    };
    go.addEventListener('click', () => { variant = 0; generate(); });
    mount(view(), head('Content studio'), tabs('collections'),
      el('div', { class: 'card' }, el('h2', { text: 'Choose 2 to 10 products' }),
        el('p', { class: 'muted small', text: 'Make a comparison (2–4), a “best of” roundup (3+), newsletters and sale banners from several products.' }),
        chosen, UI.field('Topic', topic), el('div', { class: 'actions' }, go),
        productSearch((p) => {
          if (!picked.some((x) => x.id === p.id) && picked.length < 10) picked.push(p);
          drawChosen();
        })),
      out);
  }

  // ------------------------------------------------------------------ automation

  async function automationView() {
    const s = await Api.get(S + '/settings');
    const writable = can('CATALOG_WRITE');
    const modes = [['OFF', 'Off'], ['DRAFT', 'Create a draft'], ['PUBLISH', 'Create and publish']];
    const blog = selectEl('autoBlog', modes, s.autoBlog, { disabled: !writable });
    const kind = selectEl('blogKind', ['REVIEW', 'WORTH_IT', 'FAQ', 'BUYING_TIPS'].map((k) => [k, BLOG_LABELS[k]]), s.blogKind, { disabled: !writable });
    const story = selectEl('autoStory', modes, s.autoStory, { disabled: !writable });
    const news = el('input', { type: 'checkbox', name: 'autoNewsletter', checked: s.autoNewsletter, disabled: !writable });
    const form = el('form', { class: 'card' }, el('h2', { text: 'When a product is published' }),
      el('p', { class: 'muted small', text: 'The studio can make content automatically the first time a product goes live. '
        + 'Drafts wait for you in Buying guides, Web Stories and Campaigns. Newsletters are always saved as drafts and never sent automatically.' }),
      el('div', { class: 'row' }, UI.field('Blog post', blog), UI.field('Blog type', kind)),
      el('div', { class: 'row' }, UI.field('Web Story', story, 'Needs a product photo (JPEG or PNG).')),
      el('label', { class: 'check' }, news, ' Create a launch newsletter draft'),
      writable ? el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: 'Save' })) : null);
    K.onSubmit(form, () => Api.put(S + '/settings', { autoBlog: blog.value, blogKind: kind.value, autoStory: story.value, autoNewsletter: news.checked }), 'Saved');
    mount(view(), head('Content studio'), tabs('automation'), form);
  }

  // ------------------------------------------------------------------ history

  async function historyView() {
    const rows = await Api.get(S + '/history?limit=100');
    const KIND = { BLOG: 'Blog post', STORY: 'Web Story', NEWSLETTER: 'Newsletter draft' };
    mount(view(), head('Content studio'), tabs('history'),
      el('div', { class: 'card' }, table(['Created', 'Type', 'Title', 'How'], rows.map((r) => el('tr', null,
        el('td', { class: 'small', text: UI.date(r.createdAt, true) }),
        el('td', { text: KIND[r.kind] || r.kind }),
        el('td', null, el('a', { href: r.adminPath, text: r.title || '(untitled)' })),
        el('td', { class: 'small', text: r.automatic ? 'Automatic' : 'By hand' }))), 'Nothing created yet.')));
  }

  K.register('studio', 'Content studio', 'CATALOG_READ', studioRoute, 'guides');
})();
