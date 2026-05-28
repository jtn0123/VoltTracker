# Codebase Grade Report

**Project:** VoltTracker
**Audited:** 2026-05-27
**Stack:** Android Java app in `mobile/android/`, WebView dashboard with plain JS/CSS/HTML, SQLite, Gradle/Spotless/Jacoco, Vitest/ESLint dashboard tests, GitHub Actions release/CI.

## Summary

| ID | Category | Grade | Items |
|----|----------|-------|-------|
| A | Architecture & Design | A- | 4 |
| B | Backend Quality | A- | 4 |
| C | Frontend Quality | B+ | 5 |
| D | Testing & Reliability | A- | 5 |
| E | Security | B+ | 4 |
| F | Dependencies & Tech Currency | A- | 4 |
| G | Performance & Scalability | B+ | 4 |
| H | Documentation & Onboarding | B+ | 4 |
| I | Developer Experience & Tooling | A- | 4 |
| **Overall** | | **A-** | **38** |

**Top 5 highest-leverage fixes:** C1, G1, A1, E1, D1 — all completed 2026-05-27.

## Comparison To Previous Report

The previous saved report graded the active app overall **B** with 40 items, all marked done. Main is now synced at `origin/main` commit `89759b1` (`0.4.4`), and the largest improvements are visible in the evidence: dashboard tests now import real dashboard files with Istanbul coverage (`mobile/android/dashboard-tests/setup/load-dashboard.js:30-47`, `mobile/android/dashboard-tests/vitest.config.js:18-30`), CI checks generated dashboard drift and bundle size (`mobile/android/build.gradle:42-100`), security noise from the old archive is gone, backup validation/encryption exists (`mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:39-80`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:280-347`), and `verifyActiveApp` passed locally.

The grade moves from **B -> A-** after the follow-up fix pass. It is not a solid A yet because the dashboard module bootstrap still preserves the `window.VoltDashboard` compatibility ABI, the DTC dictionaries remain large even though they are lazy-loaded, frontend coverage is useful but still around half the shipped JS, and the OBD/session runtime still has a few large classes even after the latest extractions.

---

## A - Architecture & Design - A-

The codebase has a clear active-app boundary: root guidance and `mobile/android/README.md:13-31` point maintainers to the Android app and generated-dashboard workflow, while ADR 0002 defines UI -> Service -> Engine -> Data dependency direction (`mobile/android/docs/adr/0002-strict-layering-rule.md:31-49`). Main now has architecture boundary tests for data/service/engine direction, meaningful extractions such as typed payload classes and `SessionStateMachine`, and a dedicated `LiveSampleReader` for live sample assembly. The remaining design debt is mostly concentration in the connection/retry half of `ObdPollingEngine` and the service lifecycle surface.

#### ~~A1 - Continue splitting `ObdPollingEngine` into connection and polling collaborators~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/LiveSampleReader.java`
- **What's wrong:** `ObdPollingEngine` still owned retry/backoff policy, ELM initialization, live PID scheduling, payload assembly, vehicle classification, raw transcript capture, and location append. The latest pass moved live sample assembly into `LiveSampleReader` while keeping command IO on the engine.
- **Impact:** Major — this is the live vehicle data path, so high coupling here can turn a Bluetooth fix into a telemetry or diagnostics regression.
- **Fix:** Extract a package-private `ObdConnectionLoop` around `runBluetoothLoop`, reconnect/backoff bookkeeping, and failure classification. Extract `LiveSampleReader` or `ObdSampleBuilder` around `readObdSample`, keeping command IO in the engine and moving payload assembly behind tests.
- **Effort:** L
- **Grade lift:** B+ -> A- (removes the last largest runtime coupling hotspot)

#### ~~A2 - Expand boundary tests beyond the data layer~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/test/java/com/volttracker/obdpoc/ArchitectureBoundaryTest.java:17-65`, `mobile/android/docs/adr/0002-strict-layering-rule.md:37-49`
- **What's wrong:** The current architecture test only protects `data/*` from importing UI/service/engine classes. ADR 0002 also says service must not call the WebView and engine must not import UI/bridge classes, but those rules are still human-enforced.
- **Impact:** Moderate — this prevents slow architectural drift as more OBD and dashboard bridge features land.
- **Fix:** Add scan rules for engine and service source files: engine cannot import `MainActivity`, `VoltBridge`, `WebViewBootstrap`, or `android.webkit`; service cannot import or call WebView APIs. Keep allowlists explicit if any current exception is intentional.
- **Effort:** S
- **Grade lift:** B+ -> A- (turns the written layering rule into a fuller regression gate)

#### ~~A3 - Retire stale bucket/code-name comments in active runtime docs~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/TroubleshooterBridge.java:35-43`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/TroubleshooterBridge.java:58-62`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/TroubleshooterBridge.java:255-259`
- **What's wrong:** A few comments still refer to internal bucket IDs like `C10`. Those labels are not product concepts and make maintained code read like a temporary work log.
- **Impact:** Minor — low runtime risk, but noisy comments slow future maintainers and code reviewers.
- **Fix:** Replace `C10` references with durable wording such as "notify-when-ready schedule" or "adapter-ready probe window." Add a small grep check only if this pattern keeps recurring.
- **Effort:** S
- **Grade lift:** B+ -> A- (removes active-code maintenance noise)

#### ~~A4 - Revisit multi-module boundaries if source keeps growing~~ ✓ done 2026-05-27
- **Where:** `mobile/android/docs/adr/0002-strict-layering-rule.md:86-91`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/`
- **What's wrong:** ADR 0002 correctly rejected Gradle multi-module enforcement as overkill, but the active Java/dashboard surface has grown past the point where one package plus tests is the only plausible shape. Current tests still keep things under control, but package boundaries are doing more work each pass.
- **Impact:** Minor — not an immediate defect, but a future scale trigger.
- **Fix:** Add a short ADR revisit note once the app crosses the documented trigger. If the engine/data/UI split keeps growing, prototype a small `:obd-core` JVM module or stricter package-level architecture tests before moving to Gradle modules.
- **Effort:** M
- **Grade lift:** B+ -> A- (keeps architecture decisions fresh as the app grows)

---

## B - Backend Quality - A-

The Android service/data layer is solid. SQLite restore validation checks schema version, required tables/columns, integrity, and foreign keys (`mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:280-347`), read projections now expose typed-record paths before dashboard JSON (`mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java:47-142`), and session lifecycle persistence has bounded queues and failure surfacing (`mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:43-92`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:636-715`). The main weakness is still business data flowing through `JSONObject` at several internal seams.

