# Codebase Grade Report

**Project:** VoltTracker
**Audited:** 2026-05-27
**Stack:** Android Java app in `mobile/android/`, WebView dashboard with plain JS/CSS/HTML, SQLite, Gradle/Spotless/Jacoco, Vitest/ESLint dashboard tests, GitHub Actions release/CI.

## Summary

| ID | Category | Grade | Items |
|----|----------|-------|-------|
| A | Architecture & Design | B | 4 |
| B | Backend Quality | B | 4 |
| C | Frontend Quality | C+ | 5 |
| D | Testing & Reliability | B | 5 |
| E | Security | B- | 5 |
| F | Dependencies & Tech Currency | B+ | 4 |
| G | Performance & Scalability | B | 4 |
| H | Documentation & Onboarding | B- | 4 |
| I | Developer Experience & Tooling | B | 5 |
| **Overall** | | **B** | **40** |

**Top 5 highest-leverage fixes:** C1, E1, D1, A1, I1

---

## A - Architecture & Design - B

The active app has a real layer story: `mobile/android/README.md:13-27` documents UI -> Service -> Engine -> Data, `mobile/android/docs/adr/0001-webview-dashboard.md` and the generated-dashboard rule describe important architectural choices, and `ObdLocalStore` has been split into a facade plus report/trip/snapshot/materialize helpers (`mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdLocalStore.java:27-63`). The weak spot is that the runtime orchestration still clusters too much state in a few classes: `ObdPollingEngine.java` is 1,089 lines, `ObdService.java` is 633 lines, and multiple comments still refer to old bucket-ticket implementation phases rather than domain concepts. Overall: solid structure, but not yet exemplary.

#### ~~A1 - Split the OBD polling engine by responsibility~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:42-97`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:176-253`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:651-928`
- **What's wrong:** `ObdPollingEngine` owns connection retry policy, ELM init, live polling, PID scheduling, raw transcript capture, state classification, and clear/scan runners. Its size and mixed responsibilities make future Bluetooth fixes risky because small changes can affect unrelated parsing, scheduling, and lifecycle behavior.
- **Impact:** Major — this sits on the live OBD session path, so reducing coupling lowers the chance that a Bluetooth fix breaks polling, parsing, or diagnostics.
- **Fix:** Extract `ObdConnectionLoop` for connect/reconnect/backoff, `LivePoller` for `readObdSample`/scheduled reads, and keep `ObdPollingEngine` as the coordinator. Move tests from subclassing the whole engine to focused fakes around `ElmConnection` and the extracted collaborators.
- **Effort:** L
- **Grade lift:** B -> B+ (reduces the highest-risk coupling in the active runtime path)

#### ~~A2 - Make service session state an explicit state machine~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:49-84`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:283-317`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:368-381`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:614-631`
- **What's wrong:** Session state is spread across `SESSION_ACTIVE`, `running`, `foregroundServiceActive`, `lastSessionState`, `lastSessionDetail`, `activeTask`, and `cancelRetryRequested`. The code has good guardrails, but the invariants are implicit and easy to violate when adding a new action mode.
- **Impact:** Major — service-state mistakes can leave logging, GPS, sockets, or foreground notifications in the wrong state.
- **Fix:** Introduce a small `SessionController` or `SessionState` class with explicit transitions for idle, connecting, connected, scanning, clear-dtc, demo, stopping, and error. Keep Android service calls in `ObdService`, but move transition validation and session bookkeeping behind methods with tests.
- **Effort:** M
- **Grade lift:** B -> B+ (turns lifecycle correctness from convention into a testable contract)

#### ~~A3 - Replace cross-file bucket comments with domain comments~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:40-43`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:77-89`, `mobile/android/app/src/main/assets/dashboard/js/troubleshooter.js:1-22`, `mobile/android/app/src/main/dashboard-src/partials/connection-tools.html:1-4`
- **What's wrong:** Many comments name old implementation buckets like "Bucket 4a", "C6", and "A8". Those labels do not help a new maintainer understand the current product behavior and make the code read like a patch stack instead of a maintained app.
- **Impact:** Minor — this is mostly comprehension and maintainability polish, not a current runtime risk.
- **Fix:** Replace bucket IDs with durable concepts: retry cancellation, status tools, adapter-health signals, diagnostics sharing, and stale telemetry. Keep comments only where they explain non-obvious Android/WebView behavior.
- **Effort:** S
- **Grade lift:** B -> B+ (removes architectural noise without changing behavior)

