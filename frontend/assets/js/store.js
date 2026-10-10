/* Storefront pages: catalog, product, cart, checkout, payment. */
(function () {
  'use strict';
  const { el, mount, money } = UI;

  const pages = {
    'store-index': catalogPage,
    'store-product': productPage,
    'store-cart': cartPage,
    'store-checkout': checkoutPage,
    'store-pay': payPage,
    'store-wishlist': wishlistPage,
    // content pages are fully rendered by the server; only the header and footer are added here
    'store-page': async () => {}
  };

  document.addEventListener('DOMContentLoaded', async () => {
    const page = document.body.dataset.page;
    try {
      const ctx = await Site.renderChrome({ search: page === 'store-index' || page === 'store-product' });
      await pages[page](ctx);
    } catch (e) {
      Site.pageError(e);
    }
  });

  function main() {
    return document.getElementById('main');
  }

  function isDemoProduct(p) {
    return !!(p && ((p.slug && p.slug.indexOf('demo-') === 0)
      || (p.title && p.title.indexOf('[Demo]') === 0)
      || (p.summary && p.summary.indexOf('DEMO PRODUCT') === 0)));
  }

  function affiliateOnly() {
    return !(window.HPP_STORE && window.HPP_STORE.affiliateOnly === false);
  }

  function typeBadge(p) {
    const parts = [];
    if (isDemoProduct(p)) parts.push(el('span', { class: 'badge amber', text: 'Demo' }), ' ');
    parts.push(UI.badge(p.productType === 'DIGITAL' ? 'SHIPPED' : 'DRAFT', p.productType === 'DIGITAL' ? 'Digital' : 'Physical'));
    if (p.sellingMethod === 'AFFILIATE' || affiliateOnly()) {
      parts.push(' ', el('span', { class: 'badge purple', text: 'Partner offer' }));
    }
    return parts;
  }

  // ------------------------------------------------------------------ catalog

  async function catalogPage({ sf, storeName }) {
    const params = new URLSearchParams(location.search);
    const pathCategory = decodeURIComponent((location.pathname.split('/c/')[1] || '').replace(/\/$/, ''));
    if (pathCategory) params.set('category', pathCategory);
    const page = Math.max(parseInt(params.get('page') || '0', 10) || 0, 0);
    const query = new URLSearchParams();
    ['q', 'category', 'type', 'method', 'sort'].forEach((k) => { if (params.get(k)) query.set(k, params.get(k)); });
    query.set('page', String(page));
    query.set('size', '24');

    const [categories, result] = await Promise.all([
      Api.get('/api/' + sf + '/catalog/categories'),
      Api.get('/api/' + sf + '/catalog/products?' + query.toString())
    ]);
    const category = categories.find((c) => c.slug === params.get('category'));

    const filterForm = el('form', { class: 'filters', id: 'catalog', method: 'get', action: '/' + sf },
      params.get('q') ? el('input', { type: 'hidden', name: 'q', value: params.get('q') }) : null,
      select('category', [['', 'All categories'], ...categories.map((c) => [c.slug, c.name])], params.get('category')),
      select('type', [['', 'All types'], ['PHYSICAL', 'Physical'], ['DIGITAL', 'Digital']], params.get('type')),
      affiliateOnly() ? null : select('method', [['', 'All offers'], ['DIRECT', 'Sold by us'], ['AFFILIATE', 'Partner offers']], params.get('method')),
      select('sort', [['', 'Newest'], ['title', 'Name']], params.get('sort')));
    const go = () => {
      const values = new URLSearchParams();
      new FormData(filterForm).forEach((v, k) => { if (v && k !== 'category') values.set(k, v); });
      const cat = filterForm.elements.category.value;
      const qs = values.toString();
      location.href = '/' + sf + (cat ? '/c/' + encodeURIComponent(cat) : '') + (qs ? '?' + qs : '');
    };
    filterForm.addEventListener('submit', (e) => { e.preventDefault(); go(); });
    filterForm.querySelectorAll('select').forEach((s) => s.addEventListener('change', go));

    const heading = params.get('q') ? 'Results for \u201C' + params.get('q') + '\u201D' : category ? category.name : storeName;
    const grid = result.items.length
      ? el('div', { class: 'grid' }, result.items.map((p) => productCard(sf, p)))
      : el('div', { class: 'empty card' }, 'No products found.');

    const pager = result.totalPages > 1 ? el('div', { class: 'pager' },
      page > 0 ? el('a', { class: 'btn secondary small', href: pageHref(params, page - 1), text: 'Previous' }) : null,
      el('span', { class: 'muted small', text: 'Page ' + (page + 1) + ' of ' + result.totalPages }),
      page + 1 < result.totalPages ? el('a', { class: 'btn secondary small', href: pageHref(params, page + 1), text: 'Next' }) : null) : null;

    const filtered = ['q', 'category', 'type', 'method'].some((k) => params.get(k)) || page > 0;
    mount(main(), el('div', { class: 'container' },
      filtered ? el('h1', { text: heading }) : storeHero(sf, storeName),
      filtered ? null : el('div', { class: 'section-head' }, el('h2', { text: 'Latest products' })),
      filterForm, grid, pager));
  }

  function storeHero(sf, storeName) {
    const b = Site.BRANDS[sf];
    return el('section', { class: 'store-hero' },
      el('div', null,
        el('div', { class: 'eyebrow', text: b.eyebrow }),
        el('h1', null, b.headline[0], el('span', { class: 'accent', text: b.headline[1] }), b.headline[2]),
        el('p', { text: b.tagline }),
        el('div', { class: 'actions mt' },
          el('a', { class: 'btn', href: '#catalog', text: affiliateOnly() ? 'Browse partner deals' : 'Shop now' }),
          el('a', { class: 'btn secondary', href: '/' + (sf === 'shop' ? 'digital' : 'shop'),
            text: 'Visit ' + Site.STOREFRONTS[sf === 'shop' ? 'digital' : 'shop'] }))),
      el('img', { class: 'hero-logo', src: b.logo, alt: storeName }));
  }

  function pageHref(params, page) {
    const p = new URLSearchParams(params);
    if (location.pathname.includes('/c/')) p.delete('category');
    p.set('page', String(page));
    return location.pathname + '?' + p.toString();
  }

  function select(name, options, current) {
    return el('select', { name, 'aria-label': name },
      options.map(([value, text]) => el('option', { value, text, selected: (current || '') === value })));
  }

  function productCard(sf, p) {
    const href = '/' + sf + '/p/' + encodeURIComponent(p.slug);
    return el('a', { class: 'product-card', href },
      el('div', { class: 'thumb' }, p.imageUrl ? el('img', { src: p.imageUrl, alt: p.title, loading: 'lazy', decoding: 'async' }) : 'No image'),
      el('div', { class: 'body' },
        el('div', null, typeBadge(p)),
        el('div', { class: 'title', text: p.title }),
        p.summary ? el('div', { class: 'muted small', text: p.summary }) : null,
        el('div', { class: 'spacer' }),
        p.priceMinor != null ? el('div', { class: 'price' }, money(p.priceMinor),
          p.compareAtMinor ? el('s', { text: money(p.compareAtMinor) }) : null) : null,
        p.merchantName ? el('div', { class: 'muted small', text: 'Sold by ' + p.merchantName }) : null,
        !affiliateOnly() && p.sellingMethod === 'DIRECT' && p.inStock === false
          ? el('div', { class: 'small', text: 'Out of stock' }) : null));
  }

  // ------------------------------------------------------------------ product

  async function productPage({ sf, user }) {
    const slug = decodeURIComponent((location.pathname.split('/p/')[1] || '').replace(/\/$/, ''));
    const p = await Api.get('/api/' + sf + '/catalog/products/' + encodeURIComponent(slug));
    const ratingLine = el('div', { class: 'rating-line' });

    const first = p.images[0];
    const mainImg = el('img', {
      src: first ? (first.largeUrl || first.url) : '', alt: first ? (first.alt || p.title) : '', fetchpriority: 'high'
    });
    const thumbs = p.images.length > 1 ? el('div', { class: 'thumbs' }, p.images.map((img, i) => {
      const b = el('button', { type: 'button', class: i === 0 ? 'active' : '', 'aria-label': 'Image ' + (i + 1) },
        el('img', { src: img.thumbUrl || img.url, alt: '', loading: 'lazy', decoding: 'async' }));
      b.addEventListener('click', () => {
        mainImg.src = img.largeUrl || img.url;
        mainImg.alt = img.alt || p.title;
        b.parentElement.querySelectorAll('button').forEach((x) => x.classList.remove('active'));
        b.classList.add('active');
      });
      return b;
    })) : null;
    const gallery = el('div', { class: 'gallery' },
      el('div', { class: 'main' }, p.images.length ? mainImg : el('span', { class: 'muted', text: 'No image' })), thumbs);

    const info = el('div');
    UI.append(info, [
      el('div', { class: 'mb' }, typeBadge(p)),
      el('h1', null, isDemoProduct(p) ? el('span', { class: 'badge amber', text: 'Demo product' }) : null,
        isDemoProduct(p) ? ' ' : null, p.title),
      isDemoProduct(p) ? el('p', { class: 'note', text: 'This is a sample affiliate listing so you can see how the store looks. The Buy button opens a demo partner page — delete it in the admin panel when you add real offers.' }) : null,
      affiliateOnly() ? el('p', { class: 'muted small', text: 'Affiliate disclosure: we may earn a commission when you buy through partner links. Price and fulfilment are set by the partner store.' }) : null,
      ratingLine,
      p.brand ? el('p', { class: 'muted', text: 'by ' + p.brand }) : null,
      p.summary ? el('p', { text: p.summary }) : null,
      wishlistButton(sf, p.slug, user)
    ]);

    if (p.sellingMethod === 'AFFILIATE') {
      const offer = p.affiliate;
      const variant = (p.variants && p.variants[0]) || null;
      if (offer) {
        const addBtn = el('button', { class: 'btn secondary', type: 'button', text: 'Add to cart' });
        const msg = el('div');
        addBtn.disabled = !variant || !variant.available;
        addBtn.addEventListener('click', () => UI.busy(addBtn, async () => {
          if (!user) { location.href = Site.loginUrl(); return; }
          try {
            await Api.post('/api/' + sf + '/cart/items', { variantId: variant.id, quantity: 1 });
            mount(msg, el('div', { class: 'alert success' }, 'Saved to your cart for later. ',
              el('a', { href: '/' + sf + '/cart', text: 'View cart' })));
          } catch (e) {
            mount(msg, UI.errorAlert(e));
          }
        }));
        UI.append(info, [
          offer.displayPriceMinor != null ? el('p', { class: 'price', text: money(offer.displayPriceMinor) }) : null,
          offer.displayPriceMinor != null && offer.priceCheckedAt
            ? el('p', { class: 'muted small', text: 'Price at ' + offer.merchantName + ' as of ' + UI.date(offer.priceCheckedAt, false) + '. The final price is set by the partner store.' })
            : null,
          offer.priceStale
            ? el('p', { class: 'muted small', text: 'See the current price at ' + offer.merchantName + '.' })
            : null,
          el('div', { class: 'actions mb' },
            el('a', { class: 'btn', href: offer.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer', text: 'Buy from ' + offer.merchantName }),
            addBtn),
          el('p', { class: 'muted small', text: 'Add to cart to save this deal and buy from the partner when you are ready.' }),
          msg,
          el('p', { class: 'disclosure', text: offer.disclosure })
        ]);
      } else {
        info.appendChild(el('div', { class: 'alert warning', text: 'This partner offer is currently unavailable.' }));
      }
    } else {
      const active = p.variants;
      let selected = active.find((v) => v.available) || active[0];
      const priceEl = el('p', { class: 'price' });
      const stockEl = el('p', { class: 'small' });
      const qty = el('input', { type: 'number', min: '1', max: '20', value: '1', class: 'qty', 'aria-label': 'Quantity' });
      const addBtn = el('button', { class: 'btn', type: 'button', text: 'Add to cart' });
      const msg = el('div');
      const renderSelected = () => {
        mount(priceEl, money(selected.priceMinor), selected.compareAtMinor ? el('s', { text: money(selected.compareAtMinor) }) : null);
        mount(stockEl, selected.available ? (p.productType === 'DIGITAL' ? 'Instant delivery after payment' : 'In stock') : 'Currently unavailable');
        addBtn.disabled = !selected.available;
      };
      const variantPicker = active.length > 1 ? UI.field('Option', el('select', {
        on: { change: (e) => { selected = active.find((v) => String(v.id) === e.target.value); renderSelected(); } }
      }, active.map((v) => el('option', { value: String(v.id), text: v.name + (v.available ? '' : ' (unavailable)'), selected: v === selected })))) : null;

      addBtn.addEventListener('click', () => UI.busy(addBtn, async () => {
        if (!user) { location.href = Site.loginUrl(); return; }
        try {
          await Api.post('/api/' + sf + '/cart/items', { variantId: selected.id, quantity: parseInt(qty.value, 10) || 1 });
          mount(msg, el('div', { class: 'alert success' }, 'Added to cart. ', el('a', { href: '/' + sf + '/cart', text: 'View cart' })));
        } catch (e) {
          mount(msg, UI.errorAlert(e));
        }
      }));
      renderSelected();
      UI.append(info, [priceEl, stockEl, variantPicker,
        el('div', { class: 'actions mb' }, qty, addBtn), msg,
        p.productType === 'PHYSICAL' ? pincodeChecker(sf) : null,
        p.digitalFormat ? el('p', { class: 'muted small', text: 'Format: ' + p.digitalFormat }) : null]);
    }

    if (p.description) {
      info.appendChild(el('div', { class: 'mt' }, el('h3', { text: 'Description' }), el('div', { class: 'description', text: p.description })));
    }

    const reviewsBox = el('section', { class: 'reviews mt', id: 'reviews' });
    mount(main(), el('div', { class: 'container' },
      el('nav', { class: 'breadcrumb small', 'aria-label': 'Breadcrumb' },
        el('a', { href: '/' + sf, text: 'Home' }),
        p.category ? [' / ', el('a', { href: '/' + sf + '/c/' + encodeURIComponent(p.category.slug), text: p.category.name })] : null,
        ' / ', el('span', { text: p.title })),
      el('div', { class: 'product-detail' }, gallery, info),
      reviewsBox));
    renderReviews(sf, p, user, reviewsBox, ratingLine, 0);
  }

  function stars(rating) {
    const full = Math.round(rating);
    return el('span', { class: 'stars', 'aria-label': rating.toFixed(1) + ' out of 5', text: '\u2605'.repeat(full) + '\u2606'.repeat(5 - full) });
  }

  function wishlistButton(sf, slug, user) {
    const btn = el('button', { class: 'btn ghost small wishlist-btn', type: 'button', 'aria-pressed': 'false', text: '\u2661 Save to wishlist' });
    let saved = false;
    const render = () => {
      btn.textContent = saved ? '\u2665 Saved to wishlist' : '\u2661 Save to wishlist';
      btn.setAttribute('aria-pressed', String(saved));
    };
    if (user) {
      Api.get('/api/' + sf + '/wishlist/' + encodeURIComponent(slug)).then((s) => { saved = s.saved; render(); }).catch(() => {});
    }
    btn.addEventListener('click', () => UI.busy(btn, async () => {
      if (!user) { location.href = Site.loginUrl(); return; }
      try {
        const path = '/api/' + sf + '/wishlist/' + encodeURIComponent(slug);
        const s = saved ? await Api.del(path) : await Api.put(path);
        saved = s.saved;
        render();
        UI.toast(saved ? 'Saved to your wishlist' : 'Removed from your wishlist', 'success');
      } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
    }));
    return btn;
  }

  function pincodeChecker(sf) {
    const input = el('input', { inputmode: 'numeric', maxlength: '6', placeholder: 'Enter PIN code', 'aria-label': 'Delivery PIN code',
      value: localStorage.getItem('hpp.pin') || '' });
    const btn = el('button', { class: 'btn secondary small', type: 'submit', text: 'Check' });
    const out = el('div', { class: 'small', role: 'status' });
    const form = el('form', { class: 'pincode-check' }, el('label', { class: 'small', text: 'Check delivery' }),
      el('div', { class: 'actions' }, input, btn), out);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(btn, async () => {
        const pin = input.value.trim();
        if (!/^[1-9][0-9]{5}$/.test(pin)) { mount(out, el('span', { class: 'error-text', text: 'Enter a valid 6-digit PIN code.' })); return; }
        try {
          const r = await Api.get('/api/' + sf + '/catalog/pincode/' + pin);
          localStorage.setItem('hpp.pin', pin);
          mount(out, el('span', { class: r.serviceable ? 'ok-text' : 'error-text', text: r.message }),
            r.serviceable && r.estimate ? el('div', { class: 'muted', text: r.estimate }) : null,
            r.serviceable ? el('div', { class: 'muted', text: r.codAvailable ? 'Cash on delivery available' : 'Prepaid orders only' }) : null);
        } catch (err) { mount(out, el('span', { class: 'error-text', text: UI.errorMessage(err) })); }
      });
    });
    return form;
  }

  async function renderReviews(sf, p, user, box, ratingLine, page) {
    let data;
    try {
      data = await Api.get('/api/' + sf + '/catalog/products/' + encodeURIComponent(p.slug) + '/reviews?page=' + page);
    } catch (e) { mount(box); return; }
    const r = data.rating;
    mount(ratingLine, r.count
      ? el('a', { href: '#reviews', class: 'small' }, stars(r.average), ' ' + r.average.toFixed(1) + ' (' + r.count + (r.count === 1 ? ' review)' : ' reviews)'))
      : null);

    const list = data.reviews.items.length ? data.reviews.items.map((rv) => el('article', { class: 'review card' },
      el('div', null, stars(rv.rating), rv.title ? el('strong', { text: ' ' + rv.title }) : null),
      el('div', { class: 'muted small' }, rv.authorName, rv.verifiedPurchase ? ' \u00B7 Verified purchase' : '', ' \u00B7 ' + UI.date(rv.createdAt, false)),
      rv.body ? el('p', { class: 'review-body', text: rv.body }) : null,
      rv.adminReply ? el('p', { class: 'note small', text: 'Reply from the store: ' + rv.adminReply }) : null))
      : el('p', { class: 'muted', text: 'No reviews yet.' });

    const pager = data.reviews.totalPages > 1 ? el('div', { class: 'pager' },
      page > 0 ? el('button', { class: 'btn secondary small', type: 'button', text: 'Newer', on: { click: () => renderReviews(sf, p, user, box, ratingLine, page - 1) } }) : null,
      page + 1 < data.reviews.totalPages ? el('button', { class: 'btn secondary small', type: 'button', text: 'Older', on: { click: () => renderReviews(sf, p, user, box, ratingLine, page + 1) } }) : null) : null;

    let formArea;
    if (!user) {
      formArea = el('p', { class: 'small' }, el('a', { href: Site.loginUrl(), text: 'Sign in' }), ' to write a review.');
    } else if (!data.canReview) {
      formArea = data.cannotReviewReason ? el('p', { class: 'muted small', text: data.cannotReviewReason }) : null;
    } else {
      formArea = reviewForm(sf, p, data.mine, () => renderReviews(sf, p, user, box, ratingLine, 0));
    }
    mount(box, el('h2', { text: 'Customer reviews' }), formArea, list, pager);
  }

  function reviewForm(sf, p, mine, done) {
    const rating = el('select', { required: true },
      [5, 4, 3, 2, 1].map((n) => el('option', { value: String(n), text: n + ' star' + (n > 1 ? 's' : ''), selected: mine ? mine.rating === n : n === 5 })));
    const title = el('input', { maxlength: '150', value: mine ? mine.title || '' : '' });
    const body = el('textarea', { rows: '4', maxlength: '4000' });
    body.value = mine ? mine.body || '' : '';
    const msg = el('div');
    const btn = el('button', { class: 'btn', type: 'submit', text: mine ? 'Update review' : 'Submit review' });
    const form = el('form', { class: 'card review-form' },
      el('h3', { text: mine ? 'Your review' : 'Write a review' }),
      mine && mine.status !== 'APPROVED' ? el('p', { class: 'muted small', text: 'Status: ' + UI.label(mine.status) + '. Reviews are checked before they appear.' }) : null,
      el('div', { class: 'row' }, UI.field('Rating', rating), UI.field('Title (optional)', title)),
      UI.field('Your review', body), msg, btn);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(btn, async () => {
        try {
          const saved = await Api.post('/api/' + sf + '/catalog/products/' + encodeURIComponent(p.slug) + '/reviews',
            { rating: parseInt(rating.value, 10), title: title.value.trim(), body: body.value.trim() });
          UI.toast(saved.status === 'APPROVED' ? 'Thanks! Your review is live.' : 'Thanks! Your review will appear after moderation.', 'success');
          done();
        } catch (err) { mount(msg, UI.errorAlert(err)); }
      });
    });
    return form;
  }

  // ------------------------------------------------------------------ wishlist

  async function wishlistPage({ sf }) {
    await Site.requireCustomer();
    const items = await Api.get('/api/' + sf + '/wishlist');
    const render = (list) => {
      mount(main(), el('div', { class: 'container' }, el('h1', { text: 'Your wishlist' }),
        list.length ? el('div', { class: 'grid' }, list.map((p) => {
          const remove = el('button', { class: 'btn ghost small', type: 'button', text: 'Remove' });
          remove.addEventListener('click', () => UI.busy(remove, async () => {
            try {
              await Api.del('/api/' + sf + '/wishlist/' + encodeURIComponent(p.slug));
              render(list.filter((x) => x !== p));
            } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
          }));
          return el('div', { class: 'wishlist-item' }, productCard(sf, p), remove);
        })) : el('div', { class: 'card empty' }, 'Nothing saved yet. ', el('a', { href: '/' + sf, text: 'Browse products' }))));
    };
    render(items);
  }

  // ------------------------------------------------------------------ cart

  async function cartPage({ sf }) {
    await Site.requireCustomer();
    document.title = affiliateOnly() ? 'Saved deals' : 'Your cart';
    const cart = await Api.get('/api/' + sf + '/cart');
    renderCart(sf, cart);
  }

  function renderCart(sf, cart) {
    const affiliateMode = affiliateOnly() || cart.affiliateOnly;
    const title = affiliateMode && !cart.hasDirectItems ? 'Saved deals' : 'Your cart';
    if (!cart.items.length) {
      mount(main(), el('div', { class: 'container' }, el('h1', { text: title }),
        el('div', { class: 'card empty' },
          affiliateMode
            ? 'No saved deals yet. Open a product and use Add to cart to keep partner offers for later. '
            : 'Your cart is empty. ',
          el('a', { href: '/' + sf, text: affiliateMode ? 'Browse deals' : 'Continue shopping' }))));
      return;
    }
    const msg = el('div');
    const lines = cart.items.map((item) => {
      const affiliate = item.sellingMethod === 'AFFILIATE';
      const qty = el('input', { type: 'number', min: '1', max: '20', value: String(item.quantity), class: 'qty', 'aria-label': 'Quantity' });
      qty.addEventListener('change', async () => {
        try {
          renderCart(sf, await Api.patch('/api/' + sf + '/cart/items/' + item.itemId, { quantity: parseInt(qty.value, 10) || 0 }));
        } catch (e) { mount(msg, UI.errorAlert(e)); }
      });
      const remove = el('button', { class: 'btn ghost small', type: 'button', text: 'Remove' });
      remove.addEventListener('click', async () => {
        try { renderCart(sf, await Api.del('/api/' + sf + '/cart/items/' + item.itemId)); } catch (e) { mount(msg, UI.errorAlert(e)); }
      });
      const buyPartner = affiliate && item.goUrl
        ? el('a', { class: 'btn small', href: item.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
            text: item.merchantName ? 'Buy from ' + item.merchantName : 'Buy from partner' })
        : null;
      return el('div', { class: 'cart-line' },
        el('div', { class: 'thumb' }, item.imageUrl ? el('img', { src: item.imageUrl, alt: '', loading: 'lazy', decoding: 'async' }) : null),
        el('div', null,
          item.slug ? el('a', { href: '/' + sf + '/p/' + encodeURIComponent(item.slug), text: item.title }) : el('strong', { text: item.title }),
          item.variantName && item.variantName !== 'Default' ? el('div', { class: 'muted small', text: item.variantName }) : null,
          affiliate
            ? el('div', { class: 'muted small', text: item.merchantName ? 'Partner deal · ' + item.merchantName : 'Partner deal · buy on merchant site' })
            : null,
          el('div', { class: 'small', text: item.unitPriceMinor != null ? money(item.unitPriceMinor) + (affiliate ? ' at partner' : ' each') : '' }),
          item.problem ? el('div', { class: 'small', text: '\u26A0 ' + item.problem }) : null),
        el('div', { class: 'right' },
          el('div', { class: 'price', text: money(item.lineTotalMinor) }),
          el('div', { class: 'controls' }, affiliate ? null : qty, buyPartner, remove)));
    });
    const side = [];
    if (cart.hasDirectItems) {
      side.push(el('div', { class: 'summary-row' },
        el('span', { text: 'Direct items' }), el('span', { text: money(cart.subtotalMinor) })));
      side.push(el('p', { class: 'muted small', text: cart.requiresShipping
        ? 'Shipping and tax are calculated at checkout for items we sell directly.'
        : 'Tax is calculated at checkout for items we sell directly.' }));
      if (cart.hasProblems) {
        side.push(el('div', { class: 'alert warning', text: 'Please fix the highlighted items before checking out.' }));
      } else {
        side.push(el('a', { class: 'btn block', href: '/' + sf + '/checkout', text: 'Proceed to checkout' }));
      }
    }
    if (cart.hasAffiliateItems) {
      side.push(el('p', { class: 'muted small',
        text: cart.hasDirectItems
          ? 'Partner deals stay in your cart as a list. Use Buy from partner on each line — those are not checked out here.'
          : 'Saved for later. Buy each deal on the partner site when you are ready — checkout here is not used for affiliate products.' }));
    }
    if (!side.length) {
      side.push(el('p', { class: 'muted small', text: 'Nothing to check out yet.' }));
    }
    mount(main(), el('div', { class: 'container' }, el('h1', { text: title }), msg,
      el('div', { class: 'split' },
        el('div', { class: 'card' }, lines),
        el('div', { class: 'card' }, side))));
  }

  // ------------------------------------------------------------------ checkout

  async function checkoutPage({ sf, user }) {
    if (affiliateOnly()) {
      location.replace('/' + sf);
      return;
    }
    await Site.requireCustomer();
    document.title = 'Checkout';
    const keyName = 'hpp.checkoutKey.' + sf;
    if (!sessionStorage.getItem(keyName)) sessionStorage.setItem(keyName, UI.newKey());

    const [firstQuote, states] = await Promise.all([
      Api.post('/api/' + sf + '/checkout/quote', {}),
      Api.get('/api/' + sf + '/catalog/states')
    ]);
    let quote = firstQuote;
    if (!quote.lines.length && !quote.problems.length) {
      location.href = '/' + sf + '/cart';
      return;
    }
    const msg = el('div');
    const summary = el('div', { class: 'card' });
    const shippingBox = el('div');
    const paymentBox = el('div', { class: 'card' });
    let methodId = quote.shippingMethodId;
    let couponCode = null;
    let paymentMethod = 'ONLINE';

    const stateSelect = el('select', { name: 'buyerStateCode', required: true, autocomplete: 'address-level1' },
      el('option', { value: '', text: 'Choose state' }),
      states.map((s) => el('option', { value: s.code, text: s.name })));
    const gstin = el('input', { name: 'buyerGstin', maxlength: '15', placeholder: 'Optional, for a business invoice', autocomplete: 'off' });
    const couponInput = el('input', { name: 'coupon', maxlength: '40', placeholder: 'Coupon code', 'aria-label': 'Coupon code' });
    const couponBtn = el('button', { class: 'btn secondary small', type: 'button', text: 'Apply' });
    const couponMsg = el('div', { class: 'small', role: 'status' });

    const refresh = async () => {
      try {
        quote = await Api.post('/api/' + sf + '/checkout/quote', {
          shippingMethodId: methodId, couponCode, paymentMethod,
          buyerStateCode: stateSelect.value || null,
          postalCode: quote.requiresShipping ? addr.postalCode.value.trim() || null : null
        });
        if (paymentMethod === 'COD' && !quote.codAvailable) paymentMethod = 'ONLINE';
        renderSummary();
        renderPayment();
      } catch (e) { mount(msg, UI.errorAlert(e)); }
    };

    const addr = {
      name: el('input', { name: 'name', autocomplete: 'name', value: user.fullName || '', required: true }),
      phone: el('input', { name: 'phone', autocomplete: 'tel', value: user.phone || '', required: true }),
      line1: el('input', { name: 'line1', autocomplete: 'address-line1', required: true }),
      line2: el('input', { name: 'line2', autocomplete: 'address-line2' }),
      city: el('input', { name: 'city', autocomplete: 'address-level2', required: true }),
      postalCode: el('input', { name: 'postalCode', autocomplete: 'postal-code', inputmode: 'numeric', maxlength: '6',
        value: localStorage.getItem('hpp.pin') || '', required: true }),
      country: el('input', { name: 'country', autocomplete: 'country', value: 'IN', maxlength: '2', required: true })
    };
    stateSelect.addEventListener('change', refresh);
    addr.postalCode.addEventListener('change', refresh);

    const row = (label, value, cls) => el('div', { class: 'summary-row' + (cls ? ' ' + cls : '') }, el('span', { text: label }), el('span', { text: value }));
    const pct = (bps) => (bps / 100).toString().replace(/\.0+$/, '') + '%';

    const renderSummary = () => {
      const taxLabel = quote.pricesIncludeTax ? 'Included GST' : 'GST';
      const taxRows = [];
      if (quote.cgstMinor || quote.sgstMinor) {
        taxRows.push(row(taxLabel + ' \u2013 CGST', money(quote.cgstMinor)), row(taxLabel + ' \u2013 SGST', money(quote.sgstMinor)));
      } else if (quote.igstMinor) {
        taxRows.push(row(taxLabel + ' \u2013 IGST', money(quote.igstMinor)));
      } else if (quote.taxMinor) {
        taxRows.push(row(taxLabel, money(quote.taxMinor)));
      }
      mount(summary,
        el('h2', { text: 'Order summary' }),
        quote.lines.map((l) => row(l.title + (l.variantName && l.variantName !== 'Default' ? ' (' + l.variantName + ')' : '') + ' \u00D7 ' + l.quantity
          + (l.gstRateBps ? ' \u00B7 GST ' + pct(l.gstRateBps) : ''), money(l.lineTotalMinor))),
        el('div', { class: 'coupon-box mt' },
          el('div', { class: 'actions' }, couponInput, couponBtn,
            couponCode ? el('button', { class: 'btn ghost small', type: 'button', text: 'Remove', on: { click: () => { couponCode = null; couponInput.value = ''; mount(couponMsg); refresh(); } } }) : null),
          couponMsg),
        row('Subtotal', money(quote.subtotalMinor), 'mt'),
        quote.discountMinor ? row('Discount' + (quote.couponCode ? ' (' + quote.couponCode + ')' : ''), '\u2212' + money(quote.discountMinor)) : null,
        quote.requiresShipping ? row('Shipping', quote.shippingMinor ? money(quote.shippingMinor) : 'Free') : null,
        quote.codFeeMinor ? row('Cash on delivery fee', money(quote.codFeeMinor)) : null,
        quote.pricesIncludeTax ? null : taxRows,
        row('Total', money(quote.totalMinor), 'total'),
        quote.pricesIncludeTax && taxRows.length ? el('div', { class: 'muted small' }, taxRows) : null,
        !stateSelect.value && (quote.taxMinor || quote.lines.some((l) => l.gstRateBps)) ? el('p', { class: 'muted small', text: 'Choose your state to see the exact GST split.' }) : null,
        quote.problems.length ? el('div', { class: 'alert warning mt' }, el('ul', null, quote.problems.map((p) => el('li', { text: p })))) : null,
        placeBtn);
      placeBtn.textContent = paymentMethod === 'COD' ? 'Place order (pay on delivery)' : 'Place order and pay';
      placeBtn.disabled = quote.problems.length > 0;
    };

    couponBtn.addEventListener('click', () => UI.busy(couponBtn, async () => {
      const code = couponInput.value.trim();
      if (!code) return;
      couponCode = code;
      await refresh();
      if (quote.couponMessage) {
        mount(couponMsg, el('span', { class: 'error-text', text: quote.couponMessage }));
        couponCode = null;
      } else if (quote.couponCode) {
        mount(couponMsg, el('span', { class: 'ok-text', text: 'Coupon ' + quote.couponCode + ' applied.' }));
      }
    }));

    const renderPayment = () => {
      const option = (value, title, hint, enabled) => {
        const radio = el('input', { type: 'radio', name: 'paymentMethod', value, checked: paymentMethod === value, disabled: !enabled });
        radio.addEventListener('change', () => { paymentMethod = value; refresh(); });
        return el('label', { class: 'check' }, radio, el('span', null, el('strong', { text: title }), hint ? el('span', { class: 'muted small', text: ' \u2014 ' + hint }) : null));
      };
      const store = window.HPP_STORE && window.HPP_STORE.store;
      const showCod = quote.requiresShipping && store && store.codEnabled;
      mount(paymentBox, el('h2', { text: 'Payment' }),
        option('ONLINE', 'Pay online', 'UPI, cards and net banking', true),
        showCod ? option('COD', 'Cash on delivery', quote.codAvailable
          ? 'pay in cash when your order arrives' : quote.codUnavailableReason || 'not available for this order', quote.codAvailable) : null);
    };

    const renderShipping = () => {
      const billing = el('div', { class: 'card' }, el('h2', { text: quote.requiresShipping ? 'Tax details' : 'Billing details' }),
        el('div', { class: 'row' },
          quote.requiresShipping ? null : UI.field('State', stateSelect, 'Used to calculate GST'),
          UI.field('GSTIN (optional)', gstin, 'Add your GSTIN to claim input tax credit')));
      if (!quote.requiresShipping) {
        mount(shippingBox, el('div', { class: 'card' }, el('h2', { text: 'Delivery' }),
          el('p', { text: 'Digital items are delivered to your account library right after payment.' })), billing, paymentBox);
        return;
      }
      mount(shippingBox, el('div', { class: 'card' }, el('h2', { text: 'Shipping address' }),
        el('div', { class: 'row' }, UI.field('Full name', addr.name), UI.field('Phone', addr.phone)),
        UI.field('Address line 1', addr.line1), UI.field('Address line 2 (optional)', addr.line2),
        el('div', { class: 'row' }, UI.field('City', addr.city), UI.field('State', stateSelect)),
        el('div', { class: 'row' }, UI.field('PIN code', addr.postalCode), UI.field('Country code', addr.country))),
      el('div', { class: 'card' }, el('h2', { text: 'Shipping method' }),
        quote.shippingMethods.map((m) => {
          const radio = el('input', { type: 'radio', name: 'shippingMethod', value: String(m.id), checked: m.id === methodId });
          radio.addEventListener('change', () => { methodId = m.id; refresh(); });
          return el('label', { class: 'check' }, radio, m.name + ' \u2014 ' + (m.rateMinor ? money(m.rateMinor) : 'Free') +
            (m.freeAboveMinor ? ' (free above ' + money(m.freeAboveMinor) + ')' : ''));
        })),
      billing, paymentBox);
    };

    const placeBtn = el('button', { class: 'btn block mt', type: 'button', text: 'Place order and pay' });
    placeBtn.addEventListener('click', () => UI.busy(placeBtn, async () => {
      mount(msg);
      const stateCode = stateSelect.value || null;
      const body = { couponCode, paymentMethod, buyerStateCode: stateCode, buyerGstin: gstin.value.trim() || null };
      if (quote.requiresShipping) {
        body.shippingMethodId = methodId;
        body.address = Object.fromEntries(Object.entries(addr).map(([k, input]) => [k, input.value.trim()]));
        const st = states.find((s) => s.code === stateCode);
        body.address.state = st ? st.name : '';
      }
      try {
        const placed = await Api.post('/api/' + sf + '/checkout/orders', body, { headers: { 'Idempotency-Key': sessionStorage.getItem(keyName) } });
        sessionStorage.removeItem(keyName);
        location.href = placed.paymentMethod === 'COD'
          ? '/account/order.html?n=' + encodeURIComponent(placed.orderNumber) + '&placed=1'
          : '/' + sf + '/pay/' + encodeURIComponent(placed.orderNumber);
      } catch (e) {
        if (e.code !== 'IDEMPOTENCY_KEY_REUSED') {
          sessionStorage.setItem(keyName, UI.newKey());
        }
        mount(msg, UI.errorAlert(e));
        window.scrollTo(0, 0);
      }
    }));

    renderPayment();
    renderShipping();
    renderSummary();
    mount(main(), el('div', { class: 'container' }, el('h1', { text: 'Checkout' }), msg,
      el('div', { class: 'split' }, shippingBox, summary)));
  }

  // ------------------------------------------------------------------ payment

  async function payPage({ sf }) {
    await Site.requireCustomer();
    document.title = 'Payment';
    const orderNumber = decodeURIComponent(location.pathname.split('/pay/')[1] || '');
    const info = await Api.get('/api/orders/' + encodeURIComponent(orderNumber) + '/payment');
    renderPay(sf, info);
  }

  function renderPay(sf, info) {
    const msg = el('div');
    const orderLink = el('a', { href: '/account/order.html?n=' + encodeURIComponent(info.orderNumber), text: 'View order ' + info.orderNumber });
    const paid = ['PAID', 'COMPLETED', 'SHIPPED', 'DELIVERED'].includes(info.status);
    const card = el('div', { class: 'card medium' },
      el('h1', { text: paid ? 'Thank you! Payment received' : 'Complete your payment' }),
      el('div', { class: 'summary-row total' }, el('span', { text: 'Order ' + info.orderNumber }), el('span', { text: money(info.totalMinor, info.currency) })),
      msg);

    if (paid) {
      card.appendChild(el('div', { class: 'alert success' }, info.status === 'COMPLETED'
        ? 'Your digital items are ready in your library.' : 'We will email you when your order ships.'));
      card.appendChild(el('div', { class: 'actions' }, orderLink,
        info.status === 'COMPLETED' ? el('a', { class: 'btn', href: '/account/library.html', text: 'Open my library' }) : null));
    } else if (info.status === 'CONFIRMED') {
      card.appendChild(el('div', { class: 'alert success', text: 'Your order is confirmed. Please pay in cash when it is delivered.' }));
      card.appendChild(orderLink);
    } else if (info.status !== 'PENDING_PAYMENT') {
      card.appendChild(el('div', { class: 'alert warning', text: 'This order is ' + UI.label(info.status).toLowerCase() + ' and can no longer be paid.' }));
      card.appendChild(orderLink);
    } else if (info.paymentStatus === 'FAILED') {
      card.appendChild(el('div', { class: 'alert error', text: 'Payment failed: ' + (info.failureReason || 'declined') }));
      const retry = el('button', { class: 'btn', type: 'button', text: 'Try again' });
      retry.addEventListener('click', () => UI.busy(retry, async () => {
        try { renderPay(sf, await Api.post('/api/orders/' + encodeURIComponent(info.orderNumber) + '/payment/retry')); } catch (e) { mount(msg, UI.errorAlert(e)); }
      }));
      card.appendChild(retry);
    } else if (info.demo) {
      card.appendChild(el('div', { class: 'demo-banner' },
        el('strong', { text: 'Demo payment mode. ' }),
        'No real money is charged. Razorpay will replace this screen once it is configured.'));
      if (info.expiresAt) card.appendChild(el('p', { class: 'muted small', text: 'Stock is held for you until ' + UI.date(info.expiresAt) + '.' }));
      const ok = el('button', { class: 'btn', type: 'button', text: 'Simulate successful payment' });
      const fail = el('button', { class: 'btn secondary', type: 'button', text: 'Simulate failed payment' });
      const run = (outcome, btn) => UI.busy(btn, async () => {
        try {
          renderPay(sf, await Api.post('/api/payments/demo/' + encodeURIComponent(info.orderNumber) + '/complete', { outcome }));
        } catch (e) { mount(msg, UI.errorAlert(e)); }
      });
      ok.addEventListener('click', () => run('SUCCESS', ok));
      fail.addEventListener('click', () => run('FAILURE', fail));
      card.appendChild(el('div', { class: 'actions' }, ok, fail));
    } else {
      card.appendChild(el('div', { class: 'alert info', text: 'Online payment is being set up. Please contact the store to complete this order.' }));
    }
    mount(main(), el('div', { class: 'container' }, card));
  }
})();
