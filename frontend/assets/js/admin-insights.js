/* Admin: Sales & demand — which products sell and which shoppers want, for today, this week, this month, 6 months or this year. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, SF, view, head, table } = K;

  const PERIODS = [['today', 'Today'], ['week', 'This week'], ['month', 'This month'], ['6months', 'Last 6 months'], ['year', 'This year']];
  const num = (n) => Number(n || 0).toLocaleString('en-IN');
  const plural = (n, word) => num(n) + ' ' + word + (Number(n) === 1 ? '' : 's');
  const pct = (part, whole) => (whole ? Math.round((part * 1000) / whole) / 10 + '%' : '—');
  const ext = (href, text) => el('a', { href, target: '_blank', rel: 'noopener noreferrer', text });
  const enc = encodeURIComponent;

  /** "+24%", "−8%", "New" or nothing, coloured. */
  function trend(now, before, changePercent, isNew) {
    if (isNew || (!before && now)) return el('span', { class: 'trend up', text: 'New' });
    if (changePercent === undefined || changePercent === null) return null;
    if (changePercent === 0) return el('span', { class: 'trend flat', text: '±0%' });
    return el('span', { class: 'trend ' + (changePercent > 0 ? 'up' : 'down'), text: (changePercent > 0 ? '▲ ' : '▼ ') + Math.abs(changePercent) + '%' });
  }

  function change(now, before) {
    if (!before) return now ? null : 0;
    return Math.round(((now - before) * 100) / before);
  }

  function kpi(label, now, before, format, hint) {
    const f = format || num;
    return el('div', { class: 'stat kpi' },
      el('div', { class: 'value', text: f(now) }),
      el('div', { class: 'label', text: label }),
      el('div', { class: 'kpi-foot' }, trend(now, before, change(now, before), !before && now > 0),
        el('span', { class: 'muted small', text: hint || ('before: ' + f(before)) })));
  }

  /** Width or height in % set through the CSS object model (inline style attributes are blocked by the CSP). */
  function sized(node, prop, value) {
    node.style[prop] = Math.max(0, Math.min(100, value)) + '%';
    return node;
  }

  // ------------------------------------------------------------------ research links

  function trendsUrl(term, r) {
    const preset = { today: 'now 1-d', week: 'now 7-d', month: 'today 1-m' }[r.period];
    return 'https://trends.google.com/trends/explore?geo=IN&q=' + enc(term) + '&date=' + enc(preset || r.from + ' ' + r.to);
  }

  function researchLinks(term, r) {
    return el('span', { class: 'research-links' },
      ext(trendsUrl(term, r), 'Google Trends'),
      ext('https://www.amazon.in/s?k=' + enc(term) + '&s=exact-aware-popularity-rank', 'Amazon best sellers'),
      SF === 'digital'
        ? ext('https://www.udemy.com/courses/search/?q=' + enc(term) + '&sort=popularity', 'Udemy popular')
        : ext('https://www.flipkart.com/search?q=' + enc(term) + '&sort=popularity', 'Flipkart popular'));
  }

  function marketCard(r) {
    const general = SF === 'digital'
      ? [['https://www.amazon.in/gp/bestsellers/digital-text', 'Amazon.in Kindle best sellers'],
        ['https://www.amazon.in/gp/bestsellers/software', 'Amazon.in software best sellers'],
        ['https://www.udemy.com/courses/search/?q=&sort=popularity', 'Udemy most popular courses']]
      : [['https://www.amazon.in/gp/bestsellers', 'Amazon.in best sellers'],
        ['https://www.amazon.in/gp/movers-and-shakers', 'Amazon.in movers & shakers (fastest rising)'],
        ['https://www.amazon.in/gp/new-releases', 'Amazon.in hot new releases']];
    const terms = [...r.categories.map((c) => c.name).filter((n) => n !== 'No category'), ...r.searches.map((s) => s.term)]
      .filter((v, i, a) => a.indexOf(v) === i).slice(0, 6);
    return el('div', { class: 'card' }, el('h2', { text: 'Market research (India)' }),
      el('p', { class: 'muted small', text: 'Your numbers show what your visitors want. These pages show what the whole Indian market is buying right now — use them to find products to add next.' }),
      el('div', { class: 'market-links' },
        ext('https://trends.google.com/trending?geo=IN&hours=' + (r.period === 'today' ? 24 : 168), 'Google: trending searches in India'),
        general.map(([href, text]) => ext(href, text))),
      terms.length ? el('div', { class: 'market-terms' }, el('h3', { text: 'Check your top topics' }),
        terms.map((t) => el('div', { class: 'market-term' }, el('strong', { text: t }), researchLinks(t, r)))) : null);
  }

  // ------------------------------------------------------------------ sections

  function productCell(p) {
    return el('td', null,
      el('a', { href: '#/products/' + p.productId, text: p.title }),
      el('div', { class: 'muted small' }, p.category,
        p.published && p.slug ? [' · ', ext('/' + SF + '/p/' + enc(p.slug), 'view on site')] : (p.published ? null : ' · not live')));
  }

  function demandTable(rows, r) {
    const max = Math.max(1, ...rows.map((p) => p.score));
    const headers = ['#', 'Product', 'Views', 'Clicks', 'Saves', 'Alerts', 'Sales'];
    if (r.showEarnings) headers.push('Earned');
    headers.push('Click rate', 'Demand', 'Trend');
    return table(headers.map((h, i) => i > 1 && h !== 'Trend' ? el('th', { class: 'num', text: h }) : h), rows.map((p, i) => el('tr', null,
      el('td', { class: 'rank', text: String(i + 1) }),
      productCell(p),
      el('td', { class: 'num', text: num(p.views) }),
      el('td', { class: 'num', text: num(p.clicks) }),
      el('td', { class: 'num', text: num(p.saves) }),
      el('td', { class: 'num', text: num(p.alerts) }),
      el('td', { class: 'num', text: num(p.sales) }),
      r.showEarnings ? el('td', { class: 'num', text: p.earnedMinor ? UI.money(p.earnedMinor) : '—' }) : null,
      el('td', { class: 'num', text: p.views ? pct(p.clicks, p.views) : '—' }),
      el('td', { class: 'num' }, el('div', { class: 'score' }, el('span', { text: num(p.score) }),
        el('span', { class: 'meter' }, sized(el('span'), 'width', (p.score * 100) / max)))),
      el('td', null, trend(p.score, p.previousScore, p.changePercent, p.isNew)))),
    'No shopper activity in this period yet. Share your deals, then check back.');
  }

  function bestSellers(rows, r) {
    if (!rows.length) {
      return el('div', { class: 'empty' }, 'No sales reported for this period yet. Sales appear when your affiliate network reports them ',
        el('a', { href: '#/affiliate', text: '(import a CSV or set up the postback)' }), ', and when your own orders are paid.');
    }
    const headers = ['#', 'Product', el('th', { class: 'num', text: 'Sales' })];
    if (r.showEarnings) headers.push(el('th', { class: 'num', text: 'Earned' }));
    headers.push(el('th', { class: 'num', text: 'Clicks' }), el('th', { class: 'num', text: 'Sale rate' }));
    return table(headers, rows.map((p, i) => el('tr', null,
      el('td', { class: 'rank', text: String(i + 1) }),
      productCell(p),
      el('td', { class: 'num strong', text: num(p.sales) }),
      r.showEarnings ? el('td', { class: 'num', text: UI.money(p.earnedMinor || 0) }) : null,
      el('td', { class: 'num', text: num(p.clicks) }),
      el('td', { class: 'num', text: p.clicks ? pct(p.sales, p.clicks) : '—' }))));
  }

  function risingList(rows) {
    if (!rows.length) return el('div', { class: 'empty', text: 'Nothing is rising sharply compared with the period before.' });
    return el('ol', { class: 'rising-list' }, rows.map((p) => el('li', null,
      el('a', { href: '#/products/' + p.productId, text: p.title }),
      el('span', { class: 'muted small', text: num(p.previousScore) + ' → ' + plural(p.score, 'point') }),
      trend(p.score, p.previousScore, p.changePercent, p.isNew))));
  }

  function barList(rows, valueOf, labelOf, extra) {
    if (!rows.length) return el('div', { class: 'empty', text: 'No activity yet.' });
    const max = Math.max(1, ...rows.map(valueOf));
    return el('div', { class: 'bar-list' }, rows.map((g) => el('div', { class: 'bar-row' },
      el('div', { class: 'bar-label' }, el('span', { text: labelOf(g) }), el('span', { class: 'muted small', text: extra(g) })),
      el('div', { class: 'bar-track' }, sized(el('span', { class: 'bar-fill' }), 'width', (valueOf(g) * 100) / max)))));
  }

  function activityChart(r) {
    const buckets = r.activity;
    if (!buckets.length) return null;
    const metrics = [['clicks', 'Clicks to stores'], ['sales', 'Sales']];
    if (r.period !== 'today') metrics.unshift(['views', 'Product views']);
    let current = metrics.find((m) => buckets.some((b) => b[m[0]])) || metrics[0];
    const chart = el('div', { class: 'chart', role: 'img' });
    const caption = el('p', { class: 'muted small chart-caption' });
    const buttons = metrics.map((m) => el('button', { class: 'btn small ghost', type: 'button', text: m[1], on: { click: () => { current = m; draw(); } } }));
    function draw() {
      const key = current[0];
      const max = Math.max(1, ...buckets.map((b) => b[key] || 0));
      const total = buckets.reduce((s, b) => s + (b[key] || 0), 0);
      chart.setAttribute('aria-label', current[1] + ' per ' + (r.period === 'today' ? 'hour' : r.period === 'week' || r.period === 'month' ? 'day' : 'month'));
      mount(chart, buckets.map((b) => el('div', { class: 'chart-col', title: b.label + ': ' + num(b[key]) + ' ' + current[1].toLowerCase() },
        el('span', { class: 'chart-value', text: b[key] ? num(b[key]) : '' }),
        el('div', { class: 'chart-bar-wrap' }, sized(el('span', { class: 'chart-bar' }), 'height', ((b[key] || 0) * 100) / max)),
        el('span', { class: 'chart-label', text: b.label }))));
      caption.textContent = num(total) + ' ' + current[1].toLowerCase() + ' in ' + r.periodLabel.toLowerCase() + '.';
      buttons.forEach((b, i) => b.classList.toggle('active', metrics[i] === current));
    }
    draw();
    return el('div', { class: 'card' }, el('div', { class: 'card-head' }, el('h2', { text: 'Activity over time' }), el('div', { class: 'seg-buttons' }, buttons)),
      chart, caption);
  }

  function funnel(r) {
    const t = r.totals;
    const steps = [['Product views', t.views], ['Clicks to stores', t.clicks], ['Sales', t.sales]];
    const max = Math.max(1, ...steps.map((s) => s[1]));
    return el('div', { class: 'card' }, el('h2', { text: 'From visit to sale' }),
      el('div', { class: 'funnel' }, steps.map(([label, value], i) => el('div', { class: 'funnel-step' },
        el('div', { class: 'funnel-label' }, el('strong', { text: num(value) }), ' ', label,
          i > 0 ? el('span', { class: 'muted small', text: ' · ' + pct(value, steps[i - 1][1]) + ' of the step before' }) : null),
        el('div', { class: 'bar-track' }, sized(el('span', { class: 'bar-fill' }), 'width', (value * 100) / max))))),
      el('p', { class: 'muted small', text: 'A low click rate means the page doesn’t convince — improve photos, price and the “why buy” text. '
        + 'Many clicks but few sales usually means the store’s price or stock changed.' }));
  }

  function searchTables(r) {
    const searched = table(['Search', el('th', { class: 'num', text: 'Times' }), 'Found'], r.searches.map((s) => el('tr', null,
      el('td', null, el('a', { href: '/' + SF + '?q=' + enc(s.term), target: '_blank', rel: 'noopener', text: s.term })),
      el('td', { class: 'num', text: num(s.searches) }),
      el('td', null, s.noResults >= s.searches ? el('span', { class: 'badge red', text: 'Nothing' })
        : s.noResults ? el('span', { class: 'badge amber', text: 'Sometimes' }) : el('span', { class: 'badge green', text: 'Yes' })))),
    'No searches yet in this period.');
    const missing = table(['Search', el('th', { class: 'num', text: 'Times' }), 'Research'], r.notFound.map((s) => el('tr', null,
      el('td', null, el('strong', { text: s.term })),
      el('td', { class: 'num', text: num(s.noResults) }),
      el('td', null, researchLinks(s.term, r)))),
    'Every search found something. 👍');
    return el('div', { class: 'grid-2' },
      el('div', { class: 'card' }, el('h2', { text: 'What shoppers search for' }), searched),
      el('div', { class: 'card' }, el('h2', { text: 'Searched, but you don’t have it' }),
        el('p', { class: 'muted small', text: 'Free product ideas: people looked for these on your site and found nothing. Add a deal for them.' }), missing));
  }

  // ------------------------------------------------------------------ page

  async function demandView(parts) {
    const period = PERIODS.some((p) => p[0] === parts[0]) ? parts[0] : 'month';
    const r = await Api.get(API + '/insights/demand?period=' + period);
    const t = r.totals;
    const p = r.previous;
    const tabs = el('div', { class: 'period-tabs', role: 'tablist', 'aria-label': 'Period' }, PERIODS.map(([key, label]) =>
      el('a', { href: '#/demand/' + key, role: 'tab', 'aria-selected': String(key === period), class: key === period ? 'active' : '', text: label })));
    const range = r.from === r.to ? UI.date(r.from + 'T00:00:00', false) : UI.date(r.from + 'T00:00:00', false) + ' – ' + UI.date(r.to + 'T00:00:00', false);

    mount(view(), head('Sales & demand'), tabs,
      el('p', { class: 'muted period-note', text: r.periodLabel + ' (' + range + '), compared with ' + r.compareLabel + '.' }),
      el('div', { class: 'stats kpis' },
        kpi('Product views', t.views, p.views),
        kpi('Clicks to stores', t.clicks, p.clicks),
        kpi('Saves & price alerts', t.saves + t.alerts, p.saves + p.alerts),
        kpi('Sales', t.sales, p.sales),
        r.showEarnings ? kpi('Earned', t.earnedMinor || 0, p.earnedMinor || 0, (v) => UI.money(v)) : null,
        kpi('Searches', t.searches, p.searches)),
      el('div', { class: 'card' }, el('h2', { text: 'Most in demand' }),
        el('p', { class: 'muted small', text: 'Ranked by demand points: page view ' + r.points.view + ' · save to wishlist or cart ' + r.points.save
          + ' · price alert ' + r.points.alert + ' · click to the store ' + r.points.click + ' · sale ' + r.points.sale + '.' }),
        demandTable(r.inDemand, r)),
      el('div', { class: 'grid-2' },
        el('div', { class: 'card' }, el('h2', { text: 'Best sellers' }), bestSellers(r.bestSellers, r)),
        el('div', { class: 'card' }, el('h2', { text: 'Rising fast' }),
          el('p', { class: 'muted small', text: 'Demand grew the most compared with ' + r.compareLabel + '. Feature these on the home page while they are hot.' }),
          risingList(r.rising))),
      activityChart(r),
      el('div', { class: 'grid-2' },
        el('div', { class: 'card' }, el('h2', { text: 'Top categories' }),
          barList(r.categories, (g) => g.score, (g) => g.name, (g) => plural(g.score, 'point') + ' · ' + plural(g.clicks, 'click') + ' · ' + plural(g.sales, 'sale'))),
        el('div', { class: 'card' }, el('h2', { text: 'Top partner stores' }),
          barList(r.stores, (g) => g.score, (g) => g.name, (g) => plural(g.clicks, 'click') + ' · ' + plural(g.sales, 'sale')
            + (r.showEarnings && g.earnedMinor ? ' · ' + UI.money(g.earnedMinor) : '')))),
      funnel(r),
      searchTables(r),
      marketCard(r),
      el('p', { class: 'muted small', text: (r.trackingSince
        ? 'Product views and searches are counted since ' + UI.date(r.trackingSince + 'T00:00:00', false) + '. '
        : 'Product views and searches are counted from now on. ')
        + 'Bots are ignored and no personal data is stored. Clicks, saves, alerts and sales include everything recorded before.' }));
  }

  K.register('demand', 'Sales & demand', 'DASHBOARD_READ', demandView, 'dashboard');
})();