#### ~~A4 - Add architecture boundary checks~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:13-27`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/`
- **What's wrong:** The layering rule is documented, but no test or lint task prevents future code from calling upward, for example data code reaching into Activity/service classes or dashboard bridge code acquiring storage directly.
- **Impact:** Moderate — this prevents gradual architectural drift that would make later fixes slower and riskier.
- **Fix:** Add a JVM architecture test that scans Java imports/package names and enforces the documented UI -> Service -> Engine -> Data dependency direction. Start with allow-lists for current known exceptions, then tighten as A1/A2 land.
- **Effort:** M
- **Grade lift:** B -> B+ (keeps the existing layering rule from drifting)

---

## B - Backend Quality - B

For an Android app, the service/data layer is professional: SQLite writes are transaction-wrapped (`ObdLocalStore.java:178-197`, `ObdLocalStore.java:255-273`), schema migrations are incremental (`VoltTrackerDb.java:188-260`), and runtime broadcasts use structured JSON (`ObdService.java:422-465`). The main issue is type safety and ownership: business payloads are mostly `JSONObject`, and lifecycle errors are often logged and collapsed to empty JSON or user-facing status strings. This is acceptable for a field-test app, but it leaves correctness too dependent on conventions.

#### ~~B1 - Introduce typed payload builders for status and telemetry~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:422-465`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:651-805`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/AppStateJson.java`
- **What's wrong:** Status, telemetry, and app-state objects are assembled with ad hoc `JSONObject.put` calls. A typo or renamed field has no compile-time signal and can silently break the dashboard ABI.
- **Impact:** Moderate — field drift can create user-visible dashboard bugs, but current tests and bridge checks reduce immediate risk.
- **Fix:** Add small immutable Java payload classes or builder helpers for `StatusPayload`, `TelemetryPayload`, and `AppStatePayload`, each with a `toJson()` method. Update producers first, then update tests to assert typed fields before JSON serialization.
- **Effort:** M
- **Grade lift:** B -> B+ (reduces schema drift across native and dashboard code)

#### ~~B2 - Preserve errors in bridge/store read failures~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/MainActivity.java:437-470`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/VoltBridge.java:144-168`
- **What's wrong:** Several read paths catch `RuntimeException` and return `{}` or `[]`. That prevents crashes, which is good, but it also makes database/query failures look like "no data" to the dashboard.
- **Impact:** Moderate — users and developers can lose the difference between empty history and a broken storage query.
- **Fix:** Return structured error payloads such as `{ "ok": false, "error": "storage_summary_failed" }` for storage/trips/insights reads and teach the dashboard to show a diagnostic message while preserving crash-safety.
- **Effort:** S
- **Grade lift:** B -> B+ (turns silent data loss into actionable diagnostics)

#### ~~B3 - Tighten backup restore validation beyond table presence~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:30-46`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:175-212`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/BackupController.java:178-224`
- **What's wrong:** Restore validation checks SQLite header and required table names, then swaps the DB. It does not verify `PRAGMA user_version`, schema columns, foreign-key integrity, or app/schema compatibility before replacing the live database.
- **Impact:** Major — restore is a destructive data path, so accepting a subtly incompatible DB can damage or strand user history.
- **Fix:** In `isVoltTrackerBackup`, verify `user_version` is within supported range, run `PRAGMA integrity_check`, confirm key columns for current schema version, and reject files with foreign-key violations. Add negative tests for wrong version and missing current columns.
- **Effort:** M
- **Grade lift:** B -> B+ (hardens the riskiest local data mutation path)

#### ~~B4 - Move diagnostic report queries behind stable DTOs~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java:32-38`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java:155-205`, `mobile/android/app/src/main/assets/dashboard/js/panels.js:112-130`
- **What's wrong:** Report projections return dashboard-shaped JSON directly from the data layer. That is convenient, but it couples SQLite query names, JSON keys, and UI expectations in one jump.
- **Impact:** Moderate — it slows future schema/UI changes and increases the odds of breaking a dashboard panel during data-layer work.
- **Fix:** Return typed records from `ObdStoreReports` for storage summary, latest vehicle, diagnostic counts, and review summary; serialize to dashboard JSON at the bridge boundary.
- **Effort:** M
- **Grade lift:** B -> B+ (separates persistence contracts from presentation contracts)

---

## C - Frontend Quality - C+

The dashboard has a useful product surface and several thoughtful patterns: listener cleanup with `AbortController` (`actions.js:26-35`), rAF-throttled live telemetry rendering (`telemetry.js:234-320`), DOM builders instead of HTML string insertion for storage rows (`panels.js:84-110`), and a CSP in the generated template (`index.template.html:15-18`). The weakness is maintainability: the JS is still global-IIFE based, tests execute it through `new Function`, and coverage reports cannot see real execution. This is the least mature part of the active app.

