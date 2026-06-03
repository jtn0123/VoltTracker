# Bug Polish Audit: 30 Findings

Scope: Android app only (`mobile/android/`). I did not edit generated dashboard
HTML; dashboard findings point at source JavaScript/partials and verification
tasks.

## Skills Used

- `bug-hunt`: validated and ranked a requested count of real issues with
  evidence.
- `github`: checked live open pull requests before reporting the polish state.
- `systematic-debugging`: kept fixes tied to source evidence and regression
  tests.
- `pr-make`: used for the branch validation, commit, push, and PR lifecycle.

## Pull Request Check

- PR #154: `chore(deps-dev): bump eslint from 10.4.0 to 10.4.1 in /mobile/android/dashboard-tests`
  by Dependabot. Merged after green checks.
- PR #153: `chore(deps): bump softprops/action-gh-release from 2.6.2 to 3.0.0`
  by Dependabot. Rebased via `@dependabot rebase`, then merged after green checks.

Recommendation:

- PR #154 is worth merging. It is a semver-patch dev-dependency update, touches
  only `dashboard-tests/package.json` and `package-lock.json`, and the PR checks
  are green. The release notes include a real ESLint bug fix for delegated
  command failures, so this is useful maintenance rather than noise.
- PR #153 is probably worth merging if the repo is staying on GitHub-hosted
  runners. It pins `softprops/action-gh-release` from v2.6.2 to v3.0.0, whose
  major change is the Node 24 Actions runtime. The normal PR checks are green,
  but the actual `publish-debug-release` job is skipped on PRs, so the release
  upload path is not fully proven until the workflow runs on `main`.

Merge result:

- Both PRs were rebase-merged into `main`; no open PRs remained afterward.
- PR #153 needed Dependabot's own rebase path because direct `gh pr update-branch`
  was blocked by OAuth workflow-file scope.

## Validation Snapshot

- Final validation: `cd mobile/android && ./gradlew --no-configuration-cache
  verifyActiveApp` passed. This covered JVM tests, lint, Spotless, assemble,
  JaCoCo, dashboard lint/typecheck/unit tests, Playwright e2e, bundle budgets,
  and generated-dashboard drift.
- During validation, the disk filled while Gradle/Jacoco was writing test output.
  After clearing generated caches and rebuilding the Gradle dependency cache, the
  Android unit/coverage lane passed and the full `verifyActiveApp` gate passed
  end to end.
- The old dashboard binding warnings for missing `#updatedValue` / `#loadMeter`
  are gone.

Fix result:

- All 30 findings in this report are fixed in the branch.
- Regression coverage was added or updated for restore picker/result handling,
  restore schema validation, transient backup/restore cache cleanup, diagnostics
  disclosure, permission result messaging, telemetry persistence failure
  observability, merge summaries/session dedupe/session-link preservation,
  Bluetooth adapter/address handling, stored pack voltage/current reporting, and
  dashboard remote-tile defaults.
- Local and CI gates now cover the previously advisory/omitted verification
  paths: Playwright e2e is in `verifyActiveApp`; visual screenshots, Java
  dependency audit, and emulator smoke are wired into `ci-success`.

## Findings

