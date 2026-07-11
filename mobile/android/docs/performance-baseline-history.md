# Performance Baseline History

This file records durable performance checkpoints. The full raw benchmark
reports and JSONL trend files are local build artifacts under
`mobile/android/build/reports/` and are intentionally not committed.

## 2026-06-17: Local Device Startup And Tab Baseline

Device serial: `wireless-adb-device`

Package: `com.volttracker.obdpoc`

Build type: debug APK installed with `adb install -r`, app data preserved.

Startup report:
`build/reports/adb-startup-benchmark/20260618T003413Z/summary.md`

Tab report:
`build/reports/adb-tab-benchmark/20260618T003437Z/summary.md`

This supersedes the earlier `20260618T001015Z` / `20260618T000758Z` local
baseline, which measured mostly host UiAutomator/accessibility polling overhead
for dashboard-ready and tab-title observation. It also supersedes the
`20260618T002327Z` / `20260618T002623Z` app-mark baseline after post-ready
native refresh and hidden dashboard rendering were moved farther off the
startup interaction path.

### Startup

| Host Metric | Median | Min | Max | Runs |
|---|---:|---:|---:|---:|
| Android `amTotalTimeMs` | 358 ms | 332 ms | 413 ms | 5 |
| Android `amWaitTimeMs` | 359 ms | 334 ms | 417 ms | 5 |
| Host `am start -W` duration | 533 ms | 474 ms | 680 ms | 5 |
| Host start to dashboard-ready probe | 783 ms | 754 ms | 928 ms | 5 |

| App Metric | Median | Min | Max | Runs |
|---|---:|---:|---:|---:|
| JS bootstrap start | 252 ms | 240 ms | 311 ms | 5 |
| Initial Drive render done | 380 ms | 341 ms | 403 ms | 5 |
| First dashboard frame | 386 ms | 347 ms | 408 ms | 5 |
| Dashboard-ready probe set | 386 ms | 347 ms | 410 ms | 5 |

### Startup Spans

| Span | Median | Notes |
|---|---:|---|
| WebView load to visible commit | 250 ms | `webview_load_url_start` -> `webview_page_commit_visible` |
| Page start to finish | 142 ms | `webview_page_started` -> `webview_page_finished` |
| Load URL to JS bootstrap | 130 ms | `webview_load_url_start` -> `js:actions_bootstrap_start` |
| Activity onCreate | 115 ms | `activity_on_create_start` -> `activity_on_create_end` |
| JS bootstrap to initial render | 101 ms | `js:actions_bootstrap_start` -> `js:actions_initial_render_done` |
| Storage summary read | 90 ms | Delayed post-ready background read |
| Open local SQLite store | 2 ms | `local_store_open_start` -> `local_store_open_end` |
| Ready native completion | 0 ms | `dashboard_ready_bridge_start` -> `dashboard_ready_native_complete` |

Post-ready device/storage refresh now starts around 1.0 seconds after process
startup, and hidden secondary dashboard rendering around 1.4 seconds. Those
tasks still hydrate Drive/Settings, but no longer compete with first frame or
immediate tab taps.

### Startup-To-Tab

Tap strategy: `coordinate`

Screen size: `1440x3120`

| Target | Runs | Ready source | Tab source | App ready median | JS switch median | JS paint median | App start to tab paint median |
|---|---:|---|---|---:|---:|---:|---:|
| Map | 3 | `logcat:3` | `logcat:tab-paint:3` | 369 ms | 7 ms | 11 ms | 736 ms |
| Charge | 3 | `logcat:3` | `logcat:tab-paint:3` | 384 ms | 4 ms | 8 ms | 774 ms |
| Insights | 3 | `logcat:3` | `logcat:tab-paint:3` | 343 ms | 7 ms | 11 ms | 747 ms |
| Diagnostics | 3 | `logcat:3` | `logcat:tab-paint:3` | 338 ms | 8 ms | 12 ms | 729 ms |
| Settings | 3 | `logcat:3` | `logcat:tab-paint:3` | 340 ms | 9 ms | 13 ms | 738 ms |