#### ~~C1 - Convert dashboard JS to importable modules so tests and coverage are real~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/core.js:6-20`, `mobile/android/dashboard-tests/setup/load-dashboard.js:1-23`, `mobile/android/dashboard-tests/vitest.config.js:24-41`, `mobile/android/app/src/main/dashboard-src/index.template.html:119-131`
- **What's wrong:** Dashboard files are side-effecting IIFEs loaded with script tags. Tests use `new Function()` to evaluate them, which is why V8 coverage reports `Unknown% (0/0)` even though 32 tests run.
- **Impact:** Major — this makes the frontend coverage gate misleading and lets untested dashboard code look healthier than it is.
- **Fix:** Wrap each dashboard file as an ES module that exports an initializer, keep a tiny bootstrap script for the WebView order, and change tests to `import` the modules. Raise Vitest coverage thresholds above zero after the loader change.
- **Effort:** L
- **Grade lift:** C+ -> B (unblocks meaningful frontend coverage and shrinks fixture drift)

#### ~~C2 - Remove production demo fixtures from `core.js`~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/core.js:113-136`, `mobile/android/app/src/main/assets/dashboard/js/core.js:139-188`
- **What's wrong:** Real dashboard state and sample/demo data live in the same production bootstrap. That makes it easy for fake trips, charge sessions, costs, or insights to leak into real app views when state-reset logic regresses.
- **Impact:** Moderate — demo leakage would confuse users and field testing, but it is bounded to presentation state.
- **Fix:** Move demo fixture data into `app/src/main/assets/dashboard/demos/` or a dedicated `demo-data.js` loaded only when demo mode starts. Keep `VD.state` in `core.js`, but remove hard-coded trip/session/insight arrays from the always-loaded runtime.
- **Effort:** M
- **Grade lift:** C+ -> B- (separates field data from preview data)

#### ~~C3 - Replace test DOM mega-fixture with generated markup loading~~ ✓ done 2026-05-27
- **Where:** `mobile/android/dashboard-tests/setup/load-dashboard.js:25-218`, `mobile/android/app/src/main/dashboard-src/index.template.html:30-40`
- **What's wrong:** The test fixture manually mirrors hundreds of DOM IDs from generated dashboard HTML. This drifts from the real partials and can produce false confidence when production markup changes but the fixture does not.
- **Impact:** Moderate — fixture drift can hide broken real markup while tests continue to pass.
- **Fix:** Run or reuse `generateDashboardHtml`, load the generated `index.html` into jsdom, and stub only unsupported browser APIs like canvas, scroll, and Leaflet.
- **Effort:** M
- **Grade lift:** C+ -> B- (tests the real shipped DOM instead of a parallel hand copy)

#### ~~C4 - Make map tile networking user-visible and privacy-aware~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/map.js:23-35`, `mobile/android/app/src/main/dashboard-src/index.template.html:15-18`, `mobile/android/app/src/main/AndroidManifest.xml:16-18`
- **What's wrong:** The app is described as offline/on-device, but the map tab loads remote CARTO/OSM basemap tiles. Route data stays local, but the network behavior is not surfaced as a setting or documented in the UI.
- **Impact:** Moderate — it is not leaking OBD data, but it can still surprise privacy-conscious users by contacting tile providers.
- **Fix:** Add a map setting or first-use disclosure for remote basemap tiles, and provide an "offline/no basemap" mode that only renders stored routes on a blank grid.
- **Effort:** M
- **Grade lift:** C+ -> B- (aligns UI behavior with privacy expectations)

#### ~~C5 - Add automated accessibility assertions for the dashboard~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/dashboard-src/partials/drive.html:11-24`, `mobile/android/app/src/main/dashboard-src/index.template.html:43-117`, `mobile/android/dashboard-tests/*.test.js`
- **What's wrong:** The UI includes ARIA labels/live regions, but tests do not assert navigability, focus order, button labels, or key landmarks. Regressions in a WebView dashboard can slip past visual and bridge tests.
- **Impact:** Moderate — accessibility regressions can block some users and are cheap to catch before they ship.
- **Fix:** Add focused jsdom assertions for nav `aria-current`, unique IDs, button labels, live-region presence, and modal focus/expanded states. If the module conversion lands, add `axe-core` checks over the generated HTML.
- **Effort:** S
- **Grade lift:** C+ -> B- (catches common WebView accessibility regressions cheaply)

---

## D - Testing & Reliability - B

The test suite is much healthier than a typical Android side project: 407 Java tests across 44 files, 32 dashboard tests across 7 files, Robolectric coverage for SQLite/migrations/bridge/engine paths, lint/Spotless/Jacoco gates, and dashboard tests in CI. Java coverage has real ratcheting floors (`jacoco.gradle:21-47`), and local validation passed for `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:spotlessCheck`, `:app:jacocoTestCoverageVerification`, `npm run lint`, and `npm run test:coverage`. The biggest reliability hole is frontend coverage being wired but ineffective.

