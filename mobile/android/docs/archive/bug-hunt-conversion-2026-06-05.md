# Conversion Bug Hunt - 2026-06-05

Scope: post Kotlin + TypeScript migration audit. Focused on issues created or left behind by the conversion: stale source contracts, weak type seams, old Java/JS guardrails, and Kotlin interop casts.

Status: 30 validated findings, 30 fixed in this pass.

## Findings

| # | Area | Layman explanation | Evidence | Impact | Ease |
|---|------|--------------------|----------|--------|------|
| 1 | No production-Java regression gate | The tree had 0 production Java files, but the full gate did not explicitly fail if one came back. | Added `verifyNoMigrationSourceStragglers` in `mobile/android/build.gradle:156`; wired into `verifyActiveApp` at `mobile/android/build.gradle:191`. | Medium | Easy |
| 2 | No dashboard-source-JS regression gate | The dashboard source had moved to TS, but a new `.js` source file could be added without a named migration failure. | Same guard checks `app/src/main/dashboard-src/js/**/*.js` in `mobile/android/build.gradle:156`. | Medium | Easy |
| 3 | Check task missed migration guard | `:app:check` inherited dashboard gates but not the new migration straggler check. | Added `verifyNoMigrationSourceStragglers` to subproject `check` wiring at `mobile/android/build.gradle:203`. | Medium | Easy |
| 4 | Dashboard build task still described JS source | The Gradle build task wording still said editable dashboard JS, which points future edits at the old source model. | Updated build task comment/description around `mobile/android/build.gradle:53`. | Low | Easy |
| 5 | Dashboard lint task still described JavaScript | The lint task said JavaScript even though it now lints TypeScript source. | Updated task description at `mobile/android/build.gradle:70`. | Low | Easy |
| 6 | Dashboard typecheck task still described `checkJs` | The typecheck task claimed `tsc --checkJs`, but `allowJs/checkJs` were removed. | Updated task description at `mobile/android/build.gradle:77`. | Medium | Easy |
| 7 | npm lint still accepted source JS | The npm lint glob still matched `{js,ts}`, hiding whether JS source should be allowed. | Narrowed to `app/src/main/dashboard-src/js/**/*.ts` in `mobile/android/dashboard-tests/package.json:10`. | Medium | Easy |
| 8 | ESLint config still accepted source JS | The ESLint file matcher still modeled mixed JS/TS source. | Narrowed `files` globs in `mobile/android/eslint.config.js:24` and `mobile/android/eslint.config.js:78`. | Medium | Easy |
| 9 | Spotless targeted impossible Kotlin production path | Spotless still targeted `src/main/java/**/*.kt`, which implied Kotlin might live beside old Java source. | Narrowed Kotlin target to `src/main/kotlin/**/*.kt` in `mobile/android/app/build.gradle:21`. | Low | Easy |
| 10 | Spotless targeted disallowed test path | Spotless allowed `src/test/kotlin`, but repo instructions keep tests under `src/test/java`. | Removed `src/test/kotlin/**/*.kt` from `mobile/android/app/build.gradle:21`. | Medium | Easy |
| 11 | Contributor doc contradicted test-location rule | `CONTRIBUTING.md` told people tests could go under `src/test/kotlin`. | Updated rule to keep tests under `app/src/test/java/...` at `mobile/android/CONTRIBUTING.md:103`. | Medium | Easy |
| 12 | README still called unit tests Java-only | The test command label said Java/Robolectric, stale after Kotlin production conversion. | Updated label to JVM/Robolectric at `mobile/android/README.md:114`. | Low | Easy |
| 13 | Demo loop accepted `Any` then cast | Kotlin conversion left `DemoPollingLoop` accepting `Any`, so wrong call sites would compile and crash later. | Constructor is now `ObdPollingEngine` at `DemoPollingLoop.kt:9`. | Medium | Easy |
| 14 | PID polling accepted `Any?` then cast | `PidPollingState` could compile with a null/wrong engine and fail at runtime. | Constructor is now `ObdPollingEngine` at `PidPollingState.kt:10`. | Medium | Easy |
| 15 | Diagnostic scan runner accepted `Any?` then cast | A one-shot scan path had the same unsafe engine seam. | Constructor is now `ObdPollingEngine` at `DiagnosticScanRunner.kt:17`. | Medium | Easy |
| 16 | Clear-DTC runner accepted `Any?` then cast | The DTC-clearing path had the same unsafe engine seam. | Constructor is now `ObdPollingEngine` at `ClearDtcRunner.kt:20`. | Medium | Easy |
| 17 | TPMS/detail runner accepted `Any` then cast | Detail probes had the same unsafe engine seam. | Constructor is now `ObdPollingEngine` at `TpmsDiscoveryRunner.kt:12`. | Medium | Easy |
| 18 | Bluetooth reporter accepted `Any?` SDP probe | The reporter silently tolerated wrong probe objects instead of making the constructor type-safe. | Constructor is now `SdpProbe?` at `BluetoothStateReporter.kt:40`. | Low | Easy |
| 19 | Competing-app detector accepted `Any?` service | Service side effects could silently disappear if a wrong object was passed. | Constructor is now `ObdService?` at `CompetingAppDetector.kt:17`. | Medium | Easy |
| 20 | Competing-app detector accepted `Any?` recorder | Logging could silently disappear if a wrong recorder object was passed. | Constructor is now `SessionRecorder?` at `CompetingAppDetector.kt:17`. | Medium | Easy |
| 21 | System snapshot accepted `Any?` summary store | Snapshot code cast the summary store instead of declaring the required type. | `collect` now takes `SessionSummaryStore?` at `SystemSnapshot.kt:29`. | Low | Easy |
| 22 | Voltage probe accepted `Any?` service | The low-voltage probe used a cast-only service seam. | Constructor is now `ObdService?` at `VoltageProbe.kt:13`. | Low | Easy |
| 23 | Materializer store accepted `Any` helper | Store materializer paths accepted wrong helpers until runtime. | Constructor is now `VoltTrackerDb` at `ObdStoreMaterialize.kt:16`. | Medium | Easy |
| 24 | Trips store accepted `Any` helper | Trip projections accepted wrong helpers until runtime. | Constructor is now `VoltTrackerDb` at `ObdStoreTrips.kt:13`. | Medium | Easy |
| 25 | Maintenance store accepted `Any` helper | Maintenance/reset paths accepted wrong helpers until runtime. | Constructor is now `VoltTrackerDb` at `ObdStoreMaintenance.kt:10`. | Medium | Easy |
| 26 | Vehicle store accepted `Any` helper | VIN/vehicle storage accepted wrong helpers until runtime. | Constructor is now `VoltTrackerDb` at `ObdStoreVehicles.kt:14`. | Medium | Easy |
| 27 | Route projection used JSON `!!` | Recent route filtering used `optJSONArray("points")!!`, an avoidable crash if the JSON shape ever drifted. | Replaced with a nullable local guard at `ObdStoreRouteProjection.kt:68`. | Medium | Easy |
| 28 | Recent sessions parsed as untyped JSON | `connection-status.ts` trusted parsed JSON rows without checking object shape. | Added `isRecentSession` and filtered parsed rows at `connection-status.ts:24`. | Medium | Easy |
| 29 | Low-voltage status used `any` | The status observer accepted any payload shape after the TS migration. | Added `LowVoltageStatus`/`StatusHandler` and unknown parsing at `connection-status.ts:14`. | Medium | Easy |
| 30 | Dashboard bridge helper used `any` and implicit return | `safeCall` spread `any[]` through the bridge and had no explicit result contract. | Changed args/result to `unknown` and explicit `undefined` returns at `connection-tools.ts:15`. | Medium | Easy |