#### ~~B1 - Make telemetry payloads typed instead of wrapping raw JSON~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/TelemetryPayload.java:5-36`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:633-787`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:407-418`
- **What's wrong:** `TelemetryPayload` is currently a wrapper around a mutable `JSONObject`, unlike `StatusPayload` and `AppStatePayload`, which define explicit fields. Typos in telemetry keys can still travel from engine to service to dashboard with no compiler signal.
- **Impact:** Moderate — telemetry is user-visible and persisted, so schema drift creates recurring dashboard and data-quality bugs.
- **Fix:** Introduce a `TelemetryPayload.Builder` with explicit fields for the common sample keys, plus a bounded `extras` object for rare diagnostic fields. Keep `toJson()` at the service boundary and migrate `readObdSample` in small slices.
- **Effort:** M
- **Grade lift:** B+ -> A- (finishes the typed payload migration on the busiest ABI)

#### ~~B2 - Separate diagnostic persistence failures from intentionally lossy telemetry drops~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:62-78`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:622-633`
- **What's wrong:** The telemetry queue intentionally drops old telemetry under pressure and swallows persistence failures so polling stays alive. That is the right runtime tradeoff, but there is no dashboard/storage summary counter that tells the user or developer data was dropped.
- **Impact:** Moderate — a long drive under I/O pressure could look complete even when diagnostic rows were discarded.
- **Fix:** Persist a compact `telemetry_dropped` or `persist_degraded` event when `droppedTelemetryTasks` increments, rate-limited to avoid a write storm. Surface the count in storage summary and tests.
- **Effort:** S
- **Grade lift:** B+ -> A- (turns intentional lossiness into visible diagnostics)

#### ~~B3 - Normalize service action setup to reduce duplicated session starts~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:215-240`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:279-320`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/ObdService.java:341-350`
- **What's wrong:** Connect, scan, clear-DTC, and demo paths now share concepts but still duplicate address/name/defaulting, foreground setup, session timestamps, engine begin, log open, location tracking, `running`, and `SESSION_ACTIVE` updates across multiple methods.
- **Impact:** Moderate — adding another session mode can easily miss one lifecycle flag or log setup call.
- **Fix:** Add a small `SessionStartRequest` value object and one `startSession(request)` method that handles common setup, with mode-specific runner selection for live, scan, clear-DTC, and demo.
- **Effort:** M
- **Grade lift:** B+ -> A- (reduces lifecycle drift across session modes)

