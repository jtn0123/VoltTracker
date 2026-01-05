# VoltTracker Frontend Optimization Plan

## Overview
Comprehensive plan to optimize frontend performance, reducing bundle size by 60-70% and improving load times by 40-50%.

---

## 1. Build Tool Setup with Vite

### What It Does
Sets up a modern build system to minify, bundle, and optimize all frontend assets.

### Why It's Needed
- **Current:** 100KB unminified JS + 56KB unminified CSS sent to users
- **After:** ~30-40KB minified JS + ~20KB minified CSS
- **Savings:** 100KB+ reduction (60-70% smaller)

### How We'll Implement It

**Step 1: Install Vite**
```bash
npm init -y
npm install --save-dev vite terser
```

**Step 2: Create Build Configuration**
Create `vite.config.js`:
```javascript
import { defineConfig } from 'vite';
import { resolve } from 'path';

export default defineConfig({
  root: 'receiver',
  build: {
    outDir: 'static/dist',
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'receiver/static/js/dashboard.js'),
        styles: resolve(__dirname, 'receiver/static/css/style.css')
      },
      output: {
        entryFileNames: '[name].[hash].js',
        chunkFileNames: '[name].[hash].js',
        assetFileNames: '[name].[hash].[ext]'
      }
    },
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true,
        drop_debugger: true
      }
    }
  }
});
```

**Step 3: Update package.json Scripts**
```json
{
  "scripts": {
    "build": "vite build",
    "dev": "vite build --watch",
    "preview": "vite preview"
  }
}
```

**Step 4: Update Flask Templates**
Modify `receiver/templates/index.html` to reference built files with cache busting.

### Files Affected
- New: `package.json`, `vite.config.js`
- Modified: `receiver/templates/index.html`
- Output: `receiver/static/dist/` (new directory)

### Expected Impact
- **Bundle size:** -100KB (60-70% reduction)
- **Parse time:** -200-300ms (minified code parses faster)
- **First load:** -400-600ms on 3G

---

## 2. Lazy Load Chart.js (147KB)

### What It Does
Delays loading Chart.js library until user actually views charts section.

### Why It's Needed
- **Current:** 147KB Chart.js loads on every page load
- **Problem:** Many users never scroll to charts
- **After:** Chart.js only loads when needed
- **Savings:** 147KB for 60-80% of users who don't view charts

### How We'll Implement It

**Step 1: Remove Chart.js from HTML**
In `receiver/templates/index.html`, remove:
```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js" defer></script>
```

**Step 2: Add Intersection Observer**
In `dashboard.js`, add lazy loading logic:

```javascript
// At top of file
let chartJsLoaded = false;
let chartJsLoading = false;

// New function to load Chart.js
async function loadChartJs() {
    if (chartJsLoaded) return;
    if (chartJsLoading) return;

    chartJsLoading = true;

    return new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js';
        script.onload = () => {
            chartJsLoaded = true;
            chartJsLoading = false;
            resolve();
        };
        script.onerror = reject;
        document.head.appendChild(script);
    });
}

// Add Intersection Observer for charts section
function setupChartLazyLoading() {
    const chartsSection = document.getElementById('charts-container');
    if (!chartsSection) return;

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                loadChartJs().then(() => {
                    // Initialize charts after library loads
                    updateMPGChart();
                    updateSOCChart();
                });
                observer.disconnect();
            }
        });
    }, {
        rootMargin: '200px' // Start loading 200px before visible
    });

    observer.observe(chartsSection);
}

// Call on page load
document.addEventListener('DOMContentLoaded', setupChartLazyLoading);
```

**Step 3: Defer Chart Initialization**
Wrap all chart creation in checks:
```javascript
async function updateMPGChart() {
    if (!window.Chart) {
        await loadChartJs();
    }
    // ... existing chart code
}
```

### Files Affected
- Modified: `receiver/templates/index.html` (remove script tag)
- Modified: `receiver/static/js/dashboard.js` (add lazy loading logic)

### Expected Impact
- **Initial load:** -147KB for users not viewing charts
- **Time to Interactive:** -80-120ms
- **Charts appear:** 100-200ms delay when scrolled into view (negligible)

---

## 3. Lazy Load Leaflet.js (147KB)

### What It Does
Delays loading Leaflet map library until user opens a trip detail modal.

