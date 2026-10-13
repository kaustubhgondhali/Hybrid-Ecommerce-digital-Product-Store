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
      headline: ['Digital tools, ', 'hand‑picked', ' for you'],
      tagline: 'Software, courses and e-books from partner merchants. Tap Buy to continue on their store — fulfilment is theirs.'
    }
  };
  const MARK = '/assets/img/elaa-mark.png';
  const SYMBOL = '/assets/img/elaa-symbol.png';

  /** The brand symbol, alive: heartbeat, sound-wave ripples, glow and a passing shine (still with reduced motion). */
  function liveMark(label) {
    return el('div', { class: 'live-mark', role: 'img', 'aria-label': label },
      el('span', { class: 'lm-wave' }), el('span', { class: 'lm-wave' }), el('span', { class: 'lm-wave' }),
      el('span', { class: 'lm-glow' }),
      el('span', { class: 'lm-core' }, el('img', { class: 'lm-symbol', src: SYMBOL, alt: '', decoding: 'async' }), el('span', { class: 'lm-shine' })));
  }

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

  /** Icon drawn with a CSS mask (see .icon-* in app.css), so it takes the text colour. */
  function icon(name) {
    return el('span', { class: 'icon icon-' + name, 'aria-hidden': 'true' });
  }

  /**
   * Apple-style global bar: logo, the store's top categories, then search / wishlist / bag / account. Search opens a
   * panel under the bar with quick links; on phones a menu button opens the categories and store switch.
   */
  // ------------------------------------------------------------------ installable app (PWA)

  let installPrompt = null;
  window.addEventListener('beforeinstallprompt', (e) => {
    installPrompt = e;
    document.querySelectorAll('.install-app').forEach((b) => { b.hidden = false; });
  });

  /** Manifest, theme colour and home-screen icon for this storefront, plus the offline helper. */
  function appSetup(sf) {
    const brand = sf === 'digital' ? 'elaatech' : 'elaashop';
    const head = document.head;
    if (!head.querySelector('link[rel="manifest"]')) head.appendChild(el('link', { rel: 'manifest', href: '/' + sf + '/manifest.webmanifest' }));
    if (!head.querySelector('meta[name="theme-color"]')) head.appendChild(el('meta', { name: 'theme-color', content: '#0a0b0d' }));
    if (!head.querySelector('link[rel="apple-touch-icon"]')) head.appendChild(el('link', { rel: 'apple-touch-icon', href: '/assets/img/' + brand + '-icon-192.png' }));
    if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
  }

  /** Shown only when the browser offers installation (Chrome, Edge, Samsung Internet on Android and desktop). */
  function installButton() {
    const b = el('button', { class: 'install-app', type: 'button', hidden: !installPrompt, text: 'Install the app' });
    b.addEventListener('click', async () => {
      if (!installPrompt) return;
      installPrompt.prompt();
      await installPrompt.userChoice.catch(() => null);
      installPrompt = null;
      document.querySelectorAll('.install-app').forEach((x) => { x.hidden = true; });
    });
    return b;
  }

  // ------------------------------------------------------------------ analytics, only after consent

  const CONSENT_KEY = 'hpp.consent';
  let trackers = null;

  function consentChoice() {
    try { return localStorage.getItem(CONSENT_KEY); } catch (e) { return null; }
  }

  function analyticsIds() {
    const s = (window.HPP_STORE && window.HPP_STORE.store) || {};
    return { ga4: s.ga4Id || null, pixel: s.metaPixelId || null };
  }

  /** Loads Google Analytics 4 and/or the Meta Pixel (never before the shopper says yes). */
  function startAnalytics(ids) {
    if (trackers || (!ids.ga4 && !ids.pixel)) return;
    trackers = ids;
    if (ids.ga4) {
      window.dataLayer = window.dataLayer || [];
      window.gtag = function () { window.dataLayer.push(arguments); };
      window.gtag('js', new Date());
      window.gtag('config', ids.ga4, { anonymize_ip: true });
      document.head.appendChild(el('script', { async: true, src: 'https://www.googletagmanager.com/gtag/js?id=' + encodeURIComponent(ids.ga4) }));
    }
    if (ids.pixel) {
      const f = function () { if (f.callMethod) f.callMethod.apply(f, arguments); else f.queue.push(arguments); };
      window.fbq = f;
      if (!window._fbq) window._fbq = f;
      f.push = f; f.loaded = true; f.version = '2.0'; f.queue = [];
      f('init', ids.pixel);
      f('track', 'PageView');
      document.head.appendChild(el('script', { async: true, src: 'https://connect.facebook.net/en_US/fbevents.js' }));
    }
  }

  /**
   * Records a shopper action in the analytics tools the store uses (does nothing without consent or IDs).
   * view_item {id, name, price, category, store} · search {term} · click_to_store {name, url} · sign_up · price_alert
   */
  function track(name, p) {
    if (!trackers) return;
    const d = p || {};
    if (trackers.ga4 && window.gtag) {
      if (name === 'view_item') {
        window.gtag('event', 'view_item', { currency: 'INR', value: d.price, items: [{ item_id: String(d.id), item_name: d.name,
          item_category: d.category, affiliation: d.store, price: d.price }] });
      } else if (name === 'search') {
        window.gtag('event', 'search', { search_term: d.term });
      } else if (name === 'sign_up') {
        window.gtag('event', 'sign_up', { method: 'newsletter' });
      } else {
        window.gtag('event', name, { item_name: d.name, link_url: d.url });
      }
    }
    if (trackers.pixel && window.fbq) {
      if (name === 'view_item') {
        window.fbq('track', 'ViewContent', { content_ids: [String(d.id)], content_name: d.name, content_type: 'product', value: d.price, currency: 'INR' });
      } else if (name === 'search') {
        window.fbq('track', 'Search', { search_string: d.term });
      } else if (name === 'sign_up') {
        window.fbq('track', 'Lead');
      } else {
        window.fbq('trackCustom', name === 'click_to_store' ? 'ClickToStore' : 'PriceAlert', { content_name: d.name });
      }
    }
  }

  /** The cookie banner: equal choice, remembered on this device, can be changed from the footer. */
  function consentBanner(force) {
    const ids = analyticsIds();
    if (!ids.ga4 && !ids.pixel) return;
    const before = consentChoice();
    if (!force && before === 'all') { startAnalytics(ids); return; }
    if (!force && before === 'necessary') return;
    if (document.querySelector('.consent-banner')) return;
    const pages = (window.HPP_STORE && window.HPP_STORE.pages) || [];
    const privacy = pages.find((x) => /privacy/i.test(x.url + ' ' + x.title));
    const choose = (value) => {
      try { localStorage.setItem(CONSENT_KEY, value); } catch (e) { /* storage blocked: ask again next time */ }
      bar.remove();
      if (value === 'all') startAnalytics(ids);
      else if (trackers) location.reload();
    };
    const bar = el('div', { class: 'consent-banner', role: 'dialog', 'aria-label': 'Cookie choices' },
      el('p', null, el('strong', { text: 'Cookies. ' }),
        'We use analytics cookies (Google Analytics and Meta) to see which deals people like and to improve the site. Nothing is shared until you agree. ',
        privacy ? el('a', { href: privacy.url, text: 'Privacy policy' }) : null),
      el('div', { class: 'consent-actions' },
        el('button', { class: 'btn small secondary', type: 'button', text: 'Only necessary', on: { click: () => choose('necessary') } }),
        el('button', { class: 'btn small', type: 'button', text: 'Accept all', on: { click: () => choose('all') } })));
    document.body.appendChild(bar);
  }

  // Clicks on partner-store links anywhere on the site.
  document.addEventListener('click', (e) => {
    const a = e.target.closest && e.target.closest('a[href^="/go/"]');
    if (a) track('click_to_store', { name: document.body.dataset.trackName || document.title, url: a.getAttribute('href') });
  }, true);

  async function renderChrome(options) {
    const opts = options || {};
    const sf = currentStorefront();
    const header = document.getElementById('site-header');
    const footer = document.getElementById('site-footer');
    let storeName = STOREFRONTS[sf];
    const storiesRequest = Api.get('/api/' + sf + '/catalog/stories?limit=12').catch(() => []);
    const categoriesRequest = Api.get('/api/' + sf + '/catalog/categories').catch(() => []);
    const offersRequest = Api.get('/api/' + sf + '/catalog/offers').catch(() => []);
    const guidesRequest = Api.get('/api/' + sf + '/catalog/guides?limit=3').catch(() => []);
    try {
      const info = await Api.get('/api/' + sf + '/catalog/store');
      storeName = info.store.storeName;
      window.HPP_STORE = info;
    } catch (e) { /* header still renders with the default name */ }
    const [stories, categories, offers, guides] = await Promise.all([storiesRequest, categoriesRequest, offersRequest, guidesRequest]);

    const affiliateOnly = !(window.HPP_STORE && window.HPP_STORE.affiliateOnly === false);
    const other = sf === 'shop' ? 'digital' : 'shop';
    const catHref = (c) => '/' + sf + '/c/' + encodeURIComponent(c.slug);
    const cartLink = el('a', { class: 'nav-icon nav-cart', href: '/' + sf + '/cart', 'aria-label': 'Cart', title: 'Cart' }, icon('bag'));
    const accountSlot = el('span', { class: 'nav-account' });
    const switcher = () => el('nav', { class: 'storefront-switch', 'aria-label': 'Storefronts' },
      Object.entries(STOREFRONTS).map(([key, name]) => el('a', { href: '/' + key, class: key === sf ? 'active' : '', text: name })));

    if (header) {
      const searchInput = el('input', { type: 'search', name: 'q', placeholder: 'Search ' + storeName, value: UI.param('q') || '',
        'aria-label': 'Search', autocomplete: 'off', 'aria-controls': 'search-suggestions' });
      const quickLinks = categories.length ? el('div', { class: 'quick-links' }, el('h4', { text: 'Quick links' }),
        categories.slice(0, 8).map((c) => el('a', { href: catHref(c) }, el('span', { class: 'arrow', text: '\u2192' }), c.name))) : null;
      const suggestions = el('div', { class: 'suggestions', id: 'search-suggestions', 'aria-live': 'polite' });
      const searchPanel = el('div', { class: 'nav-flyout search-flyout', hidden: true },
        el('div', { class: 'flyout-inner' },
          el('form', { class: 'header-search', role: 'search', action: '/' + sf, method: 'get' }, icon('search'), searchInput),
          suggestions, quickLinks));
      // As-you-type suggestions (Amazon / Flipkart style): products with photo and price, matching categories.
      let suggestTimer = null;
      let suggestSeq = 0;
      searchInput.addEventListener('input', () => {
        clearTimeout(suggestTimer);
        const q = searchInput.value.trim();
        if (q.length < 2) { mount(suggestions); if (quickLinks) quickLinks.hidden = false; return; }
        suggestTimer = setTimeout(async () => {
          const seq = ++suggestSeq;
          let data;
          try { data = await Api.get('/api/' + sf + '/catalog/suggest?q=' + encodeURIComponent(q)); } catch (e) { return; }
          if (seq !== suggestSeq) return;
          if (quickLinks) quickLinks.hidden = true;
          const money = (m) => UI.money(m);
          mount(suggestions,
            data.products.length ? el('div', { class: 'suggest-group' }, el('h4', { text: 'Products' }),
              data.products.map((p) => el('a', { class: 'suggest-item', href: '/' + sf + '/p/' + encodeURIComponent(p.slug) },
                p.imageUrl ? el('img', { src: p.imageUrl, alt: '', loading: 'lazy' }) : el('span', { class: 'suggest-ph' }),
                el('span', { class: 'suggest-text' }, el('span', { class: 'suggest-title', text: p.title }),
                  el('span', { class: 'muted small', text: p.priceMinor != null ? money(p.priceMinor) + (p.merchantName ? ' \u00B7 ' + p.merchantName : '')
                    : p.merchantName ? 'Check price on ' + p.merchantName : '' }))))) : null,
            data.categories.length ? el('div', { class: 'suggest-group' }, el('h4', { text: 'Categories' }),
              data.categories.map((c) => el('a', { href: catHref(c) }, el('span', { class: 'arrow', text: '\u2192' }), c.name))) : null,
            el('a', { class: 'suggest-all', href: '/' + sf + '?q=' + encodeURIComponent(q) },
              data.products.length ? 'See all results for \u201C' + q + '\u201D' : 'No quick matches \u2014 search all deals for \u201C' + q + '\u201D'));
        }, 180);
      });
      const menuPanel = el('div', { class: 'nav-flyout menu-flyout', hidden: true },
        el('div', { class: 'flyout-inner' }, switcher(),
          el('nav', { class: 'menu-links', 'aria-label': 'Categories' },
            el('a', { href: '/' + sf, text: 'All deals' }),
            categories.map((c) => el('a', { href: catHref(c), text: c.name })),
            offers.length ? el('a', { href: '/' + sf + '/offers', text: 'Coupons & offers' }) : null,
            guides.length ? el('a', { href: '/' + sf + '/guides', text: 'Buying guides' }) : null,
            stories.length ? el('a', { href: '/' + sf + '/stories', text: 'Web Stories' }) : null,
            el('a', { href: '/' + sf + '/wishlist', text: 'Wishlist' })), installButton()));
      const searchBtn = el('button', { class: 'nav-icon search-toggle', type: 'button', 'aria-label': 'Search', 'aria-expanded': 'false' }, icon('search'));
      const menuBtn = el('button', { class: 'nav-icon menu-toggle', type: 'button', 'aria-label': 'Menu', 'aria-expanded': 'false' }, icon('menu'));
      const toggle = (panel, btn, otherPanel, otherBtn) => {
        const open = panel.hidden;
        otherPanel.hidden = true;
        otherBtn.setAttribute('aria-expanded', 'false');
        panel.hidden = !open;
        btn.setAttribute('aria-expanded', String(open));
        header.classList.toggle('flyout-open', open);
        if (open && panel === searchPanel) setTimeout(() => searchInput.focus(), 50);
      };
      searchBtn.addEventListener('click', () => toggle(searchPanel, searchBtn, menuPanel, menuBtn));
      menuBtn.addEventListener('click', () => toggle(menuPanel, menuBtn, searchPanel, searchBtn));
      document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && header.classList.contains('flyout-open')) {
          searchPanel.hidden = true;
          menuPanel.hidden = true;
          searchBtn.setAttribute('aria-expanded', 'false');
          menuBtn.setAttribute('aria-expanded', 'false');
          header.classList.remove('flyout-open');
        }
      });

      mount(header, el('div', { class: 'container nav-bar' },
        el('a', { class: 'brand', href: '/' + sf, 'aria-label': storeName + ' home' },
          el('img', { class: 'brand-mark live', src: MARK, alt: '' }),
          el('img', { class: 'brand-word', src: BRANDS[sf].wordmark, alt: storeName })),
        el('nav', { class: 'nav-menu', 'aria-label': 'Categories' },
          categories.slice(0, 5).map((c) => el('a', { href: catHref(c), text: c.name })),
          offers.length ? el('a', { href: '/' + sf + '/offers', text: 'Offers' }) : null,
          guides.length ? el('a', { href: '/' + sf + '/guides', text: 'Guides' }) : null,
          stories.length ? el('a', { href: '/' + sf + '/stories', text: 'Stories' }) : null),
        el('div', { class: 'nav-tools' }, switcher(), searchBtn,
          el('a', { class: 'nav-icon nav-wishlist', href: '/' + sf + '/wishlist', 'aria-label': 'Wishlist', title: 'Wishlist' }, icon('heart')),
          cartLink, accountSlot, menuBtn)),
      searchPanel, menuPanel);
      if (opts.search === false) searchBtn.hidden = true;
    }
    if (footer) {
      const pages = (window.HPP_STORE && window.HPP_STORE.pages) || [];
      const support = window.HPP_STORE && window.HPP_STORE.store.supportEmail;
      const column = (title, links) => links.length ? el('nav', { class: 'footer-col', 'aria-label': title },
        el('h4', { text: title }), links.map(([href, text]) => el('a', { href, text }))) : null;
      mount(footer, el('div', { class: 'container' },
        el('div', { class: 'footer-notes' },
          el('p', { text: affiliateOnly
            ? 'We may earn a commission when you buy through links on this site, at no extra cost to you. Prices and stock are set by our partner stores and can change at any time.'
            : 'Prices include GST where applicable. Delivery times are estimates.' })),
        el('div', { class: 'footer-directory' },
          column('Shop', [['/' + sf, 'All deals'], ...categories.slice(0, 6).map((c) => [catHref(c), c.name])]),
          column('Explore', [offers.length ? ['/' + sf + '/offers', 'Coupons & offers'] : null,
            guides.length ? ['/' + sf + '/guides', 'Buying guides'] : null,
            stories.length ? ['/' + sf + '/stories', 'Web Stories'] : null, ['/' + sf + '/wishlist', 'Wishlist'], ['/' + sf + '/compare', 'Compare'],
            ['/' + sf + '/cart', 'Cart'], ['/' + other, 'Visit ' + STOREFRONTS[other]]].filter(Boolean)),
          column('Help & policies', [...pages.map((p) => [p.url, p.title]), support ? ['mailto:' + support, support] : null].filter(Boolean))),
        el('div', { class: 'footer-legal' },
          // Discreet staff entrance: the footer logo opens this storefront's admin sign-in (no visible label).
          el('a', { class: 'footer-staff', href: '/' + sf + '/admin/login', rel: 'nofollow', 'aria-label': 'Staff sign-in' },
            el('img', { class: 'footer-mark', src: MARK, alt: '' })),
          el('span', { text: 'Copyright © ' + new Date().getFullYear() + ' ' + storeName + '. All rights reserved.' }),
          el('span', { class: 'spacer' }),
          installButton(),
          analyticsIds().ga4 || analyticsIds().pixel
            ? el('button', { class: 'link-button', type: 'button', text: 'Cookie settings', on: { click: () => consentBanner(true) } }) : null,
          el('span', { text: 'India' }))));
    }
    appSetup(sf);
    consentBanner(false);

    const whatsapp = window.HPP_STORE && window.HPP_STORE.store.whatsappNumber;
    if (whatsapp && !document.querySelector('.whatsapp-fab')) {
      document.body.appendChild(el('a', { class: 'whatsapp-fab', target: '_blank', rel: 'noopener', 'aria-label': 'Chat with us on WhatsApp',
        title: 'Chat with us on WhatsApp',
        href: 'https://wa.me/' + whatsapp + '?text=' + encodeURIComponent('Hi ' + storeName + ', I have a question about a deal.') }, icon('chat')));
    }

    const user = await me().catch(() => null);
    if (user) {
      mount(accountSlot, el('a', { class: 'nav-icon', href: '/account?sf=' + sf, 'aria-label': 'My account', title: 'My account' }, icon('user')));
      try {
        const cart = await Api.get('/api/' + sf + '/cart');
        if (cart.itemCount > 0) cartLink.appendChild(el('span', { class: 'cart-count', text: String(cart.itemCount) }));
      } catch (e) { /* ignore */ }
    } else {
      mount(accountSlot, el('a', { class: 'nav-signin', href: loginUrl(), text: 'Sign in' }));
    }
    return { sf, user, storeName, affiliateOnly, stories, categories, offers, guides };
  }

  /**
   * Fades sections in as they scroll into view (Apple-style). Content stays visible without JavaScript, with reduced
   * motion, and in browsers without IntersectionObserver.
   */
  function reveal(root) {
    const items = (root || document).querySelectorAll('.reveal:not(.reveal-armed)');
    const reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!items.length || reduce || !('IntersectionObserver' in window)) return;
    const io = new IntersectionObserver((entries) => entries.forEach((e) => {
      if (e.isIntersecting) { e.target.classList.add('revealed'); io.unobserve(e.target); }
    }), { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    items.forEach((item) => {
      const box = item.getBoundingClientRect();
      if (box.top < window.innerHeight) return;
      item.classList.add('reveal-armed');
      io.observe(item);
    });
  }

  function pageError(err) {
    const main = document.getElementById('main');
    if (main) mount(main, el('div', { class: 'container' }, UI.errorAlert(err)));
  }

  window.Site = { STOREFRONTS, BRANDS, MARK, SYMBOL, liveMark, currentStorefront, storefrontFromPath, me, requireCustomer, loginUrl, safeNext, renderChrome, reveal, pageError, track };
})();