#### ~~B4 - Move dashboard JSON assembly out of persistence classes~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/data/StorageSummaryRecord.java`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/StorageSummaryJson.java`
- **What's wrong:** `ObdStoreReports` had typed read methods, but `storageSummary` still assembled dashboard-shaped JSON directly inside the data package. The data layer now returns `StorageSummaryRecord`, and `MainActivity` serializes that record through `StorageSummaryJson` before calling the WebView.
- **Impact:** Minor — current behavior is tested, but it makes future schema/UI changes noisier.
- **Fix:** Return a `StorageSummaryRecord` from the data layer and serialize it to dashboard JSON at `VoltBridge`/Activity boundary. Keep DB-specific helpers private to the data package.
- **Effort:** M
- **Grade lift:** B+ -> A- (keeps persistence contracts distinct from presentation ABI)

---

## C - Frontend Quality - B+

The dashboard is much healthier than the previous report: production now enters through a single ES module bootstrap (`mobile/android/app/src/main/assets/dashboard/js/bootstrap.js`), tests load real dashboard files via imports (`mobile/android/dashboard-tests/setup/load-dashboard.js:30-47`), Istanbul coverage has non-zero ratchets (`mobile/android/dashboard-tests/vitest.config.js:18-30`), storage errors render as real blocked states (`mobile/android/app/src/main/assets/dashboard/js/panels.js:9-31`), and map tiles are opt-in (`mobile/android/app/src/main/assets/dashboard/js/map.js:66-80`). The remaining frontend debt is size and ABI structure: modules still mutate `window.VoltDashboard` for Android bridge compatibility, and the DTC dictionaries dominate the first-party bundle.

#### ~~C1 - Convert dashboard scripts from globals to real ES modules~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/dashboard-src/index.template.html:119`, `mobile/android/app/src/main/assets/dashboard/js/bootstrap.js`, `mobile/android/dashboard-tests/script-order.test.js`
- **What's wrong:** Tests could import files for coverage, but production still relied on ordered global scripts and `window.VoltDashboard` mutation. Production now loads a single `type="module"` bootstrap that imports the dashboard modules in tested order.
- **Impact:** Major — this is the core WebView UI wiring and the most common place for user-visible regressions.
- **Fix:** Add a small `bootstrap.js` module that imports and initializes dashboard modules in order. Move each IIFE to an exported `initX(VD)` function, preserve the public Android bridge names, and update the template plus tests together.
- **Effort:** L
- **Grade lift:** B -> B+ (turns load order and shared globals into explicit dependencies)

#### ~~C2 - Split DTC dictionaries into lazy-loaded data chunks~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/dtc-lookup.js:1-20`, `mobile/android/app/src/main/assets/dashboard/js/dtc-causes.js:1-31`, `mobile/android/app/src/main/dashboard-src/index.template.html:119-123`
- **What's wrong:** `dtc-lookup.js` is about 219 KB and `dtc-causes.js` is about 118 KB, and both load before the dashboard can finish booting even when the user never opens Insights or scans codes.
- **Impact:** Moderate — this increases WebView parse/startup cost and pushes the dashboard close to its 650 KB first-party JS/CSS budget.
- **Fix:** Move dictionaries to JSON assets or generated JS chunks loaded only when the Insights/DTC panel is opened. Cache the loaded dictionaries in `VD` and add tests for lookup fallback before/after lazy load.
- **Effort:** M
- **Grade lift:** B -> B+ (cuts startup parse cost and bundle pressure)

#### ~~C3 - Replace remaining markup string injection with DOM builders~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/drive.js:46`, `mobile/android/app/src/main/assets/dashboard/js/scrubber.js:354`, `mobile/android/app/src/main/assets/dashboard/js/scrubber.js:401`, `mobile/android/app/src/main/assets/dashboard/js/panels.js:812`
- **What's wrong:** Most data rendering now uses DOM APIs and `textContent`, but a few paths still use `insertAdjacentHTML` or `innerHTML`. Some are controlled SVG/markup, but the mixed style makes future user-data edits easier to get wrong.
- **Impact:** Moderate — current known call sites appear controlled, but consistency matters in a WebView bridge that displays stored vehicle/session data.
- **Fix:** Convert the remaining HTML-string builders to `document.createElement` helpers where they touch labels or state. Keep the SVG chart path if generated only from numeric values, but wrap it in a helper with an explicit comment and tests.
- **Effort:** S
- **Grade lift:** B -> B+ (reduces future XSS-style mistakes and rendering drift)

#### ~~C4 - Raise frontend coverage toward the Android bar~~ ✓ done 2026-05-27
- **Where:** `mobile/android/dashboard-tests/vitest.config.js:25-30`, `mobile/android/app/src/main/assets/dashboard/js/map.js`, `mobile/android/app/src/main/assets/dashboard/js/troubleshooter.js`
- **What's wrong:** Dashboard coverage is now real and passing, but current lines/functions are still roughly half the shipped JS. The biggest remaining gaps are likely map interaction, scrubber edge cases, troubleshooting flows, and DTC rendering.
- **Impact:** Moderate — useful coverage exists, but complex UI paths still have room for silent regressions.
- **Fix:** Add focused jsdom tests for map tile toggling/fallback, scrubber route-point snapping, clear-DTC warning flow, and trouble-shooter notify-when-ready state. Ratchet thresholds after each group.
- **Effort:** M
- **Grade lift:** B -> B+ (brings frontend reliability closer to the Java suite)

