# Performance Playbook

Date: 2026-06-18

Use this page when you want the shortest safe command for a performance question.
Raw reports and copied device data stay under `build/reports/`; commit only
privacy-safe summary numbers.

## Startup Only

```sh
cd mobile/android
bash tools/benchmark-adb-startup-local.sh <ip:port-or-adb-serial>
```

Compare `appFirstFrameMs`, `appDashboardReadyProbeMs`, and the span table
against `docs/performance-baseline-history.md`. Host probe times are diagnostic
because ADB polling can dominate outliers.

## Startup Plus Tabs

```sh
cd mobile/android
bash tools/benchmark-adb-tabs-local.sh <ip:port-or-adb-serial>
```

Default tabs are Map, Charge, Insights, Diagnostics, and Settings. Use
`VOLTTRACKER_TAB_TARGETS="map settings"` or `VOLTTRACKER_TAB_RUNS=5` for a
narrower or deeper run. Compare app-reported tab paint timings, not raw host tap
latency.

## Large Database

```sh
cd mobile/android
bash tools/benchmark-real-db-local.sh /path/to/volttracker_obd_poc.db
```

The script copies the DB and sidecars before opening them, so the source file is
not mutated. Close or checkpoint the app first if it is still writing. Commit
only aggregate timings, payload sizes, and row counts that cannot identify a
vehicle or route.

To reproduce large-history behavior without private data, generate a synthetic
Los Angeles route fixture and run the same benchmark against it:

```sh
cd mobile/android
bash tools/generate-synthetic-db-local.sh build/reports/local/synthetic-1m.db
bash tools/benchmark-real-db-local.sh build/reports/local/synthetic-1m.db
```

The default generator creates 500 sessions with 2,000 samples each. For a faster
smoke run, pass smaller counts:

```sh
bash tools/generate-synthetic-db-local.sh build/reports/local/synthetic-smoke.db 5 50
```

## One Safe Local Sweep

```sh
cd mobile/android
bash tools/perf-local.sh --device <ip:port-or-adb-serial> --db /path/to/volttracker_obd_poc.db --all
```

This runs the existing startup, tab, optional real-DB, and optional
device-baseline scripts and writes an index under `build/reports/perf-local/`.
It never installs, uninstalls, clears app data, or benchmarks another package.

For a fully privacy-safe large-DB sweep, generate and benchmark a synthetic DB:

```sh
bash tools/perf-local.sh --synthetic-db build/reports/local/synthetic-1m.db --real-db-only
```

## Scan And OBD Latency

Use app logs until the first-useful-sample and scan-stage reports are fully
ratcheted:

```sh
adb -s <serial> logcat -c
adb -s <serial> logcat -v time -s VoltStartup:I VoltTracker:I '*:S'
```

Capture socket open, ELM init, first decoded sample, first telemetry broadcast,
diagnostic scan stage, and persistence timing. Summaries belong in
`performance-baseline-history.md`; raw logs with VIN/GPS stay local.

## Release Candidate

```sh
cd mobile/android
./gradlew verifyPerformance --configuration-cache
./gradlew verifyStartupPerformanceOptional --no-configuration-cache
python3 tools/privacy-scan.py
```

If startup-critical files, benchmark scripts, bundle budgets, or database read
paths changed, update `performance-baseline-history.md` or note explicitly in
the PR why no new device baseline was required.
