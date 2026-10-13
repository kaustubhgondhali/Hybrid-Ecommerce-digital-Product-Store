/*
 * Elaashop / Elaatech installable app: a friendly offline page and faster images on repeat visits.
 * Pages, styles and scripts always come fresh from the network (the cache is only an offline fallback).
 * Never touches the admin, accounts, checkout, the API or partner-store links.
 */
const VERSION = 'hpp-v1';
const OFFLINE = '/offline.html';
const PRECACHE = [OFFLINE, '/assets/css/app.css', '/assets/img/elaa-mark.png', '/assets/img/favicon.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(PRECACHE)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function untouched(url) {
  return url.origin !== self.location.origin
    || /^\/(api|go|account|actuator)(\/|$)/.test(url.pathname)
    || /\/(admin|cart|checkout|pay)(\/|$)/.test(url.pathname);
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (untouched(url)) return;

  if (req.mode === 'navigate') {
    event.respondWith(fetch(req).catch(() => caches.match(OFFLINE)));
    return;
  }
  // Product photos and brand images: show the saved copy at once, refresh it in the background.
  if (url.pathname.startsWith('/media/') || url.pathname.startsWith('/assets/img/')) {
    event.respondWith(caches.open(VERSION).then((cache) => cache.match(req).then((hit) => {
      const fresh = fetch(req).then((res) => {
        if (res.ok) cache.put(req, res.clone());
        return res;
      }).catch(() => hit);
      return hit || fresh;
    })));
    return;
  }
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(fetch(req).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(VERSION).then((cache) => cache.put(req, copy));
      }
      return res;
    }).catch(() => caches.match(req)));
  }
});