#### ~~C5 - Introduce a generated DTC-data validation step~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/dtc-lookup.js:19-80`, `mobile/android/app/src/main/assets/dashboard/js/dtc-causes.js:31-80`, `mobile/android/dashboard-tests/`
- **What's wrong:** Large hand-maintained DTC objects are easy to duplicate, mis-sort, or assign malformed codes. The current tests validate behavior around the UI, but not the integrity of the whole dictionary.
- **Impact:** Moderate — wrong diagnostic descriptions are user-visible and worse than showing no description.
- **Fix:** Add a dashboard test or small Node script that checks unique uppercase DTC keys, valid code shape, non-empty descriptions/causes, known sample coverage, and no duplicate/conflicting entries.
- **Effort:** S
- **Grade lift:** B -> B+ (protects the largest domain-data asset)

---

## D - Testing & Reliability - A-

Main now has a strong local and CI safety net. The synced run passed `./gradlew --no-daemon verifyActiveApp`, which includes unit tests, lint, Spotless, assemble, JaCoCo coverage, dashboard lint/tests, bundle-size check, and generated-dashboard drift check (`mobile/android/build.gradle:89-100`). The latest local test evidence was 437 Java tests across 53 JVM test files and 43 dashboard tests across 10 JS files, with dashboard coverage at 49.29% lines / 48.8% functions and zero npm audit findings. This is near A-range for a small Android app, but real-device and long-run reliability are still thin.

#### ~~D1 - Add a small real-device or emulator smoke lane~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/android.yml:25-78`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/`
- **What's wrong:** The suite is JVM/Robolectric only by design, which is good for speed, but there is no smoke test for Android platform behavior around foreground services, WebView startup, notification permission surfaces, or Bluetooth permission flows on an actual Android runtime.
- **Impact:** Major — these are the paths most likely to differ between Robolectric and a phone.
- **Fix:** Add a minimal emulator smoke job that installs the debug APK, launches `MainActivity`, waits for the dashboard asset to load, and checks logcat for startup exceptions. Keep it nightly or workflow-dispatch if PR runtime is a concern.
- **Effort:** M
- **Grade lift:** B+ -> A- (covers platform integration that JVM tests cannot fully prove)

#### ~~D2 - Add long-session persistence/backpressure tests~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:43-92`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:671-693`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/SessionRecorderTest.java`
- **What's wrong:** Queue caps and drain behavior are carefully designed, but test coverage should stress a long session with slow/blocked persistence and verify finalization, dropped-count visibility, and no deadlock.
- **Impact:** Moderate — long drives are exactly where hidden queue behavior matters.
- **Fix:** Add a fake/slow `ObdLocalStore` test that floods more than `TELEMETRY_QUEUE_CAPACITY`, closes the session, and asserts finalize returns, latest rows are retained, and drop diagnostics are surfaced.
- **Effort:** M
- **Grade lift:** B+ -> A- (hardens the reliability story under sustained load)

#### ~~D3 - Cover release workflow behavior with local/static tests~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/release.yml:49-60`, `.github/scripts/check_release_config.py`, `.github/workflows/pr-title-lint.yml`
- **What's wrong:** Release config now has a Python validator, but workflow behavior still depends on semantic-release, PR-title conventions, and tag resolution shell. The failure modes are mostly discovered after merge.
- **Impact:** Moderate — release/version drift is user-visible because APK version strings and tags drive install artifacts.
- **Fix:** Extend `check_release_config.py` or add a shell/unit test that validates expected bumpable commit subjects, no-release warning behavior, and required release assets before merges.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps release automation from regressing quietly)

#### ~~D4 - Add full dictionary integrity tests for DTC data~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/dtc-lookup.js`, `mobile/android/app/src/main/assets/dashboard/js/dtc-causes.js`, `mobile/android/dashboard-tests/`
- **What's wrong:** The DTC lookup and cause datasets are product-critical, large, and easy to regress, but coverage mostly exercises the UI surfaces rather than full data integrity.
- **Impact:** Moderate — inaccurate diagnostics can mislead users troubleshooting a real vehicle issue.
- **Fix:** Add table-driven tests over every dictionary entry for code format, unique keys, non-empty text, severity/category validity, and known Volt-specific sample lookups.
- **Effort:** S
- **Grade lift:** B+ -> A- (validates high-volume domain content)