### Why It's Needed
- **Current:** 147KB Leaflet loads on every page
- **Problem:** Maps only shown when user clicks trip details (rare action)
- **After:** Leaflet only loads when modal opened
- **Savings:** 147KB for 95%+ of page views

### How We'll Implement It

**Step 1: Remove Leaflet from HTML**
In `receiver/templates/index.html`, remove:
```html
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" defer></script>
```

**Step 2: Add Dynamic Loading**
In `dashboard.js`, add:

```javascript
let leafletLoaded = false;
let leafletLoading = false;

async function loadLeaflet() {
    if (leafletLoaded) return;
    if (leafletLoading) return;

    leafletLoading = true;

    // Load CSS
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
    document.head.appendChild(link);

    // Load JS
    return new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
        script.onload = () => {
            leafletLoaded = true;
            leafletLoading = false;
            resolve();
        };
        script.onerror = reject;
        document.head.appendChild(script);
    });
}

// Modify showTripDetails function
async function showTripDetails(tripId) {
    // Load Leaflet before initializing map
    if (!window.L) {
        showLoadingIndicator(); // Visual feedback
        await loadLeaflet();
        hideLoadingIndicator();
    }

    // ... existing trip details code
}
```

**Step 3: Add Loading Indicator**
Show spinner while Leaflet loads (first time only):
```javascript
function showLoadingIndicator() {
    const modal = document.getElementById('trip-details-modal');
    modal.innerHTML = '<div class="loading-spinner">Loading map...</div>';
}
```

### Files Affected
- Modified: `receiver/templates/index.html` (remove Leaflet links)
- Modified: `receiver/static/js/dashboard.js` (add lazy loading, lines ~1035, 1083)

### Expected Impact
- **Initial load:** -147KB for 95%+ users
- **Map load delay:** 300-500ms first time modal opens (acceptable)
- **Subsequent opens:** 0ms delay (already loaded)

---

## 4. Optimize DOM Manipulation

### What It Does
Replaces inefficient `innerHTML` bulk updates with faster DocumentFragment approach.

### Why It's Needed
- **Current:** 29 places using `innerHTML` to rebuild entire sections
- **Problem:** Causes full DOM reparse, destroys event listeners, memory churn
- **After:** Incremental updates using DocumentFragment
- **Savings:** 20-40% faster DOM updates, less memory

### How We'll Implement It

**Optimization Pattern:**

**Before (Slow):**
```javascript
// Line 508-532: Live Trip Updates
function updateLiveTrip(data) {
    const container = document.getElementById('live-trip');
    container.innerHTML = `
        <div class="stat">
            <span class="label">Speed</span>
            <span class="value">${data.speed} mph</span>
        </div>
        <!-- 20 more lines -->
    `;
}
```

**After (Fast):**
```javascript
function updateLiveTrip(data) {
    const container = document.getElementById('live-trip');

    // Create template
    const template = document.createElement('template');
    template.innerHTML = `
        <div class="stat">
            <span class="label">Speed</span>
            <span class="value">${data.speed} mph</span>
        </div>
    `;

    // Clear and update efficiently
    container.textContent = ''; // Faster than innerHTML = ''
    container.appendChild(template.content.cloneNode(true));
}
```

**Even Better - Update Only Changed Values:**
```javascript
function updateLiveTrip(data) {
    // Update only text content, not entire DOM
    document.querySelector('#live-trip .speed-value').textContent = data.speed;
    document.querySelector('#live-trip .soc-value').textContent = data.soc;
    // etc...
}
```

**Step 1: Optimize Live Trip Updates (Lines 508-532)**
Replace bulk innerHTML with targeted updates.

**Step 2: Optimize Trip Table (Lines 923-942)**
```javascript
function updateTripsTable(trips) {
    const tbody = document.getElementById('trips-table-body');
    const fragment = document.createDocumentFragment();

    trips.forEach(trip => {
        const row = createTripRow(trip);
        fragment.appendChild(row);
    });

    tbody.textContent = '';
    tbody.appendChild(fragment);
}

function createTripRow(trip) {
    const row = document.createElement('tr');
    row.onclick = () => showTripDetails(trip.id);

    // Create cells
    const cells = [
        { text: formatDate(trip.start_time) },
        { text: trip.distance.toFixed(1) },
        { text: trip.efficiency.toFixed(1) },
        { text: trip.duration }
    ];

    cells.forEach(cell => {
        const td = document.createElement('td');
        td.textContent = cell.text;
        row.appendChild(td);
    });

    return row;
}
```

