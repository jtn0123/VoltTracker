# High-Value Reliability Work Tracker

Date started: 2026-06-05
Source: `.claude/grade-report.md`

This tracker is for the highest-ROI follow-up items after the Kotlin/TypeScript
migration. The language conversion is treated as complete; the work here is
runtime proof, reliability, and review-surface cleanup.

## Current Batch

| ID | Status | Item | Effort | ROI | Notes |
|---|---|---|---:|---:|---|
| D1/I1 | Landed | Broaden emulator smoke and add a local runtime wrapper | Medium | Very high | `emulator-smoke.sh` now grants runtime permissions, proves the handshake, starts demo telemetry, taps every bottom-nav tab, captures screenshots, and checks logcat after each phase. `run-emulator-smoke-local.sh` runs the same proof locally. |
| C3/E3 | Landed | Remove ignored `frame-ancestors` CSP meta directive | Small | Medium | Resource CSP remains pinned; WebView navigation is still guarded by `WebViewBootstrap`. |
| F2 | Landed | Align CI dashboard bundle summary with enforced Gradle budget | Small | Medium | Workflow summary now displays the same 400,000-byte core budget Gradle enforces. |
| H1 | Landed | Add current validation matrix | Small | High | `validation-matrix.md` separates local, desktop, emulator, physical device, and real-car proof levels. |
| H2 | Landed | Add repeatable field-test checklist | Small | Medium | `field-test-checklist.md` records the artifact path from field observation to sanitized fixture follow-up. |
| I3 | Landed | Surface language policy in onboarding | Small | Medium | `mobile/android/README.md` now states the Kotlin-only/TypeScript-only source policy and guard task. |
| E1a | Landed | Document WebView bridge threat model | Small | High | `bridge-threat-model.md` classifies trust boundaries, high-risk bridge methods, and review rules for future bridge changes. |
| G1/G2a | Landed | Add deterministic dashboard startup, long-route, and long-history budget tests | Small | High | `startup-budget.test.js` now covers bootstrap load, 2,000-point route distance math, long-route scrubber render, and 250-session map history list rendering under generous local budgets. |
| B2 | Landed | Pin schema-version migration coverage | Small | High | `VoltTrackerDbMigrationTest` now covers the v10->v11 trip-list-cache migration and fails loudly if `DATABASE_VERSION` changes without updating the migration test matrix. |
| E2 | Landed | Tighten encrypted-backup passphrase expectations | Small | Medium | Backup disclosure and privacy docs now say to use a strong, unique passphrase and that Volt Tracker cannot recover encrypted backups if the passphrase is lost; Robolectric pins the copy. |
| I2 | Landed | Add an Android environment doctor | Small | Medium | `scripts/doctor.sh` reports Java, Gradle wrapper, Android SDK, ADB/device, Node/npm, dashboard dependency, Playwright, and local smoke-script readiness without installing anything. |
| J1 | Landed | Dogfood and fix API-28 WebView bottom navigation | Medium | Very high | Emulator screenshots showed the app nav hidden behind the Android nav bar and smoke taps missing the WebView. CSS now has old-WebView fallbacks and a usable bottom floor; smoke scales tap coordinates and requires tab screenshots to change. |
| J2 | Landed | Dogfood and fix Map empty-state overlap | Small | High | The Map empty message overlapped layer controls on-device. `map-empty` now reserves top space and `layout-css.test.js` guards it. |
| J3 | Landed | Fix emulator smoke service-control false positives | Small | Very high | Demo start now runs as the app UID, proves demo telemetry in the private OBD log, clears stale smoke logs, and uses plain `startservice` for disconnect so cleanup does not crash the process. |
| J4 | Landed | Dogfood Android 16 Galaxy S24 display profile | Medium | Very high | Local AVD `galaxy-s24-api36` runs Android 16/API 36 at 1080x2340 and density 416. Dogfood found the seven-item bottom nav needed `minmax(0, 1fr)`, tap-focus blur, and an opaque clipped rail to avoid Android WebView rendering artifacts. |
| J5 | Landed | Dogfood the Android demo preview with Power User data | Medium | Very high | Android dogfood showed native storage refreshes overwrote demo scenario data, leaving Charge/Trips/Map/Insights empty while Drive streamed. Demo preview state is now protected while active, native real data is parked separately, and stopping demo restores real bridge data. |
| J6 | Landed | Fix dogfood follow-up UX issues | Small | High | Fresh launch no longer auto-stacks runtime permission prompts; permissions are requested from explicit user actions. Blocked adapter actions now mirror the actionable reason into visible body copy, and Last preflights remembered-adapter state before calling the bridge. |
| J7 | Landed | Fix S24 WebView bottom-nav paint tearing | Medium | Very high | Follow-up dogfood showed the fixed floating nav hit-tested correctly but painted as split/clipped over Drive content. The shell now uses a viewport-height body, scrollable `main.app`, and a normal footer nav so content no longer composites underneath the controls. |

## Deferred Large Items

| ID | Status | Item | Effort | ROI | Deferral reason |
|---|---|---|---:|---:|---|
| A1 | Deferred | Split `MainActivity`, `ObdService`, and `ObdPollingEngine` coordinators | Large | High | Worth doing next, but it needs careful staged refactors under focused tests. |
| B1 | Deferred | Add broad real OBD failure replay fixtures | Large | High | Needs sanitized real adapter/car logs to avoid inventing unrealistic fixtures. |
| G1/G2b | Deferred | Device/WebView performance budgets from emulator or physical-device timings | Medium | High | Local deterministic budgets are landed; calibrated Android WebView timings should be added after collecting a few stable device/emulator baselines. |
| E1b | Deferred | Full WebView bridge minimization | Medium | High | Threat model is documented; code minimization should be a deliberate staged pass around destructive/admin methods. |