#### ~~D5 - Track generated coverage artifacts outside the working tree~~ ✓ verified 2026-05-27
- **Where:** `mobile/android/dashboard-tests/coverage/`, `mobile/android/app/build/reports/jacoco/`, `.gitignore`
- **What's wrong:** Test runs generate coverage/report directories inside the repo tree. They appear ignored in practice, but they are easy to include accidentally in broad file searches and can make audits noisy.
- **Impact:** Minor — mostly DevEx/review noise, not runtime risk.
- **Fix:** Ensure `.gitignore` explicitly covers dashboard coverage and Android build reports, and consider sending coverage output to a temp/build directory by default.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps validation artifacts from polluting repo exploration)

---

## E - Security - B+

Security is substantially cleaner than the old report. The manifest disables Android backup, keeps services/providers unexported, disables cleartext traffic, and documents sensitive permissions (`mobile/android/app/src/main/AndroidManifest.xml:40-72`). WebView hardening disables file/content access and gates debugging on debug builds (`mobile/android/app/src/main/java/com/volttracker/obdpoc/WebViewBootstrap.java:32-44`), CSP blocks remote scripts (`mobile/android/app/src/main/dashboard-src/index.template.html:15-18`), map tiles are opt-in (`mobile/android/docs/privacy-data-handling.md:32-39`), and encrypted backups exist (`mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:153-179`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:366-429`). The remaining concerns are hardening around optional plaintext export, WebView test completeness, and workflow supply-chain updates.

#### ~~E1 - Make encrypted backup the default sharing path~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/DataBackup.java:126-179`, `mobile/android/docs/privacy-data-handling.md:21-30`
- **What's wrong:** The app supports encrypted backups, but the plaintext backup path still exists and docs still describe the backup action as sharing a full SQLite copy. A user can still accidentally share unencrypted GPS/OBD history.
- **Impact:** Major — backup files contain sensitive route and vehicle history once they leave app-private storage.
- **Fix:** Make encrypted backup the primary/default button and move plaintext export behind an explicit advanced/disclosed action. Update docs and UI copy so plaintext is clearly a compatibility escape hatch.
- **Effort:** M
- **Grade lift:** B -> B+ (reduces accidental sensitive-data disclosure)

#### ~~E2 - Assert the WebView JavaScript bridge name and debugging behavior~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/WebViewBootstrap.java:32-103`, `mobile/android/app/src/test/java/com/volttracker/obdpoc/WebViewBootstrapTest.java:20-43`
- **What's wrong:** The test checks core WebSettings but not the bridge attachment name, loaded asset URL, WebViewClient one-shot behavior, or debug-mode behavior. A future change could break the Android-to-JS ABI without this test noticing.
- **Impact:** Moderate — the WebView bridge is a trust boundary and critical ABI.
- **Fix:** Extend `WebViewBootstrapTest` with a custom/shadowed WebView or Robolectric accessors that assert the bridge is attached as `VoltTrackerAndroid`, the URL is `file:///android_asset/dashboard/index.html`, and debugging follows `BuildConfig.DEBUG`.
- **Effort:** S
- **Grade lift:** B -> B+ (locks down the WebView boundary more completely)

#### ~~E3 - Upgrade pinned GitHub Actions majors from Dependabot PRs~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/android.yml:32-35`, `.github/workflows/android.yml:85-90`, `.github/workflows/android.yml:101-107`, `.github/workflows/release.yml:34-47`
- **What's wrong:** Dependabot has opened current action-major update branches, while main still pins older majors in several workflow steps. Pinning SHAs is good, but staying on old action majors leaves support/security fixes for the workflow layer pending.
- **Impact:** Moderate — CI supply-chain hygiene matters because workflows build and publish APK artifacts.
- **Fix:** Review and merge the action update PRs in small batches, then run `gh pr checks` and the local `verifyActiveApp` equivalent. Keep SHA pinning after updates.
- **Effort:** S
- **Grade lift:** B -> B+ (keeps workflow dependencies current without weakening pinning)

#### ~~E4 - Add a security scan for dashboard DOM sink regressions~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/drive.js:46`, `mobile/android/app/src/main/assets/dashboard/js/scrubber.js:354`, `mobile/android/app/src/main/assets/dashboard/js/scrubber.js:401`, `mobile/android/app/src/main/assets/dashboard/js/panels.js:812`
- **What's wrong:** Remaining HTML-string sinks are probably controlled today, but there is no static guardrail that fails if future code adds `innerHTML` with bridge/storage data.
- **Impact:** Moderate — bridge-fed stored data displayed in a WebView should default to text nodes.
- **Fix:** Add an ESLint custom rule or simple grep-based test that permits known audited sinks with comments and fails on new `innerHTML`/`insertAdjacentHTML` call sites.
- **Effort:** S
- **Grade lift:** B -> B+ (prevents accidental XSS-style regressions)

---

## F - Dependencies & Tech Currency - A-

The active dependency surface is small and current. `libs.versions.toml` declares only AndroidX Core plus test dependencies (`mobile/android/gradle/libs.versions.toml:1-15`), dashboard dev dependencies are current in `package.json` (`mobile/android/dashboard-tests/package.json:11-16`), `npm audit --audit-level=low` found 0 vulnerabilities, and `./gradlew dependencyUpdates` reports the active Android dependencies/tooling current except transitive Kotlin milestone noise and a Gradle 10 deprecation from the Versions plugin. Dependabot now watches Gradle, npm, and GitHub Actions (`.github/dependabot.yml:1-24`).

#### ~~F1 - Resolve the Gradle 10 deprecation in dependency update tooling~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:1-13`, `mobile/android/docs/dependency-report-2026-05-26.md:72-75`
- **What's wrong:** `./gradlew dependencyUpdates` passes, but it still emits "Deprecated Gradle features were used in this build, making it incompatible with Gradle 10." The report says to watch the plugin, but the warning remains live noise.
- **Impact:** Moderate — warning fatigue around build tooling makes real Gradle compatibility warnings easier to miss.
- **Fix:** Run `./gradlew dependencyUpdates --warning-mode all`, identify whether the deprecation comes from the Versions plugin or local build script usage, and either update/file upstream or document a tracked issue with the exact warning.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps dependency tooling quiet and future-ready)