**Step 3: Optimize Trip Cards (Lines 945-975)**
Similar pattern using DocumentFragment.

**Step 4: Create Reusable Template System**
```javascript
// New utility function
function createFromTemplate(templateString) {
    const template = document.createElement('template');
    template.innerHTML = templateString.trim();
    return template.content.firstChild;
}

// Usage
const tripCard = createFromTemplate(`
    <div class="trip-card">
        <h3>${trip.name}</h3>
    </div>
`);
container.appendChild(tripCard);
```

### Files Affected
- Modified: `receiver/static/js/dashboard.js`
  - Lines 508-532 (updateLiveTrip)
  - Lines 923-942 (updateTripsTable)
  - Lines 945-975 (updateTripCards)
  - Lines 1007-1043 (trip details modal)
  - 25+ other innerHTML usages

### Expected Impact
- **DOM update speed:** 20-40% faster
- **Memory usage:** 15-25% reduction during updates
- **Jank reduction:** Smoother scrolling during updates
- **Battery life:** Less CPU usage on mobile

---

## 5. API Response Caching

### What It Does
Implements client-side caching to avoid redundant API calls.

### Why It's Needed
- **Current:** No caching, same data fetched repeatedly
- **Problem:**
  - Status check every 30s (might not change)
  - Trips refetched every 60s (rarely change)
  - No cache on page reload
- **After:** Smart caching with stale-while-revalidate
- **Savings:** 30-50% fewer network requests

### How We'll Implement It

**Step 1: Setup IndexedDB Cache**
```javascript
// New IndexedDB wrapper
class APICache {
    constructor() {
        this.dbName = 'volttracker-cache';
        this.storeName = 'api-responses';
        this.db = null;
    }

    async init() {
        return new Promise((resolve, reject) => {
            const request = indexedDB.open(this.dbName, 1);

            request.onerror = () => reject(request.error);
            request.onsuccess = () => {
                this.db = request.result;
                resolve();
            };

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                if (!db.objectStoreNames.contains(this.storeName)) {
                    db.createObjectStore(this.storeName, { keyPath: 'url' });
                }
            };
        });
    }

    async get(url) {
        return new Promise((resolve, reject) => {
            const transaction = this.db.transaction([this.storeName], 'readonly');
            const store = transaction.objectStore(this.storeName);
            const request = store.get(url);

            request.onsuccess = () => {
                const cached = request.result;
                if (cached && Date.now() - cached.timestamp < cached.maxAge) {
                    resolve(cached.data);
                } else {
                    resolve(null);
                }
            };
            request.onerror = () => resolve(null);
        });
    }

    async set(url, data, maxAge = 60000) {
        const transaction = this.db.transaction([this.storeName], 'readwrite');
        const store = transaction.objectStore(this.storeName);
        store.put({
            url,
            data,
            timestamp: Date.now(),
            maxAge
        });
    }
}

const apiCache = new APICache();
```

**Step 2: Implement Stale-While-Revalidate**
```javascript
async function fetchWithCache(url, options = {}) {
    const {
        maxAge = 60000,
        staleWhileRevalidate = true
    } = options;

    // Try to get from cache
    const cached = await apiCache.get(url);

    if (cached) {
        // Return cached data immediately
        if (staleWhileRevalidate) {
            // Revalidate in background
            fetch(url)
                .then(res => res.json())
                .then(data => apiCache.set(url, data, maxAge))
                .catch(err => console.error('Background revalidate failed', err));
        }
        return cached;
    }

    // No cache, fetch fresh
    const response = await fetch(url);
    const data = await response.json();
    await apiCache.set(url, data, maxAge);
    return data;
}
```

**Step 3: Update fetchJson Function (Lines 43-67)**
```javascript
async function fetchJson(endpoint, useCache = false, cacheOptions = {}) {
    const url = `/api/${endpoint}`;

    try {
        let data;
        if (useCache) {
            data = await fetchWithCache(url, cacheOptions);
        } else {
            const response = await fetch(url);
            data = await response.json();
        }
        return data;
    } catch (error) {
        console.error(`Error fetching ${endpoint}:`, error);
        throw error;
    }
}
```