#### ~~D1 - Make dashboard coverage thresholds meaningful [FE]~~ ✓ done 2026-05-27
- **Where:** `mobile/android/dashboard-tests/vitest.config.js:24-41`, `mobile/android/dashboard-tests/setup/load-dashboard.js:1-23`
- **What's wrong:** CI runs `npm run test:coverage`, but all thresholds are zero and V8 reports `Unknown% (0/0)` because sources are evaluated with `new Function`. This is a coverage gate in name only.
- **Impact:** Major — this is a misleading CI signal around the most change-prone UI code.
- **Fix:** Complete C1 or use an instrumentation-aware loader, then set initial line/function thresholds to the measured value minus a small ratchet buffer. Fail CI on drops like the Java Jacoco gate.
- **Effort:** M
- **Grade lift:** B -> B+ (fixes the most misleading test signal)

#### ~~D2 - Add generated dashboard drift tests [FE]~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/build.gradle:121-145`, `mobile/android/app/src/main/assets/dashboard/index.html`, `mobile/android/app/src/main/dashboard-src/partials/*.html`
- **What's wrong:** The generated `index.html` is committed and `preBuild` regenerates it, but CI does not fail if partials and generated HTML drift in the working tree.
- **Impact:** Moderate — stale generated HTML can ship a different dashboard than the partials reviewers inspected.
- **Fix:** Add a Gradle or CI step that runs `generateDashboardHtml` and checks `git diff --exit-code -- app/src/main/assets/dashboard/index.html`.
- **Effort:** S
- **Grade lift:** B -> B+ (prevents stale generated assets from shipping)

#### ~~D3 - Add replay fixtures for real OBD transcripts [BE]~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/test/java/com/volttracker/obdpoc/ObdPollingEngineTest.java:95-176`, `mobile/android/docs/field-test-2026-05-19.md`, `mobile/android/docs/volt-pids-community-sheet.csv`
- **What's wrong:** Engine tests script fake ELM responses well, but there is no golden replay of a captured session transcript through parser, scheduler, classifier, and storage materialization.
- **Impact:** Moderate — realistic replay tests would catch parser and scheduler regressions that synthetic cases miss.
- **Fix:** Add sanitized JSONL/ELM transcript fixtures under the existing JVM test tree and replay them through `ObdProtocol`, `PidSchedule`, `VehicleStateClassifier`, and `ObdLocalStore` to lock in real-car behavior.
- **Effort:** M
- **Grade lift:** B -> B+ (catches parser regressions fake unit cases miss)

#### ~~D4 - Broaden lifecycle tests for foreground service and restore races [both]~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:485-543`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/BackupController.java:178-224`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/ObdServiceTest.java`
- **What's wrong:** There are many unit tests, but the riskiest Android lifecycle behaviors are still difficult to prove: foreground service type changes, permission revocation mid-session, and backup restore while service state is moving.
- **Impact:** Major — these are the paths most likely to create stuck services, lost restores, or OS-specific crashes.
- **Fix:** Add Robolectric tests that simulate location permission changes, active session restore rejection, and failed restore rollback while checking status broadcasts and `localStore` reopen behavior.
- **Effort:** M
- **Grade lift:** B -> B+ (hardens high-impact lifecycle edges)

#### ~~D5 - Add negative-path tests for bridge/store error payloads [both]~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/MainActivity.java:437-470`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/VoltBridgeTest.java:209-243`, `mobile/android/dashboard-tests/actions.test.js:95-127`
- **What's wrong:** Bridge ABI and happy/error-free input paths are covered, but failures from storage reads still collapse to empty JSON and are not tested as user-visible errors.
- **Impact:** Moderate — this keeps recoverable storage failures visible instead of silently presenting empty data.
- **Fix:** After B2, add JVM tests with a failing `ObdLocalStore` and dashboard tests that render the resulting error payloads.
- **Effort:** S
- **Grade lift:** B -> B+ (keeps future crash-safety from becoming silent failure)

---

## E - Security - B-

The active Android app has several strong security/privacy choices: `allowBackup=false`, backup rules exclude all app data, services/providers are not exported, cleartext traffic is disabled (`AndroidManifest.xml:42-74`), WebView file/content access is disabled (`WebViewBootstrap.java:34-44`), bridge inputs are bounded (`VoltBridge.java:25-59`), dashboard callbacks quote JSON (`MainActivity.java:380-420`), and backups show a PII disclosure (`BackupController.java:40-77`). Repository-level security noise from the deprecated receiver has been addressed by deleting the removed `archive/` tree and keeping CodeQL focused on active Android code.