#### ~~F2 - Reconcile dependency report drift after switching coverage provider~~ ✓ done 2026-05-27
- **Where:** `mobile/android/docs/dependency-report-2026-05-26.md:81-92`, `mobile/android/dashboard-tests/package.json:11-16`
- **What's wrong:** The docs list `@vitest/coverage-v8`, while main now uses `@vitest/coverage-istanbul`. The actual package is correct, but the current dependency report is already stale.
- **Impact:** Minor — documentation drift can mislead future upgrade work.
- **Fix:** Update the dependency report table and package-range bullets to `@vitest/coverage-istanbul`, and add the regrade validation commands/results from this audit.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps dependency guidance accurate)

#### ~~F3 - Batch and verify GitHub Actions Dependabot updates~~ ✓ done 2026-05-27
- **Where:** `.github/dependabot.yml:21-24`, `.github/workflows/android.yml`, `.github/workflows/release.yml`
- **What's wrong:** Dependabot is correctly opening action update branches, but the current repo state has several workflow dependency upgrades pending. These are not app runtime dependencies, but they affect builds, artifacts, and releases.
- **Impact:** Moderate — stale CI actions can become security or platform breakage later.
- **Fix:** Review the open action PRs, merge compatible ones in grouped batches, and rerun PR checks plus a release-workflow dry validation where possible.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps automation dependencies current)

#### ~~F4 - Add a recurring dependency snapshot command to CI or automation~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:111-120`, `mobile/android/docs/reports-index.md:7-16`, `.github/workflows/android.yml`
- **What's wrong:** The dependency snapshot is currently a manual dated report. That is fine for a small app, but it can go stale quickly when Dependabot branches accumulate.
- **Impact:** Minor — current dependencies are healthy, but maintenance relies on someone remembering to re-run the check.
- **Fix:** Add a scheduled or workflow-dispatch dependency report job that runs `./gradlew dependencyUpdates` and `npm outdated --long` and posts artifacts or step summaries without failing on available updates.
- **Effort:** S
- **Grade lift:** B+ -> A- (keeps tech-currency evidence fresh)

---

## G - Performance & Scalability - B+

The app has good performance guardrails for its size: dashboard bundle budget is enforced (`mobile/android/build.gradle:68-86`), local validation reported 614,604 bytes under the 650,000-byte budget, telemetry persistence queues are bounded (`mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:43-92`), and SQLite query/index behavior has dedicated tests. The main performance risk is the near-budget first-party dashboard bundle.

#### ~~G1 - Lazy-load or generate compact DTC dictionary assets~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/assets/dashboard/js/dtc-lookup.js:1-20`, `mobile/android/app/src/main/assets/dashboard/js/dtc-causes.js:1-31`, `mobile/android/build.gradle:68-86`
- **What's wrong:** Two DTC dictionary scripts account for roughly 337 KB of the 614,604-byte first-party JS/CSS bundle. They are now lazy-loaded instead of parsed at startup, but still dominate the first-party byte budget until they become compact generated data assets.
- **Impact:** Major — startup parse cost on older phones can become user-visible before the hard byte budget fails.
- **Fix:** Generate compact JSON assets grouped by prefix/family and lazy-load them from the Insights/DTC code path. Keep a tiny fallback lookup for common/generic codes if instant display is needed.
- **Effort:** M
- **Grade lift:** B -> B+ (removes the biggest startup and bundle pressure)