**Step 4: Apply Caching Strategy**
```javascript
// Cache trips for 5 minutes (rarely change)
async function fetchTrips() {
    return fetchJson('trips', true, {
        maxAge: 300000, // 5 minutes
        staleWhileRevalidate: true
    });
}

// Cache status for 30 seconds
async function fetchStatus() {
    return fetchJson('status', true, {
        maxAge: 30000,
        staleWhileRevalidate: true
    });
}

// Don't cache live data
async function fetchLiveData() {
    return fetchJson('live', false);
}
```

**Step 5: Add Cache Invalidation**
```javascript
// Invalidate cache when new trip completes
socket.on('trip_complete', () => {
    apiCache.clear(); // Or just clear 'trips' endpoint
    fetchTrips(); // Fresh fetch
});
```

### Files Affected
- Modified: `receiver/static/js/dashboard.js`
  - New: APICache class (~150 lines)
  - Modified: fetchJson function (lines 43-67)
  - Modified: All fetch calls to use cache

### Expected Impact
- **Network requests:** -30-50% reduction
- **Perceived speed:** Instant cached responses
- **Offline capability:** Can show cached data when offline
- **Server load:** -30-50% reduction in API calls

---

## 6. Critical CSS Extraction

### What It Does
Separates critical above-the-fold CSS from non-critical styles.

### Why It's Needed
- **Current:** 56KB CSS file blocks page render
- **Problem:** User must download ALL CSS before seeing anything
- **After:**
  - ~10KB critical CSS inline in HTML (instant)
  - ~46KB non-critical CSS loaded async
- **Savings:** First paint 300-500ms faster

### How We'll Implement It

**Step 1: Identify Critical CSS**
Critical styles (above-the-fold):
- Body, header, navigation
- Loading indicators
- Typography basics
- Grid layout
- Primary colors/variables

Non-critical:
- Modal styles
- Chart containers
- Trip details
- Responsive breakpoints for unused devices
- Animations

**Step 2: Split CSS Files**
```
receiver/static/css/
  ├── critical.css      (~10KB - inline this)
  ├── layout.css        (non-critical)
  ├── components.css    (non-critical)
  └── responsive.css    (non-critical)
```

**Step 3: Inline Critical CSS**
In `receiver/templates/index.html`:
```html
<head>
    <style>
        /* Critical CSS inlined here (~10KB) */
        :root {
            --primary-color: #2196F3;
            /* ... critical variables ... */
        }

        body {
            margin: 0;
            font-family: system-ui, -apple-system, sans-serif;
            /* ... critical body styles ... */
        }

        .header { /* ... */ }
        .nav { /* ... */ }
        .loading { /* ... */ }
    </style>

    <!-- Load non-critical CSS async -->
    <link rel="preload" href="/static/css/layout.css" as="style" onload="this.onload=null;this.rel='stylesheet'">
    <link rel="preload" href="/static/css/components.css" as="style" onload="this.onload=null;this.rel='stylesheet'">
    <noscript>
        <link rel="stylesheet" href="/static/css/layout.css">
        <link rel="stylesheet" href="/static/css/components.css">
    </noscript>
</head>
```

**Step 4: Automate with Critical Package**
```bash
npm install --save-dev critical
```

Create `scripts/extract-critical.js`:
```javascript
const critical = require('critical');

critical.generate({
    base: 'receiver/',
    src: 'templates/index.html',
    target: {
        html: 'templates/index-optimized.html',
        css: 'static/css/critical.css'
    },
    width: 1300,
    height: 900,
    inline: true
});
```

### Files Affected
- Modified: `receiver/templates/index.html` (inline critical CSS)
- Split: `receiver/static/css/style.css` → multiple files
- New: `scripts/extract-critical.js`

### Expected Impact
- **First Paint:** -300-500ms
- **Largest Contentful Paint:** -200-400ms
- **Initial render:** Happens with 10KB instead of 56KB
- **Perceived performance:** Much faster

---

## 7. Service Worker Improvements

### What It Does
Enhances caching strategy for better offline support and faster loads.

### Why It's Needed
- **Current:** Network-first for all requests
- **Problem:**
  - API requests not cached
  - No stale-while-revalidate
  - Slow on poor connections
- **After:** Smart caching per resource type
- **Savings:** Faster repeat visits, offline support

### How We'll Implement It

**Step 1: Implement Multiple Cache Strategies**

