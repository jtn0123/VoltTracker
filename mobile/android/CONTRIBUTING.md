# Contributing to Volt Tracker Android

Short, opinionated, and meant to bootstrap you into the inner loop quickly.

## Day-in-the-life dev loop

```sh
# 1) Full active-app verification (macOS / Linux)
npm --prefix dashboard-tests ci
./gradlew verifyActiveApp
# Repeated local loop:
./gradlew verifyActiveApp --configuration-cache

# 2) Local lint with HTML report
./gradlew :app:lintDebug
# macOS:
open app/build/reports/lint-results-debug.html
# Linux:
xdg-open app/build/reports/lint-results-debug.html

# 3) Install on a paired phone and launch the main activity
./gradlew :app:installDebug && \
  adb shell am start -n com.volttracker.obdpoc/.MainActivity

# 4) Dashboard tests (vitest + jsdom — fast, no Android emulator)
npm --prefix dashboard-tests test

# 5) Dashboard lint (ESLint flat config)
npm --prefix dashboard-tests run lint
```

Windows users: substitute `.\gradlew.bat` for `./gradlew` and use PowerShell.

## Editing the dashboard

The dashboard `index.html` under `app/src/main/assets/dashboard/` is **generated**.
Edit the sources, not the generated file:

- Markup: `app/src/main/dashboard-src/partials/*.html`
- Styles: `app/src/main/assets/dashboard/css/*.css`
- Behavior: `app/src/main/assets/dashboard/js/*.js`

After editing a partial or the template, regenerate the assembled HTML:

```sh
./gradlew generateDashboardHtml
```

CSS and JS edits load directly — no regeneration needed.

CI also runs `verifyGeneratedDashboardClean`, which compares
`app/src/main/assets/dashboard/index.html` with the partial/template output and
fails if the generated file is stale. If that check fails, rerun
`./gradlew generateDashboardHtml` and include the generated file in the same
change as the partial/template edit.

## Pre-commit hooks

```sh
brew install lefthook && lefthook install
```

Hooks run Spotless and ESLint on staged dashboard JS. Install dashboard Node
dependencies once with `npm --prefix dashboard-tests ci`; otherwise the local
ESLint hook prints a warning and CI becomes the first strict check. To bypass on
an emergency push: `git commit --no-verify` (use sparingly; CI will catch it).

## Where things live

| Layer    | Files                                                                       | Entry point                |
|----------|-----------------------------------------------------------------------------|----------------------------|
| UI       | `MainActivity.java`, `VoltBridge.java`, `assets/dashboard/*`                | `MainActivity.onCreate`    |
| Service  | `ObdService.java`, `ObdNotifications.java`, `PermissionGate.java`           | `ObdService.onStartCommand`|
| Engine   | `ObdPollingEngine.java`, `SessionRecorder.java`, `ObdProtocol.java`, …      | `ObdPollingEngine.runBluetoothLoop` |
| Data     | `data/*` (`ObdLocalStore`, `VoltTrackerDb`, `ObdStore*`, record DTOs)       | `ObdLocalStore`            |

Calls flow downward only (UI → Service → Engine → Data). See
[`docs/mobile-architecture-roadmap.md`](docs/mobile-architecture-roadmap.md#layering-rule)
for the full rule, and the ADRs in [`docs/adr/`](docs/adr/) for the load-bearing
design decisions.

Historical audit reports and dependency snapshots are indexed in
[`docs/reports-index.md`](docs/reports-index.md). Privacy/data-handling behavior
is summarized in [`docs/privacy-data-handling.md`](docs/privacy-data-handling.md).

## Coverage floors

JaCoCo enforces ratcheting baselines (see `app/jacoco.gradle`):

- Project: 71% LINE
- `com.volttracker.obdpoc.data` package: 89% LINE

When you genuinely improve coverage, bump the floor in the same PR. Never lower
without a note in the PR description.
