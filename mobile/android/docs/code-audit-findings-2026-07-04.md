# Code audit findings — 2026-07-04

> **Status: living checklist, revision 1 (2026-07-04).** This document tracks
> open audit findings and is updated as items are fixed or new passes complete;
> it is not a frozen point-in-time report.

Full-codebase audit (non-UI/UX findings). Every item below was validated against
the code at the cited location before inclusion. UI/UX findings are tracked
separately (see the dashboard UX work on the `claude/app-grading-feedback-xh3cw4`
branch). Grades reflect the state of the codebase on 2026-07-04 at v0.26.0.

Status legend: `[ ]` open · `[x]` done · `[-]` rejected/won't-do

## Report card

| Area | Grade |
|------|-------|
| Architecture & code organization | A- |
| Kotlin code quality & robustness | A- |
| Dashboard frontend engineering | A |
| Testing & QA | A |
| Security | A |
| Build system, CI/CD & release engineering | A |
| Documentation & developer experience | A- |
| Performance, reliability & resilience | A- |

Decisions made during review:
- `[-]` **Session JSONL retention/pruning — REJECTED by owner.** Raw session
  logs are intentionally kept forever for long-drive learning and metrics.
  Cost-side fixes that keep all data (flush batching, compression) remain fair game.
- One reported finding was disproven during validation and excluded: CI *does*
  surface retried Playwright tests (`android.yml` "Report retried Playwright tests").

---

## 1. Architecture & code organization (A-)

- `[ ]` **A1. 16 lower-layer files reference `MainActivity.TAG`.** Engine and
  persistence classes (`ObdPollingEngine.kt:176`, `SessionRecorder.kt:111`,
  `ObdPersistenceWorker`, `ClearDtcRunner`, …) log via `MainActivity.TAG`, which
  ADR 0002 forbids. Flat package → no import line → the string-scanning
  `ArchitectureBoundaryTest` never trips. `MainActivity.kt:920` admits `TAG` is an
  alias for `AppPrefs.LOG_TAG`. Fix: mechanical replace with `AppPrefs.LOG_TAG`,
  delete the alias, add `"MainActivity"` to the forbidden-reference lists.
- `[ ]` **A2. ~100 files flat in the root package contradict ADR 0002's four layers.**
  Direct cause of A1/A3: no imports to check, no `internal` visibility protection,
  layer membership invisible to readers. Fix: rename-only refactor into
  `ui/`, `service/`, `engine/` subpackages.
- `[ ]` **A3. `ArchitectureBoundaryTest` engine/service checks scan a stale 7-file
  allowlist** (`ArchitectureBoundaryTest.kt:48-57,71`). `LiveSampleReader` (~700 LOC),
  `PidPollingState`, `SessionRecorder`, `ObdPersistenceWorker` are unscanned — a
  `webView` reference in `LiveSampleReader` would pass CI. Fix: walk the whole layer
  directory like the data-layer check does.
- `[ ]` **A4. detekt `TooManyFunctions` ratchet is dictating architecture.**
  `ObdLocalStore.kt:404-406` inlines a raw query into the facade explicitly to stay
  under the ratchet; `StoreProjections` exists for the same reason. Fix: move the
  query into `ObdStoreReports`; suppress the rule for the facade or hand callers the
  role interfaces.
- `[ ]` **A5. `EngineHost` leaks concrete types, a shared lock, and raw mutable flags**
  (`EngineHost.kt:25,49,78,86`): methods take concrete `ObdPollingEngine?`, exposes
  `val ioLock: Any` synchronized in three classes across two layers, and raw
  `var cancelRetryRequested`. Fix: narrow probe hooks to a small transactor
  interface; move `ioLock` behind `withIo { }`; replace the flag with
  `requestCancel()`/`consumeCancel()`.
- `[ ]` **A6. Engine hardcodes user-facing English** (`ObdPollingEngine.kt:227-229`
  "Keep this literal in sync with R.string.status_retry_cancelled…", plus ~6 more).
  Blocks localization; latent divergence. Fix: broadcast machine-readable status
  keys; let `ObdService` resolve `R.string` (the `FailureClass` wire-name enum is the
  pattern to follow).
- `[ ]` **A7. Test seams via public mutable `@JvmField` fields** (`ObdService.kt:91-95`,
  `MainActivity.kt:67-73`) plus a live placeholder `SessionRecorder` writing under
  `java.io.tmpdir` at field-init (`ObdService.kt:58-65`). Fix: one overridable
  `createCollaborators()` per entry class; `@VisibleForTesting internal` setters.