Host-observed tab timings remain diagnostic only because ADB input and logcat
polling can add hundreds of milliseconds or occasional multi-second outliers.
The app-reported `jsTabPaintMs` and `jsTabPaintEndElapsedMs` are the primary
regression signals.

### Follow-Up Baselines To Capture

- Run `tools/benchmark-real-db-local.sh` against the 500 MB / 1 million line
  database.
- Run `tools/generate-synthetic-db-local.sh build/reports/local/synthetic-1m.db`
  and benchmark that fixture when private database access is not available.
- Capture per-tab data request/received/rendered timings alongside tab paint for
  Map, Charge, Insights, Diagnostics, and Settings.
- Establish pass/fail SLOs after a few more clean baselines show normal local
  device variance.
- ~~Capture first-useful-OBD-sample timing using the `obd_*` marks~~ — the
  marks and gate now exist (see the 2026-07-11 section below); what remains is
  recording the first real-device numbers into that section's table.

## 2026-07-11: OBD Connect/Scan Latency Baseline (provisional)

The `obd_*` `VoltStartup` marks referenced by the 2026-06-17 follow-ups now
exist. Debug builds emit, per session:

- `obd_connect_request:<mode>` when `ObdService.startSession` accepts a
  connect/scan/demo/tpms-scan/clear-dtc request,
- `obd_socket_connected` after the RFCOMM socket + wake nudge succeed,
- `obd_elm_init_start` / `obd_elm_init_end` around ELM327 init,
- `obd_first_sample:live` (or `:demo` from the demo loop) right after the
  first telemetry broadcast, and
- `obd_scan_start/stage/complete:<...>` around the diagnostic-scan stages
  (adapter identity, protocol/VIN sweep, DTC reads, deep probes).

`tools/perf/check_obd_latency.py` parses any logcat dump into per-session
spans and enforces ceilings; `scripts/emulator-smoke.sh` runs it with
`--require demo` so the connect→first-sample path is captured and gated on
every emulator smoke without a car (`ObdLatencyMarksContractTest` pins the
mark names on both sides).

### Measured spans

**Pending first device capture** — no device or emulator was attached when
this machinery landed, and numbers are never fabricated. Fill this table from
the first real capture:

| Span | Mode | Median | Min | Max | Runs |
|---|---|---:|---:|---:|---:|
| Connect request → first sample | demo | pending first device capture | — | — | — |
| Connect request → first sample | live | pending first device capture | — | — | — |
| Connect request → socket connected | live | pending first device capture | — | — | — |
| ELM327 init | live | pending first device capture | — | — | — |
| Quick scan total | scan | pending first device capture | — | — | — |
| Full scan total | scan | pending first device capture | — | — | — |

Capture commands (debug build):

```sh
# Demo session, no car needed (also what CI's emulator smoke runs):
bash scripts/run-emulator-smoke-local.sh
python3 tools/perf/check_obd_latency.py \
  --logcat build/emulator-smoke-logcat.txt --require demo

# Real adapter: connect (or run a scan) in the app, then:
adb logcat -d | python3 tools/perf/check_obd_latency.py --logcat -
```

### Provisional ceilings

Until a few clean captures establish real variance, the gate uses generous
order-of-magnitude ceilings (defaults in `check_obd_latency.py`, each
overridable per run). They derive from the contract expectations — first live
telemetry must not wait on the deferred VIN/mode-01/voltage probes, the
ATSP6-pinned init avoids the ~4.8 s auto-detect sweep, and a quick scan should
"return in seconds":

| Span | Provisional ceiling |
|---|---:|
| Demo connect → first sample | 15,000 ms |
| Live connect → first sample | 45,000 ms |
| Connect → socket connected | 30,000 ms |
| ELM327 init | 25,000 ms |
| Quick scan total | 120,000 ms |
| Full scan total | 900,000 ms |

Ratchet plan: after the measured-spans table above has real numbers from a few
sessions, lower each ceiling to roughly 3× the observed median and record the
change here.
