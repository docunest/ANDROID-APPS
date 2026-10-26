// Ponas web app service worker: makes the launcher and every game work offline once the
// web app has been opened (iPhone home screen, any browser). tools/build-site.mjs fills in
// VERSION and FILES; a new catalog gets a new cache and the old one is dropped.
const VERSION = '__VERSION__';
const FILES = __FILES__;
const CACHE = 'ponas-' + VERSION;

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE)
      .then((c) => c.addAll(FILES))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('ponas-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return; // ntfy.sh etc. go straight out
  // Pages and the catalog: fresh when online, cached when offline. Everything else: cache first.
  const fresh = req.mode === 'navigate' || /\/manifest\.json$/.test(new URL(req.url).pathname);
  if (fresh) {
    e.respondWith(
      fetch(req)
        .then((res) => { const copy = res.clone(); caches.open(CACHE).then((c) => c.put(req, copy)); return res; })
        .catch(() => caches.match(req, { ignoreSearch: true })),
    );
  } else {
    e.respondWith(caches.match(req, { ignoreSearch: true }).then((hit) => hit || fetch(req)));
  }
});
