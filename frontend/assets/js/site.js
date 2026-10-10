/* Shared header/footer and customer-session helpers for storefront and account pages. */
(function () {
  'use strict';
  const { el, mount } = UI;

  const STOREFRONTS = { shop: 'Elaashop', digital: 'Elaatech' };
  const BRANDS = {
    shop: {
      name: 'Elaashop',
      logo: '/assets/img/elaashop-logo.png',
      wordmark: '/assets/img/elaashop-wordmark.png',
      eyebrow: 'Elaashop · affiliate picks',
      headline: ['Gear that ', 'moves', ' with you'],
      tagline: 'Curated audio, tech and lifestyle deals from trusted partner stores. You buy on their site — we earn a commission when you do.'
    },
    digital: {
      name: 'Elaatech',
      logo: '/assets/img/elaatech-logo.png',
      wordmark: '/assets/img/elaatech-wordmark.png',
      eyebrow: 'Elaatech · affiliate picks',
      headline: ['Digital tools, ', 'hand-picked', ' for you'],
      tagline: 'Software, courses and e-books from partner merchants. Tap Buy to continue on their store — fulfilment is theirs.'
    }
  };
  const MARK = '/assets/img/elaa-mark.png';

  function storefrontFromPath() {
    const first = location.pathname.split('/').filter(Boolean)[0];
    return STOREFRONTS[first] ? first : null;
  }

  function currentStorefront() {
    const sf = storefrontFromPath() || UI.param('sf') || sessionStorage.getItem('hpp.sf') || 'shop';
    const valid = STOREFRONTS[sf] ? sf : 'shop';
    sessionStorage.setItem('hpp.sf', valid);
    return valid;
  }

  let mePromise = null;
  function me() {
    if (!mePromise) {
      mePromise = Api.get('/api/auth/me').catch((e) => {
        if (e.status === 401) return null;
        throw e;
      });
    }
    return mePromise;
  }

  function loginUrl() {
    return '/account/login.html?next=' + encodeURIComponent(location.pathname + location.search);
  }

  async function requireCustomer() {
    const user = await me();
    if (!user) {
      location.href = loginUrl();
      return new Promise(() => {});
    }
    return user;
  }

  function safeNext(next, fallback) {
    if (next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\')) return next;
    return fallback;
  }

  async function renderChrome(options) {
    const opts = options || {};
    const sf = currentStorefront();
    const header = document.getElementById('site-header');
    const footer = document.getElementById('site-footer');
    let storeName = STOREFRONTS[sf];
    try {
      const info = await Api.get('/api/' + sf + '/catalog/store');
      storeName = info.store.storeName;
      window.HPP_STORE = info;
    } catch (e) { /* header still renders with the default name */ }

    const affiliateOnly = !(window.HPP_STORE && window.HPP_STORE.affiliateOnly === false);
    const cartLink = el('a', { href: '/' + sf + '/cart' }, 'Cart');
    const accountSlot = el('span');
    const search = opts.search === false ? null : el('form', {
      class: 'header-search', role: 'search', action: '/' + sf, method: 'get'
    }, el('input', { type: 'search', name: 'q', placeholder: 'Search ' + storeName, value: UI.param('q') || '', 'aria-label': 'Search' }),
    el('button', { class: 'btn secondary', type: 'submit', text: 'Search' }));

    if (header) {
      mount(header, el('div', { class: 'container' },
        el('a', { class: 'brand', href: '/' + sf, 'aria-label': storeName + ' home' },
          el('img', { class: 'brand-mark', src: MARK, alt: '' }),
          el('img', { class: 'brand-word', src: BRANDS[sf].wordmark, alt: storeName })),
        el('nav', { class: 'storefront-switch', 'aria-label': 'Storefronts' },
          Object.entries(STOREFRONTS).map(([key, name]) =>
            el('a', { href: '/' + key, class: key === sf ? 'active' : '', text: name }))),
        search || el('span', { class: 'spacer' }),
        el('nav', { class: 'nav-links' },
          el('a', { href: '/' + sf + '/wishlist', text: 'Wishlist' }), cartLink, accountSlot)));
    }
    if (footer) {
      const pages = (window.HPP_STORE && window.HPP_STORE.pages) || [];
      mount(footer, el('div', { class: 'container' },
        el('img', { class: 'footer-mark', src: MARK, alt: '' }),
        el('span', { text: '\u00A9 ' + new Date().getFullYear() + ' ' + storeName }),
        window.HPP_STORE && window.HPP_STORE.store.supportEmail
          ? el('span', null, ' \u00B7 Support: ', el('a', { href: 'mailto:' + window.HPP_STORE.store.supportEmail, text: window.HPP_STORE.store.supportEmail }))
          : null,
        pages.length ? el('nav', { class: 'footer-links', 'aria-label': 'Store policies' },
          pages.map((p) => el('a', { href: p.url, text: p.title }))) : null));
    }

    const user = await me().catch(() => null);
    if (user) {
      mount(accountSlot, el('a', { href: '/account?sf=' + sf, text: 'My account' }));
      try {
        const cart = await Api.get('/api/' + sf + '/cart');
        if (cart.itemCount > 0) cartLink.appendChild(el('span', { class: 'cart-count', text: String(cart.itemCount) }));
      } catch (e) { /* ignore */ }
    } else {
      mount(accountSlot, el('a', { href: loginUrl(), text: 'Sign in' }));
    }
    return { sf, user, storeName, affiliateOnly };
  }

  function pageError(err) {
    const main = document.getElementById('main');
    if (main) mount(main, el('div', { class: 'container' }, UI.errorAlert(err)));
  }

  window.Site = { STOREFRONTS, BRANDS, MARK, currentStorefront, storefrontFromPath, me, requireCustomer, loginUrl, safeNext, renderChrome, pageError };
})();