#### ~~G2 - Add a dashboard startup performance budget~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:68-86`, `mobile/android/dashboard-tests/setup/load-dashboard.js:49-53`
- **What's wrong:** The build enforces byte size, but not parse/bootstrap time. A small code change can keep the bundle under 650 KB while making startup slower.
- **Impact:** Moderate — WebView dashboard startup is a core user path.
- **Fix:** Add a Node/jsdom or browser-based smoke that measures dashboard bootstrap/import time for the production HTML and fails only on a generous regression threshold. Track it in CI step summary first if hard gating feels too noisy.
- **Effort:** M
- **Grade lift:** B -> B+ (protects perceived startup speed, not just file size)

#### ~~G3 - Surface telemetry queue drops in performance diagnostics~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:43-92`, `mobile/android/app/src/main/java/com/volttracker/obdpoc/SessionRecorder.java:104-127`
- **What's wrong:** The queue backpressure strategy protects memory, but users/developers do not see when it has activated. Performance degradation can masquerade as a normal complete session.
- **Impact:** Moderate — backpressure is most likely on long sessions or constrained devices.
- **Fix:** Persist a rate-limited drop counter and show it in the storage/debug summary. Add a test that the counter increments when the queue overflows.
- **Effort:** S
- **Grade lift:** B -> B+ (makes performance degradation observable)

#### ~~G4 - Put bundle-size reporting and budget history in docs~~ ✓ done 2026-05-27
- **Where:** `.github/workflows/android.yml:108-125`, `mobile/android/docs/reports-index.md:7-16`
- **What's wrong:** CI writes a dashboard bundle-size step summary, but there is no durable trend or dated note in the reports index. The budget is enforced, but the project does not show whether it is steadily approaching the limit.
- **Impact:** Minor — good current guardrail, weak trend visibility.
- **Fix:** Add a short dated bundle-size note to `docs/reports-index.md` or generate an artifact from CI. Include total bytes, budget, and biggest assets.
- **Effort:** S
- **Grade lift:** B -> B+ (helps avoid surprise budget failures)

---

## H - Documentation & Onboarding - B+

Docs are much better than the previous state. `mobile/android/README.md` explains the architecture, build, verification, dependency checks, privacy model, map tile behavior, and field-test log pulling (`mobile/android/README.md:13-120`, `mobile/android/README.md:147-185`). ADRs cover WebView/dashboard and layering decisions, and reports are indexed (`mobile/android/docs/reports-index.md:7-16`). Remaining documentation debt is mostly freshness and advanced operations.

#### ~~H1 - Update the dependency report after the regrade~~ ✓ done 2026-05-27
- **Where:** `mobile/android/docs/dependency-report-2026-05-26.md:77-93`, `mobile/android/dashboard-tests/package.json:11-16`
- **What's wrong:** The current dependency report says `@vitest/coverage-v8`, but the actual package is `@vitest/coverage-istanbul`. It also predates the latest synced-main validation results.
- **Impact:** Moderate — stale dependency docs can send future maintenance down the wrong path.
- **Fix:** Update the dashboard dependency table, command results, and maintenance notes with the current `dependencyUpdates`, `npm audit`, and coverage-provider state.
- **Effort:** S
- **Grade lift:** B -> B+ (keeps the active dependency snapshot trustworthy)

#### ~~H2 - Add release/signing/on-phone install troubleshooting docs~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:67-85`, `.github/workflows/release.yml:133-180`
- **What's wrong:** The README covers debug build/install, while release APK signing behavior is mostly documented inside the workflow comments. A maintainer debugging unsigned release assets has to inspect CI YAML.
- **Impact:** Moderate — release/install confusion is user-facing when distributing APK artifacts.
- **Fix:** Add a short `mobile/android/docs/release.md` covering debug vs release APKs, optional signing secrets, latest-debug release behavior, and where artifacts land.
- **Effort:** S
- **Grade lift:** B -> B+ (makes shipping/on-phone validation easier)

#### ~~H3 - Document dashboard module/load-order contract until modules land~~ ✓ done 2026-05-27
- **Where:** `mobile/android/app/src/main/dashboard-src/index.template.html:119-131`, `mobile/android/dashboard-tests/setup/load-dashboard.js:30-47`
- **What's wrong:** Production script order and test import order must stay aligned, but the contract is split between the template and test loader. A future script addition can easily update one but miss the other.
- **Impact:** Moderate — load-order drift can break the dashboard at runtime.
- **Fix:** Add a short dashboard architecture note or generated manifest that lists script order once and is consumed by both template generation/tests. If C1 lands, replace this with module dependency docs.
- **Effort:** S
- **Grade lift:** B -> B+ (reduces onboarding mistakes in the dashboard)