| # | Area | Finding | Evidence | Impact | Ease |
|---|------|---------|----------|--------|------|
| 1 | Privacy | Remote map tiles are enabled by default even though the README says remote basemaps stay off by default. | `mobile/android/README.md:168-170`; `app/src/main/assets/dashboard/js/core.js:55-60,260`; `app/src/main/assets/dashboard/js/map.js:26-31,68-78`. | High | Easy |
| 2 | Dashboard DOM | The telemetry updater still writes to removed `#updatedValue`, creating noisy skipped-binding warnings and hiding future DOM drift. | `app/src/main/assets/dashboard/js/telemetry.js:449`; `app/src/main/assets/dashboard/js/core.js:328-357`; `verifyActiveApp` stderr. | Medium | Easy |
| 3 | Dashboard DOM | The telemetry updater still writes to removed `#loadMeter`, producing the same skipped-binding warning path. | `app/src/main/assets/dashboard/js/telemetry.js:497`; `app/src/main/assets/dashboard/js/core.js:363-367`; `verifyActiveApp` stderr. | Medium | Easy |
| 4 | Verification | Local `verifyActiveApp` omits Playwright e2e even though CI gates on dashboard e2e. | `mobile/android/build.gradle:113-125`; `mobile/android/README.md:95-105`; `.github/workflows/android.yml:147-171,276-286`. | Medium | Medium |
| 5 | Test Harness | The jsdom dashboard harness loads only a partial production script set by default, so many script-order regressions require opt-in tests. | `dashboard-tests/setup/load-dashboard.js:30-47,275-289`; `dashboard-tests/script-order.test.js:15-26`. | Medium | Medium |
| 6 | CI | Visual screenshot checks are advisory, so major dashboard visual regressions can merge if unit/e2e checks still pass. | `.github/workflows/android.yml:180-205,268-286`. | Medium | Medium |
| 7 | CI | The real WebView `file://` emulator smoke is advisory and outside the required CI success gate. | `.github/workflows/android-emulator-smoke.yml:3-15,39-46`; `.github/workflows/android.yml:268-286`. | Medium | Hard |
| 8 | CI | Gradle/Java dependency vulnerability scanning is advisory and omitted from the success gate. | `.github/workflows/android.yml:216-226,248-255,268-286`. | Medium | Medium |
| 9 | Restore UX | The restore picker accepts `*/*`, so users can pick unrelated files before validation fails. | `app/src/main/java/com/volttracker/obdpoc/BackupController.java:176-180`. | Low | Easy |
| 10 | Restore UX | Restore still uses deprecated `startActivityForResult` / `onActivityResult`, making lifecycle handling more fragile than the Activity Result API. | `BackupController.java:180`; `MainActivity.java:595-598`. | Low | Medium |
| 11 | Restore Safety | Replace-restore copies over the live database instead of using an atomic swap, so process death during copy can leave a partial live DB. | `BackupController.java:349-364`; `app/src/main/java/com/volttracker/obdpoc/data/DataBackup.java:490-498`. | High | Hard |
| 12 | Restore Safety | Restore waits only 2 seconds for logging to stop even though telemetry drain/finalization can wait up to 30 seconds. | `BackupController.java:22,406-424`; `app/src/main/java/com/volttracker/obdpoc/ObdPersistenceWorker.java:164-186`. | Medium | Medium |
| 13 | Restore Privacy | Encrypted restores are decrypted to a plaintext cache file while the mode dialog is open; app/process death can leave that plaintext copy behind. | `DataBackup.java:228-237,254-255`; `BackupController.java:206-229,323-325,382-385`. | High | Medium |
| 14 | Backup Privacy | Plaintext backup DB files remain in the app backup cache after sharing until a later backup cleanup runs. | `BackupController.java:95-139`; `DataBackup.java:143-150`. | Medium | Medium |
| 15 | Restore Tests | The "current version missing columns" test actually builds a schema version 8 DB while current schema is version 9, so it exercises version rejection before column validation. | `DataBackup.java:40-43,331-342`; `app/src/test/java/com/volttracker/obdpoc/DataBackupTest.java:59-66,173-176`. | Medium | Easy |
| 16 | Restore Validation | Restore column validation covers only a subset of required restore tables, leaving several required tables checked only for existence. | `DataBackup.java:51-85,357-374`. | Medium | Medium |
| 17 | Diagnostics Privacy | Diagnostics sharing has no disclosure/confirmation comparable to backup sharing even though it bundles JSONL logs and app logs. | `app/src/main/java/com/volttracker/obdpoc/DiagnosticsShareIntent.java:21-28`; `TroubleshooterBridge.java:223-233`; `dashboard-src/partials/settings.html:153-155`; `dashboard-src/partials/connection-tools.html:43-50`. | High | Medium |
| 18 | Merge Summary | Merge summaries can say "no new sessions" even when adapter-history or DTC rows were imported. | `DatabaseMerger.java:82-115,228-247`. | Medium | Easy |
| 19 | Merge Integrity | Adapter-history merge can null `last_session_id` when donor adapter data is newer but its session was skipped as a duplicate. | `DatabaseMerger.java:390-444,525-544`. | Medium | Medium |
| 20 | Merge Integrity | Diagnostic-code merge has the same `last_session_id` nulling path when newer donor DTC metadata comes from a skipped session. | `DatabaseMerger.java:448-493,525-544`. | Medium | Medium |
| 21 | Merge Integrity | Session duplicate detection uses only `started_at_ms`, so two distinct sessions that start in the same millisecond cause the donor session subtree to be dropped. | `DatabaseMerger.java:141-142,300-315,322-347`. | Medium | Medium |
| 22 | Shutdown Reliability | Service shutdown can close the SQLite store while async lifecycle finalization is still trying to drain telemetry and close a session. | `app/src/main/java/com/volttracker/obdpoc/ObdService.java:300-311`; `ObdPersistenceWorker.java:164-186,211-225`; `SessionRecorder.java:212-274`. | High | Hard |
| 23 | Persistence Observability | Telemetry persistence failures are swallowed with no log, counter, or status event. | `ObdPersistenceWorker.java:115-126`. | Medium | Medium |
| 24 | Disconnect Reliability | `stopObdService` can throw from `startService` while similar diagnostic service calls already guard that platform exception. | `app/src/main/java/com/volttracker/obdpoc/MainActivity.java:392-395,442-450`; `app/src/main/java/com/volttracker/obdpoc/BackupController.java:411`; `app/src/main/java/com/volttracker/obdpoc/TroubleshooterBridge.java:274`; `app/src/test/java/com/volttracker/obdpoc/TroubleshooterBridgeTest.java:79-84`. | Medium | Easy |
| 25 | Bluetooth Robustness | `openBluetoothSocket` re-fetches the Bluetooth adapter after preflight and then dereferences it without a null check. | `app/src/main/java/com/volttracker/obdpoc/ObdPollingEngine.java:501-505,513-522`. | Medium | Easy |
| 26 | Bluetooth Robustness | Remembered adapter addresses are only checked for non-empty strings; invalid MAC text reaches `getRemoteDevice` and fails as a generic runtime failure. | `app/src/main/java/com/volttracker/obdpoc/DeviceCatalog.java:157-169`; `MainActivity.java:357-360`; `ObdPollingEngine.java:399-403,521`. | Medium | Easy |
| 27 | Permissions UX | The permission result handler always reports Bluetooth success/failure even when the dashboard permission request also asked for Location or Notifications. | `app/src/main/java/com/volttracker/obdpoc/PermissionGate.java:44-74`; `MainActivity.java:266-280`. | Low | Easy |
| 28 | Bluetooth UX | If the Bluetooth enable intent fails, the fallback Bluetooth settings intent is launched without its own try/catch. | `MainActivity.java:323-329`. | Low | Easy |
| 29 | Foreground Service | `startSession` calls `startForegroundSession` before marking the session active, and that foreground start path does not catch `SecurityException`. | `ObdService.java:395-417,551-562,595-608`. | Medium | Medium |
| 30 | App-State Telemetry | Latest stored telemetry omits HV pack voltage/current columns, so a dashboard hydrated from storage can miss pack values that live telemetry displays. | `app/src/main/java/com/volttracker/obdpoc/data/ObdStoreReports.java:490-529`; `app/src/main/assets/dashboard/js/telemetry.js:463-475`. | Medium | Easy |

## Suggested Polish Order

1. Fix the high-risk privacy/reliability items first: remote tiles default,
   restore atomicity/plaintext cache cleanup, diagnostics disclosure, and
   shutdown/store-close ordering.
2. Remove dashboard binding drift and wire e2e into local verification so future
   polish work has a cleaner signal.
3. Patch merge integrity and Bluetooth hardening; these are small, testable
   fixes with good user-facing payoff.
4. Tighten advisory CI gates once the flakiest external pieces are understood.