- `[ ]` **A8. `data/*` ↔ `materialize/*` dependency contradicts ADR 0002's written rule**
  (`ObdLocalStore.kt:547-555` invokes materializers from inside the data layer; none
  of it is in `FORBIDDEN_DATA_LAYER_IMPORTS`). Fix: amend the ADR to permit it and add
  enforcement, or move `materializeSession` orchestration up into `SessionRecorder`.

## 2. Kotlin code quality & robustness (A-)

- `[ ]` **K1. Pack power/energy math duplicated across 5+ files with divergent sign
  conventions.** `-(v * c) / 1000.0` + guards + trapezoidal integration at
  `ChargeSessionMaterializer.kt:294` AND `:325`, `EventNotificationDecider.kt:262`;
  positive-sign variants in `TripMaterializer.kt:439` and `ObdStoreTrips.kt:530` (SQL).
  The "carry last-known voltage" fix was already hand-copied to two sites. Fix: one
  `PackPower` helper + one integrator; all call sites delegate.
- `[ ]` **K2. IO deadlines/durations use wall-clock time.** `ElmConnection.kt:25`
  defaults `Clock` to `System.currentTimeMillis()`; NTP/carrier clock steps can
  stretch or instantly expire command timeouts and falsely trip
  `lastTransactTruncated`. Fix: `SystemClock.elapsedRealtime()` for deadlines; wall
  clock only for persisted timestamps.
- `[ ]` **K3. Thread interrupt turns the transact loop into a busy-spin.**
  `ElmConnection.kt:309-315`: `sleep()` catches `InterruptedException`, re-sets the
  flag, returns normally — every later sleep throws immediately; loop spins at 100%
  CPU until deadline. `cancel(true)` makes this a normal event. Fix: exit read loops
  on interrupt.
- `[ ]` **K4. Mode-22 dispatch is a ~360-line `when` table** (`ObdVoltMode22Decoder.kt:25-378`);
  the registry migration that fixed Mode-01 (`ObdKnownValueParserRegistry`) stopped
  halfway; detekt notes `parseKnownValueLegacy` still at CC 178. Fix: represent PIDs
  as data rows consumed by the registry; keep bespoke decoders as lambdas.
- `[ ]` **K5. Check-then-`!!` race in widget telemetry coalescing** (`ObdService.kt:648-649`):
  two reads of the `AtomicReference`; safe only via an undocumented single-executor
  invariant. Fix: `latestWidgetTelemetry.get()?.let { enqueueWidgetTelemetry(it) }`.
- `[ ]` **K6. Hand-copied multi-catch filter with varying type lists** (6+ sites in
  `DataBackup.kt`, `BackupController.kt`), with `SwallowedException`/
  `TooGenericExceptionCaught` disabled globally in detekt so drift is unflagged.
  Fix: extract an inline `recover(vararg types…)` helper; per-file `@Suppress`
  instead of the global disable.
- `[ ]` **K7. `ObdService.onDestroy` closes the store without awaiting the runner**
  (`ObdService.kt:302-317`): `shutdownNow()` then `localStore?.close()`; the inline
  lifecycle fallback can then write to a closed store, logging spurious
  "lifecycle persist failed" noise at teardown. Fix: `awaitTermination(2s)` before
  `recorder.shutdown()`, or a closed flag checked by the inline fallback.

## 3. Dashboard frontend engineering (A)

- `[ ]` **D1. `parsePayload<T>` is an unchecked cast; validators cover 3 of ~15 payload
  kinds.** `core.ts:918-936` blind-parses; `payload-validators.ts` covers only
  `setStatus`/`setStorage`/`setAppState` while ~20 sites cast native JSON to rich
  types (`VoltTrip[]`, `MapRoute`, …). A renamed native field fails as silent `--`
  tiles. Fix: extend `PAYLOAD_SPECS` to trip/route/insights/maintenance, or default
  `parsePayload` to `unknown` with per-shape helpers.
- `[ ]` **D2. Native→JS callback surface untyped** (`dashboard-globals.d.ts:961`:
  `VoltTrackerNative?: Record<string, (...args: any[]) => any>`) while the JS→native
  direction is fully typed. Names are contract-tested; shapes are not. Fix: concrete
  `VoltNativeCallbacks` interface.
