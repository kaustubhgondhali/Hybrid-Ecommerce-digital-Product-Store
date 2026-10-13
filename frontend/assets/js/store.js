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
    'store-compare': comparePage,
    // content pages (and the coupons page) are rendered by the server; only the coupon buttons need wiring here
    'store-page': async () => wireOfferButtons(main())
  };

  document.addEventListener('DOMContentLoaded', async () => {
    const page = document.body.dataset.page;
    try {
      const ctx = await Site.renderChrome();
      await pages[page](ctx);
      if (page === 'store-index' || page === 'store-product' || page === 'store-page') storiesStrip(ctx);
      if (page !== 'store-compare' && page !== 'store-checkout' && page !== 'store-pay') compareTray(ctx.sf);
      linkNotices();
      Site.reveal(main());
    } catch (e) {
      Site.pageError(e);
    }
  });

  /** Web Stories at the bottom of store pages: portrait cards that open the full-screen story. */
  function storiesStrip({ sf, stories }) {
    if (!stories || !stories.length) return;
    main().appendChild(el('section', { class: 'container stories-strip reveal', 'aria-label': 'Web Stories' },
      sectionHead('Web Stories.', 'Quick, swipeable picks.', el('a', { class: 'see-all', href: '/' + sf + '/stories', text: 'See all' })),
      el('div', { class: 'stories-row' }, stories.map((st) => el('a', { class: 'story-card', href: st.url },
        st.coverUrl ? el('img', { src: st.coverUrl, alt: '', loading: 'lazy', decoding: 'async' }) : null,
        el('span', { class: 'story-title', text: st.title }))))));
  }

  /** Apple-style section title: a bold statement followed by a grey line ("Top deals. Biggest discounts right now."). */
  function sectionHead(title, subtitle, extra) {
    return el('div', { class: 'section-head' },
      el('h2', null, title, subtitle ? el('span', { class: 'sub', text: ' ' + subtitle }) : null), extra || null);
  }

  function main() {
    return document.getElementById('main');
  }

  /** Latest buying guides (or the guides that show one product); loads after the page and stays empty when there are none. */
  function guidesSection(sf, productId, title, subtitle) {
    const holder = el('div', { class: 'guides-holder' });
    Api.get('/api/' + sf + '/catalog/guides?limit=3' + (productId ? '&product=' + productId : '')).then((list) => {
      if (!list.length) return;
      mount(holder, el('section', { class: 'guides-section' }, sectionHead(title, subtitle,
        el('a', { class: 'see-all', href: '/' + sf + '/guides', text: 'All guides' })),
        el('div', { class: 'guide-grid' }, list.map((g) => el('a', { class: 'guide-card', href: g.url },
          el('div', { class: 'guide-cover' }, g.coverUrl ? el('img', { src: g.coverUrl, alt: '', loading: 'lazy', decoding: 'async' }) : null),
          el('div', { class: 'guide-card-body' }, el('span', { class: 'eyebrow', text: 'Buying guide · ' + g.readMinutes + ' min read' }),
            el('h3', { text: g.title }), g.summary ? el('p', { class: 'muted small', text: g.summary.length > 140 ? g.summary.slice(0, 137) + '…' : g.summary }) : null))))));
    }).catch(() => {});
    return holder;
  }

  function isDemoProduct(p) {
    return !!(p && ((p.slug && p.slug.indexOf('demo-') === 0)
      || (p.title && p.title.indexOf('[Demo]') === 0)
      || (p.summary && p.summary.indexOf('DEMO PRODUCT') === 0)));
  }

  function affiliateOnly() {
    return !(window.HPP_STORE && window.HPP_STORE.affiliateOnly === false);
  }

  function demoBadge(p) {
    return isDemoProduct(p) ? el('span', { class: 'badge amber', text: 'Demo' }) : null;
  }

  /** "Price checked today / yesterday / 3 days ago", so shoppers can trust how fresh a price is. */
  function checkedText(iso) {
    if (!iso) return null;
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
    return 'Price checked ' + (days <= 0 ? 'today' : days === 1 ? 'yesterday' : days + ' days ago');
  }

  /** Editor's rating in tenths of a star (45 = 4.5). */
  function expertRating(tenths, label) {
    const r = tenths / 10;
    return el('div', { class: 'expert-rating', 'aria-label': (label || 'Our rating') + ': ' + r.toFixed(1) + ' out of 5' },
      el('span', { class: 'stars', 'aria-hidden': 'true', text: '★'.repeat(Math.floor(r)) + (r % 1 ? '½' : '') }),
      ' ' + r.toFixed(1) + (label ? ' · ' + label : ''));
  }

  function priceLine(p) {
    if (p.priceMinor != null) {
      return el('div', { class: 'price' }, money(p.priceMinor), p.compareAtMinor ? el('s', { text: money(p.compareAtMinor) }) : null);
    }
    return p.checkPrice && p.merchantName ? el('div', { class: 'check-price', text: 'Check price on ' + p.merchantName }) : null;
  }

  function dealFlag(p, inline) {
    const cls = 'deal-flag' + (inline ? ' inline' : '');
    if (p.lowest30d) return el('span', { class: cls, text: 'Lowest in 30 days' });
    if (p.dropPercent) return el('span', { class: cls, text: 'Price dropped ' + p.dropPercent + '%' });
    return null;
  }

  // ------------------------------------------------------------------ catalog

  async function catalogPage({ sf, storeName }) {
    const params = new URLSearchParams(location.search);
    const pathCategory = decodeURIComponent((location.pathname.split('/c/')[1] || '').replace(/\/$/, ''));
    if (pathCategory) params.set('category', pathCategory);
    const pathStore = decodeURIComponent((location.pathname.split('/store/')[1] || '').replace(/\/$/, ''));
    if (pathStore) params.set('store', pathStore);
    const page = Math.max(parseInt(params.get('page') || '0', 10) || 0, 0);
    const query = new URLSearchParams();
    ['q', 'category', 'type', 'method', 'sort', 'store', 'minDiscount'].forEach((k) => { if (params.get(k)) query.set(k, params.get(k)); });
    const priceRange = (params.get('price') || '').split('-');
    if (priceRange[0]) query.set('minPrice', priceRange[0]);
    if (priceRange[1]) query.set('maxPrice', priceRange[1]);
    query.set('page', String(page));
    query.set('size', '24');
    const filtered = ['q', 'category', 'type', 'method', 'store', 'minDiscount', 'price'].some((k) => params.get(k)) || page > 0;

    const [categories, result, home, stores, storeOffers] = await Promise.all([
      Api.get('/api/' + sf + '/catalog/categories'),
      Api.get('/api/' + sf + '/catalog/products?' + query.toString()),
      filtered ? null : Api.get('/api/' + sf + '/catalog/home').catch(() => null),
      Api.get('/api/' + sf + '/catalog/stores').catch(() => []),
      params.get('store') ? Api.get('/api/' + sf + '/catalog/offers?store=' + encodeURIComponent(params.get('store'))).catch(() => []) : []
    ]);
    const category = categories.find((c) => c.slug === params.get('category'));
    const storeFilter = stores.find((st) => st.domain === params.get('store'));

    const filterForm = el('form', { class: 'filters', id: 'catalog', method: 'get', action: '/' + sf },
      params.get('q') ? el('input', { type: 'hidden', name: 'q', value: params.get('q') }) : null,
      select('category', [['', 'All categories'], ...categories.map((c) => [c.slug, c.name])], params.get('category')),
      stores.length > 1 ? select('store', [['', 'All stores'], ...stores.map((st) => [st.domain, st.name])], params.get('store')) : null,
      select('minDiscount', [['', 'Any discount'], ['10', '10% off or more'], ['20', '20% off or more'], ['30', '30% off or more'], ['50', '50% off or more']], params.get('minDiscount')),
      select('price', [['', 'Any price'], ['-500', 'Under \u20B9500'], ['500-1000', '\u20B9500 \u2013 \u20B91,000'], ['1000-5000', '\u20B91,000 \u2013 \u20B95,000'],
        ['5000-20000', '\u20B95,000 \u2013 \u20B920,000'], ['20000-', 'Over \u20B920,000']], params.get('price')),
      affiliateOnly() ? null : select('method', [['', 'All offers'], ['DIRECT', 'Sold by us'], ['AFFILIATE', 'Partner offers']], params.get('method')),
      select('sort', [['', 'Newest first'], ['popular', 'Most popular'], ['price_asc', 'Price: low to high'], ['price_desc', 'Price: high to low'],
        ['discount', 'Biggest discount'], ['rating', 'Top rated'], ['title', 'Name (A\u2013Z)']], params.get('sort')));
    const go = () => {
      const values = new URLSearchParams();
      new FormData(filterForm).forEach((v, k) => { if (v && k !== 'category') values.set(k, v); });
      const cat = filterForm.elements.category.value;
      const qs = values.toString();
      location.href = '/' + sf + (cat ? '/c/' + encodeURIComponent(cat) : '') + (qs ? '?' + qs : '');
    };
    filterForm.addEventListener('submit', (e) => { e.preventDefault(); go(); });
    filterForm.querySelectorAll('select').forEach((s) => s.addEventListener('change', go));

    if (params.get('q')) Site.track('search', { term: params.get('q') });
    const heading = params.get('q') ? 'Results for \u201C' + params.get('q') + '\u201D' : category ? category.name
      : storeFilter ? storeFilter.name + ' deals & coupons' : storeName;
    const grid = result.items.length
      ? el('div', { class: 'grid' }, result.items.map((p) => productCard(sf, p)))
      : el('div', { class: 'empty card' }, 'No products found.');

    const pager = result.totalPages > 1 ? el('div', { class: 'pager' },
      page > 0 ? el('a', { class: 'btn secondary small', href: pageHref(params, page - 1), text: 'Previous' }) : null,
      el('span', { class: 'muted small', text: 'Page ' + (page + 1) + ' of ' + result.totalPages }),
      page + 1 < result.totalPages ? el('a', { class: 'btn secondary small', href: pageHref(params, page + 1), text: 'Next' }) : null) : null;

    if (filtered) {
      // Category and search results: a large page title, category pills to jump around, then the grid.
      const count = result.totalItems === 1 ? '1 deal' : result.totalItems + (affiliateOnly() ? ' deals' : ' products');
      if (categories.length) filterForm.classList.add('has-pills');
      mount(main(), el('div', { class: 'container' },
        el('header', { class: 'page-title' }, el('h1', { text: heading }), el('p', { class: 'muted', text: count })),
        categoryPills(sf, categories, params.get('category')),
        offersSection(storeOffers, 'Coupons.', storeFilter ? 'Working codes for ' + storeFilter.name + '.' : null),
        filterForm, grid, pager));
      return;
    }
    mount(main(), storeHero(sf, storeName), el('div', { class: 'container' },
      home ? categoryNav(sf, home.categories) : null,
      home ? dealOfDay(sf, home.dealOfDay) : null,
      showcase(sf, home && home.featured.length >= 2 ? home.featured : result.items),
      home ? shelf(sf, 'Top deals.', 'Biggest discounts right now.', home.topDeals) : null,
      home ? shelf(sf, 'Price drops.', 'Cheaper than last week.', home.priceDrops) : null,
      home ? shelf(sf, 'Trending now.', 'Most clicked this week.', home.trending) : null,
      recentShelf(sf, null),
      el('section', { class: 'latest reveal', id: 'latest' },
        sectionHead(affiliateOnly() ? 'Latest deals.' : 'Latest products.', affiliateOnly() ? 'Fresh from our partner stores.' : 'New in the store.'),
        filterForm, grid, pager),
      guidesSection(sf, null, 'Buying guides.', 'Not sure what to pick? Start here.'),
      newsletterSection(sf)));
  }

  /** Category quick links on search and category pages (Apple's "All models · Accessories · ..." row). */
  function categoryPills(sf, categories, current) {
    if (!categories.length) return null;
    return el('nav', { class: 'category-pills', 'aria-label': 'Categories' },
      el('a', { href: '/' + sf, class: current ? '' : 'active', text: 'All' }),
      categories.map((c) => el('a', { href: '/' + sf + '/c/' + encodeURIComponent(c.slug), class: c.slug === current ? 'active' : '', text: c.name })));
  }

  function storeHero(sf, storeName) {
    const b = Site.BRANDS[sf];
    const other = sf === 'shop' ? 'digital' : 'shop';
    const trust = affiliateOnly()
      ? ['Hand-picked deals', 'Trusted partner stores', 'No extra cost to you']
      : ['Hand-picked products', 'Secure checkout', 'Fast support'];
    return el('section', { class: 'store-hero' },
      el('div', { class: 'hero-copy' },
        el('p', { class: 'eyebrow', text: b.eyebrow }),
        el('h1', null, b.headline[0], el('span', { class: 'accent', text: b.headline[1] }), b.headline[2] + '.'),
        el('p', { class: 'hero-sub', text: b.tagline }),
        el('div', { class: 'cta-row' },
          el('a', { class: 'btn', href: '#latest', text: affiliateOnly() ? 'See today\u2019s deals' : 'Shop now' }),
          el('a', { class: 'btn secondary', href: '/' + other, text: 'Visit ' + Site.STOREFRONTS[other] })),
        el('ul', { class: 'trust-chips' }, trust.map((t) => el('li', { text: t })))),
      el('div', { class: 'hero-art' }, Site.liveMark(storeName)));
  }

  /** Auto-advancing slider of featured deals that have an image (needs at least two to be worth showing). */
  function showcase(sf, items) {
    const picks = items.filter((p) => p.imageUrl).slice(0, 8);
    if (picks.length < 2) return null;
    const slides = picks.map((p) => el('a', { class: 'showcase-slide', href: '/' + sf + '/p/' + encodeURIComponent(p.slug) },
      el('div', { class: 'showcase-body' },
        el('div', { class: 'card-meta' }, demoBadge(p), p.merchantName ? el('span', { class: 'merchant', text: p.merchantName }) : null, dealFlag(p, true)),
        el('h3', { class: 'showcase-title', text: p.title }),
        p.summary ? el('p', { text: p.summary }) : null,
        priceLine(p),
        p.priceCheckedAt ? el('div', { class: 'fresh', text: checkedText(p.priceCheckedAt) }) : null,
        el('div', { class: 'cta-row' }, el('span', { class: 'btn small', text: p.sellingMethod === 'AFFILIATE' ? 'See the deal' : 'View product' }),
          el('span', { class: 'link-more', text: 'Learn more' }))),
      el('div', { class: 'showcase-media' }, el('img', { src: p.imageUrl, alt: '', decoding: 'async' }),
        p.discountPercent ? el('span', { class: 'deal-badge', text: '-' + p.discountPercent + '%' }) : null)));
    return el('section', { class: 'showcase-section reveal' },
      sectionHead('Featured.', 'Today\u2019s best picks.'),
      carousel(slides, { className: 'showcase', label: 'Featured deals', dots: true, toggle: true }).root);
  }

  /** A row of cards that scrolls sideways, with round arrow buttons on larger screens (Apple Store "shelf"). */
  function shelf(sf, title, subtitle, items) {
    if (!items || !items.length) return null;
    const track = el('div', { class: 'shelf-track' }, items.map((p) => productCard(sf, p)));
    const prev = el('button', { class: 'shelf-arrow prev', type: 'button', 'aria-label': 'Scroll back', text: '\u2039', hidden: true });
    const next = el('button', { class: 'shelf-arrow next', type: 'button', 'aria-label': 'Scroll forward', text: '\u203A' });
    const step = (dir) => track.scrollBy({ left: dir * Math.max(260, track.clientWidth * 0.8), behavior: 'smooth' });
    prev.addEventListener('click', () => step(-1));
    next.addEventListener('click', () => step(1));
    const update = () => {
      prev.hidden = track.scrollLeft <= 4;
      next.hidden = track.scrollLeft + track.clientWidth >= track.scrollWidth - 4;
    };
    track.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    setTimeout(update, 0);
    return el('section', { class: 'shelf deal-section reveal' }, sectionHead(title, subtitle), el('div', { class: 'shelf-wrap' }, track, prev, next));
  }

  /** Apple Store-style row of category tiles with a picture (or initial) and a name. */
  function categoryNav(sf, tiles) {
    if (!tiles || !tiles.length) return null;
    return el('nav', { class: 'category-nav', 'aria-label': 'Shop by category' },
      el('div', { class: 'category-tiles' }, tiles.map((t) => el('a', { class: 'category-tile', href: '/' + sf + '/c/' + encodeURIComponent(t.slug) },
        t.imageUrl ? el('img', { src: t.imageUrl, alt: '', loading: 'lazy', decoding: 'async' }) : el('span', { class: 'tile-ph', text: t.name.charAt(0) }),
        el('span', { class: 'tile-name', text: t.name }),
        el('span', { class: 'tile-count', text: t.products + (t.products === 1 ? ' deal' : ' deals') })))));
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

  /**
   * Deal card: discount and "lowest price" flags on the image, store name, price (or "Check price on ..." when the
   * store does not allow showing it), how fresh the price is, and a direct Buy button for partner offers.
   */
  function productCard(sf, p) {
    const href = '/' + sf + '/p/' + encodeURIComponent(p.slug);
    const partner = p.sellingMethod === 'AFFILIATE';
    return el('div', { class: 'product-card' },
      el('a', { class: 'thumb', href, tabindex: '-1', 'aria-hidden': 'true' },
        p.imageUrl ? el('img', { src: p.imageUrl, alt: '', loading: 'lazy', decoding: 'async' }) : el('span', { text: 'No image' }),
        p.discountPercent ? el('span', { class: 'deal-badge', text: '-' + p.discountPercent + '%' }) : null,
        dealFlag(p)),
      compareToggle(sf, p, 'card-compare'),
      el('div', { class: 'body' },
        el('div', { class: 'card-meta' }, demoBadge(p),
          partner && p.merchantName ? el('span', { class: 'merchant', text: p.merchantName }) : null,
          p.offerCount > 1 ? el('span', { class: 'muted', text: p.offerCount + ' stores' }) : null),
        el('a', { class: 'title', href, text: p.title }),
        p.expertRating ? expertRating(p.expertRating) : null,
        el('div', { class: 'spacer' }),
        priceLine(p),
        p.priceCheckedAt ? el('div', { class: 'fresh', text: checkedText(p.priceCheckedAt) }) : null,
        !partner && p.inStock === false ? el('div', { class: 'small', text: 'Out of stock' }) : null,
        partner && p.goUrl
          ? el('a', { class: 'btn block', href: p.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
            title: 'Buy on ' + (p.merchantName || 'partner store'),
            // Short store names fit the pill ("Buy on Croma"); long ones are already named just above the title.
            text: p.merchantName && p.merchantName.length <= 16 ? 'Buy on ' + p.merchantName : 'Buy now' })
          : el('a', { class: 'btn secondary block', href, text: 'View details' })));
  }

  // ------------------------------------------------------------------ compare (up to 4 products, kept in this browser)

  const compareKey = (sf) => 'hpp.compare.' + sf;

  function compareList(sf) {
    try {
      const v = JSON.parse(localStorage.getItem(compareKey(sf)) || '[]');
      return Array.isArray(v) ? v.filter((x) => x && x.slug).slice(0, 4) : [];
    } catch (e) { return []; }
  }

  function saveCompare(sf, list) {
    try { localStorage.setItem(compareKey(sf), JSON.stringify(list.slice(0, 4))); } catch (e) { /* storage blocked */ }
    document.dispatchEvent(new CustomEvent('compare:change'));
  }

  function compareToggle(sf, p, cls) {
    const btn = el('button', { type: 'button', class: 'compare-toggle ' + (cls || '') });
    const render = () => {
      const on = compareList(sf).some((x) => x.slug === p.slug);
      btn.textContent = on ? '✓ Comparing' : '+ Compare';
      btn.setAttribute('aria-pressed', String(on));
      btn.classList.toggle('on', on);
    };
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      e.stopPropagation();
      let list = compareList(sf);
      if (list.some((x) => x.slug === p.slug)) {
        list = list.filter((x) => x.slug !== p.slug);
      } else if (list.length >= 4) {
        UI.toast('You can compare up to 4 products. Remove one first.', 'error');
        return;
      } else {
        const image = p.imageUrl || (p.images && p.images[0] ? p.images[0].thumbUrl || p.images[0].url : null);
        list = [...list, { slug: p.slug, title: p.title, imageUrl: image }];
      }
      saveCompare(sf, list);
    });
    document.addEventListener('compare:change', render);
    render();
    return btn;
  }

  /** Floating bar listing the products picked for comparison. */
  function compareTray(sf) {
    const tray = el('div', { class: 'compare-tray', role: 'region', 'aria-label': 'Compare products', hidden: true });
    const render = () => {
      const list = compareList(sf);
      tray.hidden = list.length === 0;
      const clear = el('button', { type: 'button', class: 'btn ghost small', text: 'Clear' });
      clear.addEventListener('click', () => saveCompare(sf, []));
      const go = el('a', { class: 'btn small' + (list.length < 2 ? ' disabled' : ''), href: '/' + sf + '/compare', text: 'Compare now' });
      go.addEventListener('click', (e) => {
        if (list.length < 2) { e.preventDefault(); UI.toast('Add one more product to compare.', 'error'); }
      });
      mount(tray,
        el('div', { class: 'compare-thumbs' }, list.map((x) => x.imageUrl
          ? el('img', { src: x.imageUrl, alt: x.title, title: x.title })
          : el('span', { class: 'compare-ph', title: x.title, text: x.title.charAt(0) }))),
        el('span', { class: 'compare-count', text: list.length === 1 ? 'Add one more to compare' : list.length + ' products' }),
        clear, go);
    };
    document.addEventListener('compare:change', render);
    window.addEventListener('storage', (e) => { if (e.key === compareKey(sf)) render(); });
    document.body.appendChild(tray);
    render();
  }

  /** Side-by-side comparison (Flipkart / Apple style): price, store, rating, highlights, specifications, pros, cons. */
  async function comparePage({ sf, storeName }) {
    document.title = 'Compare | ' + storeName;
    const shared = (UI.param('p') || '').split(',').map((x) => x.trim()).filter(Boolean);
    const slugs = (shared.length ? shared : compareList(sf).map((x) => x.slug)).slice(0, 4);
    const header = el('header', { class: 'page-title' }, el('h1', { text: 'Compare.' }),
      el('p', { class: 'muted', text: 'Side by side, so you can pick the right one.' }));
    if (slugs.length < 2) {
      mount(main(), el('div', { class: 'container' }, header,
        el('div', { class: 'card empty' }, 'Pick at least two products with “+ Compare” to see them side by side. ',
          el('a', { href: '/' + sf, text: 'Browse deals' }))));
      return;
    }
    const products = (await Promise.all(slugs.map((s) => Api.get('/api/' + sf + '/catalog/products/' + encodeURIComponent(s)).catch(() => null))))
      .filter(Boolean);
    const specNames = [];
    products.forEach((p) => (p.specs || []).forEach((sp) => { if (!specNames.includes(sp.name)) specNames.push(sp.name); }));
    const list = (items) => items && items.length ? el('ul', null, items.map((x) => el('li', { text: x }))) : el('span', { class: 'muted', text: '—' });
    const row = (label, render) => el('tr', null, el('th', { scope: 'row', text: label }), products.map((p) => el('td', null, render(p))));
    const remove = (p) => {
      const b = el('button', { type: 'button', class: 'btn ghost small', text: 'Remove' });
      b.addEventListener('click', () => {
        saveCompare(sf, compareList(sf).filter((x) => x.slug !== p.slug));
        location.href = '/' + sf + '/compare';
      });
      return b;
    };
    const head = el('tr', null, el('th', { scope: 'col' }), products.map((p) => el('th', { scope: 'col', class: 'compare-product' },
      el('a', { href: '/' + sf + '/p/' + encodeURIComponent(p.slug) },
        p.images.length ? el('img', { src: p.images[0].thumbUrl || p.images[0].url, alt: '' }) : null,
        el('span', { text: p.title })),
      p.affiliate ? el('a', { class: 'btn small', href: p.affiliate.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
        text: 'Buy on ' + p.affiliate.merchantName }) : null,
      shared.length ? null : remove(p))));
    const shareUrl = location.origin + '/' + sf + '/compare?p=' + products.map((p) => encodeURIComponent(p.slug)).join(',');
    const copy = el('button', { type: 'button', class: 'btn secondary small', text: 'Copy link to this comparison' });
    copy.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(shareUrl); UI.toast('Link copied', 'success'); } catch (e) { UI.toast(shareUrl, 'success'); }
    });
    mount(main(), el('div', { class: 'container' }, header, el('div', { class: 'actions mb' }, copy),
      el('div', { class: 'table-wrap compare-wrap' }, el('table', { class: 'compare-table' },
        el('thead', null, head),
        el('tbody', null,
          row('Price', (p) => p.affiliate
            ? (p.affiliate.displayPriceMinor != null ? el('strong', { text: money(p.affiliate.displayPriceMinor) }) : el('span', { class: 'muted', text: 'Check price' }))
            : p.variants.length && p.variants[0].priceMinor != null ? el('strong', { text: money(p.variants[0].priceMinor) }) : '—'),
          row('Best store', (p) => p.affiliate ? p.affiliate.merchantName + (p.offers.length > 1 ? ' (' + p.offers.length + ' stores)' : '') : 'Sold here'),
          row('Our rating', (p) => p.expertRating ? expertRating(p.expertRating) : el('span', { class: 'muted', text: '—' })),
          row('Highlights', (p) => list(p.highlights)),
          specNames.map((name) => row(name, (p) => {
            const sp = (p.specs || []).find((x) => x.name === name);
            return sp ? sp.value : el('span', { class: 'muted', text: '—' });
          })),
          row('What we like', (p) => list(p.pros)),
          row('Keep in mind', (p) => list(p.cons)),
          row('Our verdict', (p) => p.verdict || el('span', { class: 'muted', text: '—' })))))));
  }

  // ------------------------------------------------------------------ recently viewed (kept in this browser)

  const recentKey = (sf) => 'hpp.recent.' + sf;

  function recentIds(sf) {
    try {
      const v = JSON.parse(localStorage.getItem(recentKey(sf)) || '[]');
      return Array.isArray(v) ? v.filter((x) => Number.isInteger(x)) : [];
    } catch (e) { return []; }
  }

  function rememberViewed(sf, id) {
    try { localStorage.setItem(recentKey(sf), JSON.stringify([id, ...recentIds(sf).filter((x) => x !== id)].slice(0, 12))); } catch (e) { /* storage blocked */ }
  }

  /** Counts one page view per product per visit for the admin's Sales & demand report (no personal data). */
  function countView(sf, slug) {
    const key = 'hpp.viewed.' + sf + '.' + slug;
    try { if (sessionStorage.getItem(key)) return; sessionStorage.setItem(key, '1'); } catch (e) { /* storage blocked: count anyway */ }
    Api.post('/api/' + sf + '/catalog/products/' + encodeURIComponent(slug) + '/view').catch(() => {});
  }

  /** "Recently viewed." shelf; loads after the page so it never slows it down. */
  function recentShelf(sf, excludeId) {
    const holder = el('div', { class: 'recent-holder' });
    const min = excludeId ? 1 : 2;
    const ids = recentIds(sf).filter((x) => x !== excludeId);
    if (ids.length < min) return holder;
    Api.get('/api/' + sf + '/catalog/cards?ids=' + ids.join(',')).then((cards) => {
      if (cards.length < min) return;
      mount(holder, shelf(sf, 'Recently viewed.', 'Pick up where you left off.', cards));
      Site.reveal(holder);
    }).catch(() => {});
    return holder;
  }

  // ------------------------------------------------------------------ share, social proof, specifications

  function shareRow(p) {
    const url = location.origin + location.pathname;
    const enc = encodeURIComponent;
    const links = [
      ['WhatsApp', 'https://wa.me/?text=' + enc(p.title + ' ' + url)],
      ['Telegram', 'https://t.me/share/url?url=' + enc(url) + '&text=' + enc(p.title)],
      ['X', 'https://twitter.com/intent/tweet?url=' + enc(url) + '&text=' + enc(p.title)],
      ['Facebook', 'https://www.facebook.com/sharer/sharer.php?u=' + enc(url)]
    ];
    const copy = el('button', { type: 'button', class: 'share-btn', text: 'Copy link' });
    copy.addEventListener('click', async () => {
      try { await navigator.clipboard.writeText(url); UI.toast('Link copied', 'success'); } catch (e) { UI.toast('Could not copy — use the address bar', 'error'); }
    });
    const native = navigator.share ? el('button', { type: 'button', class: 'share-btn', text: 'Share…' }) : null;
    if (native) native.addEventListener('click', () => navigator.share({ title: p.title, url }).catch(() => {}));
    return el('div', { class: 'share-row', 'aria-label': 'Share this deal' }, el('span', { class: 'muted small', text: 'Share' }), native,
      links.map(([name, href]) => el('a', { class: 'share-btn', href, target: '_blank', rel: 'noopener noreferrer', text: name })), copy);
  }

  /** Real clicks to the partner stores in the last 7 days; only shown once it is meaningful. */
  function socialProof(p) {
    if (!p.clicks7d || p.clicks7d < 5) return null;
    return el('p', { class: 'social-proof' }, el('span', { class: 'pulse', 'aria-hidden': 'true' }),
      p.clicks7d + ' shoppers checked this deal this week');
  }

  function specsSection(specs) {
    if (!specs || !specs.length) return null;
    return el('section', { class: 'specs reveal' }, sectionHead('Specifications.', null),
      el('dl', { class: 'spec-table' }, specs.map((sp) => el('div', { class: 'spec-row' }, el('dt', { text: sp.name }), el('dd', { text: sp.value })))));
  }

  // ------------------------------------------------------------------ coupons & offers

  /** "Copy code & go to store": copies the code, shows it, then opens the store through the tracked link. */
  function wireOfferButtons(root) {
    root.querySelectorAll('[data-offer-code]').forEach((b) => {
      if (b.dataset.wired) return;
      b.dataset.wired = '1';
      b.addEventListener('click', async () => {
        const code = b.dataset.offerCode;
        try { await navigator.clipboard.writeText(code); } catch (e) { /* the code is shown on the button anyway */ }
        b.textContent = code + ' — copied';
        b.classList.add('revealed');
        window.open(b.dataset.offerGo, '_blank', 'noopener');
        UI.toast('Code ' + code + ' copied. Paste it at checkout on the store’s site.', 'success');
      });
    });
  }

  function offerCard(o) {
    return el('article', { class: 'offer-card' },
      el('div', { class: 'offer-store-name', text: o.merchantName }),
      el('h3', { text: o.title }),
      o.description ? el('p', { class: 'muted', text: o.description }) : null,
      el('div', { class: 'offer-meta' }, o.verifiedAt ? el('span', { class: 'badge green', text: 'Verified' }) : null,
        o.endsAt ? el('span', { class: 'muted small', text: 'Ends ' + UI.date(o.endsAt, false) }) : null),
      o.code
        ? el('button', { class: 'btn offer-cta', type: 'button', dataset: { offerGo: o.goUrl, offerCode: o.code }, text: 'Copy code & go to store' })
        : el('a', { class: 'btn offer-cta', href: o.goUrl, target: '_blank', rel: 'sponsored nofollow noopener', text: 'Get this deal' }));
  }

  function offersSection(list, title, subtitle) {
    if (!list || !list.length) return null;
    const section = el('section', { class: 'offers-block reveal' }, sectionHead(title, subtitle),
      el('div', { class: 'offer-grid' }, list.map(offerCard)));
    wireOfferButtons(section);
    return section;
  }

  // ------------------------------------------------------------------ deal of the day

  /** Today's pick with a live countdown to the real end time set in the admin. */
  function dealOfDay(sf, d) {
    if (!d || !d.product) return null;
    const p = d.product;
    const end = new Date(d.endsAt).getTime();
    const parts = ['days', 'hours', 'minutes', 'seconds'].map((label) => {
      const value = el('span', { class: 'cd-value', text: '00' });
      const unit = el('span', { class: 'cd-label', text: label });
      return { value, unit, box: el('div', { class: 'cd-part' }, value, unit) };
    });
    const clock = el('div', { class: 'countdown', role: 'timer', 'aria-label': 'Time left' }, parts.map((x) => x.box));
    const section = el('section', { class: 'dotd reveal' },
      el('a', { class: 'dotd-media', href: '/' + sf + '/p/' + encodeURIComponent(p.slug) },
        p.imageUrl ? el('img', { src: p.imageUrl, alt: '', decoding: 'async' }) : null,
        p.discountPercent ? el('span', { class: 'deal-badge', text: '-' + p.discountPercent + '%' }) : null),
      el('div', { class: 'dotd-body' },
        el('p', { class: 'eyebrow', text: 'Deal of the Day' }),
        el('h2', null, el('a', { href: '/' + sf + '/p/' + encodeURIComponent(p.slug), text: p.title })),
        p.summary ? el('p', { class: 'muted', text: p.summary }) : null,
        priceLine(p),
        el('p', { class: 'small muted', text: 'Ends in' }), clock,
        el('div', { class: 'cta-row' },
          p.goUrl ? el('a', { class: 'btn', href: p.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
            text: p.merchantName && p.merchantName.length <= 16 ? 'Buy on ' + p.merchantName : 'Get the deal' }) : null,
          el('a', { class: 'link-more', href: '/' + sf + '/p/' + encodeURIComponent(p.slug), text: 'Learn more' }))));
    const tick = () => {
      let left = Math.max(0, Math.floor((end - Date.now()) / 1000));
      if (left <= 0) { section.remove(); return; }
      const days = Math.floor(left / 86400); left -= days * 86400;
      const h = Math.floor(left / 3600); left -= h * 3600;
      const m = Math.floor(left / 60);
      const s = left - m * 60;
      [days, h, m, s].forEach((v, i) => { parts[i].value.textContent = String(v).padStart(2, '0'); });
      parts[0].box.hidden = days === 0;
      parts[0].unit.textContent = days === 1 ? 'day' : 'days';
      setTimeout(tick, 1000);
    };
    tick();
    return section;
  }

  // ------------------------------------------------------------------ newsletter & price alerts

  function newsletterForm(sf, source, compact) {
    const email = el('input', { type: 'email', name: 'email', required: true, autocomplete: 'email', placeholder: 'you@example.com', 'aria-label': 'Email address' });
    const btn = el('button', { class: 'btn', type: 'submit', text: 'Get deals' });
    const msg = el('p', { class: 'small newsletter-msg', role: 'status' });
    const form = el('form', { class: 'newsletter-form' + (compact ? ' compact' : '') }, el('div', { class: 'newsletter-row' }, email, btn), msg);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(btn, async () => {
        try {
          const r = await Api.post('/api/' + sf + '/newsletter', { email: email.value.trim(), source });
          msg.textContent = r.status === 'ACTIVE' ? 'You are already subscribed — thank you!' : 'Almost done: check your inbox and tap the link to confirm.';
          Site.track('sign_up');
          msg.className = 'small newsletter-msg ok-text';
          form.reset();
        } catch (err) {
          msg.textContent = UI.errorMessage(err);
          msg.className = 'small newsletter-msg error-text';
        }
      });
    });
    return form;
  }

  function newsletterSection(sf) {
    return el('section', { class: 'newsletter reveal' },
      el('div', null, el('h2', null, 'Never miss a deal. ', el('span', { class: 'sub', text: 'The week’s best prices, once a week.' })),
        el('p', { class: 'muted small', text: 'Free. Unsubscribe with one click any time. We never share your email.' })),
      newsletterForm(sf, 'home', false));
  }

  /** "Email me when it drops below ₹X" (not for stores whose prices we may not show). */
  function priceAlertBox(sf, p) {
    const price = p.affiliate && p.affiliate.displayPriceMinor;
    if (price == null) return null;
    const suggested = Math.max(1, Math.floor((price * 0.9) / 100));
    const email = el('input', { type: 'email', required: true, autocomplete: 'email', placeholder: 'you@example.com', 'aria-label': 'Email address' });
    const target = el('input', { type: 'number', min: '1', max: String(Math.floor(price / 100) - 1), step: '1', value: String(suggested), 'aria-label': 'Target price in rupees' });
    const btn = el('button', { class: 'btn secondary small', type: 'submit', text: 'Set alert' });
    const msg = el('p', { class: 'small', role: 'status' });
    const form = el('form', { class: 'price-alert' },
      el('p', { class: 'price-alert-title' }, el('strong', { text: 'Waiting for a better price?' }), ' We’ll email you when it drops below:'),
      el('div', { class: 'price-alert-row' }, el('span', { class: 'rupee', text: '₹' }), target, email, btn), msg);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(btn, async () => {
        try {
          const r = await Api.post('/api/' + sf + '/price-alerts', { slug: p.slug, email: email.value.trim(), targetPrice: parseInt(target.value, 10) });
          msg.className = 'small ok-text';
          msg.textContent = r.status === 'ACTIVE' ? 'Your alert is updated to ' + money(r.targetMinor) + '.'
            : 'Check your inbox and confirm — then we’ll watch the price for you.';
          Site.track('price_alert', { name: p.title });
        } catch (err) {
          msg.className = 'small error-text';
          msg.textContent = UI.errorMessage(err);
        }
      });
    });
    return form;
  }

  /** Messages after clicking a link in our emails (?alert=on, ?newsletter=off, ...). */
  function linkNotices() {
    const notes = { 'alert=on': 'Price alert confirmed. We’ll email you when the price drops.', 'alert=off': 'Your price alert is cancelled.',
      'newsletter=on': 'You’re subscribed. Watch your inbox for the best deals.', 'newsletter=off': 'You’ve been unsubscribed. Sorry to see you go!' };
    Object.entries(notes).forEach(([q, text]) => { if (location.search.includes(q)) UI.toast(text, 'success'); });
  }

  // ------------------------------------------------------------------ carousel

  /**
   * Auto-advancing slider. Rotation pauses while the pointer or keyboard focus is on a slide or the tab is hidden,
   * stops once the shopper navigates by hand (the optional toggle restarts it) and does not start with reduced motion.
   */
  function carousel(contents, opts) {
    const o = Object.assign({ interval: 4000, label: 'Slides', dots: false, toggle: false }, opts);
    const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let index = 0;
    let playing = !reduceMotion;
    let hovered = false;
    let focused = false;
    let timer = null;

    const slides = contents.map((c, i) => el('div', {
      class: 'carousel-slide', role: 'group', 'aria-roledescription': 'slide', 'aria-label': (i + 1) + ' of ' + contents.length
    }, c));
    const track = el('div', { class: 'carousel-track' }, slides);
    const viewport = el('div', { class: 'carousel-viewport ' + (o.viewportClass || '') }, track);
    const arrow = (step, label, text) => el('button', {
      type: 'button', class: 'carousel-arrow ' + (step < 0 ? 'prev' : 'next'), 'aria-label': label, text,
      on: { click: () => go(index + step, true) }
    });
    const dots = o.dots ? slides.map((_, i) => el('button', {
      type: 'button', class: 'carousel-dot', 'aria-label': 'Show slide ' + (i + 1), on: { click: () => go(i, true) }
    })) : [];
    const toggle = o.toggle ? el('button', {
      type: 'button', class: 'carousel-toggle', on: { click: () => { playing = !playing; render(); } }
    }) : null;

    const root = el('div', { class: 'carousel ' + (o.className || ''), role: 'region', 'aria-roledescription': 'carousel', 'aria-label': o.label },
      el('div', { class: 'carousel-stage' }, viewport, arrow(-1, 'Previous slide', '‹'), arrow(1, 'Next slide', '›')),
      dots.length || toggle ? el('div', { class: 'carousel-controls' }, toggle, el('div', { class: 'carousel-dots' }, dots)) : null);

    function schedule() {
      clearTimeout(timer);
      if (playing && !hovered && !focused && !document.hidden) timer = setTimeout(() => go(index + 1), o.interval);
    }

    function render() {
      track.style.transform = 'translateX(' + (-100 * index) + '%)';
      slides.forEach((s, i) => {
        s.inert = i !== index;
        s.setAttribute('aria-hidden', String(i !== index));
      });
      dots.forEach((d, i) => d.setAttribute('aria-current', String(i === index)));
      track.setAttribute('aria-live', playing ? 'off' : 'polite');
      if (toggle) {
        toggle.textContent = playing ? 'Pause' : 'Play';
        toggle.setAttribute('aria-label', (playing ? 'Pause' : 'Play') + ' slideshow');
      }
      schedule();
    }

    function go(i, byUser) {
      index = (i + slides.length) % slides.length;
      if (byUser) playing = false;
      render();
      if (o.onChange) o.onChange(index);
    }

    viewport.addEventListener('mouseenter', () => { hovered = true; schedule(); });
    viewport.addEventListener('mouseleave', () => { hovered = false; schedule(); });
    viewport.addEventListener('focusin', () => { focused = true; schedule(); });
    viewport.addEventListener('focusout', (e) => {
      if (!viewport.contains(e.relatedTarget)) { focused = false; schedule(); }
    });
    document.addEventListener('visibilitychange', schedule);
    root.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault();
        go(index + (e.key === 'ArrowLeft' ? -1 : 1), true);
      }
    });
    let touchX = null;
    viewport.addEventListener('touchstart', (e) => { touchX = e.touches[0].clientX; }, { passive: true });
    viewport.addEventListener('touchend', (e) => {
      if (touchX === null) return;
      const dx = e.changedTouches[0].clientX - touchX;
      touchX = null;
      if (Math.abs(dx) > 40) go(index + (dx < 0 ? 1 : -1), true);
    });

    /** Stops the automatic slideshow (e.g. while a video plays). */
    function pause() {
      playing = false;
      render();
    }

    render();
    return { root, go, pause };
  }

  // ------------------------------------------------------------------ product

  /**
   * Product page built for clicks: title, price and the Buy button come first (also on phones, plus a sticky Buy bar),
   * then highlights, a store price comparison, our verdict, pros and cons, the description and similar deals.
   */
  async function productPage({ sf, user }) {
    const slug = decodeURIComponent((location.pathname.split('/p/')[1] || '').replace(/\/$/, ''));
    const p = await Api.get('/api/' + sf + '/catalog/products/' + encodeURIComponent(slug));
    rememberViewed(sf, p.id);
    countView(sf, p.slug);
    document.body.dataset.trackName = p.title;
    Site.track('view_item', { id: p.id, name: p.title, category: p.category ? p.category.name : undefined,
      store: p.affiliate ? p.affiliate.merchantName : undefined,
      price: p.affiliate && p.affiliate.displayPriceMinor != null ? p.affiliate.displayPriceMinor / 100 : undefined });
    const offersHolder = el('div');
    if (p.sellingMethod === 'AFFILIATE') {
      Api.get('/api/' + sf + '/catalog/offers?product=' + p.id).then((list) => {
        const block = offersSection(list, 'Coupons for this deal.', 'Use them at checkout on the store\u2019s site.');
        if (block) { mount(offersHolder, block); Site.reveal(offersHolder); }
      }).catch(() => {});
    }
    const ratingLine = el('div', { class: 'rating-line' });

    const fallbackPoster = p.images.length ? p.images[0].largeUrl || p.images[0].url : null;
    const pictures = p.images.map((img, i) => ({
      node: el('img', { src: img.largeUrl || img.url, alt: img.alt || p.title, decoding: 'async', fetchpriority: i === 0 ? 'high' : null }),
      thumb: img.thumbUrl || img.url, label: 'Image ' + (i + 1)
    }));
    const clips = (p.videos || []).map((v, i) => ({
      node: el('video', { class: 'gallery-video', controls: true, preload: 'none', playsinline: true, poster: v.posterUrl || fallbackPoster,
        'aria-label': v.title || p.title }, el('source', { src: v.url, type: v.contentType })),
      thumb: v.posterUrl || fallbackPoster, label: 'Video ' + (i + 1) + (v.title ? ': ' + v.title : ''), video: true
    }));
    // The main image stays first; videos follow it so shoppers find them without scrolling through every photo.
    const slides = pictures.length ? [pictures[0], ...clips, ...pictures.slice(1)] : clips;
    let gallery;
    if (slides.length > 1) {
      const thumbs = slides.map((m, i) => el('button', {
        type: 'button', class: (i === 0 ? 'active ' : '') + (m.video ? 'video-thumb' : ''), 'aria-label': m.label,
        on: { click: () => slider.go(i, true) }
      }, m.thumb ? el('img', { src: m.thumb, alt: '', loading: 'lazy', decoding: 'async' }) : null,
      m.video ? el('span', { class: 'play-badge', 'aria-hidden': 'true', text: '▶' }) : null));
      const slider = carousel(slides.map((m) => m.node), {
        className: 'gallery', viewportClass: 'main', label: p.title + (clips.length ? ' images and videos' : ' images'),
        onChange: (i) => {
          thumbs.forEach((b, j) => b.classList.toggle('active', i === j));
          slides.forEach((m, j) => { if (m.video && j !== i) m.node.pause(); });
        }
      });
      clips.forEach((m) => m.node.addEventListener('play', () => slider.pause()));
      slider.root.appendChild(el('div', { class: 'thumbs' }, thumbs));
      gallery = slider.root;
    } else {
      gallery = el('div', { class: 'gallery' },
        el('div', { class: 'main' }, slides.length ? slides[0].node : el('span', { class: 'muted', text: 'No image' })));
    }

    const info = el('div', { class: 'buy-box' });
    UI.append(info, [
      el('div', { class: 'card-meta' }, demoBadge(p), p.brand ? el('span', { class: 'muted', text: p.brand }) : null),
      el('h1', { text: p.title }),
      p.expertRating ? expertRating(p.expertRating, 'Our rating') : null,
      ratingLine
    ]);

    let sticky = null;
    let mainCta = null;
    if (p.sellingMethod === 'AFFILIATE') {
      const offer = p.affiliate;
      const variant = (p.variants && p.variants[0]) || null;
      if (offer) {
        const shown = offer.displayPriceMinor != null;
        const addBtn = el('button', { class: 'btn ghost small', type: 'button', text: 'Add to cart' });
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
        mainCta = el('a', { class: 'btn block buy-cta', href: offer.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
          text: 'Buy on ' + offer.merchantName + ' →' });
        UI.append(info, [
          el('div', { class: 'price-box' },
            shown
              ? el('div', { class: 'price-row' }, el('span', { class: 'price big', text: money(offer.displayPriceMinor) }),
                offer.discountPercent ? [el('s', { class: 'muted', text: money(offer.listPriceMinor) }),
                  el('span', { class: 'deal-badge inline', text: '-' + offer.discountPercent + '%' })] : null)
              : el('div', { class: 'check-price big', text: 'Check the latest price on ' + offer.merchantName }),
            el('div', { class: 'muted small' }, 'at ', el('strong', { text: offer.merchantName }),
              shown && offer.priceCheckedAt ? ' · ' + checkedText(offer.priceCheckedAt) + '. The store sets the final price.' : null),
            priceFlags(p.priceStats)),
          socialProof(p),
          mainCta,
          el('p', { class: 'disclosure-line', text: offer.disclosure }),
          p.offers.length > 1 ? compareStores(p.offers) : null,
          priceAlertBox(sf, p),
          p.summary ? el('p', { class: 'lead-text', text: p.summary }) : null,
          el('div', { class: 'actions secondary-actions' }, wishlistButton(sf, p.slug, user), compareToggle(sf, p, 'ghost-compare'), addBtn),
          msg,
          isDemoProduct(p) ? el('p', { class: 'note small', text: 'This is a sample listing so you can see how the store looks. The Buy button opens a demo partner page — delete it in the admin panel when you add real offers.' }) : null
        ]);
        sticky = el('div', { class: 'sticky-buy', role: 'region', 'aria-label': 'Buy this deal' },
          el('div', { class: 'sticky-inner' },
            el('div', { class: 'sticky-title', text: p.title }),
            el('div', { class: 'sticky-price' }, el('strong', { text: shown ? money(offer.displayPriceMinor) : 'Check price' }),
              el('span', { class: 'muted small', text: 'at ' + offer.merchantName })),
            el('a', { class: 'btn', href: offer.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer', text: 'Buy now' })));
      } else {
        info.appendChild(el('div', { class: 'alert warning', text: 'This partner offer is currently unavailable.' }));
      }
    } else {
      const active = p.variants;
      let selected = active.find((v) => v.available) || active[0];
      const priceEl = el('p', { class: 'price big' });
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
        p.summary ? el('p', { class: 'lead-text', text: p.summary }) : null,
        wishlistButton(sf, p.slug, user),
        p.productType === 'PHYSICAL' ? pincodeChecker(sf) : null,
        p.digitalFormat ? el('p', { class: 'muted small', text: 'Format: ' + p.digitalFormat }) : null]);
    }
    if (p.highlights.length) {
      info.appendChild(el('ul', { class: 'highlights', 'aria-label': 'Highlights' }, p.highlights.map((h) => el('li', { text: h }))));
    }
    info.appendChild(shareRow(p));

    const more = el('div', { class: 'product-more' },
      p.verdict ? el('section', { class: 'verdict reveal' }, el('p', { class: 'eyebrow', text: 'Our verdict' }),
        el('blockquote', { text: p.verdict }), p.expertRating ? expertRating(p.expertRating, 'Our rating') : null) : null,
      p.pros.length || p.cons.length ? el('section', { class: 'pros-cons reveal' },
        p.pros.length ? el('div', { class: 'card pros' }, el('h3', { text: 'What we like.' }), el('ul', null, p.pros.map((x) => el('li', { text: x })))) : null,
        p.cons.length ? el('div', { class: 'card cons' }, el('h3', { text: 'Keep in mind.' }), el('ul', null, p.cons.map((x) => el('li', { text: x })))) : null) : null,
      offersHolder,
      specsSection(p.specs),
      p.description ? el('section', { class: 'details reveal' }, sectionHead('The details.', null), el('div', { class: 'description', text: p.description })) : null,
      shelf(sf, 'You may also like.', 'More deals like this one.', p.similar),
      guidesSection(sf, p.id, 'Featured in our guides.', 'See how it compares.'),
      recentShelf(sf, p.id));

    const reviewsBox = el('section', { class: 'reviews', id: 'reviews' });
    mount(main(), el('div', { class: 'container' },
      el('nav', { class: 'breadcrumb small', 'aria-label': 'Breadcrumb' },
        el('a', { href: '/' + sf, text: 'Home' }),
        p.category ? [' / ', el('a', { href: '/' + sf + '/c/' + encodeURIComponent(p.category.slug), text: p.category.name })] : null,
        ' / ', el('span', { text: p.title })),
      el('div', { class: 'product-detail' }, gallery, info),
      more,
      reviewsBox), sticky);
    if (sticky && mainCta) {
      // Phones: a Buy button is always in reach; the bar hides while the main one is on screen.
      document.body.classList.add('has-sticky-buy');
      if ('IntersectionObserver' in window) {
        new IntersectionObserver(([e]) => sticky.classList.toggle('show', !e.isIntersecting)).observe(mainCta);
      } else {
        sticky.classList.add('show');
      }
    }
    renderReviews(sf, p, user, reviewsBox, ratingLine, 0);
  }

  function priceFlags(s) {
    if (!s) return null;
    const flag = s.lowest30d ? el('span', { class: 'deal-flag inline', text: 'Lowest price in 30 days' })
      : s.dropPercent ? el('span', { class: 'deal-flag inline', text: 'Price dropped ' + s.dropPercent + '% this week' }) : null;
    const range = s.low30Minor != null && s.high30Minor != null && s.low30Minor !== s.high30Minor
      ? el('div', { class: 'muted small', text: '30-day price range: ' + money(s.low30Minor) + ' – ' + money(s.high30Minor) }) : null;
    return flag || range ? el('div', { class: 'price-flags' }, flag, range) : null;
  }

  /** Every store that sells the product, best price first. "Best price" is only claimed when every store shows a price. */
  function compareStores(offers) {
    const complete = offers.every((o) => o.displayPriceMinor != null);
    return el('div', { class: 'compare-stores' },
      el('h3', { text: 'Compare ' + offers.length + ' stores' }),
      el('ul', null, offers.map((o, i) => el('li', { class: i === 0 && complete ? 'best' : '' },
        el('div', { class: 'store' }, el('strong', { text: o.merchantName }),
          i === 0 && complete ? el('span', { class: 'badge green', text: 'Best price' }) : null,
          o.displayPriceMinor != null && o.priceCheckedAt ? el('div', { class: 'muted small', text: checkedText(o.priceCheckedAt) }) : null),
        el('div', { class: 'store-price' }, o.displayPriceMinor != null ? money(o.displayPriceMinor) : el('span', { class: 'muted small', text: 'Check price' })),
        el('a', { class: 'btn small' + (i === 0 ? '' : ' secondary'), href: o.goUrl, target: '_blank', rel: 'sponsored noopener noreferrer',
          text: 'Go to ' + o.merchantName })))));
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
      el('div', { class: 'muted small' }, rv.authorName, rv.verifiedPurchase ? ' \u00B7 Verified purchase' : '',
        rv.sourceLabel ? ' \u00B7 ' + rv.sourceLabel : '', ' \u00B7 ' + UI.date(rv.createdAt, false)),
      rv.body ? el('p', { class: 'review-body', text: rv.body }) : null,
      rv.adminReply ? el('p', { class: 'note small', text: 'Reply from the store: ' + rv.adminReply }) : null))
      : el('p', { class: 'muted', text: 'No reviews yet. Used this product? Your review helps other shoppers decide.' });

    const pager = data.reviews.totalPages > 1 ? el('div', { class: 'pager' },
      page > 0 ? el('button', { class: 'btn secondary small', type: 'button', text: 'Newer', on: { click: () => renderReviews(sf, p, user, box, ratingLine, page - 1) } }) : null,
      page + 1 < data.reviews.totalPages ? el('button', { class: 'btn secondary small', type: 'button', text: 'Older', on: { click: () => renderReviews(sf, p, user, box, ratingLine, page + 1) } }) : null) : null;

    let formArea;
    if (!user) {
      const next = '/account/login.html?next=' + encodeURIComponent(location.pathname + location.search + '#reviews');
      formArea = el('div', { class: 'card review-cta' },
        el('div', null, el('strong', { text: 'Used this product?' }),
          el('div', { class: 'muted small', text: 'Share your experience to help other shoppers. It takes a minute.' })),
        el('a', { class: 'btn secondary', href: next, text: 'Write a review' }));
    } else if (!data.canReview) {
      formArea = data.cannotReviewReason ? el('p', { class: 'muted small', text: data.cannotReviewReason }) : null;
    } else {
      formArea = reviewForm(sf, p, data.mine, () => renderReviews(sf, p, user, box, ratingLine, 0));
    }
    const summary = r.count ? el('p', { class: 'review-summary' }, stars(r.average),
      el('strong', { text: ' ' + r.average.toFixed(1) + ' out of 5' }),
      el('span', { class: 'muted', text: ' \u00B7 ' + r.count + (r.count === 1 ? ' review' : ' reviews') })) : null;
    mount(box, sectionHead('Customer reviews.', 'What buyers say.'), summary, formArea, list, pager);
    if (page === 0 && location.hash === '#reviews') box.scrollIntoView({ block: 'start' });
  }

  function reviewForm(sf, p, mine, done) {
    const rating = el('select', { required: true },
      [5, 4, 3, 2, 1].map((n) => el('option', { value: String(n), text: n + ' star' + (n > 1 ? 's' : ''), selected: mine ? mine.rating === n : n === 5 })));
    const title = el('input', { maxlength: '120', value: mine ? mine.title || '' : '' });
    const body = el('textarea', { rows: '4', maxlength: '3000', placeholder: 'What did you like or dislike? How did you use it?' });
    body.value = mine ? mine.body || '' : '';
    const msg = el('div');
    const btn = el('button', { class: 'btn', type: 'submit', text: mine ? 'Update review' : 'Submit review' });
    const form = el('form', { class: 'card review-form' },
      el('h3', { text: mine ? 'Your review' : 'Write a review' }),
      mine && mine.status === 'PENDING' ? el('p', { class: 'muted small', text: 'Thanks! Your review is waiting for approval and will appear here once the store team checks it.' })
        : mine && mine.status === 'REJECTED' ? el('p', { class: 'muted small', text: 'Your review was not published. You can edit it and send it again.' })
        : !mine ? el('p', { class: 'muted small', text: 'Reviews appear after a quick check by the store team.' }) : null,
      el('div', { class: 'row' }, UI.field('Rating', rating), UI.field('Title (optional)', title)),
      UI.field('Your review', body), msg, btn);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(btn, async () => {
        try {
          const saved = await Api.post('/api/' + sf + '/catalog/products/' + encodeURIComponent(p.slug) + '/reviews',
            { rating: parseInt(rating.value, 10), title: title.value.trim(), body: body.value.trim() });
          UI.toast(saved.status === 'APPROVED' ? 'Thanks! Your review is live.' : 'Thanks! Your review will appear once the store team approves it.', 'success');
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
