/* DropLink service worker: keeps the app shell so it opens instantly and can still show the "reconnect" screen when the
   phone cannot be reached. It never touches /api/ (live data, media, thumbnails). Browsers only run it on trusted HTTPS. */
const VERSION = 'droplink-shell-v4'
const SHELL = ['/', '/manifest.json', '/favicon.svg', '/icon-192.png']

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).catch(() => undefined).then(() => self.skipWaiting()))
})
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k)))).then(() => self.clients.claim()))
})

function timeout(ms) { const c = new AbortController(); setTimeout(() => c.abort(), ms); return c.signal }

self.addEventListener('fetch', e => {
  const req = e.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== location.origin || url.pathname.startsWith('/api/') || url.pathname === '/health') return

  if (req.mode === 'navigate') {
    // Network first (with a short wait), then the saved shell, so a dead address still opens the app.
    e.respondWith(
      fetch(req, { signal: timeout(3500) })
        .then(res => { const copy = res.clone(); caches.open(VERSION).then(c => c.put('/', copy)); return res })
        .catch(() => caches.match('/').then(r => r || Response.error()))
    )
    return
  }
  if (url.pathname.startsWith('/assets/')) {
    // Hashed build files never change, so a cached copy is always right.
    e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => { const copy = res.clone(); caches.open(VERSION).then(c => c.put(req, copy)); return res })))
  }
})
