# ADR 0001 — WebView + JavaScript bridge for the dashboard UI

- **Status:** Accepted (recorded 2026-05-22, reflects the as-built design since
  inception of the Android port).
- **Deciders:** Project author.
- **Supersedes:** —
- **Superseded by:** —

## Context

VoltTracker needs a multi-screen dashboard (Drive, Trips, Charge, Insights,
Diagnostics, Settings) that renders live OBD telemetry, charts a speed trace,
and shows a map of recorded routes. The data lives in on-device SQLite; the
display surface is a single Android `Activity`.

Two realistic implementation paths exist for the dashboard surface:

1. **Native Android UI** — Jetpack Compose or classic Views, with Kotlin or Java.
2. **WebView + local HTML/JS/CSS bundled in `assets/`**, talking to the Android
   layer through a `@JavascriptInterface` bridge (`VoltBridge`).

## Decision

Use **WebView + a JavaScript bridge** for the dashboard, loaded from
`file:///android_asset/dashboard/index.html`. Bundle vanilla JS, CSS, and HTML
partials in `assets/dashboard/`. Compose at most one HTML file at build time
from per-screen partials via the `generateDashboardHtml` Gradle task (wired into
`preBuild`).

The Android layer is responsible for:

- Bluetooth IO, OBD parsing, GPS, SQLite persistence, foreground service
  lifecycle, permissions.
- Publishing a single app-state payload to the WebView through
  `VoltTrackerNative.setAppState(...)`.
- Honoring `@JavascriptInterface` calls from the dashboard for actions
  (`connect`, `scan`, `clearStoredData`, `shareBackup`, etc.).

The JS layer is responsible only for rendering the state it receives and
forwarding user intent back over the bridge.

## Consequences

### Positive

- One HTML/CSS surface targets every Android version the app supports without
  per-API-level layout work.
- Leaflet is available immediately for the map without an additional native
  dependency.
- HTML/CSS iteration is fast (no rebuild required for CSS/JS edits — they load
  on next Activity restart from `assets/`).
- The dashboard is portable: the same surface could, in principle, be hosted in
  a different shell (browser, iOS WKWebView) with a different bridge.

### Negative

- Performance on low-end WebViews is noticeably worse than native rendering;
  high-frequency telemetry updates require throttling (see grade item C1).
- Historically the dashboard had no JS test framework. This is now partially
  mitigated by a Vitest + jsdom smoke suite under `mobile/android/dashboard-tests/`
  that pins the bridge ABI, the shared `VoltDashboard.state` shape, and the
  C6 stale-tile indicator. Coverage is intentionally narrow — it's a tripwire,
  not a behavioral test for every panel — so the WebView path still costs more
  per UI test than a Compose path would.
- The bridge surface (`VoltBridge`) is a manual contract — schema drift between
  Android and JS is only caught at runtime.
- Vanilla JS without modules invites global-state sprawl (see grade items C2,
  C3) as the dashboard grows.

### Mitigations

- Treat the bridge as a trust boundary even though the page is loaded
  same-origin from `file://android_asset` (see B5/E2).
- Add a Content-Security-Policy meta tag and harden the WebView settings
  (`setAllowFileAccess(false)`, no remote scripts, no `setJavaScriptEnabled`
  outside this surface — see E1).
- Adopt the `requestAnimationFrame` + `AbortController` patterns in the JS
  rather than DOM-mutation-on-every-update.
- Generate `index.html` from partials at build time so per-screen markup is
  reviewable.

## Alternatives considered

### Jetpack Compose

- ✅ Best performance, first-class Android tooling, type-safe state management.
- ❌ Requires Kotlin (the rest of the app is Java, so this is a larger change
  than it looks).
- ❌ Would need a separate map library and chart library to replace Leaflet and
  the simple canvas trace.
- ❌ More native code surface to test; would not eliminate the JS-bridge
  problem if a future shell wanted to reuse the dashboard.

### Classic Android Views

- ❌ Verbose for a fast-moving multi-screen UI.
- ❌ Same loss of Leaflet without a native MapView dependency.

## Revisit triggers

Revisit this decision if any of these hold:

- The dashboard JS exceeds ~3,000 LOC without a modules/test story.
- Sustained user reports of jank on supported devices that throttling does not
  fix.
- A feature requires native APIs that the bridge cannot reasonably surface
  (rich camera, sensor fusion, etc.).

Until then: invest in the WebView path (the C-series and D5 grade items) rather
than starting a Compose migration.
