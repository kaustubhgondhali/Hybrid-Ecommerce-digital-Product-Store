/* Admin: bulk import of affiliate products from a spreadsheet (CSV), with a check before anything is saved. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, SF, view, head, table } = K;

  const COLUMNS = ['title', 'link', 'price', 'mrp', 'category', 'brand', 'store', 'summary', 'highlights'];

  function csvCell(v) {
    const s = String(v == null ? '' : v);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function template(category) {
    const rows = SF === 'digital'
      ? [['Python for Beginners — Complete Course', 'https://www.udemy.com/course/your-course/?referralCode=YOURCODE', '499', '3199', category, 'Udemy', 'Udemy',
        'Learn Python from zero with 40 hours of video and projects.', 'Lifetime access | Certificate of completion | 30-day refund'],
        ['Canva Pro — 1 Year', 'https://partner.canva.com/your-link', '3999', '', category, 'Canva', 'Canva', 'Premium templates, brand kit and background remover.', '']]
      : [['boAt Airdopes 141 Wireless Earbuds', 'https://www.amazon.in/dp/B09N3ZNHTY?tag=yourtag-21', '1299', '4490', category, 'boAt', 'Amazon',
        '42 hours of playback with low-latency mode for gaming.', '42H playback | ENx noise cancellation | IPX4 water resistance'],
        ['Philips LED Desk Lamp', 'https://www.flipkart.com/your-product?affid=yourid', '899', '1499', category, 'Philips', 'Flipkart', 'Eye-comfort light with 3 brightness levels.', '']];
    return [COLUMNS, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';
  }

  function download(name, text) {
    const url = URL.createObjectURL(new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' }));
    const a = el('a', { href: url, download: name });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const STATUS = { READY: ['ACTIVE', 'Ready'], CREATED: ['ACTIVE', 'Added'], NEW_STORE: ['PENDING', 'New store'], ERROR: ['FAILED', 'Problem'] };

  function report(r) {
    const rows = r.rows.map((x) => el('tr', null,
      el('td', { class: 'num muted', text: String(x.row) }),
      el('td', null, x.productId ? el('a', { href: '#/products/' + x.productId, text: x.title }) : el('span', { text: x.title || '—' }),
        x.domain ? el('div', { class: 'muted small', text: (x.store ? x.store + ' · ' : '') + x.domain }) : null),
      el('td', { class: 'small', text: x.category || '' }),
      el('td', { class: 'num', text: x.priceMinor != null ? UI.money(x.priceMinor) : '' }),
      el('td', null, UI.badge(STATUS[x.status][0], STATUS[x.status][1])),
      el('td', { class: 'small', text: x.message })));
    return table([el('th', { class: 'num', text: 'Row' }), 'Product', 'Category', el('th', { class: 'num', text: 'Price' }), 'Status', 'Note'], rows);
  }

  async function importView() {
    const cats = await Api.get('/api/' + SF + '/catalog/categories').catch(() => []);
    const file = el('input', { type: 'file', accept: '.csv,.tsv,.txt,text/csv', 'aria-label': 'CSV file' });
    const paste = el('textarea', { rows: '6', class: 'mono', placeholder: 'Or paste rows copied from Google Sheets or Excel here (with the header row).' });
    const approve = el('input', { type: 'checkbox' });
    const approveBox = el('label', { class: 'check', hidden: true }, approve, el('span'));
    const out = el('div');
    const msg = el('div', { class: 'form-msg' });
    let text = '';

    file.addEventListener('change', async () => {
      text = file.files[0] ? await file.files[0].text() : '';
      paste.value = '';
    });
    const source = () => (paste.value.trim() ? paste.value : text);

    const send = async (dryRun, btn) => UI.busy(btn, async () => {
      mount(msg);
      if (!source().trim()) { mount(msg, el('div', { class: 'alert error', text: 'Choose a CSV file or paste your rows first.' })); return; }
      try {
        const r = await Api.post(API + '/products/import', { csv: source(), dryRun, approveNewStores: approve.checked });
        approveBox.hidden = !r.newStores.length;
        approveBox.lastChild.textContent = ' I approve the new partner stores: ' + r.newStores.join(', ') + ' (shoppers will be sent there)';
        const summary = dryRun
          ? r.ready + ' of ' + r.total + ' products are ready to import' + (r.failed ? ' · ' + r.failed + ' have a problem (fix them in the file and check again)' : '')
          : r.created + ' products added as drafts' + (r.failed ? ' · ' + r.failed + ' could not be added' : '');
        const importBtn = dryRun && r.ready ? el('button', { class: 'btn', type: 'button', text: 'Import ' + r.ready + (r.ready === 1 ? ' product' : ' products') }) : null;
        if (importBtn) importBtn.addEventListener('click', () => send(false, importBtn));
        mount(out, el('div', { class: 'card' },
          el('div', { class: 'card-head' }, el('h2', { text: dryRun ? 'Check result' : 'Import result' }), importBtn),
          el('p', { class: dryRun ? 'muted' : 'ok-text', text: summary }),
          !dryRun && r.created ? el('p', { class: 'muted small' }, 'Next: open each product, add a photo and press Publish. ',
            el('a', { href: '#/products', text: 'Go to products' })) : null,
          report(r)));
        if (!dryRun) UI.toast(r.created + ' products added', 'success');
      } catch (e) {
        if (!K.handleAuthError(e)) mount(msg, UI.errorAlert(e));
      }
    });
    const check = el('button', { class: 'btn', type: 'button', text: 'Check file' });
    check.addEventListener('click', () => send(true, check));

    mount(view(), head('Import products', el('button', { class: 'btn secondary', type: 'button', text: 'Download template',
      on: { click: () => download(SF + '-products-template.csv', template(cats[0] ? cats[0].name : '')) } })),
    el('div', { class: 'card' },
      el('p', null, 'Add many affiliate products at once from a spreadsheet. Each row becomes a ', el('strong', { text: 'draft' }),
        ' with its partner link and price. Then add a photo to each and press Publish.'),
      el('ul', { class: 'story-tips' },
        el('li', { text: 'Needed columns: title and link (your affiliate link). Optional: price and mrp in rupees, category, brand, store, summary, highlights (separate with |).' }),
        el('li', { text: 'Categories must already exist: ' + (cats.length ? cats.map((c) => c.name).join(', ') : 'none yet — create them under Categories') + '.' }),
        el('li', { text: 'In Excel use File → Save as → “CSV UTF-8”, so ₹ and Hindi text stay correct. Up to 500 products per file.' }),
        el('li', { text: 'Nothing is saved when you press “Check file”. Products already in your catalog (same title) are skipped.' }))),
    el('div', { class: 'card' }, msg,
      UI.field('Spreadsheet file (CSV)', file), paste, approveBox,
      el('div', { class: 'actions' }, check)),
    out);
  }

  K.register('import', 'Import products', 'CATALOG_WRITE', importView, 'products');
})();