#### ~~E1 - Remove or quarantine archived receiver security alerts~~ ✓ done 2026-05-27
- **Where:** Former `archive/receiver/frontend/package-lock.json`, former `archive/e2e/package-lock.json`, `.github/workflows/`, GitHub Dependabot/code-scanning settings
- **What's wrong:** GitHub reported 3 open Dependabot alerts and 34 open code-scanning alerts, all in the deprecated archive. Even though the archive was deprecated, those alerts created noise and trained reviewers to ignore security dashboards.
- **Impact:** Major — noisy security dashboards make it easier to miss a real active-app alert later.
- **Fix:** Either remove archived dependency lockfiles and disable archive code scanning, or move archive into a clearly excluded artifact with CodeQL/Dependabot path ignores. Keep active Android scanning enabled.
- **Effort:** S
- **Grade lift:** B- -> B+ (clears repo-wide security noise without touching the active app)

#### ~~E2 - Add optional encrypted backups~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:91-116`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/BackupController.java:40-77`, `mobile/android/app/src/main/res/xml/file_paths.xml:1-7`
- **What's wrong:** Backups are full SQLite database copies and the UI warns about GPS/OBD/device data, but the file itself is plaintext once shared. A mistaken share or cloud sync exposes route history and vehicle records.
- **Impact:** Major — plaintext backups contain sensitive location and vehicle history once they leave app-private storage.
- **Fix:** Add an encrypted export mode using Android Keystore/passphrase-derived encryption, clearly label plaintext vs encrypted share actions, and add restore support for encrypted backups.
- **Effort:** L
- **Grade lift:** B- -> B (reduces impact of accidental backup disclosure)

#### ~~E3 - Make remote map tile behavior opt-in or disableable~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/AndroidManifest.xml:16-18`, `mobile/android/app/src/main/assets/dashboard/js/map.js:23-35`, `mobile/android/app/src/main/dashboard-src/index.template.html:15-18`
- **What's wrong:** The app says all OBD/GPS data stays on-device, but basemap tile requests disclose approximate viewed map area to third-party tile providers. The current CSP allows those domains but there is no user-facing control.
- **Impact:** Major — it is a privacy expectation mismatch in an app whose core promise is local vehicle and route data.
- **Fix:** Add an offline map mode and a setting that enables remote tiles only after disclosure. Update docs and CSP comments to reflect the privacy model.
- **Effort:** M
- **Grade lift:** B- -> B (aligns privacy claims with network behavior)

#### ~~E4 - Assert WebView hardening in tests~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/WebViewBootstrap.java:32-44`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/`
- **What's wrong:** WebView security settings are good but only enforced by code review. A future change could re-enable file/content access or debugging outside debug builds without a failing test.
- **Impact:** Moderate — it locks down an important trust boundary, though current code is already hardened.
- **Fix:** Add a Robolectric test for `WebViewBootstrap.configure` that asserts JavaScript is enabled intentionally, file/content access are disabled, web contents debugging follows `BuildConfig.DEBUG`, and only the expected bridge name is attached.
- **Effort:** S
- **Grade lift:** B- -> B (locks in the WebView trust boundary)

#### ~~E5 - Keep package-visibility and kill-process behavior tightly documented~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/AndroidManifest.xml:19-40`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/CompetingAppDetector.java`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/VoltBridge.java:294-307`
- **What's wrong:** The app declares `KILL_BACKGROUND_PROCESSES` and a package query list for competing OBD tools. The comments explain intent, but this is still a sensitive-looking permission surface for users and app review.
- **Impact:** Moderate — the behavior is intentional, but unusual permissions need strong guardrails and explanation.
- **Fix:** Add a short user-facing docs section and a targeted test that `forceStopPackage` only acts on explicit bridge-provided packages surfaced by the detector UI.
- **Effort:** S
- **Grade lift:** B- -> B (reduces review/user trust risk around unusual permissions)

---

## F - Dependencies & Tech Currency - B+

The active dependency surface is intentionally small. `libs.versions.toml` declares only AndroidX Core plus test dependencies (`mobile/android/gradle/libs.versions.toml:1-15`), Gradle's `dependencyUpdates` reports active Android dependencies/tooling current except a transitive Kotlin stdlib line, and dashboard `npm audit --audit-level=low` found 0 vulnerabilities. The remaining issues are mostly future-compatibility: a Gradle 10 deprecation from the dependency-update task and keeping dependency notes current.

