/* Customer account pages: sign-in, registration, password reset, email verification, orders, invoice, library. */
(function () {
  'use strict';
  const { el, mount, money } = UI;

  const pages = {
    'account-login': loginPage,
    'account-register': registerPage,
    'account-forgot': forgotPage,
    'account-reset': resetPage,
    'account-verify': verifyPage,
    'account-index': indexPage,
    'account-order': orderPage,
    'account-invoice': invoicePage,
    'account-library': libraryPage
  };

  document.addEventListener('DOMContentLoaded', async () => {
    const page = document.body.dataset.page;
    try {
      const ctx = page === 'account-invoice' ? { sf: Site.currentStorefront() } : await Site.renderChrome({ search: false });
      await pages[page](ctx);
    } catch (e) {
      Site.pageError(e);
    }
  });

  function main() {
    return document.getElementById('main');
  }

  function page(...children) {
    const first = children[0];
    const auth = first instanceof Element && first.classList.contains('narrow');
    const sf = Site.currentStorefront();
    mount(main(), el('div', { class: 'container' },
      auth ? el('div', { class: 'auth-brand' }, el('img', { src: Site.BRANDS[sf].logo, alt: Site.BRANDS[sf].name })) : null,
      children));
  }

  function nextUrl() {
    return Site.safeNext(UI.param('next'), '/account');
  }

  function form(fields, submitText, onSubmit) {
    const msg = el('div');
    const button = el('button', { class: 'btn block', type: 'submit', text: submitText });
    const f = el('form', { novalidate: true }, msg, fields, button);
    f.addEventListener('submit', (e) => {
      e.preventDefault();
      UI.busy(button, async () => {
        mount(msg);
        try {
          await onSubmit(UI.formValues(f), msg);
        } catch (err) {
          mount(msg, UI.errorAlert(err));
        }
      });
    });
    return f;
  }

  function input(name, type, attrs) {
    return el('input', Object.assign({ name, id: 'f-' + name, type: type || 'text' }, attrs || {}));
  }

  // ------------------------------------------------------------------ auth

  async function loginPage() {
    document.title = 'Sign in';
    if (await Site.me()) { location.href = nextUrl(); return; }
    page(el('div', { class: 'card narrow' },
      el('h1', { text: 'Sign in' }),
      form([
        UI.field('Email', input('email', 'email', { autocomplete: 'email', required: true })),
        UI.field('Password', input('password', 'password', { autocomplete: 'current-password', required: true }))
      ], 'Sign in', async (v) => {
        await Api.post('/api/auth/login', { email: v.email, password: v.password });
        location.href = nextUrl();
      }),
      el('p', { class: 'mt small' },
        el('a', { href: '/account/forgot.html', text: 'Forgot your password?' }), ' \u00B7 ',
        el('a', { href: '/account/register.html' + location.search, text: 'Create an account' }))));
  }

  async function registerPage({ sf }) {
    document.title = 'Create account';
    page(el('div', { class: 'card narrow' },
      el('h1', { text: 'Create your account' }),
      form([
        UI.field('Full name', input('fullName', 'text', { autocomplete: 'name', required: true, maxlength: '120' })),
        UI.field('Email', input('email', 'email', { autocomplete: 'email', required: true })),
        UI.field('Password', input('password', 'password', { autocomplete: 'new-password', required: true }),
          'At least 8 characters, including a letter and a number.')
      ], 'Create account', async (v) => {
        await Api.post('/api/auth/register', { fullName: v.fullName, email: v.email, password: v.password, storefront: sf });
        location.href = nextUrl();
      }),
      el('p', { class: 'mt small' }, 'Already have an account? ', el('a', { href: '/account/login.html' + location.search, text: 'Sign in' }))));
  }

  async function forgotPage({ sf }) {
    document.title = 'Reset password';
    const card = el('div', { class: 'card narrow' }, el('h1', { text: 'Reset your password' }),
      el('p', { class: 'muted', text: 'Enter your account email and we will send you a reset link.' }));
    card.appendChild(form([UI.field('Email', input('email', 'email', { autocomplete: 'email', required: true }))],
      'Send reset link', async (v, msg) => {
        await Api.post('/api/auth/password-reset/request', { email: v.email, storefront: sf });
        mount(msg, el('div', { class: 'alert success', text: 'If an account exists for that email, a reset link is on its way.' }));
      }));
    page(card);
  }

  async function resetPage() {
    document.title = 'Choose a new password';
    const token = UI.param('token');
    if (!token) { page(el('div', { class: 'alert error', text: 'This reset link is incomplete.' })); return; }
    page(el('div', { class: 'card narrow' }, el('h1', { text: 'Choose a new password' }),
      form([UI.field('New password', input('newPassword', 'password', { autocomplete: 'new-password', required: true }),
        'At least 8 characters, including a letter and a number.')], 'Save password', async (v, msg) => {
        await Api.post('/api/auth/password-reset/confirm', { token, newPassword: v.newPassword });
        mount(msg, el('div', { class: 'alert success' }, 'Password updated. ', el('a', { href: '/account/login.html', text: 'Sign in' })));
      })));
  }

  async function verifyPage() {
    document.title = 'Verify email';
    const token = UI.param('token');
    const box = el('div', { class: 'card narrow' }, el('h1', { text: 'Email verification' }));
    page(box);
    if (!token) { box.appendChild(el('div', { class: 'alert error', text: 'This verification link is incomplete.' })); return; }
    try {
      await Api.post('/api/auth/verify-email', { token });
      box.appendChild(el('div', { class: 'alert success', text: 'Thanks! Your email address is verified.' }));
      box.appendChild(el('a', { class: 'btn', href: '/account', text: 'Go to my account' }));
    } catch (e) {
      box.appendChild(UI.errorAlert(e));
    }
  }

  // ------------------------------------------------------------------ account home

  async function indexPage({ sf }) {
    const user = await Site.requireCustomer();
    document.title = 'My account';
    const orders = await Api.get('/api/orders?size=50');

    const ordersCard = el('div', { class: 'card' }, el('h2', { text: 'My orders' }),
      orders.items.length ? el('div', { class: 'table-wrap' }, el('table', null,
        el('thead', null, el('tr', null, ['Order', 'Store', 'Date', 'Status', 'Total'].map((h, i) => el('th', { class: i === 4 ? 'num' : '', text: h })))),
        el('tbody', null, orders.items.map((o) => {
          const tr = el('tr', { class: 'clickable' },
            el('td', null, el('a', { href: '/account/order.html?n=' + encodeURIComponent(o.orderNumber), text: o.orderNumber })),
            el('td', { text: Site.STOREFRONTS[o.storefront] || o.storefront }),
            el('td', { text: UI.date(o.placedAt) }),
            el('td', null, UI.badge(o.status)),
            el('td', { class: 'num', text: money(o.totalMinor, o.currency) }));
          tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.href = '/account/order.html?n=' + encodeURIComponent(o.orderNumber); });
          return tr;
        }))))
        : el('p', { class: 'muted' }, 'No orders yet. ', el('a', { href: '/' + sf, text: 'Start shopping' })));

    const profile = el('div', { class: 'card' }, el('h2', { text: 'Profile' }),
      el('p', { class: 'muted', text: user.email + (user.emailVerified ? ' (verified)' : ' (not verified)') }),
      user.emailVerified ? null : resendButton(sf),
      form([
        UI.field('Full name', input('fullName', 'text', { value: user.fullName, required: true, maxlength: '120' })),
        UI.field('Phone', input('phone', 'tel', { value: user.phone || '', maxlength: '30' }))
      ], 'Save profile', async (v, msg) => {
        await Api.put('/api/auth/me', { fullName: v.fullName, phone: v.phone || null });
        mount(msg, el('div', { class: 'alert success', text: 'Profile saved.' }));
      }));

    const password = el('div', { class: 'card' }, el('h2', { text: 'Change password' }),
      form([
        UI.field('Current password', input('currentPassword', 'password', { autocomplete: 'current-password', required: true })),
        UI.field('New password', input('newPassword', 'password', { autocomplete: 'new-password', required: true }))
      ], 'Change password', async (v, msg) => {
        await Api.post('/api/auth/change-password', v);
        mount(msg, el('div', { class: 'alert success', text: 'Password changed.' }));
      }));

    const logout = el('button', { class: 'btn secondary', type: 'button', text: 'Sign out' });
    logout.addEventListener('click', async () => {
      await Api.post('/api/auth/logout').catch(() => null);
      location.href = '/' + sf;
    });

    page(el('div', { class: 'actions mb' }, el('h1', { text: 'My account' }), el('span', { class: 'spacer' }),
      el('a', { class: 'btn secondary', href: '/account/library.html', text: 'My digital library' }), logout),
    el('div', { class: 'split' }, ordersCard, el('div', null, profile, password)));
  }

  function resendButton(sf) {
    const b = el('button', { class: 'btn small secondary mb', type: 'button', text: 'Resend verification email' });
    b.addEventListener('click', () => UI.busy(b, async () => {
      try {
        await Api.post('/api/auth/verify-email/resend?storefront=' + sf);
        UI.toast('Verification email sent', 'success');
      } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
    }));
    return b;
  }

  // ------------------------------------------------------------------ order detail

  async function orderPage() {
    await Site.requireCustomer();
    const n = UI.param('n');
    const o = await Api.get('/api/orders/' + encodeURIComponent(n || ''));
    document.title = 'Order ' + o.orderNumber;
    const lines = el('table', null,
      el('thead', null, el('tr', null, el('th', { text: 'Item' }), el('th', { text: 'Status' }), el('th', { class: 'num', text: 'Qty' }), el('th', { class: 'num', text: 'Total' }))),
      el('tbody', null, o.lines.map((l) => el('tr', null,
        el('td', null, el('div', { text: l.title }), l.variantName && l.variantName !== 'Default' ? el('div', { class: 'muted small', text: l.variantName }) : null),
        el('td', null, UI.badge(l.fulfillmentStatus)),
        el('td', { class: 'num', text: String(l.quantity) }),
        el('td', { class: 'num', text: money(l.lineTotalMinor, o.currency) })))));

    const a = o.shippingAddress;
    const side = el('div', null,
      el('div', { class: 'card' }, el('h3', { text: 'Summary' }),
        row('Subtotal', money(o.subtotalMinor, o.currency)),
        o.discountMinor ? row('Discount' + (o.couponCode ? ' (' + o.couponCode + ')' : ''), '\u2212' + money(o.discountMinor, o.currency)) : null,
        o.requiresShipping ? row('Shipping', o.shippingMinor ? money(o.shippingMinor, o.currency) : 'Free') : null,
        o.codFeeMinor ? row('Cash on delivery fee', money(o.codFeeMinor, o.currency)) : null,
        o.taxMinor ? row((o.pricesIncludeTax ? 'Included GST' : 'GST')
          + (o.cgstMinor || o.sgstMinor ? ' (CGST + SGST)' : o.igstMinor ? ' (IGST)' : ''), money(o.taxMinor, o.currency)) : null,
        el('div', { class: 'summary-row total' }, el('span', { text: 'Total' }), el('span', { text: money(o.totalMinor, o.currency) })),
        o.paymentMethod === 'COD' ? el('p', { class: 'muted small', text: o.paidAt ? 'Paid in cash on delivery.' : 'Pay in cash when your order is delivered.' }) : null,
        o.canPay ? el('a', { class: 'btn block mt', href: '/' + o.storefront + '/pay/' + encodeURIComponent(o.orderNumber), text: 'Pay now' }) : null,
        o.invoiceNumber ? el('a', { class: 'btn secondary block mt', href: '/account/invoice.html?n=' + encodeURIComponent(o.orderNumber), text: 'Invoice ' + o.invoiceNumber }) : null),
      a ? el('div', { class: 'card' }, el('h3', { text: 'Shipping to' }),
        el('div', { text: a.name }), el('div', { text: a.line1 }), a.line2 ? el('div', { text: a.line2 }) : null,
        el('div', { text: a.city + ', ' + a.state + ' ' + a.postalCode }), el('div', { text: a.country }),
        el('div', { class: 'muted small', text: a.phone }),
        o.shippingMethodName ? el('div', { class: 'muted small mt', text: o.shippingMethodName }) : null) : null);

    const shipments = o.shipments && o.shipments.length ? el('div', { class: 'card' }, el('h3', { text: 'Shipments' }),
      o.shipments.map((s) => el('p', null, UI.badge(s.status), ' ', s.carrier,
        s.trackingNumber ? ' \u00B7 ' + s.trackingNumber : '',
        s.trackingUrl ? el('span', null, ' \u00B7 ', el('a', { href: s.trackingUrl, target: '_blank', rel: 'noopener noreferrer', text: 'Track package' })) : null,
        el('span', { class: 'muted small', text: ' \u00B7 shipped ' + UI.date(s.shippedAt) })))) : null;

    const hasDigital = o.lines.some((l) => l.productType === 'DIGITAL');
    page(el('p', { class: 'small' }, el('a', { href: '/account', text: '\u2190 My account' })),
      el('div', { class: 'actions mb' }, el('h1', { text: 'Order ' + o.orderNumber }), UI.badge(o.status)),
      el('p', { class: 'muted', text: 'Placed ' + UI.date(o.placedAt) + (o.paidAt ? ' \u00B7 paid ' + UI.date(o.paidAt) : '') }),
      UI.param('placed') && o.status === 'CONFIRMED' ? el('div', { class: 'alert success', text: 'Thank you! Your cash-on-delivery order is confirmed. We will email you when it ships.' }) : null,
      o.paymentStatus === 'FAILED' && o.canPay ? el('div', { class: 'alert error', text: 'The last payment attempt failed: ' + (o.paymentFailureReason || 'declined') }) : null,
      hasDigital && o.paidAt ? el('div', { class: 'alert info' }, 'Your digital items are in your ', el('a', { href: '/account/library.html', text: 'library' }), '.') : null,
      el('div', { class: 'split' }, el('div', null, el('div', { class: 'card table-wrap' }, lines), shipments), side));
  }

  function row(label, value) {
    return el('div', { class: 'summary-row' }, el('span', { text: label }), el('span', { text: value }));
  }

  // ------------------------------------------------------------------ invoice

  async function invoicePage() {
    await Site.requireCustomer();
    const inv = await Api.get('/api/orders/' + encodeURIComponent(UI.param('n') || '') + '/invoice');
    Invoice.render(main(), inv, '/account/order.html?n=' + encodeURIComponent(inv.orderNumber));
  }

  // ------------------------------------------------------------------ library

  async function libraryPage() {
    await Site.requireCustomer();
    document.title = 'My library';
    const items = await Api.get('/api/library');
    const list = items.length ? items.map((item) => {
      const files = item.files.map((f) => {
        const b = el('button', { class: 'btn small', type: 'button', text: 'Download ' + f.fileName, disabled: !item.usable });
        b.addEventListener('click', () => UI.busy(b, async () => {
          try {
            const link = await Api.post('/api/library/' + item.entitlementId + '/download-links', { assetId: f.id });
            location.href = link.url;
            setTimeout(() => location.reload(), 1500);
          } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        return el('div', { class: 'actions mb' }, b, el('span', { class: 'muted small', text: formatSize(f.sizeBytes) + (f.versionLabel ? ' \u00B7 ' + f.versionLabel : '') }));
      });
      return el('div', { class: 'card' },
        el('div', { class: 'actions' }, el('h3', { text: item.productTitle }), UI.badge(item.status)),
        el('p', { class: 'muted small', text: 'Added ' + UI.date(item.grantedAt) +
          (item.expiresAt ? ' \u00B7 access until ' + UI.date(item.expiresAt) : '') +
          (item.downloadLimit ? ' \u00B7 ' + item.downloadsUsed + ' of ' + item.downloadLimit + ' downloads used' : '') }),
        item.licenseKeys && item.licenseKeys.length ? el('div', { class: 'mb' }, el('strong', { text: 'Licence key' + (item.licenseKeys.length > 1 ? 's' : '') + ': ' }),
          item.licenseKeys.map((k) => el('span', { class: 'license', text: k }))) : null,
        files,
        !item.usable ? el('p', { class: 'muted small', text: 'This item is no longer available for download.' }) : null);
    }) : el('div', { class: 'card empty' }, 'Your library is empty. Digital purchases appear here right after payment.');
    page(el('p', { class: 'small' }, el('a', { href: '/account', text: '\u2190 My account' })), el('h1', { text: 'My digital library' }), list);
  }

  function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
  }

})();
