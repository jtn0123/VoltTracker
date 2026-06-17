# Performance Contracts

Date: 2026-06-17

VoltTracker is a local-first Android logger. Performance changes should be
reviewed against the surfaces users feel: app/dashboard load, first OBD sample,
SQLite history reads, route rendering, and scan persistence.

## Validation Commands

From `mobile/android/`:

```sh
./gradlew verifyPerformance --configuration-cache
./gradlew verifyStartupPerformance --no-configuration-cache
./gradlew dashboardAssetReport
npm --prefix dashboard-tests test -- startup-budget.test.js
./gradlew :app:testDebugUnitTest --tests 'com.volttracker.obdpoc.data.ObdStoreReportsDbTest' --tests 'com.volttracker.obdpoc.data.ObdStoreRouteProjectionDbTest'
```

`verifyPerformance` runs the dashboard asset report, bundle-size gate,
dashboard Vitest suite, and Android JVM/Robolectric tests. Use
`VOLTTRACKER_ALLOW_UNSUPPORTED_NODE=1` only as a temporary local escape hatch
when a machine is not yet on Node 22.

`verifyStartupPerformance` runs the opt-in Macrobenchmark suite on a connected
API 29+ device or emulator. It is intentionally separate from `check` and
`verifyPerformance`: it installs and launches the app repeatedly, so it needs a
stable device surface rather than a normal JVM/Node CI runner.

## Current Budgets

| Surface | Contract | Gate |
|---|---|---|
| Dashboard startup JS/CSS | 360,000 bytes for `js/app.js` + CSS | `verifyDashboardBundleSize` |
| Lazy dashboard support JS | 90,000 bytes for non-DTC/non-panel lazy feature chunks | `verifyDashboardBundleSize` |
| Lazy dashboard panel JS | 45,000 bytes for deferred panel chunks such as Insights | `verifyDashboardBundleSize` |
| Lazy DTC data | 380,000 bytes | `verifyDashboardBundleSize` |
| Startup scripts | Leaflet and secondary action groups stay out of the startup path and load on demand | `script-order.test.js`, Playwright map tests |
| Dashboard startup work | deterministic JS startup/long-route budgets stay green | `startup-budget.test.js` |
| Device cold start | app launches to the dashboard-ready probe within the Macrobenchmark run | `verifyStartupPerformance` |
| Seeded storage reads | overview, details, trips, and route reads stay inside generous JVM budgets | `ObdStorePerformanceBudgetTest` |
| Route/scalar serialization | route, SOC, and power tracks cap at `MAX_TRACK_POINTS` and preserve first/last samples | `ObdStoreRouteProjectionDbTest` |
| Charge projection | whole-history useful OBD sessions are found without a capped all-session prefilter | `ObdStoreReportsDbTest` |

## Data-Layer Rules

- Dashboard-ready storage reads must stay bounded. Add lazy bridge reads for
  panel-specific work rather than expanding the boot summary.
- Queries that need whole-history correctness must say so in their API name or
  test. Do not hide a recent-session cap behind an "all" method.
- Long route and scalar tracks should sample in SQL before materializing JSON.
  Kotlin-side skipping is acceptable only for already-small cursors.
- New SQLite scale fixes need a regression test in
  `app/src/test/java/com/volttracker/obdpoc/data/`.

## OBD And Scan Rules

- First live telemetry should not wait on optional VIN, voltage, or Mode 01
  probes. Keep those deferred unless a field log proves the tradeoff changed.
- Wide scans should batch persistence where possible. Avoid per-observation
  lookup/update loops on the polling thread.
- Field latency claims need log evidence: socket timing, ELM prompt timing,
  command timing, and first useful sample timing.

## Dashboard Rules

- Edit dashboard TypeScript under `app/src/main/dashboard-src/js/`; rebuild
  generated JS through Gradle or `npm --prefix dashboard-tests run build`.
- Edit dashboard markup partials/template, then run `./gradlew generateDashboardHtml`.
- Treat startup budget raises as exceptions. Prefer lazy chunks first, then
  lower the budget after reclaiming stable headroom.
- The dashboard-ready probe is the WebView content description
  `VoltTracker dashboard ready`, set by `MainActivity.onDashboardReady()` after
  the JS bridge calls `VoltTrackerAndroid.dashboardReady()`. Keep this stable
  unless the Macrobenchmark is updated in the same change.