## Completion Notes

- Update this file when items land or are deliberately moved to a later batch.
- Keep validation evidence with the final PR/check summary, not only in chat.

## Validation Evidence

- `./gradlew generateDashboardHtml` passed after the CSP template update.
- `bash -n mobile/android/scripts/emulator-smoke.sh` passed.
- `bash -n mobile/android/scripts/run-emulator-smoke-local.sh` passed.
- `ruby -e 'require "yaml"; ...' .github/workflows/android.yml .github/workflows/android-emulator-smoke.yml` parsed both workflow files.
- `npm test -- csp.test.js` passed: 4 tests.
- `./gradlew verifyActiveApp` passed: Android unit/lint/coverage/build, dashboard typecheck/lint/Vitest, Playwright e2e, generated-dashboard drift, migration straggler guard, and bundle budgets.
- `./gradlew :app:testDebugUnitTest --tests com.volttracker.obdpoc.VoltBridgeTest --tests com.volttracker.obdpoc.TroubleshooterBridgeTest` passed.
- `scripts/run-emulator-smoke-local.sh` passed on local headless AVD `l3dump-api28-googleapis-arm64`: APK install, dashboard handshake, native demo telemetry proof from the app's private OBD log, seven bottom-nav screenshot-change checks, logcat checks, and screenshot capture.
- `npm test -- startup-budget.test.js` passed: 4 tests covering dashboard startup, long-route math, scrubber render, and long map history rendering.
- `npm test -- layout-css.test.js` passed: 4 tests covering app-shell scroll, Map empty-state spacing, narrow seven-item bottom-nav fit, and floating-nav readability.
- `npm test -- layout-css.test.js startup-budget.test.js` passed: 8 tests.
- `scripts/run-emulator-smoke-local.sh` passed on local headless AVD `galaxy-s24-api36`: Android 16/API 36, `1080x2340`, density `416`, APK install, dashboard handshake, native demo telemetry proof, seven bottom-nav screenshot-change checks, logcat checks, and screenshot capture.
- `npm test -- demo-data.test.js demo-sample.test.js demo-scenarios.test.js` passed: 10 tests, including the Android bridge path where native empty storage refreshes arrive during an active demo.
- `./gradlew generateDashboardHtml :app:assembleDebug :app:installDebug` passed on `galaxy-s24-api36`.
- Android dogfood report: `dogfood-output/android-s24-api36/report.md`. Final Power User demo screenshots show Drive, Trips, Map, Charge, Insights, and Signals populated and logcat-clean.
- `./gradlew :app:testDebugUnitTest --tests com.volttracker.obdpoc.MainActivityPermissionTest --tests com.volttracker.obdpoc.PermissionGateTest` passed after removing the broad launch-time permission request.
- `npm test -- actions.test.js demo-data.test.js` passed: 28 tests, including visible body feedback for blocked adapter actions and the Android demo-preview regression.
- `npm test -- layout-css.test.js` passed after converting the dashboard shell to body flex + `main.app` scroll + footer nav.
- `./gradlew :app:assembleDebug :app:installDebug` passed on `galaxy-s24-api36` after the footer-nav shell change.
- Android footer-nav dogfood screenshots: `fresh-launch-footer-shell-crop.png` and `connect-no-adapter-footer-shell-crop.png`; the no-adapter state is expected and now renders without the broken floating rail.
- `./gradlew verifyActiveApp` passed after the footer-nav shell fix: dashboard lint/typecheck/Vitest, Playwright e2e, Android unit/lint/coverage/build, generated-dashboard drift, migration straggler guard, and bundle budgets.
- `./gradlew :app:installDebug` passed on `galaxy-s24-api36` after the final verified bundle; fresh-launch screenshot `fresh-launch-footer-shell-final-crop.png` stayed logcat-clean.
- Codex takeover follow-up: `npm test -- demo-scenarios.test.js demo-data.test.js actions.test.js layout-css.test.js startup-budget.test.js` passed with 42 tests, including a new Insights regression where non-trip storage rows keep the first-run guide visible.
- Codex takeover follow-up: S24 WebView CDP proof returned `insightsEmptyHidden=false`, `insightTripCount="--"`, and `navItems=7` for the non-trip storage row edge; screenshots captured as `fresh-launch-codex-takeover.png` and `insights-empty-nontrip-rows-codex-takeover.png`.
- Codex takeover follow-up: `./gradlew verifyActiveApp` passed after applying Kotlin Spotless formatting: dashboard lint/typecheck/Vitest, Playwright e2e, Android unit/lint/coverage/build, generated-dashboard drift, migration straggler guard, and bundle budgets.
- Codex takeover follow-up: `scripts/run-emulator-smoke-local.sh` passed on the running `galaxy-s24-api36` AVD: dashboard handshake, native demo telemetry, all seven bottom-nav taps, logcat checks, and screenshot capture.
- Release takeover follow-up: `bash -n mobile/android/scripts/doctor.sh` passed.
- Release takeover follow-up: `./scripts/doctor.sh` passed with ADB access; no devices were attached, and `emulator` was not on `PATH` in this shell.
- Release takeover follow-up: `./gradlew :app:testDebugUnitTest --tests com.volttracker.obdpoc.BackupControllerShareTest --tests com.volttracker.obdpoc.data.VoltTrackerDbMigrationTest` passed.
- Release takeover follow-up: `./gradlew verifyActiveApp` passed: dashboard lint/typecheck/Vitest, Playwright e2e, Android unit/lint/coverage/build, generated-dashboard drift, migration straggler guard, and bundle budgets.