#### ~~F1 - Resolve archive dependency alerts or exclude archive from dependency scanning~~ ✓ done 2026-05-27
- **Where:** Former `archive/receiver/frontend/package-lock.json`, former `archive/e2e/package-lock.json`, `.github/dependabot.yml` if present or repository Dependabot settings
- **What's wrong:** Open Dependabot alerts were for `uuid`, `ws`, and `postcss` under the deprecated archive, not active Android. They still appeared as current repository vulnerabilities.
- **Impact:** Moderate — active app dependencies are clean, but repo-level CVE noise reduces confidence in the dependency process.
- **Fix:** Prefer deleting obsolete archive lockfiles if the archive is reference-only. If the archive must stay reproducible, update those lockfiles or configure Dependabot to ignore deprecated archive paths with a documented reason.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps dependency signal clean)

#### ~~F2 - Track the transitive Kotlin stdlib update from AGP/AndroidX~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:1-15`, `mobile/android/gradle/libs.versions.toml:1-15`, `mobile/android/docs/dependency-report-2026-05-26.md:61-65`
- **What's wrong:** `dependencyUpdates` reports `org.jetbrains.kotlin:kotlin-stdlib [2.2.10 -> 2.3.21]`, but this app does not declare Kotlin directly. The update likely waits on Android Gradle Plugin/AndroidX transitive movement.
- **Impact:** Minor — this is maintenance bookkeeping for a transitive dependency, not an active upgrade blocker.
- **Fix:** Add a note to the dependency report or maintenance checklist that this is transitive, not an app-declared dependency, and re-check when AGP moves past 9.2.1.
- **Effort:** S
- **Grade lift:** B+ -> A- (prevents noisy or unsafe direct overrides)

#### ~~F3 - File or track the Gradle 10 deprecation in the Versions plugin~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:1-16`
- **What's wrong:** `./gradlew dependencyUpdates --warning-mode all` reports `Invocation of Task.project at execution time has been deprecated` and says it will fail in Gradle 10. The active task is useful but not future-proof.
- **Impact:** Moderate — dependency-update tooling will break on Gradle 10 if the plugin does not move first.
- **Fix:** Track `com.github.ben-manes.versions` for a Gradle 10-compatible release, or add a short-term note to dependency maintenance docs that the warning is third-party-owned and isolated to `dependencyUpdates`.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps update tooling ready for Gradle 10)

#### ~~F4 - Update stale docs after AndroidX adoption~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:125-131`, `mobile/android/gradle/libs.versions.toml:8-12`
- **What's wrong:** The README still says "This uses no AndroidX dependencies yet", but the app ships `androidx.core:core` and uses AndroidX APIs like `FileProvider`, `ServiceCompat`, and `NotificationCompat`.
- **Impact:** Minor — stale docs waste time and can mislead dependency reviews, but runtime behavior is unaffected.
- **Fix:** Replace the stale note with a current "Dependency surface" section listing AndroidX Core and why it is used.
- **Effort:** S
- **Grade lift:** B+ -> A- (removes misleading dependency documentation)

---

## G - Performance & Scalability - B

The app has meaningful performance work already: SQLite indexes exist for time/session queries (`VoltTrackerDb.java:167-185`), raw retention pruning runs off the UI thread (`MainActivity.java:113-135`), live telemetry rendering is rAF-batched (`telemetry.js:234-320`), and telemetry arrays are capped (`telemetry.js:250-279`). The main risks are uncoalesced DB summary refreshes, raw transcript volume, and build/dev-loop speed rather than algorithmic collapse.

#### ~~G1 - Coalesce storage summary refreshes during active sessions~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/MainActivity.java:85-89`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/MainActivity.java:424-435`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java:155-205`
- **What's wrong:** Every status broadcast triggers `publishStorageSummary`, which runs many SQLite count/projection queries on a single background executor. During noisy connect/retry periods this can queue redundant summary work and delay more important tasks.
- **Impact:** Moderate — redundant DB work can make the dashboard feel sluggish during the already-frustrating connect/retry path.
- **Fix:** Add a debounce/coalescing flag so only one summary query is in flight and status bursts schedule at most one delayed refresh. Keep explicit refresh buttons immediate.
- **Effort:** S
- **Grade lift:** B -> B+ (cuts redundant DB work in noisy sessions)

#### ~~G2 - Bound or compress raw transcript persistence~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:654-663`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:800`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdLocalStore.java:178-197`
- **What's wrong:** Each sample can include a `raw` transcript string that is logged and persisted. Long sessions with verbose adapters can grow local storage quickly and make backups heavier.
- **Impact:** Moderate — the app remains functional, but long sessions can create avoidable storage and backup bloat.
- **Fix:** Add a per-sample raw size cap and/or store raw transcripts only in debug/diagnostics mode while keeping parsed fields always on. Add tests for truncation markers and retention pruning.
- **Effort:** M
- **Grade lift:** B -> B+ (prevents storage growth from scaling with adapter verbosity)