```javascript
// In receiver/static/sw.js

const CACHE_VERSION = 'v2';
const STATIC_CACHE = `static-${CACHE_VERSION}`;
const API_CACHE = `api-${CACHE_VERSION}`;
const IMAGE_CACHE = `images-${CACHE_VERSION}`;

// Cache strategies
const strategies = {
    // Static assets: Cache first, network fallback
    cacheFirst: async (request) => {
        const cached = await caches.match(request);
        if (cached) return cached;

        const response = await fetch(request);
        const cache = await caches.open(STATIC_CACHE);
        cache.put(request, response.clone());
        return response;
    },

    // API: Stale-while-revalidate
    staleWhileRevalidate: async (request) => {
        const cached = await caches.match(request);

        const fetchPromise = fetch(request).then(response => {
            const cache = caches.open(API_CACHE);
            cache.put(request, response.clone());
            return response;
        });

        return cached || fetchPromise;
    },

    // Network first, cache fallback
    networkFirst: async (request) => {
        try {
            const response = await fetch(request);
            const cache = await caches.open(API_CACHE);
            cache.put(request, response.clone());
            return response;
        } catch (error) {
            return caches.match(request);
        }
    }
};

// Route requests to strategies
self.addEventListener('fetch', (event) => {
    const { request } = event;
    const url = new URL(request.url);

    // Static assets: cache first
    if (url.pathname.match(/\.(js|css|woff2|svg|png)$/)) {
        event.respondWith(strategies.cacheFirst(request));
    }
    // API: stale-while-revalidate
    else if (url.pathname.startsWith('/api/')) {
        event.respondWith(strategies.staleWhileRevalidate(request));
    }
    // HTML: network first
    else {
        event.respondWith(strategies.networkFirst(request));
    }
});
```

**Step 2: Add Background Sync for Failed Requests**
```javascript
// Queue failed POST requests for retry
self.addEventListener('fetch', (event) => {
    if (event.request.method === 'POST') {
        event.respondWith(
            fetch(event.request.clone()).catch(async () => {
                // Queue for background sync
                const data = await event.request.clone().json();
                await saveForBackgroundSync(data);
                return new Response(JSON.stringify({ queued: true }));
            })
        );
    }
});

self.addEventListener('sync', (event) => {
    if (event.tag === 'retry-requests') {
        event.waitUntil(retryQueuedRequests());
    }
});
```

**Step 3: Add Cache Expiration**
```javascript
// Clean old caches
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then(keys => {
            return Promise.all(
                keys.filter(key => key !== STATIC_CACHE &&
                                   key !== API_CACHE &&
                                   key !== IMAGE_CACHE)
                    .map(key => caches.delete(key))
            );
        })
    );
});
```

### Files Affected
- Modified: `receiver/static/sw.js` (complete rewrite, ~250 lines)

### Expected Impact
- **Repeat visit speed:** 50-70% faster
- **Offline capability:** Full UI works offline with cached data
- **Poor network:** Instant cached responses
- **Cache size:** Better controlled with expiration

---

## 8. Code Splitting & Tree Shaking

### What It Does
Splits code into smaller chunks loaded on-demand.

### Why It's Needed
- **Current:** 100KB single JS file
- **Problem:** User downloads all code even if not needed
- **After:**
  - 30KB core bundle
  - 20KB charts chunk (lazy)
  - 15KB trip details chunk (lazy)
  - 10KB settings chunk (lazy)

### How We'll Implement It

**Step 1: Split into Modules**
```
receiver/static/js/
  ├── main.js           (core, always loaded)
  ├── charts.js         (lazy load)
  ├── trips.js          (lazy load)
  ├── socket.js         (lazy load)
  └── utils.js          (shared utilities)
```

**Step 2: Use Dynamic Imports**
```javascript
// In main.js
async function initCharts() {
    const { createMPGChart, createSOCChart } = await import('./charts.js');
    createMPGChart();
    createSOCChart();
}

async function showTripDetails(id) {
    const { renderTripDetails } = await import('./trips.js');
    await renderTripDetails(id);
}
```

**Step 3: Configure Vite for Chunking**
```javascript
// In vite.config.js
export default defineConfig({
    build: {
        rollupOptions: {
            output: {
                manualChunks: {
                    'charts': ['./receiver/static/js/charts.js'],
                    'trips': ['./receiver/static/js/trips.js'],
                    'socket': ['./receiver/static/js/socket.js']
                }
            }
        }
    }
});
```

