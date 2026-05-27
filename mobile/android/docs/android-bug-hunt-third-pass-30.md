# Android Bug Hunt Third Pass: 30 Findings

Date: 2026-05-26

Scope: Android app only (`mobile/android/`). I ignored `archive/`, did not hand-edit generated dashboard HTML, and checked the two existing polish audit docs so this list stays distinct from the earlier 60 findings.

## Resolution Update

Fixed on branch `codex/fix-android-third-pass-bugs`. The fixes remove the lint baseline, make lint warnings fatal, quiet the Java 24/JaCoCo and Robolectric test noise, repair the dashboard route/sample/troubleshooter issues, preserve JSON nulls for unknown data, harden restore/export paths, and add focused tests for the changed behavior.

Post-fix validation:

- `cd mobile/android && ./gradlew :app:testDebugUnitTest`: passed with clean output.
- `cd mobile/android && ./gradlew :app:assembleDebug :app:lintDebug :app:spotlessCheck :app:jacocoTestCoverageVerification`: passed with clean lint and coverage above the ratchet.
- `cd mobile/android/dashboard-tests && npm test`: passed with 7 files / 32 tests and no binding or npm update noise.

## Validation Run

- `cd mobile/android && ./gradlew :app:testDebugUnitTest`: passed, but emitted repeated JaCoCo instrumentation stack traces: `Unsupported class file major version 68` on local Java 24.0.1.
- `cd mobile/android && ./gradlew :app:assembleDebug :app:lintDebug :app:spotlessCheck`: passed, but lint reported 3 current warnings and `1 error and 4 warnings filtered by baseline lint-baseline.xml`.
- `cd mobile/android/dashboard-tests && npm test`: initially failed before dependencies were installed (`vitest: command not found`), then passed after `npm install`. The passing run still printed repeated dashboard binding warnings for a missing `#mapDriveChips` fixture node.
- `cd mobile/android && ./gradlew :app:jacocoTestCoverageVerification`: passed.
- `git status --short --branch`: detached HEAD, clean except for this audit document after creation.

## Findings

