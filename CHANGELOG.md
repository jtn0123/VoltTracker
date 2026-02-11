# Changelog

All notable changes to VoltTracker are documented in this file.

## [1.1.0] - 2026-02-10

### Added
- **Settings page** scaffold with configurable electricity cost, gas price, battery capacity, and distance units
- **Skeleton loading states** for lazy-loaded sections (battery, charging) with spinner overlay
- **API documentation** (`API.md`) with all endpoints, schemas, and error codes
- **aria-live regions** for live telemetry, power flow, trip list, and map trip container
- **Vendor bundling** — socket.io-client, flatpickr, chart.js, leaflet available as npm deps via Vite

### Changed
- **CDN fallback** — critical dependencies now bundled via npm; CDN scripts serve as fallback with `onerror` handlers
- **Map page** — switched all CDN URLs from `unpkg.com` to `cdn.jsdelivr.net` for reliability; Leaflet plugins lazy-loaded
- **Error handling** — `showFallbackUI` now shows a dismissible error banner instead of replacing `document.body.innerHTML`
- **README roadmap** — marked completed features as done, added realistic next steps

### Fixed
- Partial page recovery possible after errors (DOM no longer destroyed by fallback UI)

## [1.0.0] - 2026-01-04

### Features
- Real-time telemetry ingestion from Torque Pro via HTTP POST
- Automatic trip detection with electric-to-gas transition tracking
- Gas MPG calculation and lifetime/tank efficiency tracking
- SOC floor analysis with temperature correlation and trend monitoring
- Battery health monitoring with cell voltage heatmap and module balance
- Charging session management (L1/L2/DCFC) with cost tracking and power curves
- GPS track map with route visualization, heatmaps, and trip comparison
- Real-time power flow visualization (battery → motor → wheels)
- Weather analytics with efficiency correlation (temperature, wind, precipitation)
- Elevation analytics with gradient-based efficiency analysis
- CSV import/export for Torque Pro log files
- Full data backup/restore (JSON export)
- GPX/KML trip export
- PWA support with service worker and offline capability
- Dark/light theme with system preference detection
- Mobile-responsive design with bottom navigation
- Web Vitals performance monitoring
- WebSocket-based real-time updates with polling fallback
- Rate limiting and error reporting
- Bulk trip operations (delete, restore, update, export)