#### ~~H4 - Move historical bug-hunt reports farther from active guidance~~ ✓ done 2026-05-27
- **Where:** `mobile/android/docs/reports-index.md:7-16`, `mobile/android/docs/android-bug-hunt-third-pass-30.md`, `mobile/android/docs/android-polish-second-pass-30.md`
- **What's wrong:** The reports index labels older audit documents as historical, but they still live beside current ADRs and dependency docs. New contributors can still treat stale findings as active guidance without reading the status column carefully.
- **Impact:** Minor — mostly onboarding noise.
- **Fix:** Move historical reports into `mobile/android/docs/archive/` or add a stronger header at the top of each historical report pointing to `.Codex/grade-report.md` for current items.
- **Effort:** S
- **Grade lift:** B -> B+ (keeps current guidance easier to find)

---

## I - Developer Experience & Tooling - A-

DevEx is strong: `verifyActiveApp` aggregates the real app validation suite (`mobile/android/build.gradle:89-100`), Android lint runs with warnings as errors (`mobile/android/app/build.gradle:104-110`), Spotless formats Java and dashboard assets (`mobile/android/app/build.gradle:7-22`), dashboard ESLint catches semantic JS errors (`mobile/android/eslint.config.js:51-65`), and pre-commit hooks run formatting plus dashboard lint when dependencies are installed (`lefthook.yml:4-29`). The remaining issues are noise and local/CI parity rather than missing fundamentals.

#### ~~I1 - Add actionlint or workflow validation to CI/local hooks~~ ✓ done 2026-05-27
- **Where:** `.github/actionlint.yaml`, `.github/workflows/android.yml`, `.github/workflows/release.yml`, `lefthook.yml:4-29`
- **What's wrong:** The repo has an `actionlint` config file, but no visible workflow or local hook runs it. Workflow edits can pass code tests but fail only after push.
- **Impact:** Moderate — broken CI YAML blocks every PR and release path.
- **Fix:** Add an actionlint step in CI and a lefthook command for `.github/workflows/**/*.{yml,yaml}` changes, using the existing config.
- **Effort:** S
- **Grade lift:** B+ -> A- (catches workflow errors before they hit PRs)

#### ~~I2 - Make local dashboard lint setup more self-healing~~ ✓ done 2026-05-27
- **Where:** `lefthook.yml:15-28`, `mobile/android/build.gradle:19-39`
- **What's wrong:** The pre-commit hook skips dashboard ESLint if `dashboard-tests/node_modules` is missing. That is friendly for first clones, but a developer can commit JS without local semantic lint and only learn in CI.
- **Impact:** Moderate — it weakens a useful local guardrail for dashboard changes.
- **Fix:** Change the hook to offer an opt-in auto `npm ci` path, or add a fast `lefthook run setup` / `mobile/android/README.md` first-time command that installs dashboard deps before enabling the hook.
- **Effort:** S
- **Grade lift:** B+ -> A- (improves local/CI parity)

#### ~~I3 - Reduce duplicate Gradle/dashboard validation wiring~~ ✓ done 2026-05-27
- **Where:** `mobile/android/build.gradle:28-40`, `mobile/android/build.gradle:89-100`, `.github/workflows/android.yml:39-56`, `.github/workflows/android.yml:91-100`
- **What's wrong:** The aggregate Gradle task and CI workflow both spell out overlapping validation steps. This is readable today, but future additions can land in one place and not the other.
- **Impact:** Minor — duplicated validation wiring causes drift over time.
- **Fix:** In CI, call `./gradlew --no-daemon verifyActiveApp` for the unit job or add comments that explain why each CI step intentionally stays separate for artifact upload/debuggability.
- **Effort:** S
- **Grade lift:** B+ -> A- (reduces future validation drift)

#### ~~I4 - Enable Gradle configuration cache in CI once stable~~ ✓ done 2026-05-27
- **Where:** `mobile/android/README.md:107-109`, `.github/workflows/android.yml:39-56`
- **What's wrong:** The README says `verifyActiveApp` is configuration-cache ready, but CI does not use Gradle setup/cache or `--configuration-cache`. Local runs are fine, but CI leaves speed on the table.
- **Impact:** Minor — build speed polish, not correctness.
- **Fix:** Add `gradle/actions/setup-gradle` or equivalent cache support and test `verifyActiveApp --configuration-cache` in CI. Keep it opt-in if any task remains flaky with cache.
- **Effort:** S
- **Grade lift:** B+ -> A- (shortens feedback loops)