- `[ ]` **D3. No `visibilitychange` gating anywhere; unconditional 1 Hz interval**
  (`telemetry.ts:1565`). Backgrounded WebView keeps ticking on battery. Fix: gate the
  tick and renders on `document.hidden` with one catch-up render on resume.
- `[ ]` **D4. Several listeners bypass the AbortController discipline** the codebase
  itself documents (`drive.ts:650`, `insights-panel.ts:1057`, `scrubber.ts:798`,
  `connection-status.ts:560-563` bind bare). Fix: route through the module
  controllers so the teardown claim is true.
- `[ ]` **D5. `actions.ts` (1,603 lines) remains a grab-bag** (map gestures, trip
  search, demo lifecycle, DTC search, status ingestion, boot scheduling). Fix:
  continue the `actions-*` extraction; demo lifecycle could join the lazy chunk.
- `[ ]` **D6. `const L: any` unchecks all of `map.ts` (2,052 lines)**
  (`dashboard-globals.d.ts:613`). Fix: finish the minimal Leaflet interfaces already
  sketched nearby (~a dozen APIs used).
- `[ ]` **D7. i18n catalog covers ~7 strings**; remaining copy hard-coded across TS,
  partials, and data attributes. Fix: ratchet-test the literal count in high-churn
  modules; new copy goes through `t()`.
- `[ ]` **D8. `HistoryDevice` reused for two different native payloads**
  (`candidate` vs `obdCandidate`, `core.ts:21-27` vs `:1244`; Kotlin emits both from
  `DeviceCatalog.kt:69,109`) — compiles only via the open `Record` intersection.
  Fix: split `PairedDevice`/`HistoryDevice` without the index signature.

## 4. Testing & QA (A)

- `[ ]` **T1. `ObdVoltMode22Decoder` (633 lines) has no dedicated test** — zero test
  files reference it; header admits constants are reverse-engineered and unvalidated.
  Only ~a dozen PIDs exercised indirectly. Fix: table-driven test over the full
  dispatch table + per-class JaCoCo floor.
- `[ ]` **T2. `core.ts` has the weakest floors in the suite** (statements 61 /
  branches 41, `vitest.config.js:93-98`) despite every page routing through it.
  Fix: targeted branch tests, then ratchet.
- `[ ]` **T3. `AutoDtcScanRunner` has no direct unit test** despite the documented
  "never throws — a probe failure must not break the connect path" contract.
  Fix: direct test with scripted engine covering IOException / NO DATA / malformed
  frames / completed-vs-not.
- `[ ]` **T4. `actions-page-scroll.ts` is the only TS module with zero test references.**
  Fix: small jsdom spec.
- `[ ]` **T5. Untested root-package glue riding the 0.80 aggregate floor:**
  `DashboardBroadcastCoordinator` (158 LOC), `EventNotificationHostDelegate`,
  `DemoPollingLoop`, `SessionHealthTracker`. Fix: dedicated tests for the first and
  last; per-class floors if pure.
- `[ ]` **T6. No full-chain migration test** (each vN→vN+1 step is tested in
  isolation; a genuine v4 DB upgraded straight to current is not). Fix:
  `upgradeFromV4_toLatest_endToEnd` with representative seeded rows.
- `[ ]` **T7. Timing-dependent tests using `Thread.sleep`**
  (`DeviceCatalogHistoryTest.kt:79,262`, `ObdLocalStoreDbTest.kt:1022`,
  `WidgetUpdaterTest.kt:164`, `ObdPollingEngineTest.kt:575`) — the codebase already
  documents the fake-clock fix pattern (`ElmConnectionExtraTest.kt:190`). Fix: inject
  fake clocks.

## 5. Security (A)

Nothing exploitable found at Medium+ severity. Hardening items:

