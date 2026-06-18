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
./gradlew verifyStartupPerformanceOptional --no-configuration-cache
bash tools/benchmark-adb-startup-local.sh <ip:port-or-adb-serial>
bash tools/benchmark-adb-tabs-local.sh <ip:port-or-adb-serial>
bash tools/benchmark-real-db-local.sh /path/to/volttracker_obd_poc.db
bash tools/device-baseline-local.sh <ip:port-or-adb-serial>
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

`verifyStartupPerformanceOptional` is the laptop-safe wrapper. It runs the same
Macrobenchmark only when `adb devices` shows an authorized device/emulator. With
no ready device it exits successfully and writes
`build/reports/startup-benchmark-local/summary.md` explaining what was skipped.
When it does run, the same report links the AndroidX benchmark artifacts and
parses any benchmark metric JSON it can find.

`tools/benchmark-adb-startup-local.sh` is the no-install startup breakdown for
real user data. It repeatedly force-stops and starts only
`com.volttracker.obdpoc`, waits for the `VoltTracker dashboard ready` content
description, and parses `VoltStartup` logcat marks into Android launch,
WebView, dashboard JS, ready-handshake, and storage-summary spans. It never
installs, uninstalls, clears app data, or targets alternate package names.
Results are written under `build/reports/adb-startup-benchmark/`.
Each completed run also appends an aggregate record to
`build/reports/performance-trends/adb-startup-benchmark.jsonl`; the summary flags
large local regressions against the previous comparable run on the same device.
Use the `app*` metrics as the primary optimization signal; host-observed probe
times include ADB/logcat/UiAutomator polling overhead.

`tools/benchmark-adb-tabs-local.sh` measures startup-to-responsive tab switching.
It fresh-starts the app, waits for the dashboard-ready probe, taps each requested
bottom-nav tab, and waits for the dashboard's app-reported tab paint mark. The
default tap strategy uses deterministic screen coordinates so the benchmark does
not spend seconds dumping the accessibility tree before every tap; set
`VOLTTRACKER_TAB_TAP_STRATEGY=accessibility` to force the older content-desc
lookup path. By default it runs `map charge insights diagnostics settings` three
times each. Override with `VOLTTRACKER_TAB_TARGETS="map settings"` and
`VOLTTRACKER_TAB_RUNS=5` when you want a narrower or deeper run. Results are
written under `build/reports/adb-tab-benchmark/` and aggregate history is
appended to `build/reports/performance-trends/adb-tab-benchmark.jsonl`. Durable
checkpoint summaries are tracked in `docs/performance-baseline-history.md`.

`tools/benchmark-real-db-local.sh` benchmarks a real SQLite database copy. Pass
the path to a `volttracker_obd_poc.db` file; the test copies that file and any
adjacent `-wal`/`-shm` sidecars into Robolectric's temp database location before
opening it, so the source database is not mutated. Close the app or checkpoint
the database first if the source file is still being written. Results are written
to `build/reports/real-db-benchmark/summary.md` and `result.json`, including
copied-DB open time plus dashboard overview/details/trips/route/insights/SOH
read timings.

`tools/device-baseline-local.sh` is the full local capture template. It uses an
already-authorized ADB device, or attempts `adb connect` when passed an
`ip:port`, then captures device facts, app package/memory state, app-private
database files, and `files/obd-logs` through `run-as`. By default it
force-stops the app before copying the database so the SQLite/WAL snapshot is
coherent; set `VOLTTRACKER_KEEP_APP_RUNNING=1` to skip that. When a database is
available it runs `tools/benchmark-real-db-local.sh` against the pulled copy; by
default it also runs `tools/benchmark-adb-startup-local.sh` and
`tools/benchmark-adb-tabs-local.sh`. Each run writes a timestamped folder under
`build/reports/device-baseline/`. Set `VOLTTRACKER_SKIP_STARTUP_BENCHMARK=1`
or `VOLTTRACKER_SKIP_TAB_BENCHMARK=1` when you only want a narrower capture.

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
| Local startup breakdown | debug builds emit `VoltStartup` marks that identify WebView, dashboard JS, ready-handshake, and storage-summary spans | `tools/benchmark-adb-startup-local.sh <device>` |
| Startup-to-tab responsiveness | startup plus Map/Charge/Insights/Diagnostics/Settings tab readiness is trended per device | `tools/benchmark-adb-tabs-local.sh <device>` |
| Seeded storage reads | overview, details, trips, and route reads stay inside generous JVM budgets | `ObdStorePerformanceBudgetTest` |
| Real database reads | optional local timing on a copied SQLite database, useful for large restored histories | `tools/benchmark-real-db-local.sh /path/to/volttracker_obd_poc.db` |
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