## Validation Run

- Baseline: `./gradlew verifyActiveApp` passed before fixes; covered dashboard lint/typecheck/Vitest, Playwright, Android unit/lint/coverage, bundle size, and generated-dashboard drift.
- Strictness probe: `npm --prefix mobile/android/dashboard-tests run typecheck -- --noUncheckedIndexedAccess` still reports broad indexed-access work, mostly map/scrubber array-bound assertions. Kept as a measured follow-up rather than enabling the flag in this pass.
- Focused TS: `npm --prefix mobile/android/dashboard-tests run typecheck` passed after the TS fixes.
- Guard/lint: `./gradlew verifyNoMigrationSourceStragglers dashboardLint` passed and stored configuration cache successfully after the guard task was made cache-safe.
- Focused Android: `./gradlew :app:testDebugUnitTest --tests '*PidPollingStateTest' --tests '*DiagnosticScanRunnerTest' --tests '*ClearDtcRunnerTest' --tests '*ObdStoreTripsDbTest'` passed.
- Focused interop/data: `./gradlew :app:testDebugUnitTest --tests '*BluetoothStateReporterTest' --tests '*CompetingAppDetectorTest' --tests '*SystemSnapshotTest' --tests '*VoltageProbeTest' --tests '*ObdLocalStoreDbTest' --tests '*ObdStoreTripsDbTest'` passed.
- Final gate: `./gradlew verifyActiveApp` passed after all fixes; dashboard typecheck/lint/Vitest, Playwright, Android lint/unit tests, JaCoCo coverage, bundle budget, generated-dashboard drift, and the new migration straggler guard were all green.

## Discarded Candidates

- Remaining Kotlin `Any` values in log/JSON deep-copy paths are real generic values, not migration casts.
- `WebViewBootstrap.configure(..., bridge: Any)` is intentional because Android `addJavascriptInterface` accepts arbitrary bridge objects.
- Emitted filenames such as `js/app.js`, `dtc-lookup.js`, and lazy loader map keys are kept because the WebView ABI still ships classic JS assets even though source is TypeScript.