#### ~~G3 - Enforce dashboard bundle size budgets~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/android.yml:105-122`, `mobile/android/app/src/main/assets/dashboard/js/`, `mobile/android/app/src/main/assets/dashboard/css/`
- **What's wrong:** CI reports dashboard bundle size to the job summary but never fails on unexpected growth. The dashboard is a local WebView, so large JS/CSS still affects startup and memory on older phones.
- **Impact:** Moderate — it prevents slow creep in WebView startup cost, especially on older devices.
- **Fix:** Add a checked budget file or workflow threshold for total non-vendor JS/CSS bytes, with explicit approval required to raise it.
- **Effort:** S
- **Grade lift:** B -> B+ (turns a passive metric into a regression guard)

#### ~~G4 - Enable and fix Gradle configuration-cache readiness~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:17-45`, `mobile/android/app/build.gradle:121-145`, `lefthook.yml:7-26`
- **What's wrong:** Gradle suggests enabling configuration cache, and the dependency-update task currently reports a configuration-cache-incompatible API. Local hooks invoke Gradle repeatedly, so configuration cache would improve the edit/test loop.
- **Impact:** Moderate — this is developer-speed work, not app behavior, but it compounds across every local validation run.
- **Fix:** Run representative tasks with `--configuration-cache`, fix project-owned incompatibilities first, and document any third-party limitations separately.
- **Effort:** M
- **Grade lift:** B -> B+ (speeds up local and CI-like validation loops)

---

## H - Documentation & Onboarding - B-

Documentation is much better than average for a small Android app: root README states the active/deprecated split, `mobile/android/README.md` maps layers and build/test/install commands, and ADRs explain WebView, JSONL, strict layering, charge detection, and connection classification. The weak spots are stale notes, scattered reports, and missing user-facing privacy/field-test guidance for the current app behavior.

#### ~~H1 - Fix stale Android README notes~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:125-131`, `mobile/android/README.md:61-62`
- **What's wrong:** The README still says no AndroidX dependencies are used, and it notes Volt mode-22 formulas need real-car confirmation even though newer docs and implementation have moved beyond the original POC framing.
- **Impact:** Minor — this mainly affects onboarding accuracy, not shipped behavior.
- **Fix:** Refresh the POC notes into a "Current status" section covering AndroidX Core, current live PIDs, scan-only Volt PIDs, what has and has not been field-confirmed, and where field-test notes live.
- **Effort:** S
- **Grade lift:** B- -> B (removes misleading onboarding text)

#### ~~H2 - Add a privacy/data-handling page~~ ✓ done 2026-05-27
- **Where:** `README.md:1-24`, `mobile/android/README.md:33-40`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/BackupController.java:40-77`, `mobile/android/app/src/main/assets/dashboard/js/map.js:23-35`
- **What's wrong:** Privacy behavior is scattered across code comments and UI prompts: local SQLite, GPS samples, backup disclosure, map tile networking, and redacted VIN handling are not explained in one place.
- **Impact:** Moderate — better privacy documentation improves user trust and makes app-review/security conversations simpler.
- **Fix:** Add `mobile/android/docs/privacy-data-handling.md` covering what stays local, what a backup contains, map tile network behavior, redacted VINs, and how to delete/export data.
- **Effort:** S
- **Grade lift:** B- -> B (helps users and reviewers trust the app)

#### ~~H3 - Consolidate dated reports into an index~~ ✓ done 2026-05-27
- **Where:** `mobile/android/docs/android-bug-hunt-third-pass-30.md`, `mobile/android/docs/android-polish-second-pass-30.md`, `mobile/android/docs/debugging-issues-2026-05-26.md`, `mobile/android/docs/dependency-report-2026-05-26.md`
- **What's wrong:** Useful reports exist, but there is no index explaining which are current, which are historical, and which findings are done. New contributors have to infer status from filenames.
- **Impact:** Minor — it reduces navigation friction, but does not change product quality directly.
- **Fix:** Add `mobile/android/docs/reports-index.md` with date, scope, status, and superseded-by links for each report. Link it from `mobile/android/README.md`.
- **Effort:** S
- **Grade lift:** B- -> B (turns report history into navigable knowledge)

#### ~~H4 - Document the dashboard generated-asset workflow in contributor docs~~ ✓ done 2026-05-27
- **Where:** `CONTRIBUTING.md`, `mobile/android/CONTRIBUTING.md`, `mobile/android/app/build.gradle:121-145`
- **What's wrong:** The generated dashboard rule appears in README/CLAUDE/AGENTS guidance, but contributor docs should also spell out "edit partials, run `generateDashboardHtml`, do not hand-edit index.html" because this is a recurring footgun.
- **Impact:** Minor — it prevents contributor confusion around generated files, but CI should carry the real enforcement.
- **Fix:** Add the generated-asset workflow, validation commands, and common failure mode to `mobile/android/CONTRIBUTING.md`.
- **Effort:** S
- **Grade lift:** B- -> B (puts the key workflow where contributors look)

---

## I - Developer Experience & Tooling - B

CI is strong for the active app: Android unit tests, Spotless, Android Lint, assemble, Jacoco report/verification, dashboard ESLint/Vitest coverage command, artifact upload, pinned actions, PR-title lint, and semantic-release are all configured (`.github/workflows/android.yml:25-123`, `.github/workflows/pr-title-lint.yml:1-46`, `.github/workflows/release.yml:1-58`). Local validation also passed. The remaining friction is mostly signal quality: generated asset drift is not enforced, dashboard coverage is noisy, pre-commit hooks are optional/skippable, and archive alerts pollute GitHub dashboards.

#### ~~I1 - Add one local/CI command for full active-app validation~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:64-98`, `.github/workflows/android.yml:39-52`, `mobile/android/dashboard-tests/package.json:5-9`
- **What's wrong:** The full validation set is spread across Gradle tasks and npm scripts. Developers have to remember the right combination for Android + dashboard + coverage.
- **Impact:** Major — a single reliable command cuts down on missed checks before PRs and makes future cleanup work easier to verify.
- **Fix:** Add a root or `mobile/android` script/task such as `./gradlew verifyActiveApp` that depends on unit tests, lint, Spotless, assemble, Jacoco verification, `dashboardLint`, and `dashboardTest`. Document it as the pre-PR command.
- **Effort:** S
- **Grade lift:** B -> B+ (reduces missed local validation)

