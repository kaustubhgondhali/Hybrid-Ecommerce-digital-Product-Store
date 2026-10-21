/* Storefront admin panel (one per storefront: /shop/admin and /digital/admin). Every action is authorised by
   the server; the navigation below only hides what the signed-in role cannot use. */
(function () {
  'use strict';
  const { el, mount, money } = UI;

  const SF = location.pathname.split('/').filter(Boolean)[0] === 'digital' ? 'digital' : 'shop';
  const SF_NAME = SF === 'digital' ? 'Elaatech' : 'Elaashop';
  const BRAND_LOGO = '/assets/img/' + (SF === 'digital' ? 'elaatech' : 'elaashop') + '-logo.png';
  const BRAND_WORDMARK = '/assets/img/' + (SF === 'digital' ? 'elaatech' : 'elaashop') + '-wordmark.png';
  const API = '/api/' + SF + '/admin';
  const ROLES = ['MASTER_ADMIN', 'CATALOG_MANAGER', 'ORDER_MANAGER', 'SUPPORT'];
  const GST_RATES = [0, 25, 300, 500, 1200, 1800, 2800];
  const METHODS = {
    DIRECT: { label: 'Own stock', long: 'Own stock (we ship from our inventory)', badge: 'blue' },
    DROPSHIP: { label: 'Dropship', long: 'Dropshipping (supplier ships to the customer)', badge: 'amber' },
    AFFILIATE: { label: 'Affiliate', long: 'Affiliate (customer buys on the partner store)', badge: 'purple' }
  };
  const methodBadge = (m) => el('span', { class: 'badge ' + (METHODS[m] ? METHODS[m].badge : ''), text: METHODS[m] ? METHODS[m].label : UI.label(m) });
  const onSiteMethod = (m) => m === 'DIRECT' || m === 'DROPSHIP';
  /** Selling methods an admin can pick; own stock and dropship need checkout on, dropship needs a physical product. */
  const methodOptions = (type) => (window.AdminKit && window.AdminKit.affiliateOnly ? ['AFFILIATE'] : ['DIRECT', 'DROPSHIP', 'AFFILIATE'])
    .filter((m) => m !== 'DROPSHIP' || type !== 'DIGITAL').map((m) => [m, METHODS[m].long]);
  const ORDER_STATUSES = ['PENDING_PAYMENT', 'CONFIRMED', 'PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'NEEDS_ATTENTION', 'CANCELLED', 'EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED'];
  let me = null;

  document.addEventListener('DOMContentLoaded', () => {
    if (document.body.dataset.page === 'admin-login') loginPage();
    else app();
  });

  const can = (perm) => me && me.permissions.includes(perm);
  const view = () => document.getElementById('view');

  function handleAuthError(e) {
    if (e && (e.code === 'UNAUTHENTICATED' || e.code === 'SESSION_REVOKED')) {
      location.href = '/' + SF + '/admin/login';
      return true;
    }
    if (e && e.code === 'PASSWORD_CHANGE_REQUIRED') {
      location.reload();
      return true;
    }
    return false;
  }

  // ================================================================== login

  async function loginPage() {
    document.title = SF_NAME + ' admin \u2013 sign in';
    // The sign-in page always asks for the email and password: a session still open in this browser is ended first,
    // so the hidden footer entrance (and this page) never opens the panel without signing in.
    await Api.post(API + '/auth/logout').catch(() => null);
    const msg = el('div');
    const totp = el('input', { name: 'totpCode', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: '8' });
    const totpField = UI.field('Authenticator code', totp, 'Enter the 6-digit code from your authenticator app.');
    totpField.hidden = true;
    const button = el('button', { class: 'btn block', type: 'submit', text: 'Sign in' });
    const form = el('form', { novalidate: true }, msg,
      UI.field('Email', el('input', { name: 'email', type: 'email', autocomplete: 'username', required: true })),
      UI.field('Password', el('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true })),
      totpField, button);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(button, async () => {
        mount(msg);
        const v = UI.formValues(form);
        try {
          await Api.post(API + '/auth/login', { email: v.email, password: v.password, totpCode: v.totpCode || null });
          location.href = '/' + SF + '/admin';
        } catch (err) {
          if (err.code === 'MFA_REQUIRED') {
            totpField.hidden = false;
            totp.focus();
            mount(msg, el('div', { class: 'alert info', text: 'Enter your authenticator code to continue.' }));
          } else {
            mount(msg, UI.errorAlert(err));
          }
        }
      });
    });
    mount(document.getElementById('main'), el('div', { class: 'login-wrap' },
      el('img', { class: 'login-logo', src: BRAND_LOGO, alt: SF_NAME }),
      el('div', { class: 'card narrow' },
        el('h1', { text: 'Admin sign in' }),
        el('p', { class: 'muted', text: 'Sign in with your ' + SF_NAME + ' staff account. Accounts for the other storefront do not work here.' }),
        form)));
  }

  // ================================================================== shell

  /** Menu entries: route → [label, permission]. Pages added by other admin scripts register themselves here. */
  const NAV_ITEMS = {
    dashboard: ['Dashboard', 'DASHBOARD_READ'],
    'affiliate-products': ['Affiliate products', 'CATALOG_READ'],
    'dropship-products': ['Dropship products', 'CATALOG_READ'],
    'own-products': [SF === 'digital' ? 'My own products' : 'Own-stock products', 'CATALOG_READ'],
    'dropship-orders': ['Dropship orders', 'ORDERS_READ'],
    'own-orders': [SF === 'digital' ? 'My product orders' : 'Own-stock orders', 'ORDERS_READ'],
    products: ['All products', 'CATALOG_READ'],
    categories: ['Categories', 'CATALOG_READ'],
    inventory: ['Inventory', 'CATALOG_READ'],
    orders: ['All orders', 'ORDERS_READ'],
    entitlements: ['Digital access', 'ORDERS_READ'],
    affiliate: ['Links & earnings', 'AFFILIATE_READ'],
    settings: ['Settings', 'SETTINGS_WRITE'],
    notifications: ['Email log', 'SETTINGS_WRITE'],
    staff: ['Staff', 'STAFF_MANAGE'],
    audit: ['Audit log', 'AUDIT_READ'],
    account: ['My account', null]
  };

  /** The menu, grouped by business model: Elaashop sells affiliate, dropship and own-stock products; Elaatech
      sells affiliate products and its own digital products. */
  const NAV_GROUPS = SF === 'digital' ? [
    { title: null, items: ['dashboard', 'demand', 'crm'] },
    { title: '1 · Affiliate products', items: ['affiliate-products', 'import', 'affiliate', 'offers', 'research'] },
    { title: '2 · My own products', items: ['own-products', 'own-orders', 'entitlements', 'coupons'] },
    { title: 'Catalog & marketing', items: ['products', 'categories', 'inventory', 'reviews', 'seo', 'studio', 'guides', 'stories', 'pages'] },
    { title: 'Store & team', items: ['settings', 'notifications', 'staff', 'audit', 'system', 'security', 'account', 'help'] }
  ] : [
    { title: null, items: ['dashboard', 'demand', 'crm'] },
    { title: '1 · Affiliate products', items: ['affiliate-products', 'import', 'affiliate', 'offers', 'research'] },
    { title: '2 · Dropshipping products', items: ['dropship-products', 'dropship-orders'] },
    { title: '3 · E-commerce products', items: ['own-products', 'inventory', 'own-orders', 'entitlements', 'coupons'] },
    { title: 'Catalog & marketing', items: ['products', 'categories', 'reviews', 'seo', 'studio', 'guides', 'stories', 'pages'] },
    { title: 'Store & team', items: ['settings', 'notifications', 'staff', 'audit', 'system', 'security', 'account', 'help'] }
  ];

  /** Product and order pages for one selling channel. */
  const CHANNELS = {
    'affiliate-products': { method: 'AFFILIATE', title: 'Affiliate products', what: 'Partner offers: shoppers click "Buy" and pay on the partner store (Amazon, Flipkart, Udemy…). You earn a commission.' },
    'dropship-products': { method: 'DROPSHIP', title: 'Dropshipping products', what: 'Shoppers pay on this site and your supplier ships the order. Your own stock is not used.' },
    'own-products': SF === 'digital'
      ? { method: 'DIRECT', title: 'My own products', what: 'Products you made and upload yourself (courses, e-books, templates, software, licence keys). Shoppers pay on this site and get them instantly in their library.' }
      : { method: 'DIRECT', title: 'Own-stock products (e-commerce)', what: 'Products you keep in stock and ship yourself. Shoppers pay on this site.' }
  };
  const ORDER_CHANNELS = {
    'dropship-orders': { method: 'DROPSHIP', title: 'Dropship orders', what: 'Orders with at least one dropship item. Send these to your supplier.' },
    'own-orders': { method: 'DIRECT', title: SF === 'digital' ? 'My product orders' : 'Own-stock orders', what: SF === 'digital'
      ? 'Orders for your own digital products. Delivery to the customer’s library is automatic.'
      : 'Orders with at least one own-stock item. Pack and ship these yourself.' }
  };

  async function app() {
    // Coming back with the browser's Back button after signing out must not show the old panel from memory.
    window.addEventListener('pageshow', (e) => { if (e.persisted) location.reload(); });
    try {
      me = await Api.get(API + '/auth/me');
    } catch (e) {
      location.href = '/' + SF + '/admin/login';
      return;
    }
    try {
      const store = await Api.get(API + '/settings/store');
      window.AdminKit.affiliateOnly = !!store.affiliateOnly;
    } catch (e) {
      window.AdminKit.affiliateOnly = true;
    }
    document.body.classList.add('admin');
    if (me.mustChangePassword) {
      forcedPasswordChange();
      return;
    }
    const logout = el('button', { type: 'button', text: 'Sign out' });
    logout.addEventListener('click', async () => {
      await Api.post(API + '/auth/logout').catch(() => null);
      location.href = '/' + SF + '/admin/login';
    });
    const placed = new Set(NAV_GROUPS.flatMap((g) => g.items));
    const groups = NAV_GROUPS.map((g, i) => ({ title: g.title,
      items: i === NAV_GROUPS.length - 1 ? [...g.items, ...Object.keys(NAV_ITEMS).filter((r) => !placed.has(r))] : g.items }));
    const nav = el('nav', null, groups.map((g) => {
      const links = g.items.filter((r) => NAV_ITEMS[r] && ROUTES[r] && (!NAV_ITEMS[r][1] || can(NAV_ITEMS[r][1])))
        .map((route) => el('a', { href: '#/' + route, dataset: { route }, text: NAV_ITEMS[route][0] }));
      if (!links.length) return null;
      return el('div', { class: 'nav-group' }, g.title ? el('div', { class: 'nav-group-title', text: g.title }) : null, links);
    }));
    mount(document.getElementById('main'),
      el('aside', { class: 'admin-sidebar no-print' },
        el('a', { class: 'brand', href: '#/dashboard', 'aria-label': SF_NAME + ' admin dashboard' },
          el('img', { class: 'brand-mark', src: '/assets/img/elaa-mark.png', alt: '' }),
          el('img', { class: 'brand-word', src: BRAND_WORDMARK, alt: SF_NAME })),
        el('div', { class: 'sf-label', text: 'Admin \u00B7 /' + SF }),
        nav,
        el('div', { class: 'who' }, el('div', { text: me.fullName || me.email }), el('div', { class: 'muted', text: UI.label(me.role) }),
          el('div', { class: 'mt' }, el('a', { href: '/' + SF, target: '_blank', rel: 'noopener', text: 'View store' }), ' \u00B7 ', logout))),
      el('div', { class: 'admin-main', id: 'view' }));
    window.addEventListener('hashchange', route);
    route();
    document.dispatchEvent(new CustomEvent('admin:ready'));
  }

  /** Shows a small count next to a menu item (e.g. reviews waiting for approval); 0 hides it. */
  function navBadge(routeName, count) {
    const link = document.querySelector('.admin-sidebar nav a[data-route="' + routeName + '"]');
    if (!link) return;
    let badge = link.querySelector('.nav-count');
    if (!count) { if (badge) badge.remove(); return; }
    if (!badge) { badge = el('span', { class: 'nav-count' }); link.append(badge); }
    badge.textContent = count > 99 ? '99+' : String(count);
    badge.title = count + ' waiting';
  }

  function forcedPasswordChange() {
    mount(document.getElementById('main'), el('div', { class: 'login-wrap' },
      el('img', { class: 'login-logo', src: BRAND_LOGO, alt: SF_NAME }),
      el('div', { class: 'card narrow' },
      el('h1', { text: 'Choose a new password' }),
      el('p', { class: 'muted', text: 'For security, you must replace your temporary password before continuing.' }),
      passwordForm(() => location.reload()))));
  }

  function passwordForm(onDone) {
    const msg = el('div');
    const button = el('button', { class: 'btn', type: 'submit', text: 'Change password' });
    const form = el('form', null, msg,
      UI.field('Current password', el('input', { name: 'currentPassword', type: 'password', autocomplete: 'current-password', required: true })),
      UI.field('New password', el('input', { name: 'newPassword', type: 'password', autocomplete: 'new-password', required: true }),
        'At least 8 characters, including a letter and a number.'),
      button);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(button, async () => {
        try {
          await Api.post(API + '/auth/change-password', UI.formValues(form));
          form.reset();
          mount(msg, el('div', { class: 'alert success', text: 'Password changed.' }));
          if (onDone) onDone();
        } catch (err) { mount(msg, UI.errorAlert(err)); }
      });
    });
    return form;
  }

  const ROUTES = {
    dashboard: dashboardView,
    products: productsRoute,
    categories: categoriesView,
    inventory: inventoryView,
    orders: ordersRoute,
    entitlements: entitlementsView,
    affiliate: affiliateView,
    settings: settingsView,
    notifications: notificationsView,
    staff: staffView,
    audit: auditView,
    account: accountView
  };
  Object.keys(CHANNELS).forEach((base) => { ROUTES[base] = (parts) => productsRoute(parts, base); });
  Object.keys(ORDER_CHANNELS).forEach((base) => { ROUTES[base] = (parts) => ordersRoute(parts, base); });
  if (SF === 'digital') ['dropship-products', 'dropship-orders'].forEach((r) => { delete ROUTES[r]; });

  async function route() {
    const parts = (location.hash.replace(/^#\/?/, '') || 'dashboard').split('/');
    const name = ROUTES[parts[0]] ? parts[0] : 'dashboard';
    document.querySelectorAll('.admin-sidebar nav a').forEach((a) => a.classList.toggle('active', a.dataset.route === name));
    mount(view(), el('p', { class: 'muted', text: 'Loading\u2026' }));
    try {
      await ROUTES[name](parts.slice(1));
    } catch (e) {
      if (!handleAuthError(e)) mount(view(), UI.errorAlert(e));
    }
    window.scrollTo(0, 0);
  }

  // ------------------------------------------------------------------ helpers

  function head(title, ...actions) {
    return el('div', { class: 'page-head' }, el('h1', { text: title }), el('span', { class: 'spacer' }), actions);
  }

  function table(headers, rows, emptyText) {
    if (!rows.length) return el('div', { class: 'empty', text: emptyText || 'Nothing here yet.' });
    return el('div', { class: 'table-wrap' }, el('table', null,
      el('thead', null, el('tr', null, headers.map((h) => typeof h === 'string' ? el('th', { text: h }) : h))),
      el('tbody', null, rows)));
  }

  function pager(result, onPage) {
    if (result.totalPages <= 1) return null;
    const prev = el('button', { class: 'btn secondary small', type: 'button', text: 'Previous', disabled: result.page === 0 });
    const next = el('button', { class: 'btn secondary small', type: 'button', text: 'Next', disabled: result.page + 1 >= result.totalPages });
    prev.addEventListener('click', () => onPage(result.page - 1));
    next.addEventListener('click', () => onPage(result.page + 1));
    return el('div', { class: 'pager' }, prev, el('span', { class: 'muted small', text: 'Page ' + (result.page + 1) + ' of ' + result.totalPages + ' \u00B7 ' + result.totalItems + ' total' }), next);
  }

  /** Wires a form to an async submit handler with busy state and inline error/success output. */
  function onSubmit(form, handler, successText) {
    const msg = form.querySelector('.form-msg') || form.insertBefore(el('div', { class: 'form-msg' }), form.firstChild);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const button = form.querySelector('button[type="submit"]');
      UI.busy(button, async () => {
        mount(msg);
        try {
          const result = await handler(UI.formValues(form));
          if (successText) mount(msg, el('div', { class: 'alert success', text: successText }));
          return result;
        } catch (err) {
          if (!handleAuthError(err)) mount(msg, UI.errorAlert(err));
        }
      });
    });
    return form;
  }

  function input(name, attrs) {
    return el('input', Object.assign({ name }, attrs || {}));
  }

  function selectEl(name, options, current, attrs) {
    return el('select', Object.assign({ name }, attrs || {}), options.map((o) => {
      const [value, text] = Array.isArray(o) ? o : [o, UI.label(o)];
      return el('option', { value, text, selected: String(current == null ? '' : current) === String(value) });
    }));
  }

  function minorOrNull(v) {
    return v === '' || v === undefined ? null : UI.toMinor(v);
  }

  function intOrNull(v) {
    if (v === '' || v === undefined || v === null) return null;
    const n = parseInt(v, 10);
    return Number.isFinite(n) ? n : null;
  }

  function stat(label, value, cls) {
    return el('div', { class: 'stat ' + (cls || '') }, el('div', { class: 'value', text: String(value) }), el('div', { class: 'label', text: label }));
  }

  // ================================================================== dashboard

  async function dashboardView() {
    const d = await Api.get(API + '/dashboard');
    mount(view(), head('Dashboard'),
      el('div', { class: 'stats' },
        stat('Revenue (30 days)', money(d.revenue30dMinor, d.currency)),
        stat('Paid orders (30 days)', d.paidOrders30d),
        stat('Orders today', d.ordersToday),
        stat('Awaiting shipment', d.awaitingShipment, d.awaitingShipment ? 'warn' : ''),
        stat('Awaiting payment', d.pendingPayment),
        stat('Needs attention', d.needsAttention, d.needsAttention ? 'bad' : ''),
        stat('Published products', d.publishedProducts),
        stat('Low-stock items', d.lowStockItems, d.lowStockItems ? 'warn' : ''),
        stat('Affiliate clicks (7 days)', d.affiliateClicks7d)),
      el('div', { class: 'card' }, el('h2', { text: 'Recent orders' }), ordersTable(d.recentOrders)));
  }

  function ordersTable(orders, base) {
    const b = base || 'orders';
    return table(['Order', 'Customer', 'Placed', 'Status', el('th', { class: 'num', text: 'Total' })], orders.map((o) => {
      const tr = el('tr', { class: 'clickable' },
        el('td', null, el('a', { href: '#/' + b + '/' + o.id, text: o.orderNumber })),
        el('td', null, el('div', { text: o.customerName }), el('div', { class: 'muted small', text: o.customerEmail })),
        el('td', { text: UI.date(o.placedAt) }),
        el('td', null, UI.badge(o.status), o.paymentMethod === 'COD' ? [' ', el('span', { class: 'badge purple', text: 'COD' })] : null),
        el('td', { class: 'num', text: money(o.totalMinor, o.currency) }));
      tr.addEventListener('click', () => { location.hash = '#/' + b + '/' + o.id; });
      return tr;
    }), 'No orders yet.');
  }

  // ================================================================== products

  const productFilters = {};

  /** {@code base} is the menu route: "products" (all channels) or one channel's page, e.g. "affiliate-products". */
  function productsRoute(parts, base) {
    const b = base || 'products';
    if (parts[0] === 'new') return productNewView(b);
    if (parts[0]) return productEditView(parts[0], b);
    return productListView(b);
  }

  /** Explains why own-stock and dropship products cannot be added while the store is affiliate-only. */
  function checkoutOffNotice(method) {
    if (method === 'AFFILIATE' || !(window.AdminKit && window.AdminKit.affiliateOnly)) return null;
    return el('div', { class: 'alert warning' }, 'Selling on this site is turned off, so these products cannot be bought yet. ',
      can('SETTINGS_WRITE') ? el('a', { href: '#/settings', text: 'Turn on Settings → Selling channels → Sell on this site' }) : 'Ask a master admin to turn on Settings → Selling channels.',
      ' to add and sell them.');
  }

  async function productListView(base) {
    const b = base || 'products';
    const channel = CHANNELS[b];
    const f = productFilters[b] || (productFilters[b] = { q: '', status: '', type: '', method: '', page: 0 });
    const qs = new URLSearchParams({ page: String(f.page), size: '25' });
    ['q', 'status', 'type', 'method'].forEach((k) => { if (f[k]) qs.set(k, f[k]); });
    if (channel) qs.set('method', channel.method);
    const result = await Api.get(API + '/products?' + qs.toString());
    const filters = el('form', { class: 'filters' },
      input('q', { type: 'search', placeholder: 'Search title or SKU', value: f.q }),
      selectEl('status', [['', 'Any status'], 'DRAFT', 'PUBLISHED', 'ARCHIVED'], f.status),
      SF === 'shop' && !(channel && channel.method === 'DROPSHIP') ? selectEl('type', [['', 'Any type'], 'PHYSICAL', 'DIGITAL'], f.type) : null,
      channel ? null : selectEl('method', [['', 'Any channel'], ...Object.keys(METHODS).map((m) => [m, METHODS[m].label])], f.method),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Filter' }));
    filters.addEventListener('submit', (e) => {
      e.preventDefault();
      Object.assign(f, UI.formValues(filters), { page: 0 });
      productListView(b).catch((err) => mount(view(), UI.errorAlert(err)));
    });
    const rows = result.items.map((p) => {
      const tr = el('tr', { class: 'clickable' },
        el('td', null, p.imageUrl ? el('img', { class: 'thumb-sm', src: p.imageUrl, alt: '', loading: 'lazy', decoding: 'async' }) : el('div', { class: 'thumb-sm' })),
        el('td', null, el('a', { href: '#/' + b + '/' + p.id, text: p.title }), el('div', { class: 'muted small', text: p.slug })),
        el('td', null, el('span', { class: 'badge', text: UI.label(p.productType) }), ' ', methodBadge(p.sellingMethod)),
        el('td', null, UI.badge(p.status)),
        el('td', { class: 'num', text: p.priceMinor != null ? money(p.priceMinor) : '\u2014' }),
        el('td', { class: 'muted small', text: UI.date(p.updatedAt) }));
      tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/' + b + '/' + p.id; });
      return tr;
    });
    const notice = channel ? checkoutOffNotice(channel.method) : null;
    const canAdd = can('CATALOG_WRITE') && !notice;
    mount(view(), head(channel ? channel.title : 'All products',
      canAdd ? el('a', { class: 'btn', href: '#/' + b + '/new', text: channel ? 'New ' + METHODS[channel.method].label.toLowerCase() + ' product' : 'New product' }) : null),
      channel ? el('p', { class: 'muted', text: channel.what }) : el('p', { class: 'muted', text: 'Every product of every channel. Use the menu sections to work with one channel at a time.' }),
      notice,
      filters,
      el('div', { class: 'card' }, table(['', 'Product', 'Type', 'Status', el('th', { class: 'num', text: 'Price' }), 'Updated'], rows, 'No products here yet.'),
        pager(result, (page) => { f.page = page; productListView(b); })));
  }

  async function productNewView(base) {
    const b = base || 'products';
    const channel = CHANNELS[b];
    const notice = channel ? checkoutOffNotice(channel.method) : null;
    if (notice) {
      mount(view(), el('p', { class: 'small' }, el('a', { href: '#/' + b, text: '\u2190 ' + channel.title })), head('New product'), notice);
      return;
    }
    const categories = await Api.get(API + '/categories');
    const preset = channel ? channel.method : null;
    const type = selectEl('productType', [['PHYSICAL', 'Physical product'], ['DIGITAL', 'Digital product']],
      SF === 'digital' ? 'DIGITAL' : 'PHYSICAL', { disabled: preset === 'DROPSHIP' });
    const affiliateOnly = !!(window.AdminKit && window.AdminKit.affiliateOnly);
    const method = selectEl('sellingMethod', methodOptions(type.value), preset || (affiliateOnly ? 'AFFILIATE' : 'DIRECT'), { disabled: !!preset });
    const priceRow = el('div', { class: 'row' },
      UI.field('Price (\u20B9)', input('price', { inputmode: 'decimal', placeholder: '499.00' })),
      UI.field('Compare-at price (\u20B9, optional)', input('compareAt', { inputmode: 'decimal' })));
    const physicalRow = el('div', { class: 'row' },
      UI.field('SKU', input('sku', { maxlength: '80', placeholder: 'MUG-BLUE-01' })),
      UI.field('Weight (grams)', input('weightGrams', { type: 'number', min: '1' })));
    const digitalRow = el('div', { class: 'row' },
      UI.field('Delivery', selectEl('deliveryKind', [['DOWNLOAD', 'File download'], ['LICENSE_KEY', 'License key'], ['DOWNLOAD_AND_LICENSE', 'Download + license key']], 'DOWNLOAD')),
      UI.field('Format (optional)', input('digitalFormat', { maxlength: '60', placeholder: 'PDF, ZIP, Windows installer\u2026' })));
    const sync = () => {
      const current = method.value;
      mount(method, methodOptions(type.value).map(([value, text]) => el('option', { value, text, selected: value === current })));
      const direct = onSiteMethod(method.value);
      priceRow.hidden = !direct;
      physicalRow.hidden = !(direct && type.value === 'PHYSICAL');
      digitalRow.hidden = !(type.value === 'DIGITAL' && direct);
    };
    type.addEventListener('change', sync);
    method.addEventListener('change', sync);
    sync();
    const form = el('form', { class: 'card medium' },
      el('div', { class: 'row' }, UI.field('Product type', type), UI.field('Selling method', method)),
      UI.field('Title', input('title', { required: true, maxlength: '200' })),
      UI.field('Short summary (optional)', input('summary', { maxlength: '500' })),
      el('div', { class: 'row' },
        UI.field('Category', selectEl('categoryId', [['', 'None'], ...categories.map((c) => [String(c.id), c.name])], '')),
        UI.field('Brand (optional)', input('brand', { maxlength: '120' }))),
      priceRow, physicalRow, digitalRow,
      el('p', { class: 'muted small', text: affiliateOnly
        ? 'Created as a draft affiliate offer. Add images and the partner link next, then publish. Shoppers will leave this site to buy.'
        : 'The product is created as a draft. Add images, stock, files or the affiliate link next, then publish. Shoppers see every product the same way, whatever the channel.' }),
      el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: 'Create draft' }), el('a', { href: '#/' + b, text: 'Cancel' })));
    onSubmit(form, async (v) => {
      const productType = type.value;
      const sellingMethod = method.value;
      const direct = onSiteMethod(sellingMethod);
      const body = {
        productType, sellingMethod, title: v.title, summary: v.summary || null,
        brand: v.brand || null, categoryId: v.categoryId ? Number(v.categoryId) : null,
        priceMinor: direct ? minorOrNull(v.price) : null, compareAtMinor: direct ? minorOrNull(v.compareAt) : null,
        sku: direct && productType === 'PHYSICAL' ? (v.sku || null) : null,
        weightGrams: direct && productType === 'PHYSICAL' ? intOrNull(v.weightGrams) : null,
        deliveryKind: productType === 'DIGITAL' ? v.deliveryKind : null,
        digitalFormat: productType === 'DIGITAL' ? (v.digitalFormat || null) : null
      };
      const p = await Api.post(API + '/products', body);
      location.hash = '#/' + b + '/' + p.id;
    });
    mount(view(), el('p', { class: 'small' }, el('a', { href: '#/' + b, text: '\u2190 ' + (channel ? channel.title : 'All products') })),
      head(channel ? 'New ' + METHODS[channel.method].label.toLowerCase() + ' product' : 'New product'),
      channel ? el('p', { class: 'muted', text: channel.what }) : null, form);
  }

  async function productEditView(id, base) {
    const b = base || 'products';
    const [p, categories] = await Promise.all([Api.get(API + '/products/' + id), Api.get(API + '/categories')]);
    const writable = can('CATALOG_WRITE');
    const refresh = () => productEditView(id, b).catch((e) => mount(view(), UI.errorAlert(e)));

    // status actions
    const statusMsg = el('div');
    const statusBtn = (label, status, cls) => {
      const b = el('button', { class: 'btn ' + (cls || ''), type: 'button', text: label });
      b.addEventListener('click', () => UI.busy(b, async () => {
        try {
          await Api.post(API + '/products/' + id + '/status', { status });
          UI.toast('Product ' + UI.label(status).toLowerCase(), 'success');
          refresh();
        } catch (e) { if (!handleAuthError(e)) mount(statusMsg, UI.errorAlert(e)); }
      }));
      return b;
    };
    const actions = writable ? [
      p.status !== 'PUBLISHED' ? statusBtn('Publish', 'PUBLISHED') : statusBtn('Unpublish', 'DRAFT', 'secondary'),
      p.status !== 'ARCHIVED' ? statusBtn('Archive', 'ARCHIVED', 'secondary') : null
    ] : [];

    const header = el('div', null,
      el('p', { class: 'small' }, el('a', { href: '#/' + b, text: '\u2190 ' + (CHANNELS[b] ? CHANNELS[b].title : 'All products') })),
      head(p.title, p.status === 'PUBLISHED' ? el('a', { class: 'btn ghost', href: '/' + SF + '/p/' + encodeURIComponent(p.slug), target: '_blank', rel: 'noopener', text: 'View in store' }) : null, actions),
      el('div', { class: 'actions mb' }, UI.badge(p.status), el('span', { class: 'badge', text: UI.label(p.productType) }),
        methodBadge(p.sellingMethod),
        p.typeLocked ? el('span', { class: 'muted small', text: 'Type and selling method are locked (product has orders or an affiliate link).' }) : null),
      statusMsg,
      p.publishProblems && p.publishProblems.length && p.status !== 'PUBLISHED'
        ? el('div', { class: 'alert warning' }, 'Before publishing:', el('ul', null, p.publishProblems.map((x) => el('li', { text: x }))))
        : null);

    // details
    const details = el('form', { class: 'card' },
      el('h2', { text: 'Details' }),
      el('div', { class: 'row' },
        UI.field('Product type', selectEl('productType', ['PHYSICAL', 'DIGITAL'], p.productType, { disabled: p.typeLocked || !writable })),
        UI.field('Selling method', selectEl('sellingMethod',
          methodOptions(p.productType).concat(methodOptions(p.productType).some(([m]) => m === p.sellingMethod) ? [] : [[p.sellingMethod, METHODS[p.sellingMethod].long]]),
          p.sellingMethod, { disabled: p.typeLocked || !writable }))),
      UI.field('Title', input('title', { value: p.title, required: true, maxlength: '200' })),
      UI.field('URL slug', input('slug', { value: p.slug, maxlength: '160' }), '/' + SF + '/p/' + p.slug),
      UI.field('Short summary', input('summary', { value: p.summary || '', maxlength: '500' })),
      UI.field('Description', el('textarea', { name: 'description', maxlength: '20000', text: p.description || '' })),
      el('div', { class: 'row' },
        UI.field('Category', selectEl('categoryId', [['0', 'None'], ...categories.map((c) => [String(c.id), c.name])], p.categoryId ? String(p.categoryId) : '0')),
        UI.field('Brand', input('brand', { value: p.brand || '', maxlength: '120' }))),
      p.productType === 'DIGITAL' ? el('div', { class: 'row' },
        UI.field('Delivery', selectEl('deliveryKind', [['DOWNLOAD', 'File download'], ['LICENSE_KEY', 'License key'], ['DOWNLOAD_AND_LICENSE', 'Download + license key']], p.deliveryKind || 'DOWNLOAD')),
        UI.field('Format', input('digitalFormat', { value: p.digitalFormat || '', maxlength: '60' }))) : null,
      onSiteMethod(p.sellingMethod) ? el('div', { class: 'row' },
        UI.field('HSN / SAC code', input('hsnSac', { value: p.hsnSac || '', maxlength: '8', inputmode: 'numeric', placeholder: 'Store default' }),
          'Printed on GST invoices. Leave empty to use the store default.'),
        UI.field('GST rate', selectEl('gstRateBps', [['', 'Not set (0%)'], ...GST_RATES.map((r) => [String(r), r / 100 + '%'])],
          p.gstRateBps == null ? '' : String(p.gstRateBps)))) : null,
      el('p', { class: 'muted small' }, 'Search titles, descriptions and keywords are edited in the ',
        el('a', { href: '#/seo/products/' + p.id, text: 'SEO tool' }), '.'),
      writable ? el('button', { class: 'btn', type: 'submit', text: 'Save details' }) : null);
    onSubmit(details, async (v) => {
      await Api.put(API + '/products/' + id, {
        productType: v.productType || null, sellingMethod: v.sellingMethod || null, title: v.title, slug: v.slug,
        summary: v.summary, description: v.description, brand: v.brand, categoryId: Number(v.categoryId || 0),
        deliveryKind: v.deliveryKind || null, digitalFormat: v.digitalFormat === undefined ? null : v.digitalFormat,
        hsnSac: v.hsnSac === undefined ? null : v.hsnSac,
        gstRateBps: v.gstRateBps === undefined ? null : (v.gstRateBps === '' ? -1 : intOrNull(v.gstRateBps)),
        version: p.version
      });
      UI.toast('Saved', 'success');
      refresh();
    });

    const sections = [header, details, dealContentSection(p, writable, refresh)];
    if (onSiteMethod(p.sellingMethod)) sections.push(variantsSection(p, writable, refresh));
    sections.push(imagesSection(p, writable, refresh), videosSection(p, writable, refresh));
    if (p.sellingMethod === 'AFFILIATE') sections.push(affiliateSection(p, refresh));
    if (p.productType === 'DIGITAL' && p.sellingMethod === 'DIRECT') sections.push(await deliverySection(p, refresh));
    if (p.status === 'PUBLISHED' && writable) sections.push(await dealOfDaySection(p));
    mount(view(), sections);
  }

  /** Promote a published product as the home page's Deal of the Day, with a real end time for the countdown. */
  async function dealOfDaySection(p) {
    const store = await Api.get(API + '/settings/store').catch(() => ({}));
    const isCurrent = store.dealOfDayProductId === p.id && store.dealOfDayEndsAt && new Date(store.dealOfDayEndsAt) > new Date();
    const pad = (n) => String(n).padStart(2, '0');
    // tonight at 23:59, or tomorrow night when less than two hours of today are left
    const d = new Date(Date.now() + (new Date().getHours() >= 22 ? 24 * 3600 * 1000 : 0));
    const defaultEnd = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + 'T23:59';
    const ends = input('endsAt', { type: 'datetime-local', required: true, value: defaultEnd });
    const form = el('form', { class: 'inline-form' }, UI.field('Ends', ends),
      el('button', { class: 'btn', type: 'submit', text: isCurrent ? 'Update end time' : 'Make Deal of the Day' }));
    onSubmit(form, async () => {
      await Api.put(API + '/deal-of-the-day', { productId: p.id, endsAt: new Date(ends.value).toISOString() });
      UI.toast('This product is now the Deal of the Day', 'success');
      productsRoute([String(p.id)]);
    });
    const stop = el('button', { class: 'btn ghost', type: 'button', text: 'Stop the deal' });
    stop.addEventListener('click', () => UI.busy(stop, async () => {
      try { await Api.put(API + '/deal-of-the-day', { productId: null }); UI.toast('Deal of the Day removed', 'success'); productsRoute([String(p.id)]); }
      catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
    }));
    return el('div', { class: 'card' }, el('h2', { text: 'Deal of the Day' }),
      el('p', { class: 'muted small', text: isCurrent
        ? 'This product is the Deal of the Day until ' + UI.date(store.dealOfDayEndsAt) + '. The home page shows it with a countdown.'
        : 'Show this product at the top of the home page with a countdown until the time you choose. Only one product at a time; use a real end time.' }),
      form, isCurrent ? stop : null);
  }

  /** Highlights, pros/cons, verdict and rating: the content that convinces shoppers (and ranks on Google). */
  function dealContentSection(p, writable, refresh) {
    const area = (name, value, placeholder) => {
      const t = el('textarea', { name, rows: '4', maxlength: '2000', placeholder, disabled: !writable });
      t.value = value || '';
      return t;
    };
    const ratings = [['0', 'Not rated'], ...[50, 45, 40, 35, 30, 25, 20, 15, 10].map((r) => [String(r), (r / 10).toFixed(1) + ' stars'])];
    const specsArea = () => {
      const t = el('textarea', { name: 'specs', rows: '6', maxlength: '8000', disabled: !writable,
        placeholder: SF === 'digital' ? 'Platform: Web, Windows, Mac\nFree trial: 14 days\nPricing: From ₹499 a month\nLanguage: English, Hindi\nCertificate: Yes'
          : 'Battery: Up to 30 hours\nConnectivity: Bluetooth 5.3\nWater resistance: IPX5\nWarranty: 1 year\nIn the box: Earbuds, case, USB-C cable' });
      t.value = p.specs || '';
      return t;
    };
    const form = el('form', { class: 'card' },
      el('h2', { text: 'Deal content' }),
      el('p', { class: 'muted small', text: 'Shown on the product page next to the Buy button. One item per line, up to 8 lines each.' }),
      UI.field('Highlights', area('highlights', p.highlights, 'Up to 30 hours of battery\nActive noise cancellation\n1-year warranty')),
      el('div', { class: 'row' },
        UI.field('Pros (what we like)', area('pros', p.pros, 'Great value for money\nComfortable for long use')),
        UI.field('Cons (keep in mind)', area('cons', p.cons, 'No wireless charging'))),
      UI.field('Our verdict', area('verdict', p.verdict, 'Who should buy it, and why it is a good deal right now.'), 'One or two sentences.'),
      UI.field('Specifications', specsArea(), 'One per line as “Name: Value”. Shown as a table on the product page and lined up on the compare page.'),
      UI.field('Our rating', selectEl('expertRating', ratings, p.expertRating ? String(p.expertRating) : '0', { disabled: !writable })),
      writable ? el('button', { class: 'btn', type: 'submit', text: 'Save deal content' }) : null);
    onSubmit(form, async (v) => {
      await Api.put(API + '/products/' + p.id, {
        highlights: v.highlights, pros: v.pros, cons: v.cons, verdict: v.verdict, expertRating: intOrNull(v.expertRating) || 0,
        specs: v.specs
      });
      UI.toast('Deal content saved', 'success');
      refresh();
    });
    return form;
  }

  function variantsSection(p, writable, refresh) {
    const physical = p.productType === 'PHYSICAL';
    const ownStock = physical && p.sellingMethod === 'DIRECT';
    const rows = p.variants.map((v) => {
      const name = input('name', { value: v.name, maxlength: '120' });
      const sku = input('sku', { value: v.sku || '', maxlength: '80' });
      const price = input('price', { value: UI.fromMinor(v.priceMinor), class: 'num-input', inputmode: 'decimal' });
      const compare = input('compareAt', { value: UI.fromMinor(v.compareAtMinor), class: 'num-input', inputmode: 'decimal' });
      const weight = input('weightGrams', { value: v.weightGrams == null ? '' : String(v.weightGrams), type: 'number', class: 'num-input' });
      const active = input('active', { type: 'checkbox', checked: v.active });
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try {
          await Api.put(API + '/products/' + p.id + '/variants/' + v.id, {
            name: name.value.trim(), sku: physical ? (sku.value.trim() || null) : null, priceMinor: minorOrNull(price.value.trim()),
            compareAtMinor: compare.value.trim() === '' ? 0 : UI.toMinor(compare.value.trim()),
            weightGrams: physical ? intOrNull(weight.value) : null, active: active.checked
          });
          UI.toast('Variant saved', 'success');
          refresh();
        } catch (e) { if (!handleAuthError(e)) UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', null, name), physical ? el('td', null, sku) : null, el('td', null, price), el('td', null, compare),
        physical ? el('td', null, weight) : null, el('td', null, active),
        ownStock ? el('td', { class: 'num', text: v.available == null ? '\u2014' : String(v.available) }) : null,
        el('td', null, writable ? save : null));
    });
    const headers = ['Name', physical ? 'SKU' : null, 'Price (\u20B9)', 'Compare-at (\u20B9)', physical ? 'Weight (g)' : null, 'Active',
      ownStock ? el('th', { class: 'num', text: 'In stock' }) : null, ''].filter((h) => h !== null);

    const add = el('form', { class: 'inline-form mt' },
      UI.field('New option name', input('name', { required: true, maxlength: '120', placeholder: 'Large / Blue' })),
      physical ? UI.field('SKU', input('sku', { maxlength: '80' })) : null,
      UI.field('Price (\u20B9)', input('price', { inputmode: 'decimal' })),
      physical ? UI.field('Weight (g)', input('weightGrams', { type: 'number', min: '1' })) : null,
      el('button', { class: 'btn secondary', type: 'submit', text: 'Add option' }));
    onSubmit(add, async (v) => {
      await Api.post(API + '/products/' + p.id + '/variants', {
        name: v.name, sku: physical ? (v.sku || null) : null, priceMinor: minorOrNull(v.price),
        weightGrams: physical ? intOrNull(v.weightGrams) : null, active: true
      });
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Pricing & options' }),
      ownStock ? el('p', { class: 'muted small' }, 'Stock levels are managed on the ', el('a', { href: '#/inventory', text: 'Inventory' }), ' page.') : null,
      physical && !ownStock ? el('p', { class: 'muted small', text: 'Dropship products are shipped by your supplier, so they do not use your inventory.' }) : null,
      table(headers, rows), writable ? add : null);
  }

  function imagesSection(p, writable, refresh) {
    const list = el('div', { class: 'image-list' }, p.images.map((img, i) => {
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Remove' });
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Remove this image?')) return;
        try { await Api.del(API + '/products/' + p.id + '/images/' + img.id); refresh(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      let main = null;
      if (i === 0) {
        main = el('span', { class: 'badge green', title: 'Shown on product cards, in search results and when the page is shared', text: 'Main image' });
      } else if (writable) {
        main = el('button', { class: 'btn ghost small', type: 'button', text: 'Make main' });
        main.addEventListener('click', () => UI.busy(main, async () => {
          try { await Api.post(API + '/products/' + p.id + '/images/' + img.id + '/main'); UI.toast('Main image changed', 'success'); refresh(); }
          catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
      }
      const alt = input('alt', { value: img.alt || '', maxlength: '250', placeholder: 'Describe the image', 'aria-label': 'Alt text', disabled: !writable });
      alt.addEventListener('change', async () => {
        try {
          await Api.patch(API + '/products/' + p.id + '/images/' + img.id, { alt: alt.value.trim() });
          UI.toast('Alt text saved', 'success');
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      });
      return el('figure', null, el('img', { src: img.thumbUrl || img.url, alt: img.alt || '', loading: 'lazy', decoding: 'async' }),
        el('figcaption', null, main, alt, writable ? del : null));
    }));
    const files = input('file', { type: 'file', accept: 'image/jpeg,image/png,image/webp,image/gif', multiple: true, required: true });
    const status = el('span', { class: 'muted small' });
    const form = el('form', { class: 'inline-form' },
      UI.field('Images (JPEG, PNG, WebP or GIF, max 5 MB each; you can choose several)', files),
      UI.field('Alt text (optional, used for all chosen images)', input('alt', { maxlength: '200', class: 'wide' })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Upload' }), status);
    onSubmit(form, async () => {
      const chosen = [...files.files];
      if (!chosen.length) throw new Error('Choose at least one image');
      const room = 10 - p.images.length;
      if (chosen.length > room) throw new Error('A product can have 10 images. You can add ' + room + ' more.');
      const alt = form.elements.alt.value.trim();
      for (let i = 0; i < chosen.length; i++) {
        status.textContent = 'Uploading ' + (i + 1) + ' of ' + chosen.length + '…';
        const fd = new FormData();
        fd.append('file', chosen[i]);
        if (alt) fd.append('alt', alt);
        try {
          await Api.upload(API + '/products/' + p.id + '/images', fd);
        } catch (e) {
          UI.toast((i ? i + ' of ' + chosen.length + ' uploaded. ' : '') + chosen[i].name + ': ' + UI.errorMessage(e), 'error');
          if (i) refresh();
          return;
        }
      }
      UI.toast(chosen.length === 1 ? 'Image uploaded' : chosen.length + ' images uploaded', 'success');
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Images' }),
      p.images.length ? list : el('p', { class: 'muted', text: 'No images yet. At least one image is required to publish.' }),
      writable ? form : null);
  }

  const MAX_VIDEO_MB = 100;
  const duration = (s) => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');

  /**
   * Plays the chosen file in a hidden video element to read its length and size and to capture a still frame for the
   * poster. Resolves null when this browser cannot play it (then most shoppers' browsers cannot either).
   */
  function inspectVideo(file) {
    return new Promise((resolve) => {
      const src = URL.createObjectURL(file);
      const v = document.createElement('video');
      let done = false;
      const finish = (result) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        v.removeAttribute('src');
        v.load();
        URL.revokeObjectURL(src);
        resolve(result);
      };
      const timer = setTimeout(() => finish(null), 20000);
      v.muted = true;
      v.playsInline = true;
      v.preload = 'auto';
      v.addEventListener('error', () => finish(null));
      v.addEventListener('loadedmetadata', () => {
        if (!v.videoWidth || !v.videoHeight) { finish(null); return; }
        v.currentTime = Math.min(1, (v.duration || 0) / 3);
      });
      v.addEventListener('seeked', () => {
        const scale = Math.min(1, 1280 / Math.max(v.videoWidth, v.videoHeight));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(v.videoWidth * scale);
        canvas.height = Math.round(v.videoHeight * scale);
        canvas.getContext('2d').drawImage(v, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((poster) => finish({
          poster, duration: Math.max(1, Math.round(v.duration || 0)), width: v.videoWidth, height: v.videoHeight
        }), 'image/jpeg', 0.85);
      }, { once: true });
      v.src = src;
    });
  }

  /** Up to 3 short videos shown in the product gallery after the main image. */
  function videosSection(p, writable, refresh) {
    const videos = p.videos || [];
    const list = videos.map((v) => {
      const title = input('title', { value: v.title || '', maxlength: '200', placeholder: 'Video title (shown in Google results)', 'aria-label': 'Video title', disabled: !writable });
      title.addEventListener('change', async () => {
        try {
          await Api.patch(API + '/products/' + p.id + '/videos/' + v.id, { title: title.value.trim() });
          UI.toast('Video title saved', 'success');
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      });
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Remove' });
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Remove this video?')) return;
        try { await Api.del(API + '/products/' + p.id + '/videos/' + v.id); refresh(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      const facts = [v.durationSeconds ? duration(v.durationSeconds) : null, v.width && v.height ? v.width + '×' + v.height : null,
        (v.sizeBytes / 1024 / 1024).toFixed(1) + ' MB'].filter(Boolean).join(' · ');
      return el('figure', { class: 'video-item' },
        el('video', { src: v.url, poster: v.posterUrl, controls: true, preload: 'none', playsinline: true }),
        el('figcaption', null, el('div', { class: 'muted small', text: facts }), title, writable ? del : null));
    });

    const file = input('file', { type: 'file', accept: 'video/mp4,video/webm,video/quicktime', required: true });
    const titleInput = input('title', { maxlength: '200', class: 'wide', placeholder: 'e.g. Unboxing and sound test' });
    const preview = el('div', { class: 'video-check' });
    const bar = el('progress', { class: 'upload-progress', max: '100', value: '0', hidden: true });
    let checked = null;
    let posterUrl = null;
    file.addEventListener('change', async () => {
      checked = null;
      if (posterUrl) URL.revokeObjectURL(posterUrl);
      posterUrl = null;
      mount(preview);
      const f = file.files[0];
      if (!f) return;
      if (f.size > MAX_VIDEO_MB * 1024 * 1024) {
        mount(preview, el('div', { class: 'alert error', text: 'This video is ' + (f.size / 1024 / 1024).toFixed(0) + ' MB. Videos must be ' + MAX_VIDEO_MB + ' MB or smaller — trim it or export at 1080p.' }));
        return;
      }
      mount(preview, el('p', { class: 'muted small', text: 'Checking the video…' }));
      const result = await inspectVideo(f);
      if (file.files[0] !== f) return;
      checked = result;
      if (!result) {
        mount(preview, el('div', { class: 'alert warning', text: 'This browser cannot play this video, so many shoppers would not be able to either. '
          + 'Export it as MP4 (H.264). On iPhone: Settings → Camera → Formats → Most Compatible.' }));
        return;
      }
      posterUrl = URL.createObjectURL(result.poster);
      mount(preview, el('img', { src: posterUrl, alt: 'Frame shown before the video plays' }),
        el('div', { class: 'muted small' },
          el('div', { text: 'Length ' + duration(result.duration) + ' · ' + result.width + '×' + result.height + ' · ' + (f.size / 1024 / 1024).toFixed(1) + ' MB' }),
          el('div', { text: 'This frame is shown before the video plays.' }),
          result.duration > 90 ? el('div', { class: 'warn-text', text: 'Tip: clips under a minute keep shoppers watching.' }) : null));
    });
    const form = el('form', { class: 'inline-form video-form' },
      UI.field('Video (MP4 or WebM, max ' + MAX_VIDEO_MB + ' MB)', file),
      UI.field('Title (optional)', titleInput),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Upload video' }),
      preview, bar);
    onSubmit(form, async () => {
      const f = file.files[0];
      if (!f) throw new Error('Choose a video file');
      if (!checked && !confirm('This video may not play in shoppers’ browsers. Upload it anyway?')) return;
      const fd = new FormData();
      fd.append('file', f);
      if (titleInput.value.trim()) fd.append('title', titleInput.value.trim());
      if (checked) {
        fd.append('poster', checked.poster, 'poster.jpg');
        fd.append('durationSeconds', String(checked.duration));
        fd.append('width', String(checked.width));
        fd.append('height', String(checked.height));
      }
      bar.hidden = false;
      bar.value = 0;
      try {
        await Api.uploadWithProgress(API + '/products/' + p.id + '/videos', fd, (r) => { bar.value = Math.round(r * 100); });
      } finally {
        bar.hidden = true;
      }
      UI.toast('Video uploaded', 'success');
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Videos' }),
      el('p', { class: 'muted small', text: 'Short clips of 15–60 seconds work best: an unboxing, a demo, or the product in use. '
        + 'They appear in the product gallery after the main image, and Google can show them in video results.' }),
      list.length ? el('div', { class: 'video-list' }, list) : el('p', { class: 'muted', text: 'No videos yet.' }),
      !writable ? null : videos.length < 3 ? form : el('p', { class: 'muted small', text: 'A product can have 3 videos. Remove one to add another.' }));
  }

  /**
   * Partner stores for an affiliate product: one link per store, so shoppers can compare prices. The cheapest store
   * with a shown price becomes the main Buy button; paused stores are hidden from shoppers but keep their clicks.
   */
  function affiliateSection(p, refresh) {
    const links = p.affiliateLinks || [];
    const writable = can('AFFILIATE_WRITE');
    const base = API + '/products/' + p.id + '/affiliate-links';
    let editing = null;

    const url = input('url', { required: true, maxlength: '2048', placeholder: 'https://www.partner.com/product?tag=yourid' });
    const merchantName = input('merchantName', { maxlength: '120', placeholder: 'e.g. Amazon, Flipkart' });
    const price = input('displayPrice', { inputmode: 'decimal', placeholder: '1299.00' });
    const listPrice = input('listPrice', { inputmode: 'decimal', placeholder: '1999.00' });
    const disclosure = el('textarea', { name: 'disclosure', maxlength: '500', rows: '2' });
    const formTitle = el('h3', { text: links.length ? 'Add another store' : 'Add the partner store' });
    const submit = el('button', { class: 'btn', type: 'submit', text: 'Save store' });
    const cancel = el('button', { class: 'btn ghost', type: 'button', text: 'Cancel', hidden: true });
    const form = el('form', { class: 'mt' }, formTitle,
      UI.field('Affiliate URL', url, 'Paste the exact link from your affiliate program (Amazon, Flipkart, Cuelinks, EarnKaro, impact.com, any network). It is stored and redirected unchanged.'),
      el('div', { class: 'row' },
        UI.field('Store name (optional)', merchantName),
        UI.field('Price (\u20B9, optional)', price),
        UI.field('Original price / MRP (\u20B9, optional)', listPrice, 'Shown struck through with the % off.')),
      UI.field('Disclosure', disclosure, 'Shown under the Buy button. Leave empty for the standard disclosure.'),
      writable ? el('div', { class: 'actions' }, submit, cancel) : null);
    const startEdit = (l) => {
      editing = l;
      url.value = l.destinationUrl;
      merchantName.value = l.merchantName;
      price.value = UI.fromMinor(l.displayPriceMinor);
      listPrice.value = UI.fromMinor(l.listPriceMinor);
      disclosure.value = l.disclosure || '';
      formTitle.textContent = 'Edit ' + l.merchantName;
      submit.textContent = 'Update store';
      cancel.hidden = false;
      form.scrollIntoView({ behavior: 'smooth', block: 'center' });
      url.focus();
    };
    cancel.addEventListener('click', () => refresh());
    onSubmit(form, async (v) => {
      const body = {
        url: v.url, merchantName: v.merchantName || null, displayPriceMinor: minorOrNull(v.displayPrice),
        listPriceMinor: minorOrNull(v.listPrice), disclosure: v.disclosure || null, confirmNewDomain: false
      };
      const save = () => editing ? Api.put(base + '/' + editing.id, body) : Api.post(base, body);
      try {
        await save();
      } catch (e) {
        if (e.code !== 'AFFILIATE_DOMAIN_UNCONFIRMED') throw e;
        if (!confirm(e.message + '\n\nAllow redirects to ' + (e.data.domain || 'this domain') + '?')) return;
        body.confirmNewDomain = true;
        await save();
      }
      UI.toast(editing ? 'Store updated' : 'Store added', 'success');
      refresh();
    });

    const action = (label, cls, fn) => {
      const b = el('button', { class: 'btn small ' + (cls || ''), type: 'button', text: label });
      b.addEventListener('click', () => UI.busy(b, async () => {
        try { await fn(); refresh(); } catch (e) { if (!handleAuthError(e)) UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return b;
    };
    const rows = links.map((l) => {
      const discount = l.displayPriceMinor && l.listPriceMinor && l.listPriceMinor > l.displayPriceMinor
        ? Math.floor((l.listPriceMinor - l.displayPriceMinor) * 100 / l.listPriceMinor) : 0;
      return el('tr', null,
        el('td', null, el('strong', { text: l.merchantName }), el('div', { class: 'muted small', text: l.merchantDomain }),
          el('div', { class: 'small' }, el('a', { href: l.goUrl, target: '_blank', rel: 'noopener', text: location.origin + l.goUrl }))),
        el('td', { class: 'num' }, l.displayPriceMinor != null ? money(l.displayPriceMinor) : '\u2014',
          l.listPriceMinor ? el('div', { class: 'muted small' }, el('s', { text: money(l.listPriceMinor) }), discount ? ' \u00B7 ' + discount + '% off' : '') : null),
        el('td', { class: 'small' }, l.merchantHidesPrices ? el('span', { class: 'badge', title: 'Shoppers see "Check price" (store rule)', text: 'Price hidden' })
          : l.priceStale ? el('span', { class: 'badge amber', text: 'Needs re-check' })
            : l.priceCheckedAt ? UI.date(l.priceCheckedAt, false) : '\u2014'),
        el('td', { class: 'num', text: String(l.clicks30d) }),
        el('td', null, UI.badge(l.active ? 'ACTIVE' : 'DISABLED', l.active ? 'Live' : 'Paused')),
        el('td', null, writable ? el('div', { class: 'actions' },
          el('button', { class: 'btn small secondary', type: 'button', text: 'Edit', on: { click: () => startEdit(l) } }),
          l.displayPriceMinor != null && !l.merchantHidesPrices
            ? action('Price still correct', 'secondary', () => Api.post(base + '/' + l.id + '/confirm-price')) : null,
          action(l.active ? 'Pause' : 'Resume', 'ghost', () => Api.patch(base + '/' + l.id, { active: !l.active }))) : null));
    });
    const stale = links.filter((l) => l.active && l.priceStale && !l.merchantHidesPrices);
    return el('div', { class: 'card' }, el('h2', { text: 'Partner stores' }),
      el('p', { class: 'muted small', text: 'Add every store that sells this product. Shoppers see a price comparison, and the cheapest store with a shown price becomes the main Buy button.' }),
      stale.length ? el('div', { class: 'alert warning', text: stale.length === 1
        ? 'One price is older than the allowed age, so it is hidden from shoppers. Check the partner page, then update the price or click "Price still correct".'
        : stale.length + ' prices are older than the allowed age and hidden from shoppers. Check them, then update or confirm.' }) : null,
      links.length ? table(['Store', el('th', { class: 'num', text: 'Price' }), 'Checked', el('th', { class: 'num', text: 'Clicks (30d)' }), 'Status', ''], rows) : null,
      form);
  }

  async function deliverySection(p, refresh) {
    const d = await Api.get(API + '/products/' + p.id + '/delivery');
    const writable = can('DIGITAL_WRITE');
    const policy = el('form', { class: 'inline-form mb' },
      UI.field('Download limit per purchase', input('downloadLimit', { type: 'number', min: '0', value: d.downloadLimit == null ? '' : String(d.downloadLimit), placeholder: 'Unlimited' })),
      UI.field('Access period (days)', input('accessDays', { type: 'number', min: '0', value: d.accessDays == null ? '' : String(d.accessDays), placeholder: 'Forever' })),
      writable ? el('button', { class: 'btn secondary', type: 'submit', text: 'Save policy' }) : null);
    onSubmit(policy, async (v) => {
      await Api.put(API + '/products/' + p.id + '/delivery', { downloadLimit: intOrNull(v.downloadLimit), accessDays: intOrNull(v.accessDays) });
    }, 'Delivery policy saved.');

    const assets = table(['File', 'Version', 'Size', 'Status', 'Uploaded', ''], d.assets.map((a) => {
      const retire = el('button', { class: 'btn ghost small', type: 'button', text: 'Retire' });
      retire.addEventListener('click', () => UI.busy(retire, async () => {
        if (!confirm('Retire this file? Customers will no longer be able to download it.')) return;
        try { await Api.del(API + '/assets/' + a.id); refresh(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', { text: a.fileName }), el('td', { text: a.versionLabel || '' }),
        el('td', { text: (a.sizeBytes / 1024 / 1024).toFixed(2) + ' MB' }), el('td', null, UI.badge(a.status)),
        el('td', { class: 'small', text: UI.date(a.createdAt) }), el('td', null, writable && a.status === 'READY' ? retire : null));
    }), 'No files uploaded.');

    const upload = el('form', { class: 'inline-form mt' },
      UI.field('Upload file', input('file', { type: 'file', required: true })),
      UI.field('Version label (optional)', input('versionLabel', { maxlength: '40' })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Upload' }));
    onSubmit(upload, async () => {
      const fd = new FormData(upload);
      if (!fd.get('file') || !fd.get('file').size) throw new Error('Choose a file');
      await Api.upload(API + '/products/' + p.id + '/assets', fd);
      refresh();
    });

    const keys = el('form', { class: 'mt' },
      UI.field('Import license keys (one per line)', el('textarea', { name: 'keys', placeholder: 'XXXX-XXXX-XXXX-XXXX' })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Import keys' }));
    onSubmit(keys, async (v) => {
      const r = await Api.post(API + '/products/' + p.id + '/license-keys', { keys: v.keys });
      UI.toast('Imported ' + r.imported + ', duplicates ' + r.duplicates + ', rejected ' + r.rejected, 'success');
      refresh();
    });

    return el('div', { class: 'card' }, el('h2', { text: 'Digital delivery' }),
      el('p', { class: 'muted', text: 'Files are stored privately and only served to customers who bought the product, through short-lived download links. Available license keys: ' + d.availableLicenseKeys + '.' }),
      policy, el('h3', { text: 'Files' }), assets, writable ? upload : null, writable ? keys : null);
  }

  // ================================================================== categories

  async function categoriesView() {
    const cats = await Api.get(API + '/categories');
    const writable = can('CATALOG_WRITE');
    const rows = cats.map((c) => {
      const name = input('name', { value: c.name, maxlength: '120' });
      const slug = input('slug', { value: c.slug, maxlength: '140' });
      const order = input('sortOrder', { type: 'number', value: String(c.sortOrder), class: 'num-input' });
      const active = input('active', { type: 'checkbox', checked: c.active });
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Delete' });
      const more = el('button', { class: 'btn ghost small', type: 'button', text: 'Page & SEO' });
      const description = el('textarea', { name: 'description', rows: '3', maxlength: '5000', disabled: !writable });
      description.value = c.description || '';
      const metaTitle = input('metaTitle', { value: c.metaTitle || '', maxlength: '200', class: 'wide', disabled: !writable });
      const metaDescription = el('textarea', { name: 'metaDescription', rows: '2', maxlength: '320', disabled: !writable });
      metaDescription.value = c.metaDescription || '';
      const extra = el('tr', { class: 'sub-row', hidden: true }, el('td', { colspan: '5' },
        el('div', { class: 'row' },
          UI.field('Intro text shown on the category page', description, 'Supports # headings, - bullets and [links](https://\u2026).'),
          el('div', null,
            UI.field('Search title', metaTitle, 'Leave empty to use "' + c.name + ' | store name".'),
            UI.field('Search description', metaDescription, 'About 150 characters.')))));
      more.addEventListener('click', () => { extra.hidden = !extra.hidden; });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try {
          await Api.put(API + '/categories/' + c.id, { name: name.value.trim(), slug: slug.value.trim(), sortOrder: intOrNull(order.value),
            active: active.checked, description: description.value.trim(), metaTitle: metaTitle.value.trim(), metaDescription: metaDescription.value.trim() });
          UI.toast('Category saved', 'success');
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Delete category "' + c.name + '"?')) return;
        try { await Api.del(API + '/categories/' + c.id); categoriesView(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return [el('tr', null, el('td', null, name), el('td', null, slug), el('td', null, order), el('td', null, active),
        el('td', null, el('div', { class: 'actions' }, more, writable ? [save, del] : null))), extra];
    });
    const add = el('form', { class: 'inline-form' },
      UI.field('Name', input('name', { required: true, maxlength: '120' })),
      UI.field('Slug (optional)', input('slug', { maxlength: '140' })),
      el('button', { class: 'btn', type: 'submit', text: 'Add category' }));
    onSubmit(add, async (v) => {
      await Api.post(API + '/categories', { name: v.name, slug: v.slug || null, active: true });
      categoriesView();
    });
    mount(view(), head('Categories'), writable ? el('div', { class: 'card' }, add) : null,
      el('div', { class: 'card' }, table(['Name', 'Slug', 'Order', 'Active', ''], rows, 'No categories yet.')));
  }

  // ================================================================== inventory

  async function inventoryView() {
    const items = await Api.get(API + '/inventory');
    const writable = can('INVENTORY_WRITE');
    const tbody = [];
    items.forEach((s) => {
      const adjustRow = el('tr', { class: 'editing', hidden: true });
      const tr = el('tr', null,
        el('td', null, el('a', { href: '#/products/' + s.productId, text: s.productTitle }), s.variantName !== 'Default' ? el('div', { class: 'muted small', text: s.variantName }) : null),
        el('td', { text: s.sku || '' }),
        el('td', { class: 'num', text: String(s.onHand) }),
        el('td', { class: 'num', text: String(s.reserved) }),
        el('td', { class: 'num' }, el('strong', { text: String(s.available) })),
        el('td', { class: 'num', text: String(s.lowStockThreshold) }),
        el('td', null, s.low ? el('span', { class: 'badge amber', text: 'Low' }) : null),
        el('td', null, writable ? toggleButton('Adjust', adjustRow, () => adjustForm(s)) : null, ' ', toggleButton('History', adjustRow, () => historyPanel(s))));
      tbody.push(tr, adjustRow);
    });
    mount(view(), head('Inventory'),
      el('p', { class: 'muted', text: 'Available = on hand \u2212 reserved. Stock is reserved when a customer places an order and released if payment does not arrive in time.' }),
      el('div', { class: 'card' }, table(['Product', 'SKU', el('th', { class: 'num', text: 'On hand' }), el('th', { class: 'num', text: 'Reserved' }),
        el('th', { class: 'num', text: 'Available' }), el('th', { class: 'num', text: 'Low at' }), '', ''], tbody, 'No physical products yet.')));
  }

  function toggleButton(label, row, render) {
    const b = el('button', { class: 'btn ghost small', type: 'button', text: label });
    b.addEventListener('click', async () => {
      if (!row.hidden && row.dataset.panel === label) { row.hidden = true; return; }
      row.dataset.panel = label;
      mount(row, el('td', { colspan: '8' }, await render()));
      row.hidden = false;
    });
    return b;
  }

  function adjustForm(s) {
    const form = el('form', { class: 'inline-form' },
      UI.field('Change (+/\u2212 units)', input('delta', { type: 'number', required: true, placeholder: '+10 or -2' })),
      UI.field('Reason', selectEl('reason', [['RECEIPT', 'Stock received'], ['ADJUSTMENT', 'Adjustment'], ['DAMAGE', 'Damaged / lost'], ['CORRECTION', 'Count correction']], 'RECEIPT')),
      UI.field('Note', input('note', { maxlength: '300', class: 'wide' })),
      UI.field('Low-stock alert at', input('lowStockThreshold', { type: 'number', min: '0', value: String(s.lowStockThreshold) })),
      el('button', { class: 'btn', type: 'submit', text: 'Apply' }));
    onSubmit(form, async (v) => {
      await Api.post(API + '/inventory/' + s.variantId + '/adjust', {
        delta: intOrNull(v.delta) || 0, reason: v.reason, note: v.note || null, lowStockThreshold: intOrNull(v.lowStockThreshold)
      });
      UI.toast('Stock updated', 'success');
      inventoryView();
    });
    return form;
  }

  async function historyPanel(s) {
    const moves = await Api.get(API + '/inventory/' + s.variantId + '/movements');
    return table(['When', 'Change', 'Reason', 'Order', 'Note'], moves.map((m) => el('tr', null,
      el('td', { class: 'small', text: UI.date(m.createdAt) }), el('td', { text: (m.delta > 0 ? '+' : '') + m.delta }),
      el('td', { text: UI.label(m.reason) }), el('td', { text: m.orderId ? '#' + m.orderId : '' }), el('td', { text: m.note || '' }))), 'No stock movements yet.');
  }

  // ================================================================== orders

  const orderFiltersByBase = {};

  function ordersRoute(parts, base) {
    const b = base || 'orders';
    if (parts[0] && parts[1] === 'invoice') return orderInvoiceView(parts[0], b);
    if (parts[0]) return orderDetailView(parts[0], b);
    return orderListView(b);
  }

  async function orderListView(base) {
    const b = base || 'orders';
    const channel = ORDER_CHANNELS[b];
    const f = orderFiltersByBase[b] || (orderFiltersByBase[b] = { status: '', q: '', page: 0 });
    const qs = new URLSearchParams({ page: String(f.page), size: '25' });
    if (f.status) qs.set('status', f.status);
    if (f.q) qs.set('q', f.q);
    if (channel) qs.set('method', channel.method);
    const result = await Api.get(API + '/orders?' + qs.toString());
    const filters = el('form', { class: 'filters' },
      input('q', { type: 'search', placeholder: 'Order number, email or name', value: f.q }),
      selectEl('status', [['', 'Any status'], ...ORDER_STATUSES], f.status),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Filter' }));
    filters.addEventListener('submit', (e) => {
      e.preventDefault();
      Object.assign(f, UI.formValues(filters), { page: 0 });
      orderListView(b);
    });
    mount(view(), head(channel ? channel.title : 'All orders'),
      channel ? el('p', { class: 'muted', text: channel.what }) : null, filters,
      el('div', { class: 'card' }, ordersTable(result.items, b), pager(result, (page) => { f.page = page; orderListView(b); })));
  }

  async function orderDetailView(id, base) {
    const b = base || 'orders';
    const d = await Api.get(API + '/orders/' + id);
    const o = d.order;
    const a = o.shippingAddress;
    const refresh = () => orderDetailView(id, b).catch((e) => mount(view(), UI.errorAlert(e)));
    const writable = can('ORDERS_WRITE');

    const lines = table(['Item', 'SKU', 'Type', 'Status', el('th', { class: 'num', text: 'Qty' }), el('th', { class: 'num', text: 'Unit' }), el('th', { class: 'num', text: 'Total' })],
      o.lines.map((l) => el('tr', null,
        el('td', null, el('a', { href: '#/products/' + l.productId, text: l.title }), l.variantName !== 'Default' ? el('div', { class: 'muted small', text: l.variantName }) : null),
        el('td', { text: l.sku || '' }), el('td', { text: UI.label(l.productType) }), el('td', null, UI.badge(l.fulfillmentStatus)),
        el('td', { class: 'num', text: String(l.quantity) }), el('td', { class: 'num', text: money(l.unitPriceMinor, o.currency) }),
        el('td', { class: 'num', text: money(l.lineTotalMinor, o.currency) }))));

    const summary = el('div', { class: 'card' }, el('h3', { text: 'Totals' }),
      kvRow('Subtotal', money(o.subtotalMinor, o.currency)),
      o.discountMinor ? kvRow('Discount' + (o.couponCode ? ' (' + o.couponCode + ')' : ''), '\u2212' + money(o.discountMinor, o.currency)) : null,
      o.requiresShipping ? kvRow('Shipping' + (o.shippingMethodName ? ' (' + o.shippingMethodName + ')' : ''), money(o.shippingMinor, o.currency)) : null,
      o.codFeeMinor ? kvRow('COD fee', money(o.codFeeMinor, o.currency)) : null,
      o.cgstMinor || o.sgstMinor ? [kvRow('CGST', money(o.cgstMinor, o.currency)), kvRow('SGST', money(o.sgstMinor, o.currency))]
        : o.igstMinor ? kvRow('IGST', money(o.igstMinor, o.currency)) : kvRow('Tax', money(o.taxMinor, o.currency)),
      el('div', { class: 'summary-row total' }, el('span', { text: 'Total' }), el('span', { text: money(o.totalMinor, o.currency) })),
      el('p', { class: 'muted small', text: (o.pricesIncludeTax ? 'Prices include GST.' : 'GST added on top of prices.')
        + (o.buyerStateCode ? ' Place of supply: ' + o.buyerStateCode + '.' : '') + (o.buyerGstin ? ' Buyer GSTIN: ' + o.buyerGstin + '.' : '') }),
      o.paymentMethod === 'COD' ? el('div', { class: 'alert ' + (o.paidAt ? 'success' : 'warning') + ' small',
        text: o.paidAt ? 'Cash on delivery \u2013 cash collected.' : 'Cash on delivery \u2013 collect ' + money(o.totalMinor, o.currency) + ' at delivery. Marking the order delivered records the cash as received.' }) : null,
      d.payment ? el('div', { class: 'mt small' },
        el('div', null, 'Payment: ', UI.badge(d.payment.status), ' via ', d.payment.provider === 'demo' ? 'Demo provider' : d.payment.provider === 'cod' ? 'Cash on delivery' : d.payment.provider),
        d.payment.providerPaymentId ? el('div', { class: 'muted', text: 'Ref: ' + d.payment.providerPaymentId }) : null,
        d.payment.refundedMinor ? el('div', { text: 'Refunded: ' + money(d.payment.refundedMinor, o.currency) }) : null,
        d.payment.failureReason ? el('div', { class: 'muted', text: 'Last failure: ' + d.payment.failureReason }) : null) : null,
      o.invoiceNumber ? el('a', { class: 'btn secondary block mt', href: '#/' + b + '/' + id + '/invoice', text: 'Invoice ' + o.invoiceNumber }) : null);

    const customer = el('div', { class: 'card' }, el('h3', { text: 'Customer' }),
      el('div', { text: d.customerName }), el('div', null, el('a', { href: 'mailto:' + d.customerEmail, text: d.customerEmail })),
      a ? el('div', { class: 'mt' }, el('strong', { text: 'Ship to' }), el('div', { text: a.name }), el('div', { text: a.line1 }),
        a.line2 ? el('div', { text: a.line2 }) : null, el('div', { text: a.city + ', ' + a.state + ' ' + a.postalCode + ', ' + a.country }),
        el('div', { class: 'muted', text: a.phone })) : null);

    const shipments = o.shipments && o.shipments.length ? el('div', { class: 'card' }, el('h3', { text: 'Shipments' }),
      o.shipments.map((s) => el('p', null, UI.badge(s.status), ' ', s.carrier, s.trackingNumber ? ' \u00B7 ' + s.trackingNumber : '',
        s.trackingUrl ? el('span', null, ' \u00B7 ', el('a', { href: s.trackingUrl, target: '_blank', rel: 'noopener noreferrer', text: 'tracking' })) : null,
        el('span', { class: 'muted small', text: ' \u00B7 ' + UI.date(s.shippedAt) + (s.deliveredAt ? ' \u2192 delivered ' + UI.date(s.deliveredAt) : '') })))) : null;

    const actionCards = [];
    if (writable && d.actions.canShip) {
      const ship = el('form', null,
        el('div', { class: 'row' }, UI.field('Carrier', input('carrier', { required: true, maxlength: '80', placeholder: 'Delhivery, Blue Dart, India Post\u2026' })),
          UI.field('Tracking number', input('trackingNumber', { maxlength: '120' }))),
        UI.field('Tracking URL (optional)', input('trackingUrl', { maxlength: '500', placeholder: 'https://' })),
        el('button', { class: 'btn', type: 'submit', text: 'Mark as shipped' }));
      onSubmit(ship, async (v) => {
        await Api.post(API + '/orders/' + id + '/shipments', { carrier: v.carrier, trackingNumber: v.trackingNumber || null, trackingUrl: v.trackingUrl || null });
        UI.toast('Order marked as shipped; the customer is notified by email', 'success');
        refresh();
      });
      actionCards.push(el('div', { class: 'card' }, el('h3', { text: 'Ship order' }), ship));
    }
    if (writable && d.actions.canMarkDelivered) {
      const cod = o.paymentMethod === 'COD' && !o.paidAt;
      const b = el('button', { class: 'btn', type: 'button', text: cod ? 'Mark delivered & cash collected' : 'Mark as delivered' });
      b.addEventListener('click', () => UI.busy(b, async () => {
        if (cod && !confirm('Confirm the courier collected ' + money(o.totalMinor, o.currency) + ' in cash? An invoice will be issued.')) return;
        try { await Api.post(API + '/orders/' + id + '/delivered'); refresh(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      actionCards.push(el('div', { class: 'card' }, el('h3', { text: 'Delivery' }), b));
    }
    if (can('REFUNDS') && d.actions.canRefund) {
      const refund = el('form', null,
        el('div', { class: 'row' },
          UI.field('Amount (\u20B9)', input('amount', { value: UI.fromMinor(d.actions.refundableMinor), inputmode: 'decimal' }), 'Refundable: ' + money(d.actions.refundableMinor, o.currency)),
          UI.field('Reason', input('reason', { maxlength: '300' }))),
        o.requiresShipping ? el('label', { class: 'check mb' }, input('restock', { type: 'checkbox', checked: true }), 'Return items to stock (full refunds only)') : null,
        el('button', { class: 'btn danger', type: 'submit', text: 'Issue refund' }));
      onSubmit(refund, async (v) => {
        const amount = UI.toMinor(v.amount);
        if (!confirm('Refund ' + money(amount, o.currency) + ' for order ' + o.orderNumber + '?')) return;
        await Api.post(API + '/orders/' + id + '/refund', { amountMinor: amount, restock: Boolean(v.restock), reason: v.reason || null });
        UI.toast('Refund issued', 'success');
        refresh();
      });
      actionCards.push(el('div', { class: 'card' }, el('h3', { text: 'Refund' }),
        el('p', { class: 'muted small', text: 'A full refund also revokes access to any digital items in this order.' }), refund));
    }
    if (writable && d.actions.canCancel) {
      const cancel = el('form', { class: 'inline-form' }, UI.field('Reason (sent to the customer)', input('reason', { maxlength: '300', class: 'wide' })),
        el('button', { class: 'btn danger', type: 'submit', text: 'Cancel order' }));
      onSubmit(cancel, async (v) => {
        if (!confirm('Cancel this unpaid order? Its stock will be returned to inventory.')) return;
        await Api.post(API + '/orders/' + id + '/cancel', { reason: v.reason || null });
        refresh();
      });
      actionCards.push(el('div', { class: 'card' }, el('h3', { text: 'Cancel' }), cancel));
    }

    mount(view(),
      el('p', { class: 'small' }, el('a', { href: '#/' + b, text: '\u2190 ' + (ORDER_CHANNELS[b] ? ORDER_CHANNELS[b].title : 'All orders') })),
      head('Order ' + o.orderNumber, UI.badge(o.status)),
      el('p', { class: 'muted', text: 'Placed ' + UI.date(o.placedAt) + (o.paidAt ? ' \u00B7 paid ' + UI.date(o.paidAt) : '') + (o.expiresAt && o.status === 'PENDING_PAYMENT' ? ' \u00B7 payment window ends ' + UI.date(o.expiresAt) : '') }),
      o.status === 'NEEDS_ATTENTION' ? el('div', { class: 'alert error', text: 'Payment arrived after the order expired and the stock was no longer available (or the amount did not match). Refund the customer or contact them.' }) : null,
      el('div', { class: 'split' }, el('div', null, el('div', { class: 'card' }, lines), shipments, actionCards), el('div', null, summary, customer)));
  }

  function kvRow(label, value) {
    return el('div', { class: 'summary-row' }, el('span', { text: label }), el('span', { text: value }));
  }

  async function orderInvoiceView(id, base) {
    const inv = await Api.get(API + '/orders/' + id + '/invoice');
    Invoice.render(view(), inv, '#/' + (base || 'orders') + '/' + id, 'Back to order');
  }

  // ================================================================== entitlements

  let entitlementEmail = '';

  async function entitlementsView() {
    const qs = new URLSearchParams({ size: '100' });
    if (entitlementEmail) qs.set('customerEmail', entitlementEmail);
    const result = await Api.get(API + '/entitlements?' + qs.toString());
    const writable = can('ORDERS_WRITE');
    const search = el('form', { class: 'filters' }, input('customerEmail', { type: 'search', placeholder: 'Customer email', value: entitlementEmail }),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Search' }));
    search.addEventListener('submit', (e) => { e.preventDefault(); entitlementEmail = UI.formValues(search).customerEmail; entitlementsView(); });

    const rows = result.items.map((x) => {
      const revoke = el('button', { class: 'btn ghost small', type: 'button', text: 'Revoke' });
      revoke.addEventListener('click', () => UI.busy(revoke, async () => {
        const reason = prompt('Reason for revoking access to "' + x.productTitle + '"?');
        if (!reason) return;
        try { await Api.post(API + '/entitlements/' + x.id + '/revoke', { reason }); entitlementsView(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', { text: x.customerEmail }), el('td', { text: x.productTitle }), el('td', { text: UI.label(x.source) }),
        el('td', null, UI.badge(x.status)), el('td', { class: 'small', text: UI.date(x.grantedAt) }),
        el('td', { text: x.downloadLimit ? x.downloadsUsed + ' / ' + x.downloadLimit : String(x.downloadsUsed) }),
        el('td', null, writable && x.status === 'ACTIVE' ? revoke : el('span', { class: 'muted small', text: x.revokeReason || '' })));
    });

    let grant = null;
    if (writable) {
      const products = await Api.get(API + '/products?type=DIGITAL&method=DIRECT&size=100');
      grant = el('form', { class: 'inline-form' },
        UI.field('Customer email', input('customerEmail', { type: 'email', required: true })),
        UI.field('Product', selectEl('productId', products.items.map((p) => [String(p.id), p.title]), '')),
        UI.field('Reason', input('reason', { required: true, maxlength: '300', class: 'wide', placeholder: 'Support replacement, gift\u2026' })),
        el('button', { class: 'btn', type: 'submit', text: 'Grant access' }));
      onSubmit(grant, async (v) => {
        await Api.post(API + '/entitlements', { customerEmail: v.customerEmail, productId: Number(v.productId), reason: v.reason });
        UI.toast('Access granted', 'success');
        entitlementsView();
      });
    }
    mount(view(), head('Digital access'),
      grant ? el('div', { class: 'card' }, el('h3', { text: 'Grant access manually' }), grant) : null,
      el('div', { class: 'card' }, search, table(['Customer', 'Product', 'Source', 'Status', 'Granted', 'Downloads', ''], rows, 'No digital access records.')));
  }

  // ================================================================== affiliate

  function perfTable(rows, isProduct) {
    const num = (t) => el('th', { class: 'num', text: t });
    return table([isProduct ? 'Product' : 'Merchant', isProduct ? 'Merchant' : null, num('Clicks'), num('Conversions'),
      num('Conv. rate'), num('Commission'), num('Per click')].filter(Boolean), rows.map((x) => el('tr', null,
      el('td', null, isProduct ? el('a', { href: '#/products/' + x.productId, text: x.label }) : el('strong', { text: x.label })),
      isProduct ? el('td', { text: x.merchantName }) : null,
      el('td', { class: 'num', text: String(x.clicks30d) }),
      el('td', { class: 'num', text: String(x.conversions30d) }),
      el('td', { class: 'num', text: x.conversionRatePct == null ? '\u2014' : x.conversionRatePct + '%' }),
      el('td', { class: 'num', text: money(x.commission30dMinor) }),
      el('td', { class: 'num', text: x.commissionPerClickMinor == null ? '\u2014' : money(x.commissionPerClickMinor) }))),
      'No clicks in the last 30 days.');
  }

  async function affiliateView(parts) {
    const tab = ['links', 'merchants', 'conversions', 'report'].includes(parts[0]) ? parts[0] : 'links';
    const tabs = el('div', { class: 'tabs' }, [['links', 'Links'], ['merchants', 'Merchants'], ['conversions', 'Conversions'], ['report', 'Report']]
      .map(([k, label]) => el('a', { href: '#/affiliate/' + k, class: k === tab ? 'active' : '', text: label })));
    const body = el('div');
    mount(view(), head('Affiliate'), tabs, body);
    const writable = can('AFFILIATE_WRITE');

    if (tab === 'links') {
      const links = await Api.get(API + '/affiliate/links');
      const staleCount = links.filter((l) => l.priceStale).length;
      mount(body,
        staleCount ? el('div', { class: 'alert warning' }, staleCount + (staleCount === 1 ? ' price is' : ' prices are')
          + ' out of date and hidden from shoppers. Open each product to update or confirm it.') : null,
        el('div', { class: 'card' },
        el('p', { class: 'muted', text: 'Affiliate links are added on each affiliate product\u2019s page. Customers are sent through /go/{code} so every click is counted.' }),
        table(['Product', 'Merchant', 'Tracking link', 'Price', el('th', { class: 'num', text: 'Clicks (30d)' }), 'Status'], links.map((l) => el('tr', null,
          el('td', null, el('a', { href: '#/products/' + l.productId, text: l.productTitle })),
          el('td', { text: l.merchantName }),
          el('td', null, el('a', { href: l.goUrl, target: '_blank', rel: 'noopener', text: l.goUrl })),
          el('td', null, l.displayPriceMinor == null ? el('span', { class: 'muted', text: '\u2014' })
            : [money(l.displayPriceMinor), l.priceStale ? [' ', el('span', { class: 'badge amber', text: 'Stale' })] : null]),
          el('td', { class: 'num', text: String(l.clicks30d) }),
          el('td', null, UI.badge(l.active ? 'ACTIVE' : 'DISABLED')))), 'No affiliate links yet.')));
    } else if (tab === 'merchants') {
      const merchants = await Api.get(API + '/affiliate/merchants');
      mount(body, el('div', { class: 'card' },
        el('p', { class: 'muted', text: 'A merchant is created automatically the first time you confirm a new affiliate domain. Set a sub-ID parameter (e.g. "ascsubtag", "subid", "aff_sub") to pass each click\u2019s reference to the network for conversion matching.' }),
        el('p', { class: 'muted small', text: '"Hide prices" shows shoppers "Check price on <store>" instead of a price. It is on for Amazon automatically: Amazon only allows prices that come from its own API and are refreshed every 24 hours.' }),
        table(['Domain', 'Name', 'Sub-ID parameter', 'Allow http', 'Hide prices', 'Active', ''], merchants.map((m) => {
          const name = input('name', { value: m.name, maxlength: '120' });
          const sub = input('subIdParam', { value: m.subIdParam || '', maxlength: '40', placeholder: 'none' });
          const http = input('allowHttp', { type: 'checkbox', checked: m.allowHttp });
          const hide = input('hidePrices', { type: 'checkbox', checked: m.hidePrices, 'aria-label': 'Hide prices' });
          const active = input('active', { type: 'checkbox', checked: m.active });
          const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
          save.addEventListener('click', () => UI.busy(save, async () => {
            try {
              await Api.patch(API + '/affiliate/merchants/' + m.id, { name: name.value.trim(), subIdParam: sub.value.trim(), allowHttp: http.checked,
                hidePrices: hide.checked, active: active.checked });
              UI.toast('Merchant saved', 'success');
            } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
          }));
          return el('tr', null, el('td', { text: m.domain }), el('td', null, name), el('td', null, sub), el('td', null, http), el('td', null, hide),
            el('td', null, active), el('td', null, writable ? save : null));
        }), 'No merchants yet.')));
    } else if (tab === 'conversions') {
      const result = await Api.get(API + '/affiliate/conversions?size=100');
      const rows = result.items.map((c) => {
        const status = selectEl('status', ['PENDING', 'APPROVED', 'REVERSED', 'PAID'], c.status, { disabled: !writable });
        status.addEventListener('change', async () => {
          try { await Api.patch(API + '/affiliate/conversions/' + c.id, { status: status.value }); UI.toast('Conversion updated', 'success'); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        });
        return el('tr', null, el('td', { text: c.externalTxnId }), el('td', { class: 'small', text: c.clickRef || '' }),
          el('td', { class: 'num', text: c.saleAmountMinor != null ? money(c.saleAmountMinor) : '' }),
          el('td', { class: 'num', text: c.commissionMinor != null ? money(c.commissionMinor) : '' }),
          el('td', null, status), el('td', { text: UI.label(c.source) }), el('td', { class: 'small', text: UI.date(c.reportedAt) }));
      });
      let importForm = null;
      if (writable) {
        const out = el('div');
        importForm = el('form', null,
          UI.field('Paste the CSV exported from your affiliate network',
            el('textarea', { name: 'csv', required: true, placeholder: 'external_txn_id,click_ref,sale_amount,commission,status\nTXN-1001,abc123,1499.00,74.95,approved' }),
            'Columns: external_txn_id, click_ref, sale_amount, commission, status (pending/approved/reversed/paid). Amounts in rupees.'),
          el('button', { class: 'btn', type: 'submit', text: 'Import' }), out);
        onSubmit(importForm, async (v) => {
          const r = await Api.post(API + '/affiliate/conversions/import', { csv: v.csv });
          mount(out, el('div', { class: 'alert ' + (r.errors.length ? 'warning' : 'success') },
            'Created ' + r.created + ', updated ' + r.updated + ', matched to clicks ' + r.matchedClicks + '.',
            r.errors.length ? el('ul', null, r.errors.map((x) => el('li', { text: x }))) : null));
        });
      }
      const postbackUrl = location.origin + '/api/' + SF + '/affiliate/postback?token=YOUR_TOKEN&txn={txn_id}&click_ref={sub_id}&sale={sale_amount}&commission={commission}&status={status}';
      const postbackCard = el('div', { class: 'card' }, el('h3', { text: 'Automatic import (postback)' }),
        el('p', { class: 'muted small', text: 'If your network supports server-to-server postbacks, give it this URL. Replace YOUR_TOKEN with the AFFILIATE_POSTBACK_TOKEN value from the server configuration (the endpoint stays off until it is set), and map the {placeholders} to the network\u2019s own macro names. Amounts are in rupees.' }),
        el('code', { class: 'secret', text: postbackUrl }));
      mount(body, postbackCard, importForm ? el('div', { class: 'card' }, el('h3', { text: 'Import conversions' }), importForm) : null,
        el('div', { class: 'card' }, table(['Transaction', 'Click ref', el('th', { class: 'num', text: 'Sale' }), el('th', { class: 'num', text: 'Commission' }), 'Status', 'Source', 'Reported'], rows, 'No conversions recorded yet.')));
    } else {
      const r = await Api.get(API + '/affiliate/report');
      mount(body,
        el('div', { class: 'stats' }, stat('Clicks (7 days)', r.clicks7d), stat('Clicks (30 days)', r.clicks30d),
          Object.entries(r.commissionByStatusMinor || {}).map(([k, v]) => stat('Commission ' + UI.label(k).toLowerCase(), money(v))),
          Object.entries(r.conversionsByStatus || {}).map(([k, v]) => stat('Conversions ' + UI.label(k).toLowerCase(), v))),
        r.stalePrices ? el('div', { class: 'alert warning' }, r.stalePrices + ' partner price(s) are out of date and hidden from shoppers. ',
          el('a', { href: '#/affiliate/links', text: 'Review links' })) : null,
        el('p', { class: 'muted small', text: 'Conversions are counted against the clicks that produced them in the last 30 days (reversed sales excluded). Rates fill in as your network reports sales via CSV import or postback.' }),
        el('div', { class: 'card' }, el('h3', { text: 'Products (30 days)' }), perfTable(r.products, true)),
        el('div', { class: 'card' }, el('h3', { text: 'Merchants (30 days)' }), perfTable(r.merchants, false)));
    }
  }

  // ================================================================== settings

  async function settingsView() {
    const [store, methods, smtp, states] = await Promise.all([
      Api.get(API + '/settings/store'), Api.get(API + '/settings/shipping-methods'), Api.get(API + '/settings/smtp'),
      Api.get('/api/' + SF + '/catalog/states')]);

    const storeForm = el('form', { class: 'card' }, el('h2', { text: 'Store & invoice details' }),
      el('div', { class: 'row' },
        UI.field('Store name', input('storeName', { value: store.storeName, required: true, maxlength: '120' })),
        UI.field('Support email', input('supportEmail', { type: 'email', value: store.supportEmail || '', maxlength: '254' }))),
      UI.field('WhatsApp number for the chat button', input('whatsappNumber', { value: store.whatsappNumber ? '+' + store.whatsappNumber : '',
        maxlength: '20', placeholder: '+91 98765 43210', inputmode: 'tel' }), 'Shoppers see a chat button on every page. Leave empty to hide it.'),
      el('div', { class: 'row' },
        UI.field('Google Analytics 4 ID', input('ga4Id', { value: store.ga4Id || '', maxlength: '20', placeholder: 'G-XXXXXXXXXX' }),
          'From Google Analytics → Admin → Data streams. Loads only after a shopper accepts cookies. Leave empty to turn off.'),
        UI.field('Meta (Facebook/Instagram) Pixel ID', input('metaPixelId', { value: store.metaPixelId || '', maxlength: '20', placeholder: '1234567890123456', inputmode: 'numeric' }),
          'From Meta Events Manager → Data sources. Loads only after a shopper accepts cookies. Leave empty to turn off.')),
      el('div', { class: 'row' },
        UI.field('Seller legal name', input('sellerLegalName', { value: store.sellerLegalName || '', maxlength: '200' })),
        UI.field('GSTIN', input('sellerTaxId', { value: store.sellerTaxId || '', maxlength: '15', placeholder: '27ABCDE1234F1Z5' }), 'Your state is filled in from the GSTIN.')),
      UI.field('Registered address (printed on invoices)', el('textarea', { name: 'sellerAddress', maxlength: '500', text: store.sellerAddress || '' })),
      el('div', { class: 'row' },
        UI.field('Seller state', selectEl('sellerStateCode', [['', 'Not set'], ...states.map((s) => [s.code, s.name + ' (' + s.code + ')'])], store.sellerStateCode || ''),
          'Same-state buyers pay CGST + SGST; other states pay IGST.'),
        UI.field('Invoice number prefix', input('invoicePrefix', { value: store.invoicePrefix, maxlength: '10' }), 'Invoices are numbered PREFIX-YEAR-000001.')),
      el('div', { class: 'row' },
        UI.field('Default GST rate', selectEl('taxRateBps', GST_RATES.map((r) => [String(r), r / 100 + '%']).concat(
          GST_RATES.includes(store.taxRateBps) ? [] : [[String(store.taxRateBps), store.taxRateBps / 100 + '% (custom)']]), String(store.taxRateBps)),
          'Used for products without their own GST rate.'),
        UI.field('Default HSN / SAC', input('defaultHsnSac', { value: store.defaultHsnSac || '', maxlength: '8', inputmode: 'numeric' }))),
      el('label', { class: 'check mb' }, input('pricesIncludeTax', { type: 'checkbox', checked: store.pricesIncludeTax }),
        'Prices already include GST (GST is shown as a breakdown, not added on top)'),
      el('button', { class: 'btn', type: 'submit', text: 'Save store details' }));
    onSubmit(storeForm, async (v) => {
      await Api.put(API + '/settings/store', {
        storeName: v.storeName, supportEmail: v.supportEmail, sellerLegalName: v.sellerLegalName, sellerTaxId: v.sellerTaxId,
        sellerAddress: v.sellerAddress, taxRateBps: intOrNull(v.taxRateBps), invoicePrefix: v.invoicePrefix,
        sellerStateCode: v.sellerStateCode, defaultHsnSac: v.defaultHsnSac, pricesIncludeTax: Boolean(v.pricesIncludeTax),
        whatsappNumber: v.whatsappNumber || '', ga4Id: v.ga4Id || '', metaPixelId: v.metaPixelId || ''
      });
      settingsView();
      UI.toast('Store details saved', 'success');
    });

    const channelForm = el('form', { class: 'card' }, el('h2', { text: 'Selling channels' }),
      el('p', { class: 'muted small', text: 'Affiliate products are always available. Turn this on to also sell your own stock and dropship products with cart checkout. Shoppers see one normal store either way.' }),
      el('label', { class: 'check mb' }, input('onsiteCheckout', { type: 'checkbox', checked: !store.affiliateOnly }),
        'Sell on this site (own stock & dropshipping, with cart and checkout)'),
      el('button', { class: 'btn', type: 'submit', text: 'Save' }));
    onSubmit(channelForm, async (v) => {
      await Api.put(API + '/settings/store', { onsiteCheckout: Boolean(v.onsiteCheckout) });
      window.AdminKit.affiliateOnly = !v.onsiteCheckout;
      settingsView();
      UI.toast('Saved', 'success');
    });

    const commerceForm = el('form', { class: 'card' }, el('h2', { text: store.affiliateOnly ? 'Reviews' : 'Delivery, cash on delivery & reviews' }),
      store.affiliateOnly
        ? el('p', { class: 'muted small', text: 'Affiliate-only mode is on: shoppers buy on partner sites, so delivery areas and cash on delivery are not used on this storefront.' })
        : (SF === 'shop' ? [
        el('div', { class: 'row' },
          UI.field('Deliver to', selectEl('pincodeMode', [['ALL', 'All PIN codes'], ['ALLOW', 'Only the PIN codes listed'], ['BLOCK', 'All except the PIN codes listed']], store.pincodeMode)),
          UI.field('Delivery estimate shown to buyers', input('deliveryEstimate', { value: store.deliveryEstimate || '', maxlength: '80', placeholder: 'Delivered in 3\u20136 working days' }))),
        UI.field('PIN codes', el('textarea', { name: 'pincodeList', rows: '3', text: store.pincodeList || '' }),
          'Full 6-digit PIN codes or prefixes (e.g. 4110 for all of 4110xx), separated by commas, spaces or new lines.'),
        el('label', { class: 'check mb' }, input('codEnabled', { type: 'checkbox', checked: store.codEnabled }), 'Offer cash on delivery'),
        el('div', { class: 'row' },
          UI.field('COD order limit (\u20B9)', input('codMax', { value: UI.fromMinor(store.codMaxMinor), inputmode: 'decimal', placeholder: 'No limit' })),
          UI.field('COD fee (\u20B9)', input('codFee', { value: UI.fromMinor(store.codFeeMinor), inputmode: 'decimal' }))),
        UI.field('COD only for these PIN codes (optional)', el('textarea', { name: 'codPincodeList', rows: '2', text: store.codPincodeList || '' }),
          'Leave empty to allow COD wherever you deliver.')
      ] : el('p', { class: 'muted small', text: 'Digital products are delivered instantly, so delivery areas and cash on delivery do not apply.' })),
      el('label', { class: 'check mb' }, input('reviewsAutoApprove', { type: 'checkbox', checked: store.reviewsAutoApprove }),
        'Publish customer reviews immediately (otherwise approve them on the Reviews page)'),
      el('button', { class: 'btn', type: 'submit', text: 'Save' }));
    onSubmit(commerceForm, async (v) => {
      const body = { reviewsAutoApprove: Boolean(v.reviewsAutoApprove) };
      if (SF === 'shop' && !store.affiliateOnly) {
        Object.assign(body, {
          pincodeMode: v.pincodeMode, pincodeList: v.pincodeList, deliveryEstimate: v.deliveryEstimate,
          codEnabled: Boolean(v.codEnabled), codMaxMinor: v.codMax === '' ? 0 : UI.toMinor(v.codMax),
          codFeeMinor: v.codFee === '' ? 0 : UI.toMinor(v.codFee), codPincodeList: v.codPincodeList
        });
      }
      await Api.put(API + '/settings/store', body);
      settingsView();
      UI.toast('Saved', 'success');
    });

    const methodRows = methods.map((m) => {
      const name = input('name', { value: m.name, maxlength: '120' });
      const rate = input('rate', { value: UI.fromMinor(m.rateMinor), class: 'num-input', inputmode: 'decimal' });
      const free = input('free', { value: UI.fromMinor(m.freeAboveMinor), class: 'num-input', inputmode: 'decimal', placeholder: 'never' });
      const active = input('active', { type: 'checkbox', checked: m.active });
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try {
          await Api.put(API + '/settings/shipping-methods/' + m.id, { name: name.value.trim(), rateMinor: UI.toMinor(rate.value || '0'),
            freeAboveMinor: free.value.trim() === '' ? 0 : UI.toMinor(free.value), active: active.checked });
          UI.toast('Shipping method saved', 'success');
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('tr', null, el('td', null, name), el('td', null, rate), el('td', null, free), el('td', null, active), el('td', null, save));
    });
    const addMethod = el('form', { class: 'inline-form mt' },
      UI.field('Name', input('name', { required: true, maxlength: '120', placeholder: 'Express delivery' })),
      UI.field('Rate (\u20B9)', input('rate', { required: true, inputmode: 'decimal' })),
      UI.field('Free above (\u20B9, optional)', input('free', { inputmode: 'decimal' })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Add method' }));
    onSubmit(addMethod, async (v) => {
      await Api.post(API + '/settings/shipping-methods', { name: v.name, rateMinor: UI.toMinor(v.rate), freeAboveMinor: minorOrNull(v.free), active: true });
      settingsView();
    });

    const smtpForm = el('form', { class: 'card' }, el('h2', { text: 'Email (SMTP)' }),
      el('p', { class: 'muted', text: 'Order confirmations, shipping updates, refunds, email verification and password resets for this storefront are sent through this server. Works with Gmail/Google Workspace (app password), Zoho, Outlook, Amazon SES, SendGrid, Brevo or any SMTP provider.' }),
      el('label', { class: 'check mb' }, input('enabled', { type: 'checkbox', checked: smtp.enabled }), 'Send emails'),
      el('div', { class: 'row' },
        UI.field('SMTP host', input('host', { value: smtp.host || '', maxlength: '253', placeholder: 'smtp.gmail.com' })),
        UI.field('Port', input('port', { type: 'number', value: smtp.port == null ? '' : String(smtp.port), placeholder: '587' })),
        UI.field('Security', selectEl('security', [['STARTTLS', 'STARTTLS (port 587)'], ['SSL', 'SSL/TLS (port 465)'], ['NONE', 'None (not recommended)']], smtp.security))),
      el('div', { class: 'row' },
        UI.field('Username', input('username', { value: smtp.username || '', maxlength: '254', autocomplete: 'off' })),
        UI.field('Password', input('password', { type: 'password', autocomplete: 'new-password', placeholder: smtp.passwordSet ? '\u2022\u2022\u2022\u2022\u2022\u2022 (saved \u2013 leave blank to keep)' : '' }),
          'Stored encrypted. Never shown again after saving.')),
      el('div', { class: 'row' },
        UI.field('From email', input('fromEmail', { type: 'email', value: smtp.fromEmail || '', maxlength: '254', placeholder: 'orders@yourstore.in' })),
        UI.field('From name', input('fromName', { value: smtp.fromName || '', maxlength: '120' }))),
      el('div', { class: 'actions' }, el('button', { class: 'btn', type: 'submit', text: 'Save email settings' }),
        smtp.passwordSet ? el('label', { class: 'check' }, input('clearPassword', { type: 'checkbox' }), 'Remove saved password') : null));
    onSubmit(smtpForm, async (v) => {
      const body = { enabled: Boolean(v.enabled), host: v.host, port: intOrNull(v.port), username: v.username, security: v.security,
        fromEmail: v.fromEmail, fromName: v.fromName };
      if (v.clearPassword) body.password = '';
      else if (v.password) body.password = v.password;
      await Api.put(API + '/settings/smtp', body);
      settingsView();
      UI.toast('Email settings saved', 'success');
    });
    const testForm = el('form', { class: 'inline-form' },
      UI.field('Send a test email to', input('to', { type: 'email', required: true, class: 'wide', value: me.email })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Send test' }));
    onSubmit(testForm, async (v) => { await Api.post(API + '/settings/smtp/test', { to: v.to }); }, 'Test email sent. Check the inbox (and spam folder).');
    smtpForm.appendChild(el('div', { class: 'mt' }, testForm));

    mount(view(), head('Settings'), storeForm, channelForm, commerceForm,
      el('div', { class: 'card' }, el('h2', { text: 'Shipping methods' }),
        table(['Name', 'Rate (\u20B9)', 'Free above (\u20B9)', 'Active', ''], methodRows, 'No shipping methods.'), addMethod),
      smtpForm);
  }

  async function notificationsView(page) {
    const p = typeof page === 'number' ? page : 0;
    const result = await Api.get(API + '/notifications?page=' + p + '&size=50');
    const retryBtn = (n) => {
      const b = el('button', { class: 'btn ghost small', type: 'button', text: 'Retry' });
      b.addEventListener('click', () => UI.busy(b, async () => {
        try { await Api.post(API + '/notifications/' + n.id + '/retry'); UI.toast('Queued for sending', 'success'); notificationsView(p); }
        catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return b;
    };
    mount(view(), head('Email log'),
      el('p', { class: 'muted', text: '"Skipped" means email is not configured or turned off in Settings. Emails that fail are retried automatically (after 1, 5 and 15 minutes, then 1 and 3 hours) before being marked failed.' }),
      el('div', { class: 'card' }, table(['When', 'To', 'Subject', 'Type', 'Status', 'Error'], result.items.map((n) => el('tr', null,
        el('td', { class: 'small', text: UI.date(n.createdAt) }), el('td', { text: n.recipient }), el('td', { text: n.subject }),
        el('td', { class: 'small', text: UI.label(n.kind) }),
        el('td', null, UI.badge(n.status, n.status === 'PENDING' && n.attempts ? 'Retrying' : null),
          n.attempts > 1 ? el('div', { class: 'muted small', text: n.attempts + ' attempts' }) : null,
          n.status === 'PENDING' && n.nextAttemptAt ? el('div', { class: 'muted small', text: 'next ' + UI.date(n.nextAttemptAt) }) : null),
        el('td', { class: 'small' }, n.error || '', n.retryable ? [' ', retryBtn(n)] : null))), 'No emails yet.'),
      pager(result, (pg) => notificationsView(pg))));
  }

  // ================================================================== staff

  async function staffView() {
    const staff = await Api.get(API + '/staff');
    const out = el('div');
    const rows = staff.map((s) => {
      const role = selectEl('role', ROLES, s.role);
      const status = selectEl('status', ['ACTIVE', 'DISABLED'], s.status);
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save' });
      const reset = el('button', { class: 'btn ghost small', type: 'button', text: 'Reset password' });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try { await Api.patch(API + '/staff/' + s.id, { role: role.value, status: status.value }); UI.toast('Saved', 'success'); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      reset.addEventListener('click', () => UI.busy(reset, async () => {
        if (!confirm('Generate a new temporary password for ' + s.email + '?')) return;
        try {
          const r = await Api.post(API + '/staff/' + s.id + '/reset-password');
          mount(out, tempPasswordAlert(s.email, r.temporaryPassword));
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      const self = s.id === me.id;
      return el('tr', null,
        el('td', null, el('div', { text: s.fullName }), el('div', { class: 'muted small', text: s.email })),
        el('td', null, self ? UI.label(s.role) : role), el('td', null, self ? UI.badge(s.status) : status),
        el('td', { text: s.mfaEnabled ? 'On' : 'Off' }), el('td', { class: 'small', text: s.lastLoginAt ? UI.date(s.lastLoginAt) : 'Never' }),
        el('td', null, self ? el('span', { class: 'muted small', text: 'You' }) : el('div', { class: 'actions' }, save, reset)));
    });
    const add = el('form', { class: 'inline-form' },
      UI.field('Email', input('email', { type: 'email', required: true })),
      UI.field('Full name', input('fullName', { required: true, maxlength: '120' })),
      UI.field('Role', selectEl('role', ROLES, 'SUPPORT')),
      el('button', { class: 'btn', type: 'submit', text: 'Add staff member' }));
    onSubmit(add, async (v) => {
      const r = await Api.post(API + '/staff', v);
      await staffView();
      mount(document.getElementById('staff-out'), tempPasswordAlert(r.staff.email, r.temporaryPassword));
    });
    out.id = 'staff-out';
    mount(view(), head('Staff'),
      el('p', { class: 'muted', text: 'Staff accounts only work in the ' + SF_NAME + ' admin. Roles: Master admin (everything), Catalog manager (products, stock, files, affiliate), Order manager (orders, refunds, shipping, digital access), Support (read-only orders and customers).' }),
      out, el('div', { class: 'card' }, add), el('div', { class: 'card' }, table(['Name', 'Role', 'Status', 'MFA', 'Last sign-in', ''], rows)));
  }

  function tempPasswordAlert(email, password) {
    return el('div', { class: 'alert success' },
      el('div', { text: 'Temporary password for ' + email + ' (shown once \u2013 share it securely; they must change it at first sign-in):' }),
      el('div', { class: 'secret mt', text: password }));
  }

  async function auditView(page) {
    const p = typeof page === 'number' ? page : 0;
    const result = await Api.get(API + '/audit-log?page=' + p + '&size=50');
    mount(view(), head('Audit log'),
      el('div', { class: 'card' }, table(['When', 'Admin', 'Action', 'Entity', 'Details'], result.items.map((a) => el('tr', null,
        el('td', { class: 'small', text: UI.date(a.createdAt) }), el('td', { text: a.adminEmail }), el('td', { text: UI.label(a.action) }),
        el('td', { class: 'small', text: (a.entityType || '') + (a.entityId ? ' #' + a.entityId : '') }), el('td', { class: 'small', text: a.details || '' }))), 'No activity yet.'),
      pager(result, (pg) => auditView(pg))));
  }

  // ================================================================== my account

  async function accountView() {
    me = await Api.get(API + '/auth/me');
    const mfaBox = el('div');
    const renderMfa = () => {
      if (me.mfaEnabled) {
        const form = el('form', { class: 'inline-form' }, UI.field('Current authenticator code', input('code', { required: true, inputmode: 'numeric', maxlength: '8' })),
          el('button', { class: 'btn danger', type: 'submit', text: 'Turn off two-factor sign-in' }));
        onSubmit(form, async (v) => { await Api.post(API + '/auth/mfa/disable', { code: v.code }); me.mfaEnabled = false; renderMfa(); UI.toast('Two-factor sign-in turned off'); });
        mount(mfaBox, el('p', null, UI.badge('ACTIVE', 'On'), ' Two-factor sign-in is protecting your account.'), form);
      } else {
        const start = el('button', { class: 'btn', type: 'button', text: 'Set up two-factor sign-in' });
        start.addEventListener('click', () => UI.busy(start, async () => {
          try {
            const setup = await Api.post(API + '/auth/mfa/setup');
            const confirmForm = el('form', { class: 'inline-form mt' }, UI.field('Code from the app', input('code', { required: true, inputmode: 'numeric', maxlength: '8' })),
              el('button', { class: 'btn', type: 'submit', text: 'Verify and turn on' }));
            onSubmit(confirmForm, async (v) => { await Api.post(API + '/auth/mfa/enable', { code: v.code }); me.mfaEnabled = true; renderMfa(); UI.toast('Two-factor sign-in is on', 'success'); });
            mount(mfaBox,
              el('p', { text: 'In Google Authenticator, Microsoft Authenticator, Authy or 1Password choose "Enter a setup key" and type this key (time-based):' }),
              el('div', { class: 'secret', text: setup.secret.replace(/(.{4})/g, '$1 ').trim() }),
              el('p', { class: 'muted small mt', text: 'Or use this setup URI in apps that accept it:' }),
              el('div', { class: 'secret small', text: setup.otpauthUri }),
              confirmForm);
          } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        mount(mfaBox, el('p', { class: 'muted', text: 'Add a one-time code from an authenticator app to every sign-in.' }), start);
      }
    };
    renderMfa();
    mount(view(), head('My account'),
      el('div', { class: 'card medium' }, el('dl', { class: 'kv' },
        el('dt', { text: 'Name' }), el('dd', { text: me.fullName || '' }), el('dt', { text: 'Email' }), el('dd', { text: me.email }),
        el('dt', { text: 'Role' }), el('dd', { text: UI.label(me.role) }), el('dt', { text: 'Storefront' }), el('dd', { text: SF_NAME }))),
      el('div', { class: 'card medium' }, el('h2', { text: 'Change password' }), passwordForm()),
      el('div', { class: 'card medium' }, el('h2', { text: 'Two-factor sign-in' }), mfaBox));
  }

  /** Lets feature modules loaded after this file add their own sections to the panel. */
  window.AdminKit = {
    SF, SF_NAME, API, GST_RATES, can, view, head, table, pager, onSubmit, input, selectEl, minorOrNull, intOrNull,
    stat, kvRow, handleAuthError, navBadge, inspectVideo, affiliateOnly: true,
    /** Adds a page; its place in the menu comes from NAV_GROUPS (pages not listed there go in the last group). */
    register(route, label, perm, fn) {
      ROUTES[route] = fn;
      NAV_ITEMS[route] = [label, perm];
    }
  };
})();
