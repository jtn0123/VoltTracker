/**
 * VoltTracker Service Worker
 * Provides offline support and caching for the PWA
 */

// Cache version - update on each deploy to ensure fresh assets
const CACHE_VERSION = '2025-01-05-v2';
const STATIC_CACHE = `volttracker-static-${CACHE_VERSION}`;
const API_CACHE = `volttracker-api-${CACHE_VERSION}`;
const CDN_CACHE = `volttracker-cdn-${CACHE_VERSION}`;

const STATIC_ASSETS = [
    '/',
    '/static/dist/styles.min.css',
    '/static/dist/dashboard.min.js',
    '/static/manifest.json'
];

// External CDN resources to cache
// Note: Chart.js and Leaflet are now lazy-loaded, so not pre-cached
const CDN_ASSETS = [
    'https://cdn.jsdelivr.net/npm/flatpickr/dist/flatpickr.min.css',
    'https://cdn.jsdelivr.net/npm/flatpickr/dist/themes/dark.css',
    'https://cdn.socket.io/4.7.2/socket.io.min.js'
];

// API endpoints that can be cached with stale-while-revalidate
const CACHEABLE_API_ENDPOINTS = [
    '/api/trips',
    '/api/efficiency/summary',
    '/api/charging/summary'
];

/**
 * Install event - cache static assets
 */
self.addEventListener('install', (event) => {
    event.waitUntil(
        Promise.all([
            // Cache static assets
            caches.open(STATIC_CACHE).then((cache) => {
                console.log('[SW] Caching static assets');
                return cache.addAll(STATIC_ASSETS);
            }),
            // Cache CDN assets
            caches.open(CDN_CACHE).then((cache) => {
                console.log('[SW] Caching CDN assets');
                return Promise.allSettled(
                    CDN_ASSETS.map(url =>
                        fetch(url).then(response => {
                            if (response.ok) {
                                return cache.put(url, response);
                            }
                        }).catch(() => {
                            console.log('[SW] Could not cache CDN asset:', url);
                        })
                    )
                );
            })
        ])
    );
    // Activate immediately
    self.skipWaiting();
});

/**
 * Activate event - clean up old caches
 */
self.addEventListener('activate', (event) => {
    const currentCaches = [STATIC_CACHE, API_CACHE, CDN_CACHE];
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames
                    .filter((cacheName) => !currentCaches.includes(cacheName))
                    .map((cacheName) => {
                        console.log('[SW] Deleting old cache:', cacheName);
                        return caches.delete(cacheName);
                    })
            );
        })
    );
    // Take control of all clients immediately
    self.clients.claim();
});

/**
 * Stale-while-revalidate strategy
 * Returns cached response immediately, then updates cache in background
 */
async function staleWhileRevalidate(request, cacheName) {
    const cache = await caches.open(cacheName);
    const cachedResponse = await cache.match(request);

    const fetchPromise = fetch(request).then((networkResponse) => {
        if (networkResponse.ok) {
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    }).catch(() => null);

    // Return cached response immediately if available, otherwise wait for network
    return cachedResponse || fetchPromise;
}

/**
 * Cache-first strategy
 * Returns cached response if available, otherwise fetches from network
 */
async function cacheFirst(request, cacheName) {
    const cachedResponse = await caches.match(request);
    if (cachedResponse) {
        return cachedResponse;
    }

    const networkResponse = await fetch(request);
    if (networkResponse.ok) {
        const cache = await caches.open(cacheName);
        cache.put(request, networkResponse.clone());
    }
    return networkResponse;
}

/**
 * Network-first strategy
 * Try network first, fallback to cache
 */
async function networkFirst(request, cacheName) {
    try {
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            const cache = await caches.open(cacheName);
            cache.put(request, networkResponse.clone());
        }
        return networkResponse;
    } catch (error) {
        const cachedResponse = await caches.match(request);
        if (cachedResponse) {
            return cachedResponse;
        }
        throw error;
    }
}

/**
 * Fetch event - use appropriate strategy based on request type
 */
self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);

    // Skip non-GET requests
    if (event.request.method !== 'GET') {
        return;
    }

    // Skip WebSocket upgrade requests
    if (event.request.headers.get('upgrade') === 'websocket') {
        return;
    }

    // Skip Torque upload endpoint and analytics
    if (url.pathname.startsWith('/torque/') || url.pathname.startsWith('/api/analytics/')) {
        return;
    }

    // API requests: Use stale-while-revalidate for cacheable endpoints
    if (url.pathname.startsWith('/api/')) {
        const isCacheable = CACHEABLE_API_ENDPOINTS.some(endpoint =>
            url.pathname.startsWith(endpoint)
        );

        if (isCacheable) {
            event.respondWith(staleWhileRevalidate(event.request, API_CACHE));
        } else {
            // Network-first for non-cacheable API endpoints
            event.respondWith(networkFirst(event.request, API_CACHE));
        }
        return;
    }

    // Static assets (JS, CSS, images): Cache-first
    if (url.pathname.match(/\.(js|css|png|jpg|jpeg|svg|woff2|webp)$/)) {
        event.respondWith(cacheFirst(event.request, STATIC_CACHE));
        return;
    }

    // CDN resources: Cache-first with long expiry
    if (url.hostname.includes('cdn.') || url.hostname.includes('unpkg.com')) {
        event.respondWith(cacheFirst(event.request, CDN_CACHE));
        return;
    }

    // HTML navigation: Network-first with offline fallback
    if (event.request.mode === 'navigate') {
        event.respondWith(
            networkFirst(event.request, STATIC_CACHE).catch(() => {
                return caches.match('/');
            })
        );
        return;
    }

    // Default: Network-first
    event.respondWith(networkFirst(event.request, STATIC_CACHE));
});
