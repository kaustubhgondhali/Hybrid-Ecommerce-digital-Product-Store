/* DOM helpers. All dynamic text is inserted with textContent (never innerHTML) to prevent XSS. */
(function () {
  'use strict';

  function el(tag, props, ...children) {
    const node = document.createElement(tag);
    const p = props || {};
    for (const [key, value] of Object.entries(p)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'on') Object.entries(value).forEach(([evt, fn]) => node.addEventListener(evt, fn));
      else if (key === 'dataset') Object.assign(node.dataset, value);
      else if (key === 'value') node.value = value;
      else if (key === 'checked' || key === 'disabled' || key === 'hidden' || key === 'selected' || key === 'required' || key === 'multiple') node[key] = Boolean(value);
      else node.setAttribute(key, value === true ? '' : String(value));
    }
    append(node, children);
    return node;
  }

  function append(node, children) {
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) continue;
      node.appendChild(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function mount(target, ...children) {
    const node = typeof target === 'string' ? document.querySelector(target) : target;
    node.replaceChildren();
    return append(node, children);
  }

  const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
  function money(minor, currency) {
    if (minor === null || minor === undefined) return '';
    if (currency && currency !== 'INR') {
      return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(minor / 100);
    }
    return inr.format(minor / 100);
  }

  function toMinor(value) {
    if (value === null || value === undefined || String(value).trim() === '') return null;
    const n = Number(String(value).replace(/,/g, '').trim());
    if (!Number.isFinite(n) || n < 0) throw new Error('Enter a valid amount');
    return Math.round(n * 100);
  }

  function fromMinor(minor) {
    return minor === null || minor === undefined ? '' : (minor / 100).toFixed(2);
  }

  function date(iso, withTime) {
    if (!iso) return '';
    const d = new Date(iso);
    return withTime === false
      ? d.toLocaleDateString('en-IN', { dateStyle: 'medium' })
      : d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
  }

  function param(name) {
    return new URLSearchParams(location.search).get(name);
  }

  function host() {
    let h = document.querySelector('.toast-host');
    if (!h) {
      h = el('div', { class: 'toast-host', role: 'status', 'aria-live': 'polite' });
      document.body.appendChild(h);
    }
    return h;
  }

  function toast(message, kind) {
    const t = el('div', { class: 'toast ' + (kind || ''), text: message });
    host().appendChild(t);
    setTimeout(() => t.remove(), 4500);
  }

  function errorMessage(err) {
    if (!err) return 'Something went wrong';
    return err.message || 'Something went wrong';
  }

  /** Renders an error (including server-provided problem lists / field errors) as an alert element. */
  function errorAlert(err) {
    const box = el('div', { class: 'alert error', role: 'alert' }, errorMessage(err));
    const data = (err && err.data) || {};
    const items = [];
    if (Array.isArray(data.problems)) items.push(...data.problems);
    if (Array.isArray(data.errors)) data.errors.forEach((e) => items.push((e.field ? e.field + ': ' : '') + e.message));
    if (data.fields && typeof data.fields === 'object') Object.entries(data.fields).forEach(([k, v]) => items.push(k + ': ' + v));
    if (items.length) box.appendChild(el('ul', null, items.map((i) => el('li', { text: i }))));
    return box;
  }

  function showError(container, err) {
    const node = typeof container === 'string' ? document.querySelector(container) : container;
    if (!node) { toast(errorMessage(err), 'error'); return; }
    mount(node, errorAlert(err));
    node.hidden = false;
  }

  function formValues(form) {
    const data = {};
    new FormData(form).forEach((value, key) => {
      if (typeof value !== 'string') return;
      const input = form.elements[key];
      data[key] = input && input.type === 'password' ? value : value.trim();
    });
    form.querySelectorAll('input[type="checkbox"]').forEach((c) => { if (c.name) data[c.name] = c.checked; });
    return data;
  }

  async function busy(button, fn) {
    if (button) button.disabled = true;
    try {
      return await fn();
    } finally {
      if (button) button.disabled = false;
    }
  }

  const STATUS_COLORS = {
    PUBLISHED: 'green', ACTIVE: 'green', PAID: 'green', CONFIRMED: 'green', USED: 'green', COMPLETED: 'green', DELIVERED: 'green', SENT: 'green', SUCCEEDED: 'green', APPROVED: 'green', READY: 'green',
    DRAFT: 'amber', PENDING_PAYMENT: 'amber', PENDING: 'amber', CREATED: 'amber', SKIPPED: 'amber', PARTIALLY_REFUNDED: 'amber',
    SHIPPED: 'blue', DELIVERED_DIGITAL: 'blue',
    ARCHIVED: '', EXPIRED: '', CANCELLED: '', RETIRED: '',
    FAILED: 'red', REFUNDED: 'red', REVOKED: 'red', DISABLED: 'red', NEEDS_ATTENTION: 'red', REVERSED: 'red'
  };

  function label(s) {
    return String(s || '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
  }

  function badge(status, text) {
    return el('span', { class: 'badge ' + (STATUS_COLORS[status] || ''), text: text || label(status) });
  }

  function newKey() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    return 'k' + Date.now().toString(36) + Math.random().toString(36).slice(2, 12);
  }

  let fieldSeq = 0;
  function field(labelText, input, hint) {
    const label = el('label', { text: labelText });
    if (input && input.tagName && /^(INPUT|SELECT|TEXTAREA)$/.test(input.tagName)) {
      if (!input.id) input.id = 'f' + (++fieldSeq);
      label.htmlFor = input.id;
    }
    return el('div', { class: 'field' }, label, input, hint ? el('div', { class: 'hint', text: hint }) : null);
  }

  window.UI = { el, append, mount, money, toMinor, fromMinor, date, param, toast, errorMessage, errorAlert, showError,
    formValues, busy, badge, label, newKey, field };
})();