| # | Area | Layman explanation | Evidence | Impact | Ease |
|---|------|--------------------|----------|--------|------|
| 1 | JaCoCo is noisy on the current JDK | The normal unit-test command passes while dumping scary coverage stack traces, so real failures become easier to miss. | `:app:testDebugUnitTest` emitted `Unsupported class file major version 68`; local `java -version` is 24.0.1; `app/jacoco.gradle:3-5` pins JaCoCo `0.8.12`. | Medium | Easy |
| 2 | Lint baseline hides current debt | Lint says the build is green while suppressing one error and four warnings, so CI can drift without anyone seeing the real state. | `:app:lintDebug` reported `1 error and 4 warnings filtered by baseline`; `app/build.gradle:103-107` enables the baseline and keeps warnings non-fatal. | Medium | Easy |
| 3 | Lint warnings do not fail the build | The build can keep passing even as warnings accumulate, which makes the warning budget meaningless. | `app/build.gradle:103-107` has `warningsAsErrors = false`; the same lint run still reported 3 active warnings. | Medium | Easy |
| 4 | Foreground-service type constants trigger API warnings | The app references API 29 service-type constants while supporting API 23, leaving platform compatibility warnings in every lint run. | Lint flags `ObdService.currentForegroundServiceType`; `ObdService.java:456-460` reads `ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` and `LOCATION`. | Medium | Easy |
| 5 | Competing-app scan still hits package-visibility warning | The troubleshooter may miss other OBD apps on Android 11+ because installed-app visibility is restricted. | Lint flags `CompetingAppDetector.java:134`; the production path uses `packageManager.getInstalledApplications(0)` at `CompetingAppDetector.java:127-139`. | Medium | Medium |
| 6 | API 24 call is hidden by the lint baseline | A min-SDK-23 app still has a baseline entry for `BooleanSupplier#getAsBoolean`, which lint considers API 24-only without desugaring. | `app/lint-baseline.xml:4-13` points at `ElmConnection.java:83`. | Medium | Easy |
| 7 | Backup metadata warning is hidden by the lint baseline | Older Android backup behavior is unclear because `dataExtractionRules` is configured without the companion setting lint expects for min SDK 23. | `AndroidManifest.xml:40-43` sets `android:dataExtractionRules`; `app/lint-baseline.xml:26-35` suppresses `DataExtractionRules`. | Low | Easy |
| 8 | Launcher icon warnings are hidden by the lint baseline | The adaptive icons are missing monochrome variants, so themed icons may look broken even though lint stays green. | `app/lint-baseline.xml:37-57` suppresses `MonochromeLauncherIcon` for both launcher icons. | Low | Easy |
| 9 | Dashboard tests tolerate binding warnings | The dashboard test harness can omit production nodes and still pass, so future ID drift may hide in noisy logs. | `dashboard-tests/setup/load-dashboard.js:189-202` defines map DOM without `mapDriveChips`; `actions.js:265` binds it; `npm test` printed `listener bind skipped: missing #mapDriveChips`. | Medium | Easy |
| 10 | Sample map fallback masks real empty-route state | A phone with real sessions but no GPS route can show fake sample drives instead of the true empty state. | `panels.js:9-33` loads sample data whenever `recentRoutes` is empty, regardless of whether the payload came from the Android bridge. | Medium | Medium |
| 11 | Sample fallback blocks real route-less storage updates | After sample data is loaded, route-less real storage updates are ignored, so counts and DTCs can stay stale until a route exists. | `panels.js:22-28` returns early when `_mapSampleLoaded` is true and incoming `recentRoutes` is empty. | High | Medium |
| 12 | Last-drive chip never sees real route IDs | The Drive tab looks for a top-level `sessionId`, but real routes put the ID under `route.session.id`, so the "Last drive" chip can disappear after real drives. | `drive.js:149-157` filters on `r.sessionId`; `ObdStoreTrips.java:561-590` emits `route.put("session", sessionToJson(session))`. | Medium | Easy |
| 13 | Map drive chips are built with HTML strings | Route/session values are written through `innerHTML`, which is easier to break and less safe than the DOM builders used elsewhere. | `map.js:176-214` concatenates chip markup and assigns `wrap.innerHTML`; nearby dashboard code explicitly prefers DOM APIs in `panels.js:84-102`. | Medium | Medium |
| 14 | Stuck-bond suggestion checks an impossible status | The troubleshooter wants the last three sessions to be `failed`, but stored sessions use `error`, `disconnected`, `complete`, or `active`. | `troubleshooter.js:325-350` checks `status === "failed"`; `ObdLocalStore.java:41-44` defines the stored statuses; `ObdElmDecode.java:120-132` maps failures to `error` or `disconnected`. | Medium | Easy |
| 15 | "Useful telemetry" counts rows with only source/raw text | Empty or failed OBD loops can be counted as useful just because they have `source` or raw text, inflating dashboards and review metrics. | `ObdStoreSupport.java:23-31` includes source/raw in `USEFUL_TELEMETRY_WHERE`; `ObdStoreSupport.java:239-256` returns true for non-empty source/raw; `ObdPollingEngine.java:758-788` always adds `source="obd"` and raw text. | High | Medium |
| 16 | Average sample interval spans unrelated sessions | The review interval can be wildly wrong because it divides the time between the first and last sample across the entire database, including gaps between drives. | `ObdStoreSupport.java:375-390` computes `MAX(captured_at_ms)-MIN(captured_at_ms)` across all useful telemetry. | Medium | Medium |
| 17 | Lifetime distance only counts 20 sessions | Older drives disappear from "total" distance, making long-term vehicle stats undercount. | `ObdStoreTrips.java:163-168` sums route distance over `getRecentSessions(db, 20)`. | Medium | Medium |
| 18 | Insights only aggregate 100 trips | The Insights screen looks like a lifetime summary, but old trips vanish once there are more than 100. | `ObdStoreTrips.java:112-160` builds totals from `tripsJson(100)`. | Medium | Medium |
| 19 | Route fallback skips telemetry GPS when any location row exists | One partial or bad location row can block the telemetry latitude/longitude fallback, leaving the map empty despite usable GPS in telemetry samples. | `ObdStoreTrips.java:606-627` returns location-sample route data whenever any row exists; telemetry fallback only runs at `ObdStoreTrips.java:628-650` when there are zero location rows. | Medium | Medium |
| 20 | Route JSON turns unknown values into zero | Missing accuracy, bearing, speed, altitude, or SOC become real zeroes, which can draw misleading maps and charts. | `ObdStoreSupport.java:105-112` returns `0` for SQL nulls; `ObdStoreTrips.java:691-705` uses those helpers for route points. | Medium | Easy |
| 21 | Charge-session JSON turns unknown values into zero | Open or incomplete charge sessions can show 0 kWh, 0%, or ended-at-zero instead of "unknown". | `ObdStoreReports.java:519-555` uses `nullableLong`/`nullableDouble` for charge fields. | Medium | Easy |
| 22 | Battery snapshot JSON turns unknown values into zero | Missing battery SOH, capacity, current, or temperature can be presented as real zero values. | `ObdStoreReports.java:559-594` uses `nullableDouble` for battery snapshot fields. | Medium | Easy |
| 23 | Restore overwrites the live database without rollback | If the restore copy fails mid-write, the original database may already be replaced or truncated. | `BackupController.java:163-184` closes the store and copies the staged DB directly over the live DB, then deletes WAL/SHM files. | High | Hard |
| 24 | Restore validation checks only two tables | A random or stale SQLite file with `obd_sessions` and `telemetry_samples` can pass validation even if required schema is missing. | `DataBackup.java:147-166` verifies the SQLite header and only those two table names. | High | Medium |
| 25 | Restore staging has no size cap | A user-selected huge file can fill app cache/storage before validation rejects it. | `DataBackup.java:100-124` streams the whole URI into cache with no byte limit. | Medium | Medium |
| 26 | Debug export can crash on missing external files dir | Some Android states can return null for `getExternalFilesDir`; this path would throw a `NullPointerException` instead of returning a JSON error. | `DataBackup.java:37-66` constructs `new File(context.getExternalFilesDir(null), "exports")` and catches only `JSONException | IOException`. | Medium | Easy |
| 27 | Telemetry queue drops old samples silently | Under slow SQLite writes, the app discards telemetry without a counter, event, or user-visible signal. | `SessionRecorder.java:102-113` polls the oldest queue item and retries the new task without logging. | Medium | Medium |
| 28 | Session finalization proceeds after drain timeout | A slow telemetry backlog can produce incomplete trips/reviews because finalize/materialize continues after a two-second timeout. | `SessionRecorder.java:640-665` logs `telemetry drain timed out after 2s; proceeding with finalize`. | Medium | Medium |
| 29 | Competing-app refresh creates unbounded one-shot threads | Repeated connect/scan taps can spawn multiple package-manager scan threads that outlive the immediate session. | `ObdService.java:263-287` creates `new Thread(...).start()` on each session start. | Low | Medium |
| 30 | Location permission blocks OBD connection | A user who denies location cannot connect to the OBD adapter at all, even though the service can otherwise run with GPS skipped. | `PermissionGate.java:25-50` requires fine and coarse location; `MainActivity.java:238-243` blocks connect on any missing permission. | High | Medium |

## Suggested Order

1. Fix the noisy validation surface first: JaCoCo/JDK compatibility, live lint warnings, stale lint baseline, and the dashboard fixture warning.
2. Fix the high-user-impact data correctness issues: sample fallback stale storage, useful telemetry counting, restore rollback/validation, and location-permission gating.
3. Fix dashboard correctness and safety: route ID mismatch, map chip DOM building, stuck-bond status mismatch, and null-as-zero JSON projections.
4. Fix longer-tail reliability: restore size limits, debug export null handling, telemetry queue visibility, finalization timeout behavior, and competing-app thread management.

## Discarded Candidates

- Deprecated Flask/PostgreSQL receiver issues: out of scope per `AGENTS.md`.
- Generated `assets/dashboard/index.html` issues: fix targets must be source partials/assets, not generated HTML.
- Failure-class copy mismatch: checked and rejected because snake-case wire names become the uppercase keys expected by the dashboard.
- Empty adapter persistence: checked and rejected because `DeviceCatalog.remember()` returns early for blank addresses.
