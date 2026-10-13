/* Web Stories builder: full-screen, tap-through stories in Google's Web Stories format, shown at the bottom of the
   storefront and listed in the sitemap. Uses the helpers exported by admin.js. */
(function () {
  'use strict';
  const { el, mount } = UI;
  const K = window.AdminKit;
  const { API, SF, can, view, head, onSubmit, input } = K;

  const STORE_PATH = '/' + SF;
  const MAX_PHOTO_MB = 5;
  const MAX_VIDEO_MB = 30;
  const MAX_VIDEO_SECONDS = 60;

  // ------------------------------------------------------------------ image helpers (run in the browser)

  function loadImage(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => resolve({ img, url });
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('This photo could not be opened. Use a JPEG, PNG or WebP image.')); };
      img.src = url;
    });
  }

  function toJpeg(canvas) {
    return new Promise((resolve) => canvas.toBlob((b) => resolve(b), 'image/jpeg', 0.86));
  }

  /** Cover: centre-cropped to 3:4 portrait at 720 x 960, the size Google uses for story posters. */
  async function coverFromFile(file) {
    const { img, url } = await loadImage(file);
    try {
      const W = 720;
      const H = 960;
      const scale = Math.max(W / img.naturalWidth, H / img.naturalHeight);
      const w = img.naturalWidth * scale;
      const h = img.naturalHeight * scale;
      const canvas = document.createElement('canvas');
      canvas.width = W;
      canvas.height = H;
      const g = canvas.getContext('2d');
      g.fillStyle = '#ffffff';
      g.fillRect(0, 0, W, H);
      g.drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
      return { blob: await toJpeg(canvas), small: img.naturalWidth < 640 || img.naturalHeight < 853 };
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  /** Page photo: shrunk to fit 1080 x 1920 (phone screen) as JPEG; GIFs are kept as they are. */
  async function pagePhoto(file) {
    if (file.type === 'image/gif') {
      const { img, url } = await loadImage(file);
      URL.revokeObjectURL(url);
      return { blob: file, name: file.name, width: img.naturalWidth, height: img.naturalHeight };
    }
    const { img, url } = await loadImage(file);
    try {
      const scale = Math.min(1, 1080 / img.naturalWidth, 1920 / img.naturalHeight);
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.naturalWidth * scale);
      canvas.height = Math.round(img.naturalHeight * scale);
      const g = canvas.getContext('2d');
      g.fillStyle = '#ffffff';
      g.fillRect(0, 0, canvas.width, canvas.height);
      g.drawImage(img, 0, 0, canvas.width, canvas.height);
      return { blob: await toJpeg(canvas), name: 'page.jpg', width: canvas.width, height: canvas.height };
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  // ------------------------------------------------------------------ product pickers

  async function searchProducts(q, publishedOnly) {
    const res = await Api.get(API + '/products?size=50' + (publishedOnly ? '&status=PUBLISHED' : '') + (q ? '&q=' + encodeURIComponent(q) : ''));
    return res.items;
  }

  /** Search box + single product select; "No product" unlinks the page. */
  function productSelect(current, currentTitle) {
    const search = el('input', { type: 'search', placeholder: 'Search products', 'aria-label': 'Search products' });
    const select = el('select', { name: 'productId', 'aria-label': 'Linked product' });
    const fill = (items) => mount(select, el('option', { value: '0', text: 'No product link' }),
      current && !items.some((p) => p.id === current) ? el('option', { value: String(current), text: currentTitle || 'Current product', selected: true }) : null,
      items.map((p) => el('option', { value: String(p.id), text: p.title, selected: p.id === current })));
    let timer = null;
    const load = async () => {
      try { fill(await searchProducts(search.value.trim(), true)); } catch (e) { fill([]); }
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 300); });
    search.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(timer); load(); } });
    fill([]);
    load();
    return { root: el('div', { class: 'product-picker' }, search, select), select };
  }

  // ------------------------------------------------------------------ list

  async function storiesRoute(parts) {
    if (parts[0]) return editorView(Number(parts[0]));
    return listView();
  }

  function statusBadge(s) {
    return s.status === 'PUBLISHED' ? UI.badge('ACTIVE', 'Published') : el('span', { class: 'badge amber', text: 'Draft' });
  }

  async function listView() {
    const stories = await Api.get(API + '/stories');
    const writable = can('CATALOG_WRITE');

    const cards = stories.map((s) => el('a', { class: 'story-admin-card', href: '#/stories/' + s.id },
      el('div', { class: 'cover' }, s.coverUrl ? el('img', { src: s.coverUrl, alt: '', loading: 'lazy' }) : el('span', { class: 'muted small', text: 'No cover yet' })),
      el('div', { class: 'meta' }, el('strong', { text: s.title }),
        el('div', { class: 'actions' }, statusBadge(s), el('span', { class: 'muted small', text: s.pages + (s.pages === 1 ? ' page' : ' pages') })),
        el('div', { class: 'muted small', text: 'Updated ' + UI.date(s.updatedAt) }))));

    mount(view(), head('Web Stories', el('a', { class: 'btn secondary', href: STORE_PATH + '/stories', target: '_blank', rel: 'noopener', text: 'View on site' })),
      el('div', { class: 'card' },
        el('p', { class: 'mb' }, 'Web Stories are full-screen, tap-through stories (like Instagram stories) in Google’s Web Stories format. ',
          'Published stories appear at the bottom of your store pages, on ', el('a', { href: STORE_PATH + '/stories', target: '_blank', rel: 'noopener', text: STORE_PATH + '/stories' }),
          ' and in your sitemap, so Google can show them in Search, Discover and Images.'),
        el('p', { class: 'muted small' }, 'Google only shows stories from a live website on your own domain (with HTTPS) after it has crawled them. ',
          'Submit https://your-domain/sitemap.xml once in Google Search Console; new stories are picked up automatically after that.')),
      writable ? el('div', { class: 'story-create' }, fromProductsCard(), blankCard()) : null,
      cards.length ? el('div', { class: 'story-admin-grid' }, cards)
        : el('div', { class: 'card empty', text: 'No stories yet. The quickest start: create one from 3–10 of your products above.' }));
  }

  function blankCard() {
    const form = el('form', null,
      UI.field('Story title', input('title', { required: true, maxlength: '90', placeholder: 'e.g. 5 smartwatches under ₹5,000' })),
      el('button', { class: 'btn secondary', type: 'submit', text: 'Create blank story' }));
    onSubmit(form, async (v) => {
      const s = await Api.post(API + '/stories', { title: v.title });
      location.hash = '#/stories/' + s.id;
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Start from scratch' }),
      el('p', { class: 'muted small', text: 'Add your own photos and short videos page by page — unboxings, how-tos, buying tips.' }), form);
  }

  /** One click: a story with one page per chosen product (photo, name, highlight, swipe-up link, live price). */
  function fromProductsCard() {
    const chosen = new Map();
    const chips = el('div', { class: 'chosen-products' });
    const results = el('div', { class: 'product-checklist' });
    const search = el('input', { type: 'search', placeholder: 'Search your published products', 'aria-label': 'Search products' });
    const count = el('span', { class: 'muted small' });
    const renderChosen = () => {
      count.textContent = chosen.size + ' of 3–10 chosen';
      mount(chips, [...chosen.entries()].map(([id, title]) => {
        const x = el('button', { type: 'button', class: 'chip-remove', 'aria-label': 'Remove ' + title, text: '×' });
        x.addEventListener('click', () => { chosen.delete(id); renderChosen(); renderResults(lastItems); });
        return el('span', { class: 'chip' }, title, x);
      }));
    };
    let lastItems = [];
    const renderResults = (items) => {
      lastItems = items;
      mount(results, items.length ? items.map((p) => {
        const box = el('input', { type: 'checkbox', checked: chosen.has(p.id), disabled: !p.imageUrl });
        box.addEventListener('change', () => {
          if (box.checked) {
            if (chosen.size >= 10) { box.checked = false; UI.toast('A story can have up to 10 products', 'error'); return; }
            chosen.set(p.id, p.title);
          } else chosen.delete(p.id);
          renderChosen();
        });
        return el('label', { class: 'check product-option' + (p.imageUrl ? '' : ' disabled') }, box,
          p.imageUrl ? el('img', { src: p.imageUrl, alt: '' }) : null,
          el('span', null, p.title, p.imageUrl ? null : el('span', { class: 'muted small', text: ' (needs a photo)' })));
      }) : el('p', { class: 'muted small', text: 'No published products match.' }));
    };
    let timer = null;
    const load = async () => {
      try { renderResults(await searchProducts(search.value.trim(), true)); } catch (e) { renderResults([]); }
    };
    search.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(load, 300); });
    search.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(timer); load(); } });
    load();
    renderChosen();

    const form = el('form', null,
      UI.field('Story title', input('title', { required: true, maxlength: '90', placeholder: SF === 'digital' ? 'e.g. 5 AI tools that save hours every week' : 'e.g. Top 5 earbuds deals this week' })),
      UI.field('Products', el('div', null, search, results)),
      el('div', { class: 'actions mb' }, count), chips,
      el('label', { class: 'check mb' }, input('publish', { type: 'checkbox', checked: true }), ' Publish right away'),
      el('button', { class: 'btn', type: 'submit', text: 'Create story' }));
    onSubmit(form, async (v) => {
      if (chosen.size < 3) throw new Error('Choose at least 3 products');
      const s = await Api.post(API + '/stories/from-products', { title: v.title, productIds: [...chosen.keys()], publish: v.publish });
      UI.toast(s.status === 'PUBLISHED' ? 'Story published' : 'Story created', 'success');
      location.hash = '#/stories/' + s.id;
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Create from products' }),
      el('p', { class: 'muted small', text: 'Fastest way: pick 3 to 10 products. Each becomes a page with its photo, name and a highlight, plus a “See the deal” link. '
        + 'Prices on the story always show the current price.' }), form);
  }

  // ------------------------------------------------------------------ editor

  async function editorView(id) {
    const s = await Api.get(API + '/stories/' + id);
    const writable = can('CATALOG_WRITE');
    const refresh = () => editorView(id);
    const liveUrl = s.url;

    const actions = [];
    actions.push(el('a', { class: 'btn secondary', href: liveUrl, target: '_blank', rel: 'noopener',
      text: s.status === 'PUBLISHED' ? 'View live' : 'Preview' }));
    if (writable) {
      if (s.status === 'PUBLISHED') {
        const un = el('button', { class: 'btn secondary', type: 'button', text: 'Unpublish' });
        un.addEventListener('click', () => UI.busy(un, async () => {
          try { await Api.post(API + '/stories/' + id + '/unpublish'); UI.toast('Story hidden from the site', 'success'); refresh(); }
          catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        actions.push(un);
      } else {
        const pub = el('button', { class: 'btn', type: 'button', text: 'Publish', disabled: s.problems.length > 0 });
        pub.addEventListener('click', () => UI.busy(pub, async () => {
          try { await Api.post(API + '/stories/' + id + '/publish'); UI.toast('Story published', 'success'); refresh(); }
          catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        actions.push(pub);
      }
      const del = el('button', { class: 'btn ghost', type: 'button', text: 'Delete' });
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Delete this story and its photos and videos?')) return;
        try { await Api.del(API + '/stories/' + id); location.hash = '#/stories'; } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      actions.push(del);
    }

    const checklist = el('div', { class: 'card' },
      el('div', { class: 'actions mb' }, el('a', { href: '#/stories', text: '← All stories' }), el('span', { class: 'spacer' }), statusBadge(s),
        el('span', { class: 'muted small', text: (s.pages.length + 2) + ' pages incl. cover and closing page' })),
      s.problems.length ? el('div', { class: 'alert warning' }, 'Before publishing: ', s.problems.join('; '), '.') : null,
      s.tips.length ? el('ul', { class: 'story-tips' }, s.tips.map((t) => el('li', { text: t }))) : null);

    mount(view(), head(s.title, actions), checklist,
      el('div', { class: 'story-editor' },
        el('div', null, detailsCard(s, writable, refresh), coverCard(s, writable, refresh)),
        el('div', null, pagesCard(s, writable, refresh), writable ? addPageCard(s, refresh) : null)));
  }

  function detailsCard(s, writable, refresh) {
    const title = input('title', { value: s.title, maxlength: '90', required: true, disabled: !writable });
    const counter = el('span', { class: 'char-count' });
    const update = () => {
      const n = title.value.trim().length;
      counter.textContent = n + ' / 70';
      counter.className = 'char-count ' + (n > 70 ? 'amber' : 'green');
    };
    title.addEventListener('input', update);
    update();
    const desc = el('textarea', { name: 'description', rows: '3', maxlength: '300', disabled: !writable, placeholder: 'One line about the story (shown when shared)' });
    desc.value = s.description || '';
    const form = el('form', null,
      UI.field('Title', title), counter,
      UI.field('Description', desc),
      UI.field('Web address', input('slug', { value: s.slug, maxlength: '120', disabled: !writable }), 'Shown as ' + STORE_PATH + '/stories/' + s.slug),
      writable ? el('button', { class: 'btn secondary', type: 'submit', text: 'Save details' }) : null);
    onSubmit(form, async (v) => {
      await Api.put(API + '/stories/' + s.id, { title: v.title, description: v.description, slug: v.slug });
      UI.toast('Saved', 'success');
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Story details' }), form);
  }

  function coverCard(s, writable, refresh) {
    const file = input('file', { type: 'file', accept: 'image/jpeg,image/png,image/webp', required: true });
    const preview = el('div', { class: 'story-cover-preview' }, s.coverUrl ? el('img', { src: s.coverUrl, alt: 'Story cover' }) : el('span', { class: 'muted small', text: 'No cover yet' }));
    const note = el('p', { class: 'muted small' });
    let prepared = null;
    file.addEventListener('change', async () => {
      prepared = null;
      note.textContent = '';
      const f = file.files[0];
      if (!f) return;
      try {
        prepared = await coverFromFile(f);
        mount(preview, el('img', { src: URL.createObjectURL(prepared.blob), alt: 'New cover' }));
        note.textContent = prepared.small ? 'This photo is small; it may look soft on large phones (at least 720 × 960 works best).' : 'Cropped to a 3:4 portrait, the size Google uses.';
      } catch (e) {
        note.textContent = UI.errorMessage(e);
      }
    });
    const form = el('form', null, UI.field('Cover photo (portrait works best)', file), note,
      el('button', { class: 'btn secondary', type: 'submit', text: 'Upload cover' }));
    onSubmit(form, async () => {
      if (!prepared) throw new Error('Choose a photo first');
      const fd = new FormData();
      fd.append('file', prepared.blob, 'cover.jpg');
      await Api.upload(API + '/stories/' + s.id + '/cover', fd);
      UI.toast('Cover updated', 'success');
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Cover' }),
      el('p', { class: 'muted small', text: 'The first thing people see in Google and on your site. Use a bright, close-up photo.' }),
      preview, writable ? form : null);
  }

  function pagesCard(s, writable, refresh) {
    const items = s.pages.map((p, i) => {
      const media = p.mediaType === 'VIDEO'
        ? el('video', { src: p.mediaUrl, poster: p.posterUrl, muted: true, playsinline: true, preload: 'none', controls: true })
        : el('img', { src: p.mediaUrl, alt: p.altText || '', loading: 'lazy' });
      const headline = input('headline', { value: p.headline || '', maxlength: '90', placeholder: 'Headline (short and bold)', disabled: !writable });
      const body = el('textarea', { name: 'body', rows: '2', maxlength: '250', placeholder: 'Text (up to 250 characters)', disabled: !writable });
      body.value = p.body || '';
      const picker = productSelect(p.productId, p.productTitle);
      picker.select.disabled = !writable;
      const cta = input('ctaLabel', { value: p.ctaLabel || '', maxlength: '40', placeholder: 'See the deal', disabled: !writable });
      const save = el('button', { class: 'btn small', type: 'button', text: 'Save page' });
      save.addEventListener('click', () => UI.busy(save, async () => {
        try {
          await Api.put(API + '/stories/' + s.id + '/pages/' + p.id, {
            headline: headline.value, body: body.value, ctaLabel: cta.value, productId: Number(picker.select.value || 0)
          });
          UI.toast('Page saved', 'success');
          refresh();
        } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      const move = (dir, label, disabled) => {
        const b = el('button', { class: 'btn ghost small', type: 'button', text: label, disabled, 'aria-label': dir < 0 ? 'Move page up' : 'Move page down' });
        b.addEventListener('click', () => UI.busy(b, async () => {
          try { await Api.post(API + '/stories/' + s.id + '/pages/' + p.id + '/move?direction=' + dir); refresh(); }
          catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
        }));
        return b;
      };
      const del = el('button', { class: 'btn ghost small', type: 'button', text: 'Remove' });
      del.addEventListener('click', () => UI.busy(del, async () => {
        if (!confirm('Remove this page?')) return;
        try { await Api.del(API + '/stories/' + s.id + '/pages/' + p.id); refresh(); } catch (e) { UI.toast(UI.errorMessage(e), 'error'); }
      }));
      return el('div', { class: 'story-page-row' },
        el('div', { class: 'story-page-media' }, media, el('span', { class: 'page-no', text: String(i + 1) })),
        el('div', { class: 'story-page-fields' },
          UI.field('Headline', headline), UI.field('Text', body),
          el('div', { class: 'row' }, UI.field('Link to product (swipe up)', picker.root), UI.field('Button text', cta)),
          writable ? el('div', { class: 'actions' }, save, move(-1, '↑', i === 0), move(1, '↓', i === s.pages.length - 1), el('span', { class: 'spacer' }), del) : null));
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Pages' }),
      el('p', { class: 'muted small', text: 'Shown after the cover, in this order. Keep text short: readers tap through quickly.' }),
      items.length ? items : el('p', { class: 'muted', text: 'No pages yet. Add at least 3.' }));
  }

  function addPageCard(s, refresh) {
    const file = input('file', { type: 'file', accept: 'image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,video/quicktime', required: true });
    const headline = input('headline', { maxlength: '90', placeholder: 'e.g. 40-hour battery' });
    const body = el('textarea', { name: 'body', rows: '2', maxlength: '250', placeholder: 'One or two short sentences' });
    const picker = productSelect(null, null);
    const cta = input('ctaLabel', { maxlength: '40', placeholder: 'See the deal' });
    const check = el('div', { class: 'video-check' });
    const bar = el('progress', { class: 'upload-progress', max: '100', value: '0', hidden: true });
    let prepared = null;
    file.addEventListener('change', async () => {
      prepared = null;
      mount(check);
      const f = file.files[0];
      if (!f) return;
      try {
        if (f.type.startsWith('video/')) {
          if (f.size > MAX_VIDEO_MB * 1024 * 1024) throw new Error('Story videos must be ' + MAX_VIDEO_MB + ' MB or smaller.');
          mount(check, el('p', { class: 'muted small', text: 'Checking the video…' }));
          const v = await K.inspectVideo(f);
          if (!v) throw new Error('This browser cannot play this video. Export it as MP4 (H.264) and try again.');
          if (v.duration > MAX_VIDEO_SECONDS) throw new Error('Story videos must be ' + MAX_VIDEO_SECONDS + ' seconds or shorter (about 15 seconds works best).');
          prepared = { video: true, file: f, poster: v.poster, duration: v.duration, width: v.width, height: v.height };
          mount(check, el('img', { src: URL.createObjectURL(v.poster), alt: '' }),
            el('span', { class: 'muted small', text: 'Video · ' + v.duration + ' s · ' + v.width + '×' + v.height }));
        } else {
          if (f.size > 25 * 1024 * 1024) throw new Error('This photo is too large.');
          const photo = await pagePhoto(f);
          if (photo.blob.size > MAX_PHOTO_MB * 1024 * 1024) throw new Error('Photos must be ' + MAX_PHOTO_MB + ' MB or smaller.');
          prepared = { video: false, file: photo.blob, name: photo.name, width: photo.width, height: photo.height };
          mount(check, el('img', { src: URL.createObjectURL(photo.blob), alt: '' }),
            el('span', { class: 'muted small', text: photo.width + '×' + photo.height + (photo.width / photo.height > 0.7 ? ' · shown whole on a soft background' : ' · fills the screen') }));
        }
      } catch (e) {
        mount(check, el('div', { class: 'alert error', text: UI.errorMessage(e) }));
      }
    });
    const form = el('form', null,
      UI.field('Photo or short video', file, 'Portrait (9:16) photos and videos fill the screen best.'), check,
      UI.field('Headline', headline), UI.field('Text', body),
      el('div', { class: 'row' }, UI.field('Link to product (optional)', picker.root), UI.field('Button text', cta)),
      bar, el('button', { class: 'btn', type: 'submit', text: 'Add page' }));
    onSubmit(form, async (v) => {
      if (!prepared) throw new Error('Choose a photo or video');
      const fd = new FormData();
      if (prepared.video) {
        fd.append('file', prepared.file);
        fd.append('poster', prepared.poster, 'poster.jpg');
        fd.append('durationSeconds', String(prepared.duration));
      } else {
        fd.append('file', prepared.file, prepared.name);
      }
      fd.append('width', String(prepared.width));
      fd.append('height', String(prepared.height));
      if (v.headline) fd.append('headline', v.headline);
      if (v.body) fd.append('body', v.body);
      if (v.ctaLabel) fd.append('ctaLabel', v.ctaLabel);
      if (Number(v.productId) > 0) fd.append('productId', v.productId);
      bar.hidden = false;
      try {
        await Api.uploadWithProgress(API + '/stories/' + s.id + '/pages', fd, (r) => { bar.value = Math.round(r * 100); });
      } finally {
        bar.hidden = true;
      }
      UI.toast('Page added', 'success');
      refresh();
    });
    return el('div', { class: 'card' }, el('h2', { text: 'Add a page' }), form);
  }

  K.register('stories', 'Web Stories', 'CATALOG_READ', storiesRoute, 'pages');
})();