- `[ ]` **S1. `transact()` response buffer is time-bounded but not size-bounded**
  (`ElmConnection.kt:219-246`; `drainInput` caps at 8192 but `transact` doesn't).
  A babbling paired adapter can stream MBs per transaction into heap + raw logs.
  Fix: cap at ~64 KiB, set `lastTransactTruncated`.
- `[ ]` **S2. Dashboard served from `file://` origin instead of `WebViewAssetLoader`**
  (`WebViewBootstrap.kt:24-25,138`). CSP `'self'`/same-origin semantics are weaker on
  `file:`. Current mitigations contain it; migration is hardening.
- `[ ]` **S3. VIN hash is unsalted SHA-256 of a low-entropy identifier**
  (`ObdStoreVehicles.kt:74-80`) stored alongside `vin_redacted` — offline
  brute-forceable in seconds, defeating SECURITY.md's promise. Fix: per-install salt
  or Keystore-keyed HMAC (it's only a local dedupe key).
- `[ ]` **S4. Backup passphrase as immutable `String` + dialog input
  `autocomplete="current-password"`** (`VoltBridgeDataExports.kt:22-37`,
  `app-dialog.html:30-31`). Fix: `new-password`/`off` on the inputs; optionally
  `char[]` + wipe at the `BackupCrypto` boundary.
- `[ ]` **S5. `logClientError` allows logcat line injection** (`VoltBridge.kt:302-316`;
  `bridgeSafe` doesn't strip `\r\n`/control chars; the `startupMark` path already
  sanitizes). Fix: same sanitizer for `label`/`detail`.
- `[ ]` **S6. Plain unencrypted full-DB export (incl. location history) is an
  equal-prominence peer of the encrypted path** (`VoltBridge.kt:118-121`). Fix:
  encrypted by default; plaintext behind an explicit confirmation naming location
  history.
- `[ ]` **S7. `KILL_BACKGROUND_PROCESSES` permission** (`AndroidManifest.xml:24`) —
  justified and allowlist-gated, but unusual; consider deep-linking to App Info
  (`ACTION_APPLICATION_DETAILS_SETTINGS`) instead and dropping the capability.

## 6. Build, CI/CD & release engineering (A)

- `[ ]` **B1. `buildDashboardJs` can ship a stale bundle after a dependency bump.**
  `build.gradle:255-264` declares only the TS dir + `build.mjs` as inputs, but the
  output depends on `package-lock.json` (Leaflet assets copied from `node_modules`;
  esbuild version shapes output). Fix: add the lockfile as an input; add
  `mobile/android/dashboard-tests/**` to the emulator-smoke path filter.
- `[ ]` **B2. No `timeout-minutes` on any job except emulator-smoke** (1 occurrence in
  `android.yml`, 0 in `release.yml`). A hang burns the 360-min default and blocks
  the branch concurrency slot. Fix: size from the wall-clock numbers jobs already
  print to `GITHUB_STEP_SUMMARY`.
- `[ ]` **B3. Bundle budgets defined in three places; the Python trend-snapshot step
  hardcodes them and already omits the lazy-CSS budget** (`android.yml:194-197` vs
  `build.gradle:39-43`). Fix: emit budgets from Gradle to JSON; both consumers read it.
- `[ ]` **B4. Lefthook has no Kotlin formatting hook** (`lefthook.yml`: `spotless-java`
  globs `**/*.java` only) in a Kotlin-complete codebase. Fix: add
  `:app:spotlessKotlinCheck` on `**/*.kt`; consider `:app:detekt` in pre-push.
- `[ ]` **B5. Dashboard HTML assembly logic duplicated** between generator
  (`app/build.gradle:270-282`) and verifier (`build.gradle:335-346`) —
  character-for-character load-bearing. Fix: extract a shared method both call.
- `[ ]` **B6. CI Gradle caching is dependency-only** (`cache: gradle` in setup-java);
  build/config cache cold each run; seven sequential `--no-daemon` invocations in the
  unit-tests job. Fix: `gradle/actions/setup-gradle`, or share one daemon per job.
- `[ ]` **B7. `npm audit` is a blocking PR gate for dev-only trees**
  (`android.yml:333-337,508-513`) — a new upstream advisory reddens every open PR at
  once. Fix: keep it blocking in the release lane; move PR-time audit to the
  scheduled snapshot workflow or add an allowlist mechanism.
- `[ ]` **B8. `versionCode` from per-workflow `GITHUB_RUN_NUMBER`**
  (`app/build.gradle:77`) — cross-lane monotonicity unguaranteed; local builds get
  `versionCode=1` and hit downgrade rejection. Fix: single monotonic source
  (`git rev-list --count HEAD` or date-based).

## 7. Documentation & DX (A-)

- `[ ]` **DOC1. Missing LICENSE file.** `README.md:70` says "MIT License — see LICENSE
  file"; no LICENSE exists anywhere. Legally all-rights-reserved until fixed.
  Fix: add a standard MIT LICENSE at the root (two minutes, high consequence).
- `[ ]` **DOC2. Architecture roadmap six schema versions stale.**
  `mobile-architecture-roadmap.md:17` says v9; `VoltTrackerDb.kt:183` says 15
  (`data-model.md` says 14). Both CONTRIBUTING files point newcomers here.
  Fix: update; link the roadmap to `data-model.md` for the number; consider a
  doc-freshness grep gate (the repo already has drift gates for generated HTML).
- `[ ]` **DOC3. Root CONTRIBUTING stale toolchain facts** ("SDK 36" vs compileSdk 37;
  gate list omits detekt/typecheck/Playwright; leads with Java formatting in a
  Kotlin-first repo). Fix: correct and extend, or defer to the Android-level doc.
- `[ ]` **DOC4. `reports-index.md` no longer indexes ≥8 existing docs** (newest indexed
  entry 2026-06-17; releases continue through 2026-07-03). Fix: add missing rows;
  archive resolved dated bug-hunts per the index's own convention.
- `[ ]` **DOC5. Personal Windows paths hardcoded in Android README**
  (`README.md:423,434`: `C:\Users\Justin\OneDrive\...`). Fix: relative placeholders
  + bash equivalents.
- `[ ]` **DOC6. Android README still titled "POC"** for a v0.26 shipping app. Fix:
  retitle; note the historical package name once.
- `[ ]` **DOC7. No single "start here" prerequisite block** (JDK/SDK/licenses/Node
  steps split across two files; `./gradlew doctor` only documented at Android level).
  Fix: 4-line prereq block above the quick-start in root CONTRIBUTING.
- `[ ]` **DOC8. Agent-workflow internals leak into contributor docs**
  (grade-report/`.Codex` references in README/CONTRIBUTING). Fix: move to
  AGENTS.md/CLAUDE.md.

## 8. Performance, reliability & resilience (A-)

- `[ ]` **P1. Mid-drive reconnect gives up permanently after ~2-3 minutes.**
  `MAX_RECONNECT_ATTEMPTS = 6` (`ObdProbes.kt:11`), backoffs ≈90s total, then
  `reportReconnectExhausted` → `stopSelf()` (`ObdPollingEngine.kt:394`). The only
  recovery trigger (ACL-connected receiver) is registered in `MainActivity.onResume`
  and gone while the phone is pocketed — the rest of the drive is silently lost.
  Fix: low-power extended retry tier (60s interval for 10-15 min) when the vehicle
  was in a driving state, or move the ACL receiver into the foreground service.
  **Highest-impact item in this document.**
- `[ ]` **P2. Session JSONL flushes to flash on every line, inside the OBD IO lock**
  (`ObdSessionLog.kt:91`, called synchronously under `ioLock` from the poll path) —
  5-15 small flash writes/sec for hours; adds latency to every adapter transaction.
  Fix: periodic (~1s) or every-N-lines flush; keep `writeDurable` fd-sync for errors.
  (Retention itself is intentional — see decisions above. This is only about flush
  batching.)
- `[ ]` **P3. Every telemetry sample triggers a second, heavier `setAppState` publish**
  (`DashboardBroadcastCoordinator.kt:115-120` → `MainActivity.getAppStateJson`
  rebuild + second `evaluateJavascript` per sample on the main thread). Fix: publish
  app-state only on status/permission/storage changes, or throttle like
  `StorageSummaryPublisher`.
- `[ ]` **P4. A system broadcast per telemetry sample even with no receiver alive**
  (`ObdService.kt:718-726`, ~4,200/hour through system_server; Activity unregisters
  on pause; same process). Fix: in-process bus (listener registry / SharedFlow).
- `[ ]` **P5. Process death leaves session rows `active` forever** — no startup
  reconciliation; `ensureRollupsAndCollectActive` (`ObdStoreTrips.kt:286-305`)
  recomputes the orphan's drive windows on every trips read; the dead session can be
  selected as the "in-progress" route (`ObdLocalStore.kt:396-413`). Fix: on store
  open, finalize `active` sessions whose `last_event_at` is stale, stamping
  `ended_at_ms` from the last telemetry row (status `interrupted`).
- `[ ]` **P6. Baseline profile is hand-authored and stale relative to the benchmark
  rig** (`baseline-prof.txt:1-3` says "no Macrobenchmark rig yet" — the rig now
  exists in `startup-benchmark/`). Fix: add a `BaselineProfileRule` generator and
  regenerate from the measured cold-start + first-telemetry path.

---

*Future deeper audit passes append here as dated revisions.*
