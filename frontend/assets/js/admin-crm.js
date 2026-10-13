/* CRM: customer profiles, sales segments, follow-up tasks, email campaigns and automatic reminders.
   Uses the helpers exported by admin.js. Every action is authorised by the server. */
(function () {
  'use strict';
  const { el, mount, money } = UI;
  const K = window.AdminKit;
  const { API, can, view, head, table, pager, onSubmit, input, selectEl, stat } = K;
  const CRM = API + '/crm';

  const TABS = [['overview', 'Overview'], ['customers', 'Customers'], ['tasks', 'Tasks'], ['campaigns', 'Campaigns'], ['automations', 'Automations']];
  const CARD_SEGMENTS = ['HOT_LEADS', 'ABANDONED_CART', 'CLICKED_NO_SALE', 'WISHLIST', 'NEW_SIGNUPS', 'FIRST_TIME', 'REPEAT', 'VIP', 'AT_RISK', 'LAPSED', 'SUBSCRIBERS'];
  const TEMP = { HOT: ['red', 'Hot'], WARM: ['amber', 'Warm'], COLD: ['', 'Cold'] };
  const EVENT_LABEL = { ORDER: 'Order', CART: 'Cart', WISHLIST: 'Wishlist', CLICK: 'Partner click', CONVERSION: 'Partner sale', REVIEW: 'Review', EMAIL: 'Email', SIGNUP: 'Signed up' };
  const EVENT_CLASS = { ORDER: 'green', CONVERSION: 'green', CART: 'amber', WISHLIST: 'purple', CLICK: 'blue', REVIEW: '', EMAIL: '', SIGNUP: '' };

  let segments = null;
  const listState = { segment: 'ALL', q: '', tag: '', sort: '', page: 0 };

  // ------------------------------------------------------------------ helpers

  function tabs(active) {
    return el('nav', { class: 'tabs mb' }, TABS.map(([key, label]) =>
      el('a', { href: '#/crm/' + key, class: key === active ? 'active' : '', text: label })));
  }

  function hashQuery(part) {
    const i = (part || '').indexOf('?');
    return [i < 0 ? part : part.slice(0, i), new URLSearchParams(i < 0 ? '' : part.slice(i + 1))];
  }

  async function segmentDefs() {
    if (!segments) segments = await Api.get(CRM + '/segments');
    return segments;
  }

  function segmentLabel(key) {
    const s = (segments || []).find((x) => x.key === key);
    return s ? s.label : UI.label(key);
  }

  function ago(iso) {
    if (!iso) return '—';
    const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + ' min ago';
    const hours = Math.round(mins / 60);
    if (hours < 24) return hours + (hours === 1 ? ' hour ago' : ' hours ago');
    const days = Math.round(hours / 24);
    if (days < 45) return days + (days === 1 ? ' day ago' : ' days ago');
    return UI.date(iso, false);
  }

  function intentBadge(c) {
    const [cls, text] = TEMP[c.temperature] || TEMP.COLD;
    return el('span', { class: 'badge ' + cls, title: 'Buying-intent score ' + c.intentScore + '/100', text: text + ' · ' + c.intentScore });
  }

  function segmentBadges(keys, max) {
    const shown = keys.filter((k) => k !== 'ALL' && k !== 'SUBSCRIBERS').slice(0, max || 3);
    return shown.map((k) => el('span', { class: 'badge seg', text: segmentLabel(k) }));
  }

  function contactLinks(c, whatsapp) {
    return el('span', { class: 'contact-links' },
      c.email ? el('a', { class: 'btn ghost small', href: 'mailto:' + c.email, title: 'Write an email', text: 'Email' }) : null,
      c.phone ? el('a', { class: 'btn ghost small', href: 'tel:' + c.phone.replace(/[^\d+]/g, ''), title: 'Call', text: 'Call' }) : null,
      whatsapp ? el('a', { class: 'btn ghost small', href: 'https://wa.me/' + whatsapp, target: '_blank', rel: 'noopener', text: 'WhatsApp' }) : null);
  }

  function smtpWarning(ready) {
    if (ready) return null;
    return el('div', { class: 'alert warning' }, 'Email is not set up for this store, so campaigns and reminders cannot be sent. ',
      can('SETTINGS_WRITE') ? el('a', { href: '#/settings', text: 'Set up email (SMTP)' }) : 'Ask a master admin to set up email.');
  }

  function today() {
    const d = new Date();
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }

  function dueText(t) {
    if (!t.dueOn) return el('span', { class: 'muted', text: 'No date' });
    const cls = t.done ? 'muted' : t.dueOn < today() ? 'danger-text' : t.dueOn === today() ? 'warn-text' : '';
    const label = !t.done && t.dueOn < today() ? 'Overdue · ' : !t.done && t.dueOn === today() ? 'Today · ' : '';
    return el('span', { class: cls, text: label + UI.date(t.dueOn + 'T00:00:00', false) });
  }

  // ------------------------------------------------------------------ router

  async function crmRoute(parts) {
    await segmentDefs();
    const [tab, query] = hashQuery(parts[0] || 'overview');
    if (tab === 'customers' && parts[1]) return customerView(parts[1]);
    if (tab === 'campaigns' && parts[1]) {
      const [id, q] = hashQuery(parts[1]);
      return campaignEditor(id, q);
    }
    if (tab === 'customers' && query.get('segment')) {
      Object.assign(listState, { segment: query.get('segment'), q: '', tag: '', page: 0 });
    }
    const name = TABS.some(([k]) => k === tab) ? tab : 'overview';
    const body = await { overview: overviewTab, customers: customersTab, tasks: tasksTab, campaigns: campaignsTab, automations: automationsTab }[name]();
    mount(view(), head('CRM'), tabs(name), body);
  }

  // ================================================================== overview

  async function overviewTab() {
    const o = await Api.get(CRM + '/overview');
    const subscribedPct = o.customers ? Math.round(100 * o.subscribers / o.customers) : 0;
    const byKey = Object.fromEntries(o.segments.map((s) => [s.key, s]));

    const cards = el('div', { class: 'segment-grid' }, CARD_SEGMENTS.map((key) => {
      const s = byKey[key];
      if (!s) return null;
      return el('div', { class: 'segment-card' + (s.count ? '' : ' empty-seg') },
        el('div', { class: 'seg-count', text: String(s.count) }),
        el('div', { class: 'seg-name', text: s.label }),
        el('p', { class: 'muted small', text: s.description }),
        el('div', { class: 'actions' },
          el('a', { class: 'btn secondary small', href: '#/crm/customers?segment=' + key, text: 'View' }),
          can('MARKETING_SEND') && s.subscribed > 0
            ? el('a', { class: 'btn small', href: '#/crm/campaigns/new?segment=' + key, text: 'Email ' + s.subscribed,
              title: 'Email the ' + s.subscribed + ' subscriber' + (s.subscribed === 1 ? '' : 's') + ' in this segment' }) : null));
    }));

    const hot = o.hotList.length ? table(['Customer', 'Intent', 'Saved', 'Last active', 'Suggested next step', ''], o.hotList.map((c) => el('tr', null,
      el('td', null, el('a', { href: '#/crm/customers/' + c.id, text: c.name }), el('div', { class: 'muted small', text: c.email })),
      el('td', null, intentBadge(c)),
      el('td', { class: 'small', text: c.cartItems + ' in cart · ' + c.wishlistItems + ' wishlist' }),
      el('td', { class: 'small', text: ago(c.lastActivityAt) }),
      el('td', { class: 'small', text: c.nextAction }),
      el('td', null, contactLinks(c))))) : el('p', { class: 'muted', text: 'No hot leads right now. They appear when customers save items to their cart or wishlist, or click partner deals.' });

    const due = o.dueTasks.length ? el('ul', { class: 'task-mini' }, o.dueTasks.map((t) => el('li', null,
      dueText(t), ' ', el('a', { href: '#/crm/customers/' + t.customerId, text: t.title }),
      el('span', { class: 'muted small', text: ' · ' + (t.customerName || t.customerEmail || '') }))))
      : el('p', { class: 'muted', text: 'No open follow-ups. Add one from any customer profile.' });

    return [
      smtpWarning(o.smtpReady),
      el('div', { class: 'stats crm-stats' },
        stat('Customers' + (o.newLast30Days ? ' · ' + o.newLast30Days + ' new in 30 days' : ''), o.customers),
        stat('Email subscribers · ' + subscribedPct + '% of customers', o.subscribers, o.customers && subscribedPct < 20 ? 'warn' : ''),
        stat('Hot leads', o.hotLeads),
        stat('Abandoned carts' + (o.abandonedCartItems ? ' · ' + o.abandonedCartItems + (o.abandonedCartItems === 1 ? ' item' : ' items') : ''),
          o.abandonedCarts, o.abandonedCarts ? 'warn' : ''),
        stat('Buyers who came back', o.buyers ? o.repeatRatePercent + '%' : '—'),
        stat('Open follow-ups', o.openTasks)),
      el('div', { class: 'card' }, el('div', { class: 'section-title mb' }, el('h2', { text: 'Contact these first' }),
        el('span', { class: 'muted small', text: 'Highest buying intent right now' })), hot),
      el('div', { class: 'split' },
        el('div', { class: 'card' }, el('h2', { text: 'Segments' }), cards),
        el('div', null,
          el('div', { class: 'card' }, el('div', { class: 'section-title mb' }, el('h2', { text: 'Follow-ups' }), el('span', { class: 'spacer' }),
            el('a', { class: 'small', href: '#/crm/tasks', text: 'All tasks' })), due),
          el('div', { class: 'card' }, el('h2', { text: 'Grow sales with the CRM' }),
            el('ol', { class: 'playbook' },
              el('li', null, el('strong', { text: 'Every day: ' }), 'work through "Contact these first" and your follow-ups.'),
              el('li', null, el('strong', { text: 'Turn on ' }), el('a', { href: '#/crm/automations', text: 'automatic reminders' }), ' so saved carts and lapsed buyers get a nudge.'),
              el('li', null, el('strong', { text: 'Weekly: ' }), 'email "Clicked, no sale" and "Wishlist" customers your best new deals.'),
              el('li', null, el('strong', { text: 'Grow your list: ' }), 'customers can tick "email me offers" when they sign up. Only subscribers receive marketing email.')))))
    ];
  }

  // ================================================================== customers

  async function customersTab() {
    const params = new URLSearchParams({ page: String(listState.page), size: '25' });
    ['segment', 'q', 'tag', 'sort'].forEach((k) => { if (listState[k]) params.set(k, listState[k]); });
    const [result, tagCounts] = await Promise.all([Api.get(CRM + '/customers?' + params), Api.get(CRM + '/tags')]);

    const search = input('q', { type: 'search', placeholder: 'Search name, email or phone', value: listState.q, 'aria-label': 'Search customers' });
    const segment = selectEl('segment', segments.map((s) => [s.key, s.label]), listState.segment, { 'aria-label': 'Segment' });
    const tag = selectEl('tag', [['', 'Any tag'], ...Object.entries(tagCounts).map(([t, n]) => [t, t + ' (' + n + ')'])], listState.tag, { 'aria-label': 'Tag' });
    const sort = selectEl('sort', [['', 'Recently active'], ['intent', 'Buying intent'], ['value', 'Total spend'], ['purchases', 'Purchases'], ['newest', 'Newest sign-ups'], ['name', 'Name']], listState.sort, { 'aria-label': 'Sort' });
    const reload = () => { listState.page = 0; rerender(); };
    let timer = null;
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => { listState.q = search.value.trim(); reload(); }, 350); });
    segment.addEventListener('change', () => { listState.segment = segment.value; reload(); });
    tag.addEventListener('change', () => { listState.tag = tag.value; reload(); });
    sort.addEventListener('change', () => { listState.sort = sort.value; reload(); });

    const seg = segments.find((s) => s.key === listState.segment);
    const rows = result.items.map((c) => {
      const tr = el('tr', { class: 'clickable' },
        el('td', null, el('a', { href: '#/crm/customers/' + c.id, text: c.name }), el('div', { class: 'muted small', text: c.email }),
          c.tags.length ? el('div', { class: 'chip-row tight' }, c.tags.map((t) => el('span', { class: 'tag-chip', text: t }))) : null),
        el('td', null, intentBadge(c)),
        el('td', null, segmentBadges(c.segments)),
        el('td', { class: 'num', text: String(c.purchases) }),
        el('td', { class: 'num', text: c.spendMinor ? money(c.spendMinor) : '—' }),
        el('td', { class: 'small', text: c.cartItems + ' / ' + c.wishlistItems }),
        el('td', { class: 'small', text: ago(c.lastActivityAt) }),
        el('td', null, c.subscribed ? el('span', { class: 'badge green', text: 'Subscribed' }) : el('span', { class: 'muted small', text: '—' })));
      tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/crm/customers/' + c.id; });
      return tr;
    });

    return [
      el('div', { class: 'card' },
        el('div', { class: 'toolbar crm-toolbar mb' }, search, segment, tag, sort,
          el('span', { class: 'muted small', text: result.totalItems + (result.totalItems === 1 ? ' customer' : ' customers') }),
          el('span', { class: 'spacer' }),
          can('MARKETING_SEND') && listState.segment ? el('a', { class: 'btn small secondary', href: '#/crm/campaigns/new?segment=' + listState.segment, text: 'Email this segment' }) : null),
        seg && seg.key !== 'ALL' ? el('p', { class: 'muted small', text: seg.description }) : null,
        table(['Customer', 'Intent', 'Segments', el('th', { class: 'num', text: 'Purchases' }), el('th', { class: 'num', text: 'Spend' }), 'Cart / wishlist', 'Last active', 'Email'],
          rows, 'No customers match. Customers appear here when they sign up on this store or use it.'),
        pager(result, (p) => { listState.page = p; rerender(); }))
    ];
  }

  async function rerender() {
    mount(view(), head('CRM'), tabs('customers'), await customersTab());
  }

  // ------------------------------------------------------------------ customer profile

  async function customerView(id) {
    const d = await Api.get(CRM + '/customers/' + encodeURIComponent(id));
    const c = d.customer;
    const writable = can('CRM_WRITE');

    const timeline = d.timeline.length ? el('ol', { class: 'timeline' }, d.timeline.map((e) => el('li', null,
      el('span', { class: 'badge ' + (EVENT_CLASS[e.type] || ''), text: EVENT_LABEL[e.type] || e.type }),
      el('div', { class: 'tl-body' },
        el('div', null, e.productId ? el('a', { href: '#/products/' + e.productId, text: e.title }) : el('strong', { text: e.title }),
          e.amountMinor != null ? el('span', { class: 'muted', text: ' · ' + money(e.amountMinor) }) : null),
        el('div', { class: 'muted small' }, e.type === 'ORDER' ? UI.badge(e.detail) : e.detail, ' · ', UI.date(e.at)))))
    ) : el('p', { class: 'muted', text: 'No activity on this store yet.' });

    mount(view(),
      head(c.name || c.email, el('a', { class: 'btn secondary small', href: '#/crm/customers', text: '← All customers' })),
      el('div', { class: 'profile-head card' },
        el('div', null,
          el('div', null, el('a', { href: 'mailto:' + c.email, text: c.email }), c.phone ? el('span', { class: 'muted', text: ' · ' + c.phone }) : null),
          el('div', { class: 'chip-row' }, intentBadge(c), segmentBadges(c.segments, 6),
            c.subscribed ? el('span', { class: 'badge green', text: 'Subscribed to emails' }) : el('span', { class: 'badge', title: 'Campaigns and reminders do not reach this customer', text: 'Not subscribed' }),
            c.active ? null : el('span', { class: 'badge red', text: 'Account disabled' }))),
        el('span', { class: 'spacer' }), contactLinks(c, d.whatsappNumber)),
      el('div', { class: 'alert info next-action' }, el('strong', { text: 'Suggested next step: ' }), c.nextAction,
        writable ? el('button', { class: 'btn small secondary', type: 'button', text: 'Make it a task', on: { click: () => {
          const f = document.getElementById('task-title');
          if (f) { f.value = c.nextAction.replace(/\.$/, ''); f.focus(); }
        } } }) : null),
      el('div', { class: 'stats crm-stats' },
        stat('Purchases', c.purchases),
        stat('Total spend', c.spendMinor ? money(c.spendMinor) : '—'),
        c.commissionMinor ? stat('Commission earned', money(c.commissionMinor)) : null,
        stat('In cart', c.cartItems),
        stat('Wishlist', c.wishlistItems),
        stat('Partner clicks', c.clicks),
        stat('Customer since', c.signedUpAt ? UI.date(c.signedUpAt, false) : '—')),
      el('div', { class: 'split crm-split' },
        el('div', { class: 'card' }, el('h2', { text: 'Activity' }), timeline),
        el('div', null, tagsCard(c, writable), tasksCard(c, d.tasks, writable), notesCard(c, d.notes, writable))));
  }

  function tagsCard(c, writable) {
    let tags = c.tags.slice();
    const list = el('div', { class: 'chip-row' });
    const save = async (next) => {
      try {
        tags = await Api.put(CRM + '/customers/' + c.id + '/tags', { tags: next });
        render();
      } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
    };
    const render = () => mount(list, tags.length ? tags.map((t) => el('span', { class: 'tag-chip' }, t,
      writable ? el('button', { type: 'button', class: 'chip-x', 'aria-label': 'Remove tag ' + t, text: '×', on: { click: () => save(tags.filter((x) => x !== t)) } }) : null))
      : el('span', { class: 'muted small', text: 'No tags yet.' }));
    render();
    const field = input('tag', { placeholder: 'e.g. bulk buyer, gifting', maxlength: '30', 'aria-label': 'New tag' });
    const form = el('form', { class: 'inline-form mt' }, field, el('button', { class: 'btn small', type: 'submit', text: 'Add tag' }));
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const v = field.value.trim();
      if (v) { field.value = ''; save(tags.concat(v)); }
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Tags' }), list, writable ? form : null);
  }

  function tasksCard(c, tasks, writable) {
    const rows = tasks.map((t) => taskRow(t, writable, () => customerView(c.id), false));
    const form = el('form', { class: 'mt' },
      UI.field('New follow-up', input('title', { id: 'task-title', required: true, maxlength: '200', placeholder: 'e.g. Call about the saved earbuds' })),
      el('div', { class: 'inline-form' }, UI.field('Due', input('dueOn', { type: 'date', value: today() })),
        el('button', { class: 'btn small', type: 'submit', text: 'Add task' })));
    onSubmit(form, async (v) => {
      await Api.post(CRM + '/tasks', { customerId: c.id, title: v.title, dueOn: v.dueOn || null });
      UI.toast('Task added', 'success');
      customerView(c.id);
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Follow-ups' }),
      rows.length ? el('ul', { class: 'task-list' }, rows) : el('p', { class: 'muted small', text: 'No follow-ups for this customer.' }),
      writable ? form : null);
  }

  function taskRow(t, writable, onChange, showCustomer) {
    const box = el('input', { type: 'checkbox', checked: t.done, disabled: !writable, 'aria-label': 'Done' });
    box.addEventListener('change', async () => {
      try { await Api.patch(CRM + '/tasks/' + t.id, { done: box.checked }); onChange(); }
      catch (e) { box.checked = !box.checked; UI.toast(UI.errorMessage(e), 'error'); }
    });
    const del = writable ? el('button', { class: 'btn ghost small', type: 'button', text: 'Delete', on: { click: async () => {
      if (!confirm('Delete this task?')) return;
      try { await Api.del(CRM + '/tasks/' + t.id); onChange(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
    } } }) : null;
    return el('li', { class: t.done ? 'done' : '' }, box,
      el('div', { class: 'task-body' },
        el('div', { class: 'task-title', text: t.title }),
        el('div', { class: 'small' }, dueText(t),
          showCustomer ? [' · ', el('a', { href: '#/crm/customers/' + t.customerId, text: t.customerName || t.customerEmail || 'Customer' })] : null,
          el('span', { class: 'muted', text: ' · by ' + t.createdByEmail }))), del);
  }

  function notesCard(c, notes, writable) {
    const list = notes.length ? notes.map((n) => el('div', { class: 'note-item' },
      el('div', { class: 'note-body', text: n.body }),
      el('div', { class: 'muted small' }, n.adminEmail + ' · ' + UI.date(n.createdAt),
        n.deletable && writable ? el('button', { class: 'btn ghost small', type: 'button', text: 'Delete', on: { click: async () => {
          if (!confirm('Delete this note?')) return;
          try { await Api.del(CRM + '/notes/' + n.id); customerView(c.id); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        } } }) : null)))
      : el('p', { class: 'muted small', text: 'No notes yet.' });
    const form = el('form', { class: 'mt' },
      el('textarea', { name: 'body', rows: '3', maxlength: '2000', required: true, placeholder: 'What did you learn? Preferences, budget, sizes, what they asked about…', 'aria-label': 'New note' }),
      el('button', { class: 'btn small mt', type: 'submit', text: 'Add note' }));
    onSubmit(form, async (v) => {
      await Api.post(CRM + '/customers/' + c.id + '/notes', { body: v.body });
      customerView(c.id);
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Notes' }), el('p', { class: 'muted small', text: 'Private to your store’s staff.' }),
      list, writable ? form : null);
  }

  // ================================================================== tasks

  let showDone = false;

  async function tasksTab() {
    const tasks = await Api.get(CRM + '/tasks?done=' + showDone);
    const writable = can('CRM_WRITE');
    const toggle = el('label', { class: 'check' }, el('input', { type: 'checkbox', checked: showDone, on: { change: (e) => { showDone = e.target.checked; reloadTasks(); } } }), 'Show completed');
    return [el('div', { class: 'card' },
      el('div', { class: 'toolbar mb' }, el('h2', { text: showDone ? 'Recently completed' : 'Open follow-ups' }), el('span', { class: 'spacer' }), toggle),
      tasks.length ? el('ul', { class: 'task-list' }, tasks.map((t) => taskRow(t, writable, reloadTasks, true)))
        : el('p', { class: 'muted', text: showDone ? 'Nothing completed yet.' : 'No open follow-ups. Open a customer profile to add one.' }))];
  }

  async function reloadTasks() {
    mount(view(), head('CRM'), tabs('tasks'), await tasksTab());
  }

  // ================================================================== campaigns

  const TEMPLATES = {
    deals: ['New deals', 'ALL', 'Fresh deals picked for you at {store}',
      'Hi {name},\n\nWe just added new deals we think you will like:\n\n- [Product name] - [why it is a good deal]\n- [Product name] - [why it is a good deal]\n\nSee them all: {link}\n\nThanks for shopping with {store}!'],
    cart: ['Saved-cart nudge', 'ABANDONED_CART', '{name}, your saved items are waiting',
      'Hi {name},\n\nYou saved a few items in your cart at {store}. Prices and stock change quickly, so now is a good time to take another look.\n\nYour cart: {link}/cart\n\nQuestions? Just reply to this email.'],
    clicked: ['Similar deals', 'CLICKED_NO_SALE', 'Still looking? More deals like the one you checked',
      'Hi {name},\n\nYou recently checked out a deal on {store}. Here are a few similar picks, some at an even better price:\n\n- [Product name]\n- [Product name]\n\nBrowse more: {link}'],
    winback: ['Win-back', 'AT_RISK', 'We miss you, {name}',
      'Hi {name},\n\nIt has been a while! Here is what is new at {store}:\n\n- [Product name]\n- [Product name]\n\nCome take a look: {link}'],
    thanks: ['Thank-you + next purchase', 'FIRST_TIME', 'Thank you, {name} – something you might like next',
      'Hi {name},\n\nThank you for your first purchase with {store}! Customers who bought the same item also liked:\n\n- [Product name]\n- [Product name]\n\nSee more: {link}']
  };

  async function campaignsTab() {
    const [list, auto] = await Promise.all([Api.get(CRM + '/campaigns'), Api.get(CRM + '/automations')]);
    const rows = list.map((c) => {
      const r = c.results;
      const tr = el('tr', { class: 'clickable' },
        el('td', null, el('a', { href: '#/crm/campaigns/' + c.id, text: c.name }), el('div', { class: 'muted small', text: c.subject })),
        el('td', { class: 'small', text: c.segmentLabel }),
        el('td', null, UI.badge(c.status === 'SENT' ? 'SENT' : c.status === 'SENDING' ? 'PENDING' : 'DRAFT', UI.label(c.status))),
        el('td', { class: 'small', text: c.sentAt ? UI.date(c.sentAt) + ' · ' + c.recipients + ' sent' : '—' }),
        el('td', { class: 'small' }, r ? resultsLine(r) : '—'));
      tr.addEventListener('click', (e) => { if (e.target.tagName !== 'A') location.hash = '#/crm/campaigns/' + c.id; });
      return tr;
    });
    return [
      smtpWarning(auto.smtpReady),
      el('div', { class: 'card' },
        el('div', { class: 'toolbar mb' }, el('h2', { text: 'Email campaigns' }), el('span', { class: 'spacer' }),
          can('MARKETING_SEND') ? el('a', { class: 'btn', href: '#/crm/campaigns/new', text: 'New campaign' }) : null),
        el('p', { class: 'muted small', text: auto.subscribers + ' customer' + (auto.subscribers === 1 ? ' has' : 's have') + ' opted in to marketing email from this store. Campaigns only go to them.' }),
        table(['Campaign', 'Segment', 'Status', 'Sent', 'Results (7 days after sending)'], rows, 'No campaigns yet. Start one from a template.'))
    ];
  }

  function resultsLine(r) {
    const parts = [r.clicks + ' click' + (r.clicks === 1 ? '' : 's') + ' by ' + r.clickers];
    if (r.conversions) parts.push(r.conversions + ' partner sale' + (r.conversions === 1 ? '' : 's') + (r.commissionMinor ? ' · ' + money(r.commissionMinor) + ' commission' : ''));
    if (r.orders) parts.push(r.orders + ' order' + (r.orders === 1 ? '' : 's') + ' · ' + money(r.revenueMinor));
    return [parts.join(' · '), r.windowOpen ? el('span', { class: 'badge amber ml', text: 'Still counting' }) : null];
  }

  async function campaignEditor(id, query) {
    const isNew = id === 'new';
    const c = isNew ? null : await Api.get(CRM + '/campaigns/' + encodeURIComponent(id));
    const editable = can('MARKETING_SEND') && (isNew || c.status === 'DRAFT');
    const back = el('a', { class: 'btn secondary small', href: '#/crm/campaigns', text: '← Campaigns' });

    if (!editable) {
      const r = c.results;
      mount(view(), head(c.name, back),
        el('div', { class: 'stats' },
          stat('Status', UI.label(c.status)), stat('Sent to', c.recipients), stat('Partner clicks', r ? r.clicks : '—'),
          stat('Partner sales', r ? r.conversions : '—'), stat('Commission', r && r.commissionMinor ? money(r.commissionMinor) : '—'),
          stat('Orders', r ? r.orders + (r.revenueMinor ? ' · ' + money(r.revenueMinor) : '') : '—')),
        r && r.windowOpen ? el('p', { class: 'muted small', text: 'Results keep counting for 7 days after sending.' }) : null,
        el('div', { class: 'card' }, el('dl', { class: 'kv' },
          el('dt', { text: 'Segment' }), el('dd', { text: c.segmentLabel }),
          el('dt', { text: 'Sent' }), el('dd', { text: c.sentAt ? UI.date(c.sentAt) + ' by ' + c.createdByEmail : 'Sending…' }),
          el('dt', { text: 'Subject' }), el('dd', { text: c.subject }),
          c.couponCode ? [el('dt', { text: 'Coupon' }), el('dd', { text: c.couponCode })] : null),
          el('pre', { class: 'email-preview mt', text: c.body })));
      return;
    }

    const segment = selectEl('segment', segments.map((s) => [s.key, s.label]), c ? c.segment : (query && query.get('segment')) || 'ALL');
    const name = input('name', { required: true, maxlength: '120', value: c ? c.name : '' });
    const subject = input('subject', { required: true, maxlength: '200', value: c ? c.subject : '' });
    const body = el('textarea', { name: 'body', rows: '12', required: true, maxlength: '10000' });
    body.value = c ? c.body : '';
    const coupon = input('couponCode', { maxlength: '40', class: 'upper', value: c && c.couponCode ? c.couponCode : '', placeholder: 'Optional, e.g. WELCOME10' });
    const audience = el('div', { class: 'audience small' });
    const preview = el('pre', { class: 'email-preview' });
    const template = selectEl('template', [['', 'Start from a template…'], ...Object.entries(TEMPLATES).map(([k, t]) => [k, t[0]])], '');

    let store = K.SF_NAME;
    let storeUrl = location.origin + '/' + K.SF;
    const refreshPreview = () => {
      const fill = (s) => s.replaceAll('{name}', 'Asha').replaceAll('{store}', store).replaceAll('{link}', storeUrl)
        .replaceAll('{coupon}', coupon.value.trim().toUpperCase() || '[no coupon chosen]');
      preview.textContent = 'Subject: ' + fill(subject.value) + '\n\n' + fill(body.value)
        + '\n\n--\nYou are receiving this because you subscribed to offers from ' + store + '.\nUnsubscribe with one click: [personal link]';
    };
    const refreshAudience = async () => {
      try {
        const a = await Api.get(CRM + '/campaigns/audience?segment=' + segment.value);
        store = a.storeName;
        storeUrl = a.storeUrl;
        refreshPreview();
        mount(audience, el('strong', { text: a.willReceive + ' subscriber' + (a.willReceive === 1 ? '' : 's') + ' will receive this' }),
          ' (' + a.inSegment + ' customer' + (a.inSegment === 1 ? '' : 's') + ' in "' + a.segmentLabel + '"). ',
          a.willReceive === 0 ? el('span', { class: 'warn-text', text: 'Nobody in this segment has opted in to marketing email yet.' }) : null);
      } catch (e) { mount(audience, UI.errorAlert(e)); }
    };
    template.addEventListener('change', () => {
      const t = TEMPLATES[template.value];
      if (!t) return;
      if ((subject.value || body.value) && !confirm('Replace the subject and message with this template?')) { template.value = ''; return; }
      if (!name.value) name.value = t[0];
      segment.value = t[1];
      subject.value = t[2];
      body.value = t[3];
      refreshAudience();
      refreshPreview();
    });
    segment.addEventListener('change', refreshAudience);
    [subject, body, coupon].forEach((f) => f.addEventListener('input', refreshPreview));

    const payload = () => ({ name: name.value, segment: segment.value, subject: subject.value, body: body.value, couponCode: coupon.value || null });
    const saveDraft = async () => {
      const saved = isNew ? await Api.post(CRM + '/campaigns', payload()) : await Api.put(CRM + '/campaigns/' + c.id, payload());
      return saved;
    };
    const msg = el('div', { class: 'form-msg' });
    const act = (label, cls, fn) => {
      const b = el('button', { class: 'btn ' + (cls || ''), type: 'button', text: label });
      b.addEventListener('click', () => UI.busy(b, async () => {
        mount(msg);
        try { await fn(); } catch (e) { if (!K.handleAuthError(e)) mount(msg, UI.errorAlert(e)); }
      }));
      return b;
    };
    const saveBtn = act('Save draft', 'secondary', async () => {
      const saved = await saveDraft();
      UI.toast('Draft saved', 'success');
      if (isNew) location.hash = '#/crm/campaigns/' + saved.id;
    });
    const testBtn = act('Send me a test', 'secondary', async () => {
      const saved = await saveDraft();
      await Api.post(CRM + '/campaigns/' + saved.id + '/test', {});
      UI.toast('Test email sent to you', 'success');
      if (isNew) location.hash = '#/crm/campaigns/' + saved.id;
    });
    const sendBtn = act('Send now', '', async () => {
      const a = await Api.get(CRM + '/campaigns/audience?segment=' + segment.value);
      if (!confirm('Send "' + (name.value || 'this campaign') + '" to ' + a.willReceive + ' subscriber' + (a.willReceive === 1 ? '' : 's') + ' now? This cannot be undone.')) return;
      const saved = await saveDraft();
      await Api.post(CRM + '/campaigns/' + saved.id + '/send');
      UI.toast('Campaign is being sent', 'success');
      location.hash = '#/crm/campaigns/' + saved.id;
    });
    const delBtn = !isNew ? act('Delete draft', 'ghost', async () => {
      if (!confirm('Delete this draft?')) return;
      await Api.del(CRM + '/campaigns/' + c.id);
      location.hash = '#/crm/campaigns';
    }) : null;

    mount(view(), head(isNew ? 'New campaign' : 'Edit campaign', back),
      el('div', { class: 'split crm-split' },
        el('div', { class: 'card' }, msg,
          UI.field('Template', template, 'Fill the form with a proven message for a common goal, then edit the [placeholders].'),
          UI.field('Campaign name (internal)', name),
          UI.field('Send to', segment), audience,
          UI.field('Subject', subject),
          UI.field('Message', body, 'Plain text. {name} = first name, {store} = store name, {link} = store address' + (K.affiliateOnly ? '.' : ', {coupon} = coupon code.')),
          K.affiliateOnly ? null : UI.field('Coupon (optional)', coupon, 'Create coupons under Coupons. Orders that use it within 7 days are counted in the results.'),
          el('div', { class: 'actions mt' }, sendBtn, testBtn, saveBtn, delBtn)),
        el('div', { class: 'card' }, el('h2', { text: 'Preview' }), preview,
          el('p', { class: 'muted small mt', text: 'Every email automatically ends with why the customer is receiving it and a one-click unsubscribe link.' }))));
    refreshAudience();
    refreshPreview();
  }

  // ================================================================== automations

  const DAYS = [['1', 'Monday'], ['2', 'Tuesday'], ['3', 'Wednesday'], ['4', 'Thursday'], ['5', 'Friday'], ['6', 'Saturday'], ['7', 'Sunday']];

  /** Every free automation in one place: customer emails, store upkeep and the Telegram channel. */
  async function automationsTab() {
    const a = await Api.get(CRM + '/automations');
    const writable = can('MARKETING_SEND');
    const sent = a.sentLast30Days || {};
    const box = (name, checked) => el('input', { type: 'checkbox', name, checked, disabled: !writable });
    const num = (name, value, min, max) => input(name, { type: 'number', min: String(min), max: String(max), value: String(value), disabled: !writable });
    const item = (checkbox, title, count, text, fields) => el('div', { class: 'automation' },
      el('label', { class: 'check automation-head' }, checkbox, el('strong', { text: title }),
        count == null ? null : el('span', { class: 'muted small', text: count + ' sent in the last 30 days' })),
      el('p', { class: 'muted small', text }),
      fields ? el('div', { class: 'inline-form' }, fields) : null);

    const f = {
      cart: box('cartReminderEnabled', a.cartReminderEnabled), winback: box('winbackEnabled', a.winbackEnabled),
      welcome: box('welcomeEnabled', a.welcomeEnabled), weekly: box('weeklyDigestEnabled', a.weeklyDigestEnabled),
      reviews: box('reviewRequestEnabled', a.reviewRequestEnabled), summary: box('dailySummaryEnabled', a.dailySummaryEnabled),
      links: box('linkCheckEnabled', a.linkCheckEnabled), tg: box('telegramEnabled', a.telegramEnabled),
      tgNew: box('telegramPostNew', a.telegramPostNew), tgDrops: box('telegramPostDrops', a.telegramPostDrops)
    };
    const token = input('telegramToken', { type: 'password', autocomplete: 'off', placeholder: a.telegramTokenSet ? 'Saved – type a new one to replace' : '123456789:AA…', disabled: !writable });
    const chat = input('telegramChatId', { value: a.telegramChatId || '', placeholder: '@your_channel', maxlength: '120', disabled: !writable });

    const form = el('form', null,
      el('h2', { text: 'Customer emails' }),
      el('p', { class: 'muted small', text: 'Only customers who opted in receive these (' + a.subscribers + ' so far). Each email goes out once and ends with a one-click unsubscribe link.' }),
      item(f.welcome, 'Welcome email', sent.WELCOME, 'Greets new subscribers with three of today’s best deals.'),
      item(f.cart, 'Saved-cart reminder', sent.CART_REMINDER, 'Reminds a subscriber once when items sit in their cart this long with no purchase or partner click (at most once every 3 days; carts older than 14 days are left alone).',
        UI.field('Send after (hours)', num('cartReminderHours', a.cartReminderHours, 1, 168))),
      item(f.weekly, 'Weekly top deals', sent.WEEKLY_DIGEST, 'Every week from 10:00, sends subscribers your best deals: price drops first, then the biggest discounts and what shoppers click most.',
        UI.field('Day', selectEl('weeklyDigestDay', DAYS, String(a.weeklyDigestDay), { disabled: !writable }))),
      item(f.reviews, 'Review requests', sent.REVIEW_REQUEST, 'Asks a subscriber for a review some days after a partner sale or a delivered order, once per purchase. New reviews wait for your approval in Reviews.',
        UI.field('Days after the purchase', num('reviewRequestDays', a.reviewRequestDays, 2, 60))),
      item(f.winback, 'Win-back email', sent.WINBACK, 'Emails a subscriber who bought before but not for this many days, with your three newest products (at most once every 90 days).',
        [UI.field('Days since last purchase', num('winbackDays', a.winbackDays, 14, 365)),
          a.affiliateOnly ? null : UI.field('Coupon (optional)', input('winbackCouponCode', { class: 'upper', maxlength: '40', value: a.winbackCouponCode || '', disabled: !writable }))]),
      el('p', { class: 'muted small', text: 'Price-drop alerts are always on: customers who saved an item in their cart or wishlist hear about a drop of 5% or more (never for stores whose prices are hidden).' }),

      el('h2', { class: 'mt', text: 'Store upkeep' }),
      item(f.links, 'Nightly dead-link check', null, 'Every night at 03:30 each partner link is visited. A deal whose page is gone on two nights in a row is hidden from shoppers until it works again. ' + (a.brokenLinks ? a.brokenLinks + ' link(s) are hidden right now.' : 'No dead links right now.')),
      item(f.summary, 'Daily summary email to you', null, 'Every morning: partner clicks and sales, new customers and subscribers, reviews waiting for approval, dead links, prices to re-check and follow-ups due. Goes to the master admins.',
        UI.field('Send from (hour, India time)', num('dailySummaryHour', a.dailySummaryHour, 0, 23))),

      el('h2', { class: 'mt', text: 'Telegram channel' }),
      item(f.tg, 'Post deals to Telegram', null, 'Posts new deals and price drops to your Telegram channel automatically, with a link to the deal on this site. Free: uses Telegram’s bot service.',
        [UI.field('Bot token', token), UI.field('Channel', chat)]),
      el('div', { class: 'automation-sub' },
        el('label', { class: 'check' }, f.tgNew, 'Post new deals when you publish them'),
        el('label', { class: 'check' }, f.tgDrops, 'Post price drops of 5% or more')),
      el('details', { class: 'muted small mb' }, el('summary', { text: 'How to set up Telegram (5 minutes, free)' }),
        el('ol', null,
          el('li', { text: 'In Telegram, open @BotFather, send /newbot and follow the steps. Copy the token it gives you.' }),
          el('li', { text: 'Create a public channel (or use yours) and add the new bot as an administrator that can post messages.' }),
          el('li', { text: 'Paste the token and the channel’s @username here, save, then click "Send test message".' }))),
      writable ? el('div', { class: 'actions mt' },
        el('button', { class: 'btn', type: 'submit', text: 'Save automations' }),
        el('button', { class: 'btn secondary', type: 'button', text: 'Send test message', on: { click: (e) => runAction(e.target, async () => {
          await Api.post(CRM + '/automations/telegram-test');
          UI.toast('Test message sent to Telegram', 'success');
        }) } }),
        el('button', { class: 'btn secondary', type: 'button', text: 'Check links now', on: { click: (e) => runAction(e.target, async () => {
          const r = await Api.post(CRM + '/automations/link-check');
          UI.toast(r.checked + ' links checked: ' + r.ok + ' live, ' + r.broken + ' hidden, ' + r.unknown + ' could not be verified', 'success');
          reloadAutomations();
        }) } })) : null);
    onSubmit(form, async (v) => {
      await Api.put(CRM + '/automations', {
        cartReminderEnabled: f.cart.checked, cartReminderHours: K.intOrNull(v.cartReminderHours),
        winbackEnabled: f.winback.checked, winbackDays: K.intOrNull(v.winbackDays),
        winbackCouponCode: a.affiliateOnly ? null : (v.winbackCouponCode || ''),
        welcomeEnabled: f.welcome.checked, weeklyDigestEnabled: f.weekly.checked, weeklyDigestDay: K.intOrNull(v.weeklyDigestDay),
        reviewRequestEnabled: f.reviews.checked, reviewRequestDays: K.intOrNull(v.reviewRequestDays),
        dailySummaryEnabled: f.summary.checked, dailySummaryHour: K.intOrNull(v.dailySummaryHour),
        linkCheckEnabled: f.links.checked,
        telegramToken: token.value.trim() ? token.value.trim() : null, telegramChatId: chat.value.trim(),
        telegramEnabled: f.tg.checked, telegramPostNew: f.tgNew.checked, telegramPostDrops: f.tgDrops.checked
      });
      reloadAutomations();
    }, 'Automations saved.');
    return [
      smtpWarning(a.smtpReady),
      el('div', { class: 'card medium' }, el('p', { class: 'muted', text: 'These run on their own every hour (link check: every night). Nothing to buy: emails go through your store’s email account and Telegram posts through Telegram’s free bot service.' }), form)
    ];
  }

  function runAction(button, fn) {
    UI.busy(button, async () => {
      try { await fn(); } catch (e) { if (!K.handleAuthError(e)) UI.toast(UI.errorMessage(e), 'error'); }
    });
  }

  async function reloadAutomations() {
    mount(view(), head('CRM'), tabs('automations'), await automationsTab());
  }

  K.register('crm', 'CRM', 'CRM_READ', crmRoute, 'dashboard');
})();