#### ~~I2 - Enforce generated dashboard cleanliness in CI~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/build.gradle:121-145`, `.github/workflows/android.yml:39-52`
- **What's wrong:** `generateDashboardHtml` runs before build, but CI does not check that the committed generated file is updated. A PR can pass while leaving uncommitted generated output in CI.
- **Impact:** Moderate — it prevents review/build drift for the shipped dashboard asset.
- **Fix:** After the Gradle build or as a dedicated step, run `git diff --exit-code -- mobile/android/app/src/main/assets/dashboard/index.html`.
- **Effort:** S
- **Grade lift:** B -> B+ (prevents generated-file churn from landing)

#### ~~I3 - Make dashboard coverage output honest~~ ✓ done 2026-05-27
- **Where:** `mobile/android/dashboard-tests/vitest.config.js:24-41`, `.github/workflows/android.yml:93-97`
- **What's wrong:** CI says it runs "dashboard JS smoke tests with coverage gate", but the report currently returns `Unknown% (0/0)` with zero thresholds. That mismatch creates false confidence.
- **Impact:** Major — CI wording overstates the safety net and can hide untested UI code.
- **Fix:** Rename the current CI step to "dashboard smoke tests" until C1 lands, or fix C1 and then set non-zero thresholds.
- **Effort:** S
- **Grade lift:** B -> B+ (aligns CI labels with actual signal)

#### ~~I4 - Make pre-commit setup fail less silently~~ ✓ done 2026-05-27
- **Where:** `lefthook.yml:15-26`, `mobile/android/README.md:84-90`
- **What's wrong:** The dashboard ESLint pre-commit hook skips itself when `node_modules` is missing. That is convenient for first-time clones, but it also means a developer can commit JS syntax mistakes locally and only learn in CI.
- **Impact:** Moderate — it shifts avoidable JavaScript failures from local commit time to CI.
- **Fix:** Add a setup task that runs `npm ci` for dashboard tests, and have the hook print a stronger one-time warning or fail for staged dashboard JS unless an explicit env var bypass is set.
- **Effort:** S
- **Grade lift:** B -> B+ (catches JS errors before CI more reliably)

#### ~~I5 - Keep GitHub security dashboards scoped to active code~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/`, GitHub code scanning setup, former `archive/`
- **What's wrong:** GitHub code scanning had 34 open alerts in the deprecated archive. This was not active-app risk, but it reduced the usefulness of the dashboard for future Android findings.
- **Impact:** Moderate — active alerts become harder to spot when the dashboard is already full of deprecated-code noise.
- **Fix:** Update CodeQL/Semgrep scanning paths to active Android code or remove the deprecated archive code from the main repo. Add a short note in contributor docs explaining the active-app scan scope.
- **Effort:** S
- **Grade lift:** B -> B+ (restores actionable signal in GitHub security tooling)
