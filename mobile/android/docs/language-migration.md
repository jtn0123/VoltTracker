# Language Modernization Plan & Tracker

Living plan for two parallel, independent modernizations:

- **Part A — Kotlin** for the Android app (currently Java).
- **Part B — TypeScript / build** for the dashboard WebView JS.

This file is the single source of truth for *what's planned, what's done, and what's
next*. Update it in the same PR as the work (see [How to update](#how-to-update)).

> Status at a glance — update the counters when a wave item lands.
>
> | Track | Done | In progress | Planned | Notes |
> |---|---:|---:|---:|---|
> | Kotlin files converted | 86 | 0 | K4 staged core | K0–K3 + K5–K10 done; lifecycle/service core remains |
> | Kotlin waves complete | K0–K3, K5–K10 | — | K4 staged behind tests | large stateful core converts only with focused coverage |
> | Dashboard JS type-safety | checkJs + full `strict` + all source `.ts` ✅ | — | optional source maps/dev server | max checking, bundled WebView output |
> | Dashboard build step | esbuild bundle + all `.ts` entries ✅ | — | optional source maps/dev server | source in `dashboard-src/js`, built `app.js` shipped |

**Status legend:** `[ ]` not started · `[~]` in progress · `[x]` done · `[-]` won't do / deferred indefinitely.

---

## Current state (baseline)

- **Android:** 98 Java files (~35k LOC) + 1 Kotlin file (`BuildFlags.kt`). AGP 9.2.1
  built-in Kotlin is enabled; Spotless/ktlint formats `.kt`; JaCoCo measures Kotlin
  (`built_in_kotlinc` output). Policy is documented in `CONTRIBUTING.md` and the repo
  `CLAUDE.md`: **new code is Kotlin; existing Java stays Java.**
- **Dashboard:** 13 first-party JS files (~14k LOC), classic IIFE pattern sharing
  `window.VoltDashboard`, loaded by ordered `<script>` tags. Type-checked by
  `tsc --checkJs` (`checkJs:true`, `noImplicitAny:true`, `strictNullChecks:false`),
  linted by ESLint, tested by Vitest (jsdom) + Playwright (e2e + visual). **No build
  step** — files ship as-is into the APK.

---

## End state (where each language lives)

Kotlin and TypeScript are **not blended** — they live in two separate runtimes that
communicate only across the WebView bridge (a string/JSON ABI, not a code boundary). A
`.kt` file never imports a `.ts` file.

| Layer | Runs | Source: today → end state | Tests: today → end state |
|---|---|---|---|
| **Native app** | on device (APK) | Java → **Kotlin** (new) + Java (legacy) | `app/src/test/java/` Java → **new tests in Kotlin**, existing stay Java |
| **Dashboard** | inside the WebView | JS → **TS** (Wave T2) *or* JS + checkJs/strict | `dashboard-tests/` `.js` → **`.ts` iff source goes TS (T2)** |

**Test-language rule:** tests are authored in whatever language their layer uses.
- Native: **write new tests in Kotlin** (the build already compiles `src/test/**/*.kt`;
  Spotless/ktlint already targets it). Don't rewrite passing Java tests.
- Dashboard: tests stay `.js` at State 0/1; they become `.ts` only if/when the source
  migrates to TS in Wave T2 (Vitest + Playwright run TS natively once esbuild is in play).

## Conversion ownership map

Use this map before converting a file. The goal is modernizing the codebase without
turning stable runtime plumbing into language-only churn.

### TypeScript-owned files
All first-party dashboard behavior under `app/src/main/dashboard-src/js/` should move to
`.ts` modules during Wave T2b. The emitted WebView assets remain classic `.js` files in
`app/src/main/assets/dashboard/js/`; source filenames and shipped filenames intentionally
diverge.

| Status | Files |
|---|---|
| Converted | `demo-data.ts`, `connection-tools.ts`, `connection-status.ts`, `dtc-causes.ts`, `dtc-lookup.ts`, `drive.ts`, `troubleshooter.ts`, `scrubber.ts`, `telemetry.ts`, `map.ts`, `core.ts`, `actions.ts`, `panels.ts` |
| Next low/medium risk | none remaining in the current queue |
| Next higher risk | none remaining in the current queue |
| Leave until late | none remaining in the current queue |

### Kotlin-owned files
New Android app code should be Kotlin. Existing Java should convert only when the file has a
clear test safety net or is already being substantially reworked. Good future Kotlin
candidates are small, tested helpers/payload utilities such as
`BluetoothStateReporter`, `DeviceCatalog`, `DiagnosticsShareIntent`, `PermissionGate`,
`RollingAppLog`, `WebViewBootstrap`, `StorageSummaryJson`,
and narrowly-scoped `ObdStore*` helpers when their
database tests cover the behavior.

### Java-for-now files
These files are intentionally not Kotlin targets for a language-only pass:
`MainActivity.java`, `VoltBridge.java`, `ObdService.java`, `ObdPollingEngine.java`,
`SessionRecorder.java`, `ObdProtocol.java`, `BackupController.java`, `DataBackup.java`,
and `TroubleshooterBridge.java`. Convert them only during a real refactor with focused tests,
because they own UI lifecycle, WebView bridge ABI, threads, Bluetooth/service control,
session persistence, or destructive backup/restore flows.

---

# Part A — Kotlin adoption

## Why Kotlin (benefits)

- **Null-safety** at the type level — the biggest correctness win for a codebase full
  of nullable OBD/telemetry values.
- **Data classes** — `equals`/`hashCode`/`toString`/`copy`/destructuring for free,
  replacing hand-written value classes.
- **Coroutines** — a cleaner model than the current threads/`Worker`s for the OBD
  polling loops (longer-term, not a near-term wave).
- **Conciseness** — far less boilerplate; sealed classes/`when` exhaustiveness for the
  classifier/state-machine code.
- It's the Android-first language; new SDK/AndroidX guidance assumes it.

## Interop rules (read before converting anything)

These are the facts that make conversions safe/unsafe here:

1. **No Java `record`s exist** — so there's no `field()`→`getField()` accessor trap.
2. **Public-field DTOs need `@JvmField`.** Java callers read `obj.session` directly. A
   Kotlin `val session` exposes `getSession()` instead and would break those callers.
   Annotate each property `@JvmField val session` to keep field access, *or* update the
   call sites to `getSession()`. Pick per file. **Prefer a plain `class` over `data
   class`** for these: the existing DTOs have no `equals`/`hashCode` (identity equality)
   and often normalize/validate in their constructors, so a `data class` would change
   equality semantics and add uncovered generated methods. See the Wave K2 decision note.
3. **Constants holders use `const val`** inside an `object` → compiles to
   `public static final`, so `Foo.BAR` keeps working and stays a compile-time constant
   (branch-folding preserved). This is the `BuildFlags.kt` pattern. `const val` only
   works for primitives + `String`.
4. **Enums convert cleanly.** Java callers (`FailureClass.INSTANT_DROP`, `.wireName()`)
   are unaffected; keep methods as methods (not `@JvmStatic`-needing) for instance
   members.
5. **Coverage:** new Kotlin is measured by JaCoCo and held to the same ratcheting
   floors (project 71% LINE, `data` package 89%). Write tests with the conversion.
6. **Wire formats are load-bearing.** `FailureClass.wireName()` snake-case strings and
   any JSON field names are an ABI with the dashboard — do not rename during a
   conversion. See `docs/bridge-abi.md`.

## Waves (order of attack)

Each wave is independently shippable. Recommended order is K1 → K2, with K3/K4
opportunistic. Tick items as they land and bump the counters at the top.

### Wave K0 — Toolchain + first file ✅ done
- [x] AGP built-in Kotlin enabled, Java 17 / Kotlin JVM 17 pinned (`app/build.gradle`)
- [x] Spotless ktlint lane for `.kt`
- [x] JaCoCo measures Kotlin classes (`app/jacoco.gradle`)
- [x] `BuildFlags.java` → `BuildFlags.kt` (constants-holder canary, 9/9 lines covered)
- [x] Policy documented (`CONTRIBUTING.md`, `CLAUDE.md`)

### Wave K1 — Constants + pure enums (clean, zero call-site churn) ✅ done
Trivial, low-risk, no Java caller changes. All converted with no call-site edits; full
build + tests + lint + coverage green, all five now measured from `.kt` by JaCoCo.

| # | File | Type | Lines | Notes |
|---|---|---|---:|---|
| [x] | `VehicleActivityThresholds.kt` | `object`+`const val` | 22 | `Foo.BAR` static access preserved |
| [x] | `FailureClass.kt` | enum | 73 | `wireName()` ABI strings kept verbatim |
| [x] | `classify/VehicleState.kt` | enum | 40 | `asPayloadKey()` → exhaustive `when` (no `else`) |
| [x] | `classify/Confidence.kt` | enum | 23 | `name.lowercase(Locale.ROOT)` |
| [x] | `materialize/Confidence.kt` | enum | 28 | distinct from `classify/Confidence` — both left in place |

### Wave K2 — Value/DTO classes → plain Kotlin class + `@JvmField` ✅ done
**Decision: plain `class`, not `data class`.** During conversion the original Java proved
this out: 8 of the 11 carry constructor logic (`nonNull`/`clean` normalization, validation,
`toJson()`, overloaded constructors) that a `data class` primary constructor can't express,
and none of the Java originals defined `equals`/`hashCode` — they used identity equality. A
`data class` would have (a) silently switched them to value equality (a behavior change for
any future map/set use) and (b) added generated `equals`/`hashCode`/`toString`/`copy` lines
that risk the `data` package's tight 89% LINE floor. Plain classes with `@JvmField val`
preserve the exact semantics: identity equality, `obj.field` Java access, normalization, and
all methods — and add accurate nullability types. Upgrading a specific class to `data class`
later is a separate, per-class decision after auditing its equality usage.

Result: all 11 converted with **zero call-site changes**; full suite green; `data` package
held at **90.5%** (floor 89%), project **76.7%** (floor 71%).

| # | File | Fields | Notes |
|---|---|---:|---|
| [x] | `data/RecentSessionSummaryRecord.kt` | 3 | ctor widened package-private → public |
| [x] | `classify/ClassifierResult.kt` | 3 | non-null `state`/`confidence` (replaces `requireNonNull`); `reasons` immutable-copied |
| [x] | `materialize/LocationSample.kt` | 5 | boxed cols → nullable |
| [x] | `materialize/MaterializerInput.kt` | 3 | `require(...)` validation preserved |
| [x] | `location/FilteredLocation.kt` | 10 | boxed cols → nullable |
| [x] | `data/ObdSessionRecord.kt` | 10 | `nonNull` normalization via property initializers |
| [x] | `materialize/PidObservation.kt` | 6 | 3 strings null→`""`; `parserKey`/`parsedNumeric` stay nullable |
| [x] | `data/StatusEventRecord.kt` | 8 | `toJson()` method ported |
| [x] | `materialize/TelemetrySample.kt` | 8 | back-compat secondary constructor kept |
| [x] | `data/AdapterHistoryRecord.kt` | 14 | `nonNull` normalization |
| [x] | `EnhancedPidProfile.kt` | 16 | `clean` (trim + null→`""`) via file-private helper |

### Wave K3 — Class-with-nested-enum + mid-size logic ✅ done (except BackupController)
More surface than K1/K2 — converted the 5 with a **test safety net**, faithfully
(identity-equality, `@JvmField` for field access, `@JvmStatic`/`const val` for statics,
`require(...)` for the validating constructors). `BackupController` has **no test**, so it
stays Java until it gets coverage — converting untested backup/restore orchestration blind
is exactly the risk the "opportunistic" rule guards against.

| # | File | Lines | Notes |
|---|---|---:|---|
| [x] | `SessionStateMachine.kt` | 90 | `@Synchronized` methods; `@JvmStatic phaseForDashboardState`; `switch`→exhaustive `when` |
| [x] | `location/LocationFilter.kt` | 152 | `Decision` enum; secondary no-arg ctor; `const val` defaults; `@JvmStatic` helpers |
| [x] | `PidSchedule.kt` | 223 | `object`; `Header`/`PidSpec` nested; `@JvmField` lists/fields; `require` validation |
| [x] | `data/BackupMigrator.kt` | 134 | `object`; `@JvmStatic`; try-with-resources → `.use {}`; multi-catch split into IOException/RuntimeException |
| [x] | `materialize/ChargeSessionMaterializer.kt` | 278 | `object`; nullable doubles captured into locals for smart-casts; arithmetic preserved exactly; `in`→`input` (Kotlin keyword) |
| [-] | `BackupController.java` | 444 | **stays Java for now.** It now has dialog/lifecycle tests, but destructive restore orchestration is still tightly coupled to concrete `MainActivity` seams (`AlertDialog`/`Intent`/`FileProvider`/`runOnUiThread` plus `activity.localStore`/`isLoggingActive`/`stopObdService`). Convert after a focused restore-path test or interface extraction. |

### Wave K4 — Large stateful core (stage behind focused tests) `[~]`
High interop surface (threads, listeners, the WebView bridge). Convert these when the
target has a focused test harness or when a refactor creates one; Git is the rollback
tool, but focused tests are the reliability tool.
- [-] `ObdPollingEngine.java`, `ObdService.java`, `SessionRecorder.java`,
  `ObdProtocol.java`, `MainActivity.java`, `VoltBridge.java`, `BackupController.java`

### Wave K5 — Small tested helpers `[~]`
After the original K0-K3 waves, continue only with files that are small and already covered by
focused JVM/Robolectric tests. This wave is opportunistic; each item should stand alone and pass
`verifyActiveApp` before moving to the next helper.

| # | File | Lines | Notes |
|---|---|---:|---|
| [x] | `SpeedPlausibilityFilter.kt` | 45 | stateful speed-glitch filter; existing Java test covers every branch |
| [x] | `ConnectionFailureClassifier.kt` | 147 | pure connect-failure classifier; existing Java test covers wire buckets |
| [x] | `AppStateJson.kt` | 37 | pure app-state payload wrapper; existing app-state tests cover dashboard shape |
| [x] | `StatusPayload.kt` | 76 | live-status payload builder; existing tests cover optional fields and extras override |
| [x] | `data/DiagnosticCodeReport.kt` | 104 | DTC report read-model; existing tests cover trimming and dashboard JSON names |
| [x] | `RollingAppLog.kt` | 178 | append-only diagnostics log; existing tests cover null flattening and 7-day rotation |
| [x] | `SystemSnapshot.kt` | 134 | session-start diagnostic payload; existing Robolectric tests cover keys and last-successful lookup |
| [x] | `WebViewBootstrap.kt` | 120 | WebView startup wiring; existing tests cover hardening, bridge attach, and origin blocking |
| [x] | `PermissionGate.kt` | 105 | runtime-permission helper; existing tests cover connect-only and optional feature permission paths |
| [x] | `DiagnosticsShareIntent.kt` | 181 | diagnostics zip/share helper; existing tests cover zip contents, cap, stale cleanup, and intent shape |
| [x] | `StorageSummaryJson.kt` | 157 | storage-summary dashboard JSON serializer; database and backup tests cover the emitted key contract |
| [x] | `TelemetryPayload.kt` | 335 | typed telemetry JSON wrapper; direct tests cover typed fields, GPS/diagnostic extras, deep-copy behavior, and null payloads |
| [x] | `DeviceCatalog.kt` | 314 | paired-adapter catalog and remembered-device history; existing Robolectric tests cover heuristics and invalid-address rejection |
| [x] | `BluetoothStateReporter.kt` | 421 | Bluetooth observability/status-streak helper; existing Robolectric tests cover SDP-refresh streak logic |

Remaining K5 evaluation:
- No remaining K5 candidates. The tested helper queue is complete.
- K4 lifecycle/service/bridge classes and backup restore orchestration are no longer
  "never convert"; they are staged behind pre-conversion tests.

### Wave K6 — Tested medium-risk helpers ✅ done
Broaden conversion beyond tiny helpers. Each item has direct tests or strong integration
coverage, and each commit should still pass focused tests plus `verifyActiveApp`.

| # | File | Lines | Notes |
|---|---|---:|---|
| [x] | `CompetingAppDetector.kt` | 124 | package allowlist detector; Robolectric tests cover filtering/order and Java override seam |
| [x] | `DashboardPublisher.kt` | 86 | WebView publish allowlist/lifecycle gate; Robolectric tests cover quoting, readiness, teardown |
| [x] | `VoltageProbe.kt` | 120 | PID-42 voltage parser/probe; JVM tests cover decode bounds and malformed frames |
| [x] | `ClearDtcRunner.kt` | 147 | Mode 04 clear-codes command runner; focused tests cover positive, negative, and unusable replies |
| [x] | `DiagnosticScanRunner.kt` | 157 | diagnostic probe sweep; new focused test captures command sequence, status progression, location append, and raw transcript |
| [x] | `PidPollingState.kt` | 306 | live PID schedule/carry-forward state; focused tests cover stale caps and Mode-01 batching |
| [x] | `SessionSummaryStore.kt` | 210 | JSONL session-summary store; focused tests cover round-trip, singleton, ordering, and retention |
| [x] | `ElmConnection.kt` | 297 | RFCOMM stream wrapper; in-memory tests cover transact prompt/timeout behavior and engine fake override seams |
| [x] | `TripMaterializer.kt` | 479 | trip materializer with focused tests |
| [x] | `DriveWindowDetector.kt` | 436 | drive-window splitter with DB/integration coverage |

### Wave K7 — Record/interface utility batch ✅ done
Converted a deliberately boring batch of ten Java files: package helpers, DTO-style records,
the materializer read interface, and the telemetry statement cache. These preserve Java field
access/static call shapes with `@JvmField`/`@JvmStatic`/companion fields where callers rely on
them, while adding Kotlin nullability on constructor inputs and JSON helpers.

| # | File | Notes |
|---|---|---|
| [x] | `AppStatePayload.kt` | app-state snapshot builder; dashboard payload tests cover emitted shape |
| [x] | `MainActivityUtils.kt` | package helper object; static Java call surface preserved |
| [x] | `SessionSummary.kt` | JSONL summary record; constants/fromJson preserved |
| [x] | `classify/ClassifierInput.kt` | classifier input validation; direct bounds tests cover failures |
| [x] | `materialize/Trip.kt` | trip DTO; constants and Java field access preserved |
| [x] | `materialize/ChargeSession.kt` | charge DTO; back-compat defaults preserved |
| [x] | `materialize/MaterializerData.kt` | read interface for materializers |
| [x] | `data/TelemetrySampleRecord.kt` | telemetry read record; `toJson()` fallback preserved |
| [x] | `data/StorageSummaryRecord.kt` | storage summary read model; defensive JSON/list/map copies preserved |
| [x] | `data/ObdStatementCache.kt` | telemetry insert statement cache; SQL static field access preserved |

### Wave K8 — Interfaces and data-store helpers ✅ done
Converted ten more files while staying outside the lifecycle/service/bridge core. This batch
focused on narrow interfaces, Android helper wrappers, the pure vehicle-state classifier, and
DB helper classes that are exercised through the local-store/materializer test suite.

| # | File | Notes |
|---|---|---|
| [x] | `BluetoothAdapters.kt` | nullable Bluetooth adapter lookup helper; static Java call surface preserved |
| [x] | `BroadcastReceiverGroup.kt` | grouped receiver register/unregister helper |
| [x] | `location/LocationTracker.kt` | GPS abstraction interface with listener SAM |
| [x] | `data/ObdQueryStore.kt` | read-side store interface |
| [x] | `data/ObdSessionStore.kt` | write/lifecycle-side store interface |
| [x] | `classify/VehicleStateClassifier.kt` | pure classifier decision table; direct tests cover rules |
| [x] | `data/ObdStoreVehicles.kt` | VIN redaction/hash/upsert helper; DB tests cover vehicle summary |
| [x] | `data/ObdStoreMaintenance.kt` | clear/checkpoint/prune/merge maintenance helper |
| [x] | `data/ObdStoreMaterialize.kt` | materializer read/write helper; materializer/local-store tests cover paths |
| [x] | `data/ObdStoreSnapshots.kt` | write-side payload/value builders and DTC/adapter upserts |

### Wave K9 — Runtime helper and probe utilities ✅ done
Converted another ten files that sit near the service/runtime surface but have focused tests
or narrow behavior: logging, notification construction, static probe catalogs, file-backed
session logs, location tracking, ELM decode helpers, the enhanced PID catalog, demo polling,
and the detail-probe runner.

| # | File | Notes |
|---|---|---|
| [x] | `LogcatMirror.kt` | explicit logcat-to-rolling-log facade |
| [x] | `OBDLog.kt` | structured static logging helper; mirror hook preserved |
| [x] | `ObdProbes.kt` | ELM UUID/probe constants; Java field access preserved |
| [x] | `ObdNotifications.kt` | foreground-service notification/channel helper |
| [x] | `ObdSessionLog.kt` | synchronized JSONL session log writer |
| [x] | `location/LocationManagerTracker.kt` | platform GPS/network tracker implementation |
| [x] | `ObdElmDecode.kt` | static decode/backoff/status helpers |
| [x] | `EnhancedPidProfiles.kt` | enhanced PID catalog and JSON export |
| [x] | `DemoPollingLoop.kt` | synthetic demo telemetry loop |
| [x] | `TpmsDiscoveryRunner.kt` | staged detail-probe runner |

### Wave K10 — Data facade, store projections, and typed runtime seams ✅ done
Converted ten more files, favoring helpers with direct DB/runtime test coverage and avoiding
the still-large service/activity/bridge classes. This wave moved the local-store facade and
schema helper into Kotlin, which gives the remaining Java callers typed constants and helper
contracts without changing the SQLite or WebView wire formats.

| # | File | Notes |
|---|---|---|
| [x] | `SdpProbe.kt` | SDP retry/cooldown helper; `open` Java test seam and default constants preserved |
| [x] | `SessionHealthTracker.kt` | session health JSON enricher; synchronized IO-lock behavior preserved |
| [x] | `ObdPersistenceWorker.kt` | bounded async persistence queues; lifecycle/telemetry rejection behavior preserved |
| [x] | `data/ObdStoreSupport.kt` | shared DB/query/JSON helpers; static Java call surface preserved |
| [x] | `data/ObdStoreRouteProjection.kt` | route projection/downsampling helper |
| [x] | `data/ObdLocalStore.kt` | main store facade; interface overrides preserved for Java/Kotlin callers |
| [x] | `data/ObdStoreTrips.kt` | trips/insights read model and rollup cache helper |
| [x] | `data/ObdStoreSessionReview.kt` | diagnostic session-review projection and warnings |
| [x] | `data/VoltTrackerDb.kt` | SQLiteOpenHelper, table constants, and migration transaction wrapper |
| [x] | `LiveSampleReader.kt` | live telemetry sample builder; Java-facing `SampleContext` interop preserved |

Remaining Java after K10: **13 files**.

| Bucket | Files | Next action |
|---|---|---|
| Data helpers still reasonable | `data/DatabaseMerger.java`, `data/ObdStoreReports.java`, `data/ObdStoreWriter.java`, `data/VoltTrackerSchema.java` | Convert one or two at a time with DB/migration focused tests. Watch Kotlin public signatures that expose helper-only types. |
| Runtime/lifecycle late-stage | `MainActivity.java`, `ObdService.java`, `ObdPollingEngine.java`, `SessionRecorder.java`, `ObdProtocol.java`, `VoltBridge.java`, `TroubleshooterBridge.java`, `BackupController.java`, `DataBackup.java` | Convert only as part of a behavior refactor or after adding focused seams/tests; these own lifecycle, threads, bridge ABI, protocol parsing, or destructive restore paths. |

---

# Part B — TypeScript / dashboard build

## The three states (differences & benefits)

The dashboard can sit at one of three levels of type-safety/tooling. We're at **State 0**
today. Here's what each adds and costs.

| | **State 0 — checkJs (today)** | **State 1 — strictNullChecks** | **State 2 — full TS + bundler** |
|---|---|---|---|
| Source files | `.js` + JSDoc | `.js` + JSDoc | `.ts` |
| Build step | **none** | **none** | **yes** (esbuild/vite) |
| Catches wrong types / bad arity | ✅ | ✅ | ✅ |
| Catches **null/undefined** misuse | ❌ | ✅ | ✅ |
| Real interfaces / enums / generics | awkward (JSDoc) | awkward (JSDoc) | ✅ native |
| ES modules (`import`/`export`) | ❌ (file:// blocks them) | ❌ | ✅ (bundler emits classic IIFE) |
| Source maps for debugging | n/a (ships source) | n/a | ✅ |
| Tree-shake / minify | ❌ | ❌ | ✅ (can *help* the 400 KB budget) |
| HMR / live reload in browser preview | ❌ | ❌ | ✅ (vite dev server) |
| Effort to reach | done | **low** (flip a flag, fix errors) | **high** (migrate source + tests + Gradle + CI) |

**Plain-English summary:**

- **State 0 → 1** is the cheapest real *correctness* win. `strictNullChecks` makes the
  compiler force every possibly-`null`/`undefined` access to be handled. No build, no
  new tooling — just turn it on and clear the errors. This is the recommended immediate
  step (**Wave T1**).
- **State 1 → 2** is an ergonomics/DX leap, not mainly a "catch more bugs" leap (State 1
  already catches the high-value class). You get real modules (kill the global-namespace
  juggling), proper TS syntax, source maps, minification, and **live reload in the
  preview loop**. It costs a build pipeline and a migration (**Wave T2**).

## The file:// constraint (why a build is the *enabler*, not just a cost)

The dashboard is served from `file:///android_asset/` in the WebView.
`<script type="module">` is fetched with CORS semantics that `file://` can't satisfy, so
**ES modules silently never execute on-device** (see the comment block in
`app/src/main/assets/dashboard/index.html` and `docs/dashboard-script-contract.md`).
That's why every file is a self-contained IIFE sharing `window.VoltDashboard`.

Consequence: you **cannot** write `import`/`export` source without a bundler. A bundler
(esbuild/vite) compiles modules down to a **single classic IIFE script** that loads fine
from `file://`. So "TS requires a build" is the same statement as "modules require a
build" — the build is what unlocks both.

**Lazy chunks must stay classic.** `dtc-lookup.js` / `dtc-causes.js` (and `demo-data.js`)
are loaded on demand by injecting a classic `<script>` (`core.js` `loadDashboardScript`).
A bundler's dynamic `import()` code-splitting would emit *module* chunks and reintroduce
the file:// problem. Keep lazy data as **separate IIFE entry points** the app injects the
same way — not auto-split chunks.

## Waves

### Wave T1 — `strictNullChecks` (zero build) ✅ done
- [x] Flip `strictNullChecks: true` in `dashboard-tests/tsconfig.json`
- [x] Triage: 709 raw errors → **656 were noise** from the `VoltDashboard` interface declaring
      every member optional. Root-cause fix: model the eager-script API as **required** and keep
      only genuinely-absent members optional (`bridge` is null off-WebView; the lazy dtc-* members
      load via `ensureDtcData()`). That left **53 real errors**.
- [x] Fix the 53: `telemetry.js` canvas `ctx` null-guard (19); 5 bootstrap `|| {}` casts;
      `core.js` Promise `resolve(undefined)`; `panels.js` `list?.` + `String(pair[0])`;
      `scrubber.js` tightened the `ScrubPoint` typedef (frac/distMi/grade/etc. are always built),
      optional `scrubChip` opts, `filter(Boolean)` cast, `Number(s.eff)`; `actions.js`
      `clearInterval(... ?? undefined)`, `dataset.x ?? ""`, optional `handleAction` 2nd arg.
- [x] No exclusions / no `@ts-nocheck`, and **no `VD`-as-`any` shortcuts** — all 5 IIFE
      bootstraps keep `VD` typed as `VoltDashboard` (only the empty `({})` seed is cast). This is
      *stronger* than before T1: `core.js`/`connection-status.js`/`dtc-*.js` were previously
      `/** @type {any} */`, which hid bugs. Typing them surfaced 3 real ones — `setDevices`/
      `setHistory` used `el()` results without null/element-type narrowing, and the `VoltDtcInfo`
      interface required a `dtc` field that `dtcInfo` never returns (it returns `code`). All fixed.
- [x] Verified: `typecheck` 0 errors, ESLint clean, Vitest 121/121, `spotlessCheck` clean.
- [x] `CONTRIBUTING.md` + tsconfig header updated. CI already gates `typecheck` — no workflow change.

### Wave T2a — esbuild build pipeline (bundle the existing IIFE source) ✅ done
The build pipeline landed *without* rewriting the source to ES modules (that's T2b) — the
existing IIFE files bundle as-is, which is lower-risk and immediately useful.

- [x] `esbuild` added to `dashboard-tests`; `build.mjs` bundles via side-effect imports
- [x] Source moved `assets/dashboard/js/` → **`dashboard-src/js/`** (editable source); the
      eager scripts bundle (in dependency order) into a single classic-IIFE `app.js`, the lazy
      `dtc-lookup`/`dtc-causes`/`demo-data` chunks build alongside with their original filenames
- [x] Output `assets/dashboard/js/` is **gitignored** (build artifact); minified, no `type=module`
- [x] Gradle `buildDashboardJs` task wired into `preBuild` (+ `dashboardE2e`/`verifyDashboardBundleSize`)
- [x] `index.template.html` loads the single `js/app.js`; `generateDashboardHtml` regenerated
- [x] Budget gates measure the **built** bundle — minification gave big headroom (core 240 KB /
      400 KB, DTC 273 KB / 380 KB). Spotless `dashboard` format corrected to HTML-only (its old
      `{js,css,html}` brace-glob never matched — Spotless globs don't expand braces)
- [x] Vitest/ESLint/`tsc` repointed to `dashboard-src/js`; `script-order.test.js` rewritten for the
      single bundle (asserts classic non-module + `build.mjs` eager order)
- [x] CI: Node added to the APK-building jobs (`unit-tests`, `emulator-smoke`); `npm run build`
      step added to `dashboard-tests`/`dashboard-e2e`/`dashboard-visual`
- [x] Verified locally: `npm run build`, Vitest 122/122, **Playwright e2e 30/30 against the bundle**,
      `assembleDebug` (preBuild builds + packages the bundle), budget, spotless, typecheck, lint

### Wave T2b — Convert source to `.ts` modules ✅ done
The bundler now consumes all first-party dashboard source files as `.ts`. The shipped WebView
assets stay classic `.js` (`app.js` plus lazy data chunks), and the public API remains attached to
`window.VoltDashboard` for the native bridge ABI. A later cleanup can replace more of the global
namespace shim with explicit imports after the runtime surface is fully modeled.
- [x] Migrate files incrementally to `.ts` modules; keep the public API attached to
      `window.VoltDashboard` for the native bridge ABI until all consumers are modules
      - [x] `demo-data.js` -> `demo-data.ts` canary: real exported function, typed fixture rows,
            still shipped/lazy-loaded as `js/demo-data.js`
      - [x] `connection-tools.js` / `connection-status.js` -> `.ts`: typed DOM/bridge helpers
            and status observer while preserving eager bundle order
      - [x] `dtc-causes.js` / `dtc-lookup.js` -> `.ts`: lazy DTC data/lookup modules still
            build to classic `js/dtc-causes.js` and `js/dtc-lookup.js`
      - [x] `drive.js` -> `drive.ts`: live Drive chip/chart render helpers now use TS types
            while still attaching the same `VoltDashboard` public functions
      - [x] `troubleshooter.js` -> `troubleshooter.ts`: modal state, stale telemetry rows,
            force-stop package parsing, and observer wrappers now use TS types
      - [x] `scrubber.js` -> `scrubber.ts`: route-derived scrub samples, chart tracks,
            cursor state, and the Leaflet marker handle now use TS types
      - [x] `telemetry.js` -> `telemetry.ts`: native payload ingestion, live-tile stale
            state, validation rows, and canvas trace helpers now use TS types
      - [x] `map.js` -> `map.ts`: Leaflet map lifecycle, route drawing, session chips,
            stop detection, demo route generation, and scenario loading now use TS types
      - [x] `core.js` -> `core.ts`: dashboard namespace bootstrap, lazy script loading,
            state seeding, DOM setters, view switching, and device history helpers now use TS types
      - [x] `actions.js` -> `actions.ts`: bridge dispatch, busy-button guard,
            DTC actions, backup/restore commands, demo ticker, and listener binding now use TS types
      - [x] `panels.js` -> `panels.ts`: storage/trips/insights render helpers,
            DTC and enhanced-signal cards, charge rows, and real-trip map previews now use TS types
- [ ] (Optional) source maps for on-device debugging, with a `*.map` packaging exclude
- [ ] (Optional, T3) debug-only WebView → vite dev server hook for on-device live reload

---

## How to update

- **When you finish a wave item:** flip its `[ ]` → `[x]`, add the PR link in Notes, and
  update the at-a-glance counters at the top.
- **When you start one:** mark it `[~]` and put your name/PR so two people don't collide.
- **When you defer/drop one:** mark it `[-]` with a one-line reason.
- **Keep it in the same PR as the code.** This doc drifting from reality is worse than no
  doc. CI doesn't enforce it — treat it like the coverage floors: a discipline, not a gate.
- New conversions should follow the [Interop rules](#interop-rules-read-before-converting-anything);
  if you discover a new gotcha, add it there.

## Decision log

| Date | Decision |
|---|---|
| 2026-06-04 | Enabled AGP built-in Kotlin; `BuildFlags` converted as canary. Policy: new code Kotlin, no bulk migration. |
| 2026-06-04 | Dashboard `checkJs` rollout completed (`checkJs:true`). Next: `strictNullChecks` (T1); full TS+bundler (T2) proposed, pending decision. |
| 2026-06-04 | Wave K1 landed: `VehicleActivityThresholds` + 4 enums (`FailureClass`, `classify/VehicleState`, `classify/Confidence`, `materialize/Confidence`) converted to Kotlin. Zero call-site changes; all gates green. Next: K2 (DTOs) or T1. |
| 2026-06-04 | Wave K2 landed: all 11 DTO/value classes converted. Chose **plain `class` + `@JvmField`** over `data class` (ctor normalization + identity-equality preservation + `data` coverage floor). Zero call-site changes; `data` 90.5%/project 76.7%. Java main files now 82, Kotlin 17. Next: K3 (opportunistic) or T1. |
| 2026-06-04 | Wave T1 landed: `strictNullChecks` ON, dashboard fully null-safe. Root-cause fix (required vs optional `VoltDashboard` members) cleared 656 of 709 noise errors; fixed the 53 real ones with no exclusions. typecheck/ESLint/Vitest(121)/spotless all green. Remaining: K3 and T2 (full TS+bundler). |
| 2026-06-04 | Post-review: kept all `VD` bootstraps typed (dropped `any`), which surfaced + fixed 3 latent bugs (setDevices/setHistory null-guards, `VoltDtcInfo` had a phantom `dtc` field). |
| 2026-06-04 | Wave K3 (partial): converted SessionStateMachine, LocationFilter, PidSchedule to Kotlin (the tested, non-data-integrity logic). All gates green. BackupMigrator + ChargeSessionMaterializer remain (tested, data-touching); BackupController stays Java (no test). |
| 2026-06-04 | Wave K3 complete (5 of 6): added BackupMigrator (SQLite/file I/O via `.use {}`) and ChargeSessionMaterializer (charge heuristics, arithmetic preserved). All gates green incl. data-layer + materializer integration tests. BackupController stays Java until it has a test. 77 Java + 22 Kotlin main files. |
| 2026-06-04 | Dashboard typecheck taken to full `strict` (subsumes the earlier strictNullChecks). Only 2 new errors — `useUnknownInCatchVariables` catch-var `.message` accesses — fixed with `instanceof Error` narrowing. typecheck/ESLint/Vitest(121)/spotless green. This is the max the JSDoc+checkJs setup gives; the remaining TS step is the .ts+bundler migration (T2). |
| 2026-06-04 | Added `noImplicitReturns` + `noFallthroughCasesInSwitch` (both 0 errors — free guards against missing returns / switch fallthrough). `noUncheckedIndexedAccess` measured at 56 errors (map.js/scrubber.js/core.js) — deferred as low-ROI defensive churn; revisit if those files are reworked. |
| 2026-06-04 | Wave T2a landed: esbuild build pipeline. JS source moved to `dashboard-src/js`; eager files bundle into a single classic-IIFE `app.js` + lazy chunks; output gitignored, built by Gradle `buildDashboardJs` in preBuild. Kept IIFE source (no `.ts` rewrite yet — that's T2b) for low risk. Minification → core 240 KB / DTC 273 KB (big budget headroom). Verified: build, Vitest 122, **Playwright 30 against the bundle**, assembleDebug, budget, spotless, typecheck, lint. CI: Node added to APK jobs + build steps to dashboard jobs. |
| 2026-06-04 | Audited all 56 `noUncheckedIndexedAccess` sites as a bug hunt: every one is provably-safe — `core.js` relies on the guaranteed `realViewMeta.drive` fallback, `map.js` 505-560 is `buildSampleRoute` over hardcoded `SAMPLE_ROUTE` slices with bounded loop indices, `scrubber.js` `scrubSampleAt` is only called after a `scrubData.length` check. No real bugs found → flag stays off (would be 56 assertion-churn sites + permanent friction for zero caught bugs). Whole branch validated locally: Android unit/lint/coverage, dashboard typecheck/ESLint/Vitest(121), and **Playwright e2e (30 passed)**. |
| 2026-06-04 | T2b takeover canary: dashboard build/test tooling now accepts mixed `.js`/`.ts` source and `demo-data` moved to a real `.ts` module with typed fixture rows. The public lazy asset remains `js/demo-data.js`, preserving the WebView/native contract while proving esbuild, Vitest, `tsc`, and ESLint all understand the next migration shape. |
| 2026-06-05 | T2b small eager-module slice: `connection-tools` and `connection-status` moved to `.ts` modules. They keep the same bundle order and public behavior, but bridge calls, recent-session parsing, low-voltage status rendering, and the `setStatus` observer now use explicit TS annotations instead of JSDoc comments. |
| 2026-06-05 | T2b lazy-data slice: `dtc-causes` and `dtc-lookup` moved to `.ts` modules. The big curated tables stayed structurally unchanged; the wrapper now uses TS signatures and still emits the same lazy classic script filenames for the WebView. |
| 2026-06-05 | T2b Drive slice: `drive` moved to a `.ts` module. The public `VoltDashboard` render helpers stay attached for `telemetry.js`, while the chip model, chart points, DOM widths, canvas node, and resize debounce state now use TS annotations. |
| 2026-06-05 | T2b support-UI slice: `troubleshooter` and `scrubber` moved to `.ts` modules. The WebView still receives `js/troubleshooter.js` and `js/scrubber.js`; local TS types now cover failure copy, stale telemetry rows, route scrub samples, chart tracks, and scrubber cursor/marker state. |
| 2026-06-05 | T2b telemetry slice: `telemetry` moved to a `.ts` module while preserving the native callback/public `VoltDashboard` surface. Payloads remain open records at the bridge boundary; concrete helpers now type live-tile IDs, validation tones, formatting inputs, and the speed trace canvas path. |
| 2026-06-05 | T2b map slice: `map` moved to a `.ts` module. Leaflet remains a runtime global for the WebView; local types now cover route points, stop rows, demo route options, live breadcrumb coordinates, and route/session helper signatures. |
| 2026-06-05 | T2b core slice: `core` moved to a `.ts` module while keeping the `VoltDashboard` namespace and lazy classic script paths intact. Types now cover the bootstrap data bag, demo-data callbacks, history devices, guarded listeners, DOM setters, and lazy DTC/demo script promises. |
| 2026-06-05 | T2b actions slice: `actions` moved to a `.ts` module while preserving the `VoltTrackerNative` callback ABI. Types now cover bridge-command buttons, busy cooldowns, clear-DTC focus state, signal export/delete IDs, page drag-scroll state, delegated click handlers, and demo timer wiring. |
| 2026-06-05 | Wave K10 landed: converted 10 more Android helpers (`SdpProbe`, `SessionHealthTracker`, `ObdPersistenceWorker`, `LiveSampleReader`, `ObdLocalStore`, `ObdStoreSupport`, `ObdStoreTrips`, `ObdStoreRouteProjection`, `ObdStoreSessionReview`, `VoltTrackerDb`). Java main files now 13, Kotlin 86. Remaining reasonable data targets are `DatabaseMerger`, `ObdStoreReports`, `ObdStoreWriter`, and `VoltTrackerSchema`; service/activity/bridge/restore flows stay late-stage. |
