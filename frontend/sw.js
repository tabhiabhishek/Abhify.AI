/* =====================================================================
   sw.js: Abhify.AI service worker

   Strategy
   * App shell (HTML, CSS, JS, icons): precached on install and served
     cache-first, so the app opens instantly and works with no network.
   * Navigations: network-first, falling back to the cached index.html.
   * API calls (/health, /chat, /news, ...): never cached here. app.js
     caches the content it needs in localStorage and shows Offline Mode.

   Bump CACHE_VERSION whenever shell files change, so users get the update.
   ===================================================================== */
const CACHE_VERSION = 'abhify-v2.0.0-dev.8';
// 5.6 Neural UI: the shell changed within the same app version, so the cache name carries a shell revision too
// (installed browsers drop the old cache and fetch the new shell). Bump SHELL_REV whenever shell files change.
const SHELL_REV = 'ui62.1';
const CACHE_NAME = `${CACHE_VERSION}+${SHELL_REV}`;
const SHELL = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './ondevice.js',          // small; the vendored engine (vendor/webllm) is cached on first use only
  './mock-data.js',
  './manifest.json',
  './favicon.ico',
  './brand/logo-mark-96.png',
  './brand/logo-320.png',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/maskable-512.png',
  './icons/apple-touch-icon.png',
];
const API_PATHS = ['/health', '/ping', '/chat', '/news', '/generate-question', '/generate-mcq', '/transcribe', '/voice',
                   '/knowledge', '/progress', '/memory', '/preferences', '/conversations', '/system', '/setup',
                   '/backup', '/companion', '/updates', '/models', '/tasks', '/projects', '/suggestions', '/discover', '/briefing', '/search', '/plan', '/skills', '/agent', '/eval', '/career', '/realtime', '/docs', '/redoc', '/openapi.json'];

self.addEventListener('install', (event) => {
  // cache: 'reload' bypasses the browser's HTTP cache, so a new service worker always stores the current app files
  event.waitUntil(caches.open(CACHE_NAME).then((c) => c.addAll(SHELL.map((u) => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  // Remove caches from older versions.
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;                            // POSTs go straight to the network
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;             // cross-origin API / links: untouched
  if (API_PATHS.some((p) => url.pathname === p || url.pathname.startsWith(p + '/'))) return;

  // Page navigations: try the network (fresh deploys), fall back to the cached shell.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((res) => { caches.open(CACHE_NAME).then((c) => c.put('./index.html', res.clone())); return res; })
        .catch(() => caches.match('./index.html'))
    );
    return;
  }

  // Static assets: cache-first, then network (and remember the result).
  event.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      if (res.ok) { const copy = res.clone(); caches.open(CACHE_NAME).then((c) => c.put(req, copy)); }
      return res;
    }))
  );
});