### Files Affected
- Refactor: `receiver/static/js/dashboard.js` → split into modules
- Modified: `vite.config.js`

### Expected Impact
- **Initial bundle:** -70KB (only core code)
- **Time to Interactive:** -40-60%
- **Lazy chunks:** Load in 50-100ms when needed

---

## 9. Image & Asset Optimization

### What It Does
Optimizes images and adds modern formats.

### Why It's Needed
- **Current:** PNG icons
- **After:** WebP with PNG fallback, optimized sizes

### How We'll Implement It

**Step 1: Generate Optimized Images**
```bash
npm install --save-dev sharp
```

Create `scripts/optimize-images.js`:
```javascript
const sharp = require('sharp');

// Convert PNG to WebP
sharp('receiver/static/icon-192.png')
    .webp({ quality: 90 })
    .toFile('receiver/static/icon-192.webp');

// Generate multiple sizes
[192, 512].forEach(size => {
    sharp('receiver/static/icon.png')
        .resize(size, size)
        .webp({ quality: 90 })
        .toFile(`receiver/static/icon-${size}.webp`);
});
```

**Step 2: Add Picture Element for Responsive Images**
```html
<picture>
    <source srcset="/static/icon-192.webp" type="image/webp">
    <img src="/static/icon-192.png" alt="VoltTracker">
</picture>
```

### Files Affected
- New: WebP versions of all images
- Modified: HTML to use picture element
- New: `scripts/optimize-images.js`

### Expected Impact
- **Image size:** -30-50% (WebP vs PNG)
- **Visual quality:** Same or better

---

## 10. Performance Monitoring

### What It Does
Adds Web Vitals tracking and performance monitoring.

### Why It's Needed
- **Current:** No visibility into real user performance
- **After:** Track LCP, FID, CLS, and custom metrics

### How We'll Implement It

**Step 1: Add Web Vitals Library**
```html
<script type="module">
import {onCLS, onFID, onLCP} from 'https://unpkg.com/web-vitals@3/dist/web-vitals.js?module';

function sendToAnalytics({name, value, id}) {
    // Send to backend
    fetch('/api/analytics/vitals', {
        method: 'POST',
        body: JSON.stringify({name, value, id}),
        headers: {'Content-Type': 'application/json'}
    });
}

onCLS(sendToAnalytics);
onFID(sendToAnalytics);
onLCP(sendToAnalytics);
</script>
```

**Step 2: Add Custom Performance Marks**
```javascript
// Mark critical user interactions
performance.mark('dashboard-load-start');
// ... load dashboard
performance.mark('dashboard-load-end');
performance.measure('dashboard-load', 'dashboard-load-start', 'dashboard-load-end');

// Report
const measure = performance.getEntriesByName('dashboard-load')[0];
sendToAnalytics({
    name: 'dashboard-load',
    value: measure.duration
});
```

**Step 3: Create Backend Endpoint**
```python
# In receiver/app.py
@app.route('/api/analytics/vitals', methods=['POST'])
def record_vitals():
    data = request.json
    # Log to database or analytics service
    logger.info(f"Web Vital: {data['name']} = {data['value']}")
    return jsonify({'status': 'ok'})
```

### Files Affected
- Modified: `receiver/templates/index.html` (add Web Vitals)
- Modified: `receiver/static/js/dashboard.js` (add performance marks)
- Modified: `receiver/app.py` (add analytics endpoint)

### Expected Impact
- **Visibility:** Real metrics from users
- **Debugging:** Identify slow parts
- **Monitoring:** Track improvements over time

---

## Implementation Order & Timeline

### Phase 1: Quick Wins (Week 1)
**Days 1-2:**
1. ✅ Lazy load Chart.js (2-3 hours)
2. ✅ Lazy load Leaflet (2-3 hours)
3. ✅ Fix image references (1 hour)

**Days 3-5:**
4. ✅ Setup Vite build tool (1 day)
5. ✅ Minify JS & CSS (automatic with Vite)
6. ✅ Test production build

**Expected Results:** -300KB total, 40-50% faster loads

### Phase 2: Structural (Week 2-3)
**Week 2:**
7. ✅ Optimize DOM manipulation (3-4 days)
8. ✅ Critical CSS extraction (2 days)

