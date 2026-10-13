/* Admin: buying guides / blog. Articles with product cards, published at /{store}/guides/{slug}. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, SF, can, view, head, table, input } = K;

  const TEMPLATE = [
    'Looking for the best wireless earbuds under ₹3,000? We compared the most popular options in India so you can choose in five minutes.',
    '',
    '> Quick answer: our top pick is the first product below. It has the best sound and battery for the price.',
    '',
    '# Our top picks',
    '(Use “Insert products row” to show your 3 best picks here.)',
    '',
    '# 1. Product name — best overall',
    'Who it is for, what we like, and what to keep in mind.',
    '(Use “Insert product” to show the product card here.)',
    '',
    '# Quick comparison',
    '| Feature | Product A | Product B | Product C |',
    '|---|---|---|---|',
    '| Battery | | | |',
    '| Best for | | | |',
    '',
    '# How to choose',
    '- Budget: what you get at each price',
    '- The one feature that matters most',
    '- What you can skip',
    '',
    '# FAQs',
    '## Is it worth paying more?',
    'A short, honest answer.'
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

  function counter(field, good, max) {
    const out = el('span', { class: 'muted small' });
    const update = () => {
      const n = field.value.length;
      out.textContent = n + ' characters' + (n > max ? ' — too long for Google, it will be cut' : n >= good * 0.6 ? ' — good' : '');
    };
    field.addEventListener('input', update);
    update();
    return out;
  }

  // ------------------------------------------------------------------ list

  async function guidesRoute(parts) {
    if (parts[0]) return editView(parts[0]);
    const list = await Api.get(API + '/guides');
    const writable = can('CATALOG_WRITE');
    mount(view(), head('Buying guides', el('a', { class: 'btn secondary', href: '/' + SF + '/guides', target: '_blank', rel: 'noopener', text: 'View on site' }),
      writable ? el('a', { class: 'btn', href: '#/guides/new', text: 'New guide' }) : null),
      el('div', { class: 'card' }, el('p', { class: 'muted', text: 'Guides like “Best earbuds under ₹2,000” bring visitors from Google and help them decide. '
        + 'Each guide shows your products as cards with a buy button, and appears on the home page, in the menu and on the products it mentions.' })),
      el('div', { class: 'card' }, table(['Guide', 'Status', el('th', { class: 'num', text: 'Products' }), 'Updated', ''], list.map((g) => {
        const tr = el('tr', { class: 'clickable' },
          el('td', null, el('a', { href: '#/guides/' + g.id, text: g.title }), el('div', { class: 'muted small' }, el('code', { text: g.url }))),
          el('td', null, UI.badge(g.status)),
          el('td', { class: 'num', text: String(g.productCount) }),
          el('td', { class: 'small', text: UI.date(g.updatedAt) }),
          el('td', null, g.status === 'PUBLISHED' ? el('a', { href: g.url, target: '_blank', rel: 'noopener', text: 'View' }) : null));
        tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/guides/' + g.id; });
        return tr;
      }), 'No guides yet. Click “New guide” — a ready-made outline helps you start.')));
  }

  // ------------------------------------------------------------------ product picker

  /** Search published products; "one" inserts [product: slug], "row" collects up to 4 for [products: a, b, c]. */
  function productPicker(onPick) {
    const search = el('input', { type: 'search', placeholder: 'Search your products…', 'aria-label': 'Search products' });
    const results = el('div', { class: 'product-checklist picker-results' });
    const chosen = el('div', { class: 'chosen-products' });
    let mode = 'one';
    let picked = [];
    const insertRow = el('button', { class: 'btn small', type: 'button', text: 'Insert these products', disabled: true });
    const drawChosen = () => {
      mount(chosen, picked.map((p) => el('span', { class: 'chip' }, p.title,
        el('button', { class: 'chip-remove', type: 'button', 'aria-label': 'Remove ' + p.title, text: '×',
          on: { click: () => { picked = picked.filter((x) => x.slug !== p.slug); drawChosen(); } } }))));
      insertRow.disabled = picked.length < 2;
    };
    insertRow.addEventListener('click', () => {
      onPick('[products: ' + picked.map((p) => p.slug).join(', ') + ']');
      picked = [];
      drawChosen();
      box.hidden = true;
    });
    let timer;
    const load = () => {
      Api.get(API + '/products?size=20&status=PUBLISHED' + (search.value.trim() ? '&q=' + encodeURIComponent(search.value.trim()) : '')).then((res) => {
        mount(results, res.items.length ? res.items.map((p) => el('button', { class: 'product-option check', type: 'button',
          on: { click: () => {
            if (mode === 'one') { onPick('[product: ' + p.slug + ']'); box.hidden = true; return; }
            if (!picked.some((x) => x.slug === p.slug) && picked.length < 4) picked.push(p);
            drawChosen();
          } } },
          p.imageUrl ? el('img', { src: p.imageUrl, alt: '' }) : null,
          el('span', null, p.title, el('span', { class: 'muted small', text: '  ' + p.slug })))) : el('p', { class: 'muted small', text: 'No published products match.' }));
      }).catch((e) => mount(results, UI.errorAlert(e)));
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 200); });
    const title = el('strong');
    const rowBar = el('div', { class: 'actions' }, insertRow, el('span', { class: 'muted small', text: 'Pick 2 to 4 products for a “top picks” row.' }));
    const box = el('div', { class: 'card picker', hidden: true }, title, search, chosen, rowBar, results,
      el('div', { class: 'actions' }, el('button', { class: 'btn ghost small', type: 'button', text: 'Close', on: { click: () => { box.hidden = true; } } })));
    box.open = (m) => {
      mode = m;
      picked = [];
      drawChosen();
      title.textContent = m === 'one' ? 'Click a product to insert its card' : 'Click 2 to 4 products, then “Insert these products”';
      rowBar.hidden = m === 'one';
      box.hidden = false;
      search.focus();
      load();
    };
    return box;
  }

  // ------------------------------------------------------------------ editor

  async function editView(id) {
    const isNew = id === 'new';
    const g = isNew ? { title: '', slug: '', summary: '', body: '', authorName: '', metaTitle: '', metaDescription: '', status: 'DRAFT' }
      : await Api.get(API + '/guides/' + id);
    const writable = can('CATALOG_WRITE');
    const body = el('textarea', { name: 'body', rows: '26', maxlength: '60000', disabled: !writable, class: 'mono guide-editor' });
    body.value = g.body || '';
    const summary = el('textarea', { name: 'summary', rows: '2', maxlength: '400', disabled: !writable,
      placeholder: 'One or two sentences under the title, e.g. “We tested 12 pairs. These 5 are worth your money.”' });
    summary.value = g.summary || '';
    const metaTitle = input('metaTitle', { value: g.metaTitle || '', maxlength: '200', class: 'wide', disabled: !writable,
      placeholder: 'Best Wireless Earbuds Under ₹3,000 in India (' + new Date().getFullYear() + ')' });
    const metaDescription = el('textarea', { name: 'metaDescription', rows: '2', maxlength: '320', disabled: !writable });
    metaDescription.value = g.metaDescription || '';
    const title = input('title', { value: g.title, required: true, maxlength: '200', disabled: !writable, placeholder: 'Best wireless earbuds under ₹3,000' });
    const slug = input('slug', { value: g.slug, maxlength: '140', disabled: !writable, placeholder: 'made from the title' });
    const author = input('authorName', { value: g.authorName || '', maxlength: '120', disabled: !writable, placeholder: 'Your name (optional)' });
    const msg = el('div', { class: 'form-msg' });
    const picker = productPicker((line) => insertAtCursor(body, line));

    const save = async (publish, btn) => {
      const payload = { title: title.value, slug: slug.value.trim() || null, summary: summary.value, body: body.value, authorName: author.value,
        metaTitle: metaTitle.value, metaDescription: metaDescription.value, publish };
      await UI.busy(btn, async () => {
        mount(msg);
        try {
          const saved = isNew ? await Api.post(API + '/guides', payload) : await Api.put(API + '/guides/' + id, payload);
          UI.toast(publish === true ? 'Guide published' : publish === false ? 'Guide moved to drafts' : 'Guide saved', 'success');
          if (isNew) location.hash = '#/guides/' + saved.id; else editView(saved.id);
        } catch (e) {
          if (!K.handleAuthError(e)) mount(msg, UI.errorAlert(e));
        }
      });
    };
    const button = (text, cls, publish) => {
      const b = el('button', { class: 'btn ' + cls, type: 'button', text });
      b.addEventListener('click', () => save(publish, b));
      return b;
    };
    const published = g.status === 'PUBLISHED';
    const tools = writable ? el('div', { class: 'actions guide-tools' },
      el('button', { class: 'btn small', type: 'button', text: 'Insert product', on: { click: () => picker.open('one') } }),
      el('button', { class: 'btn small secondary', type: 'button', text: 'Insert products row', on: { click: () => picker.open('row') } }),
      el('button', { class: 'btn small ghost', type: 'button', text: 'Heading', on: { click: () => insertAtCursor(body, '# Section title') } }),
      el('button', { class: 'btn small ghost', type: 'button', text: 'Comparison table', on: { click: () => insertAtCursor(body,
        '| Feature | Product A | Product B |\n|---|---|---|\n| Price | ₹ | ₹ |\n| Best for | | |') } }),
      el('button', { class: 'btn small ghost', type: 'button', text: 'Highlighted note', on: { click: () => insertAtCursor(body, '> Tip: ') } }),
      !body.value.trim() ? el('button', { class: 'btn small ghost', type: 'button', text: 'Start from an outline',
        on: { click: (e) => { body.value = TEMPLATE; e.target.remove(); body.focus(); } } }) : null) : null;

    const form = el('div', { class: 'guide-edit-grid' },
      el('div', { class: 'card' },
        msg,
        el('div', { class: 'row' }, UI.field('Title', title), UI.field('Web address', slug, '/' + SF + '/guides/…')),
        UI.field('Summary', summary),
        UI.field('Author', author),
        UI.field('Guide text', body, 'Formatting: "# " big heading, "## " small heading, "- " bullet, "> " highlighted note, [link text](https://…), '
          + 'tables with "|". A line [product: web-address] shows a product card; [products: a, b, c] shows a row of top picks.'),
        tools, picker,
        el('div', { class: 'row' },
          el('div', { class: 'field' }, el('label', { text: 'Search title (optional)' }), metaTitle, counter(metaTitle, 60, 65)),
          el('div', { class: 'field' }, el('label', { text: 'Search description (optional)' }), metaDescription, counter(metaDescription, 160, 165))),
        writable ? el('div', { class: 'actions' },
          published ? button('Save changes', '', null) : button('Publish', '', true),
          published ? button('Move to drafts', 'ghost', false) : button('Save draft', 'secondary', null),
          isNew ? null : el('a', { class: 'btn ghost', href: API + '/guides/' + id + '/preview', target: '_blank', rel: 'noopener', text: 'Preview' }),
          isNew ? null : el('button', { class: 'btn ghost danger-text', type: 'button', text: 'Delete', on: { click: async () => {
            if (!confirm('Delete "' + g.title + '"? Its page will show "page not found".')) return;
            await Api.del(API + '/guides/' + id);
            UI.toast('Guide deleted', 'success');
            location.hash = '#/guides';
          } } })) : null),
      el('aside', { class: 'card guide-tips' }, el('h2', { text: 'Write a guide that ranks' }),
        el('ol', { class: 'story-tips' },
          el('li', { text: 'Answer one question people search for: “best X under ₹Y”, “X vs Y”, “how to choose X”.' }),
          el('li', { text: 'Give the quick answer first, then the details.' }),
          el('li', { text: 'Compare 3–5 products and say who each one is for.' }),
          el('li', { text: 'Add a comparison table — shoppers and Google love them.' }),
          el('li', { text: 'Be honest about downsides. It builds trust and sales.' }),
          el('li', { text: 'Update it every month or two; the date shows “Updated”.' })),
        el('p', { class: 'muted small', text: 'Check “Sales & demand” → “Searched, but you don’t have it” for guide ideas.' })));

    mount(view(), el('p', { class: 'small' }, el('a', { href: '#/guides', text: '← Buying guides' }),
      published ? [' · ', el('a', { href: g.url, target: '_blank', rel: 'noopener', text: 'View live guide' })] : null),
      head(isNew ? 'New guide' : g.title, isNew ? null : UI.badge(g.status)),
      isNew ? null : el('p', { class: 'muted small', text: g.productCount + (g.productCount === 1 ? ' product' : ' products') + ' · about ' + g.readMinutes + ' min read' }),
      form);
  }

  K.register('guides', 'Buying guides', 'CATALOG_READ', guidesRoute, 'stories');
})();
