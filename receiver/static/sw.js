/**
 * VoltTracker Service Worker
 * Provides offline support and caching for the PWA
 */

// Cache version - update on each deploy to ensure fresh assets
const CACHE_VERSION = '2025-01-04';
const CACHE_NAME = `volttracker-${CACHE_VERSION}`;
const STATIC_ASSETS = [
    '/',
    '/static/css/style.css',
    '/static/js/dashboard.js',
    '/static/manifest.json'
];

// External CDN resources to cache
const CDN_ASSETS = [
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
    'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
    'https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js',
    'https://cdn.jsdelivr.net/npm/flatpickr/dist/flatpickr.min.css',
    'https://cdn.jsdelivr.net/npm/flatpickr/dist/themes/dark.css',
    'https://cdn.jsdelivr.net/npm/flatpickr',
    'https://cdn.socket.io/4.7.2/socket.io.min.js'
];

/**
 * Install event - cache static assets
 */
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => {
            console.log('Caching static assets');
            // Cache static assets first
            return cache.addAll(STATIC_ASSETS).then(() => {
                // Try to cache CDN assets, but don't fail if they're unavailable
                return Promise.allSettled(
                    CDN_ASSETS.map(url =>
                        fetch(url).then(response => {
                            if (response.ok) {
                                return cache.put(url, response);
                            }
                        }).catch(() => {
                            console.log('Could not cache CDN asset:', url);
                        })
                    )
                );
            });
        })
    );
    // Activate immediately
    self.skipWaiting();
});

/**
 * Activate event - clean up old caches
 */
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames
                    .filter((cacheName) => cacheName !== CACHE_NAME)
                    .map((cacheName) => caches.delete(cacheName))
            );
        })
    );
    // Take control of all clients immediately
    self.clients.claim();
});

/**
 * Fetch event - network first, fallback to cache
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

    // Always fetch API requests fresh (don't cache)
    if (url.pathname.startsWith('/api/')) {
        event.respondWith(
            fetch(event.request).catch(() => {
                // Return cached data if available and network fails
                return caches.match(event.request);
            })
        );
        return;
    }

    // Skip Torque upload endpoint
    if (url.pathname.startsWith('/torque/')) {
        return;
    }

    // For static assets, use network first, fallback to cache
    event.respondWith(
        fetch(event.request)
            .then((response) => {
                // Clone and cache successful responses
                if (response.status === 200) {
                    const responseClone = response.clone();
                    caches.open(CACHE_NAME).then((cache) => {
                        cache.put(event.request, responseClone);
                    });
                }
                return response;
            })
            .catch(() => {
                // Network failed, try cache
                return caches.match(event.request).then((cachedResponse) => {
                    if (cachedResponse) {
                        return cachedResponse;
                    }
                    // If it's a navigation request, return the cached index page
                    if (event.request.mode === 'navigate') {
                        return caches.match('/');
                    }
                });
            })
    );
});