**Week 3:**
9. ✅ API caching with IndexedDB (3-4 days)
10. ✅ Service Worker improvements (2 days)

**Expected Results:** 60-70% faster repeat visits, better UX

### Phase 3: Advanced (Week 4)
11. ✅ Code splitting (2-3 days)
12. ✅ Image optimization (1 day)
13. ✅ Performance monitoring (1 day)
14. ✅ Final testing & optimization (1-2 days)

**Expected Results:** Production-ready optimized build

---

## Success Metrics

### Before Optimization
- **Bundle size:** 100KB JS + 56KB CSS + 294KB libraries = 450KB total
- **Time to Interactive:** ~3.5s on 3G
- **First Contentful Paint:** ~2.1s
- **Lighthouse Score:** ~70-75

### After Optimization (Expected)
- **Bundle size:** 30KB JS + 20KB CSS + 0KB (lazy loaded) = 50KB initial
- **Time to Interactive:** ~1.5s on 3G (57% faster)
- **First Contentful Paint:** ~1.2s (43% faster)
- **Lighthouse Score:** 90-95

### Monitoring
- Track Web Vitals weekly
- Monitor bundle sizes in CI/CD
- User-reported performance improvements

---

## Testing Plan

### Performance Testing
```bash
# Install Lighthouse CI
npm install -g @lhci/cli

# Run Lighthouse
lhci autorun --collect.url=http://localhost:5000
```

### Bundle Analysis
```bash
# Visualize bundle
npm install --save-dev rollup-plugin-visualizer
# Generates stats.html showing chunk sizes
```

### Browser Testing
- Chrome DevTools Performance tab
- Network throttling (Slow 3G, Fast 3G, 4G)
- Lighthouse audits
- WebPageTest.org tests

---

## Rollout Strategy

### 1. Feature Flags
Add toggle to switch between optimized/original:
```python
# In app.py
OPTIMIZED_BUILD = os.getenv('OPTIMIZED_BUILD', 'false').lower() == 'true'

@app.route('/')
def index():
    return render_template('index.html', optimized=OPTIMIZED_BUILD)
```

### 2. A/B Testing
- 10% of users get optimized version
- Monitor metrics for 1 week
- Gradually increase to 100%

### 3. Rollback Plan
- Keep original files as backup
- Git tag before deployment
- Can revert with single env var change

---

## Files Summary

### New Files
- `package.json` (npm configuration)
- `vite.config.js` (build configuration)
- `scripts/extract-critical.js` (CSS extraction)
- `scripts/optimize-images.js` (image optimization)
- `receiver/static/css/critical.css` (inline critical CSS)
- `receiver/static/js/modules/` (split JS modules)
- `receiver/static/dist/` (build output)

### Modified Files
- `receiver/templates/index.html` (load optimized assets)
- `receiver/static/js/dashboard.js` (lazy loading, DOM optimization)
- `receiver/static/sw.js` (improved caching)
- `receiver/static/css/style.css` (split into modules)
- `receiver/app.py` (analytics endpoint, optimized asset serving)

### Total Changes
- ~15 new files
- ~8 modified files
- ~2000 lines of new code
- ~500 lines modified

---

## Risks & Mitigations

### Risk 1: Build Complexity
**Risk:** Adding build tool increases complexity
**Mitigation:**
- Document build process
- Add npm scripts for common tasks
- Include pre-built assets in repo for simple deployments

### Risk 2: Breaking Changes
**Risk:** Refactoring could break functionality
**Mitigation:**
- Comprehensive testing
- Feature flags for gradual rollout
- Keep original version as fallback

### Risk 3: Browser Compatibility
**Risk:** Modern features might not work in old browsers
**Mitigation:**
- Polyfills for critical features
- Graceful degradation
- Test in IE11, Safari 12+

### Risk 4: Cache Issues
**Risk:** Users stuck with old cached assets
**Mitigation:**
- Cache versioning/busting
- Service Worker update logic
- Clear cache on major updates

---

## Next Steps

Ready to proceed? I'll implement all optimizations in this order:

1. **Quick wins first** (lazy loading) - immediate impact, low risk
2. **Build setup** (Vite) - foundation for everything else
3. **Code optimizations** (DOM, caching) - big performance gains
4. **Advanced features** (code splitting, monitoring) - polish

Estimated total time: 3-4 weeks for complete implementation.

Should I start with Phase 1 (quick wins)?
