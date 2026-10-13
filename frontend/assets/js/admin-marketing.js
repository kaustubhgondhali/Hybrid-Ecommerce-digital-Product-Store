/* Admin: coupon codes and deals at partner stores (shown on /{store}/offers, store pages and product pages). */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, SF, can, view, head, table, onSubmit, input } = K;

  const local = (iso) => {
    if (!iso) return '';
    const d = new Date(iso);
    const pad = (n) => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T' + pad(d.getHours()) + ':' + pad(d.getMinutes());
  };
  const iso = (v) => (v ? new Date(v).toISOString() : null);

  function state(o) {
    const now = Date.now();
    if (!o.active) return ['DISABLED', 'Paused'];
    if (o.endsAt && new Date(o.endsAt).getTime() <= now) return ['EXPIRED', 'Expired'];
    if (o.startsAt && new Date(o.startsAt).getTime() > now) return ['PENDING', 'Scheduled'];
    return ['ACTIVE', 'Live'];
  }

  async function offersView() {
    const offers = await Api.get(API + '/offers');
    const writable = can('AFFILIATE_WRITE');
    let editing = null;

    const products = el('select', { name: 'productId', 'aria-label': 'Product' }, el('option', { value: '0', text: 'Any product at this store' }));
    Api.get(API + '/products?size=100&status=PUBLISHED').then((res) => res.items.forEach((p) =>
      products.appendChild(el('option', { value: String(p.id), text: p.title })))).catch(() => {});
    const form = el('form', null,
      el('div', { class: 'row' },
        UI.field('Affiliate link for the offer', input('url', { required: true, maxlength: '2048', placeholder: 'https://www.udemy.com/courses/...?ref=you' }),
          'Where the shopper lands. It must be on a store you have approved (or confirm a new one).'),
        UI.field('Store name (only for a new store)', input('merchantName', { maxlength: '120', placeholder: 'Udemy' }))),
      el('div', { class: 'row' },
        UI.field('Title', input('title', { required: true, maxlength: '140', placeholder: 'Flat 20% off all courses' })),
        UI.field('Coupon code (optional)', input('code', { maxlength: '40', placeholder: 'SAVE20', class: 'upper' }), 'Leave empty for a deal that needs no code.')),
      UI.field('Details (optional)', input('description', { maxlength: '500', placeholder: 'Valid on orders above ₹999. New users only.' })),
      el('div', { class: 'row' },
        UI.field('For product', products),
        UI.field('Starts (optional)', input('startsAt', { type: 'datetime-local' })),
        UI.field('Ends (optional)', input('endsAt', { type: 'datetime-local' }))),
      el('label', { class: 'check mb' }, input('verified', { type: 'checkbox', checked: true }), ' I checked that this code works today (shows a “Verified” badge)'),
      el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: 'Save offer' }),
        el('button', { class: 'btn ghost', type: 'reset', text: 'Clear' })));
    form.addEventListener('reset', () => { editing = null; form.querySelector('button[type=submit]').textContent = 'Save offer'; });
    const save = async (v, confirmNewDomain) => {
      const body = { url: v.url, merchantName: v.merchantName, title: v.title, code: v.code, description: v.description,
        productId: Number(v.productId || 0), startsAt: iso(v.startsAt), endsAt: iso(v.endsAt), verified: v.verified, confirmNewDomain };
      return editing ? Api.put(API + '/offers/' + editing, body) : Api.post(API + '/offers', body);
    };
    onSubmit(form, async (v) => {
      try {
        await save(v, false);
      } catch (e) {
        if (e.code !== 'AFFILIATE_DOMAIN_UNCONFIRMED') throw e;
        if (!confirm(e.message + '\n\nApprove this store?')) return;
        await save(v, true);
      }
      UI.toast(editing ? 'Offer updated' : 'Offer added', 'success');
      offersView();
    });

    const edit = (o) => {
      editing = o.id;
      form.elements.url.value = o.url;
      form.elements.title.value = o.title;
      form.elements.code.value = o.code || '';
      form.elements.description.value = o.description || '';
      form.elements.productId.value = String(o.productId || 0);
      form.elements.startsAt.value = local(o.startsAt);
      form.elements.endsAt.value = local(o.endsAt);
      form.querySelector('button[type=submit]').textContent = 'Update offer';
      form.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };
    const action = (label, fn, cls) => {
      const b = el('button', { class: 'btn small ' + (cls || 'ghost'), type: 'button', text: label });
      b.addEventListener('click', () => UI.busy(b, async () => {
        try { await fn(); offersView(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return b;
    };
    const rows = offers.map((o) => {
      const [status, label] = state(o);
      return el('tr', null,
        el('td', null, el('strong', { text: o.title }), el('div', { class: 'muted small', text: o.merchantName + (o.productTitle ? ' · ' + o.productTitle : '') })),
        el('td', null, o.code ? el('code', { text: o.code }) : el('span', { class: 'muted', text: 'No code' })),
        el('td', { class: 'small', text: o.endsAt ? UI.date(o.endsAt) : 'No end date' }),
        el('td', null, UI.badge(status, label), o.verifiedAt ? el('div', { class: 'muted small', text: 'Checked ' + UI.date(o.verifiedAt, false) }) : null),
        el('td', { class: 'num', text: String(o.clicks) }),
        el('td', null, writable ? el('div', { class: 'actions' },
          (() => { const b = el('button', { class: 'btn small ghost', type: 'button', text: 'Edit' }); b.addEventListener('click', () => edit(o)); return b; })(),
          action('Verified today', () => Api.put(API + '/offers/' + o.id, { verified: true })),
          action(o.active ? 'Pause' : 'Resume', () => Api.put(API + '/offers/' + o.id, { active: !o.active })),
          action('Delete', async () => { if (confirm('Delete this offer?')) await Api.del(API + '/offers/' + o.id); })) : null));
    });

    mount(view(), head('Coupons & offers', el('a', { class: 'btn secondary', href: '/' + SF + '/offers', target: '_blank', rel: 'noopener', text: 'View on site' })),
      el('div', { class: 'card' }, el('p', { class: 'muted', text: 'Coupon codes are one of the strongest reasons shoppers click through. '
        + 'Each offer appears on the Coupons page, on its store’s page and on matching product pages. Shoppers copy the code and go to the store through your tracked link.' })),
      writable ? el('div', { class: 'card' }, el('h2', { text: 'Add an offer' }), form) : null,
      el('div', { class: 'card' }, table(['Offer', 'Code', 'Ends', 'Status', el('th', { class: 'num', text: 'Clicks' }), ''], rows,
        'No offers yet. Add the coupon codes your affiliate networks give you.')));
  }

  K.register('offers', 'Coupons & offers', 'AFFILIATE_READ', offersView, 'affiliate');
})();
