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

The **pre-commit** hook runs Spotless and ESLint on staged dashboard JS. Install
dashboard Node dependencies once with `npm --prefix dashboard-tests ci`;
otherwise the local ESLint hook prints a warning and CI becomes the first strict
check.

The **pre-push** hook runs the Java unit tests and the dashboard Vitest suite so
a broken push is caught in ~30-60s instead of ~8min later in CI.

To bypass in an emergency: `git commit --no-verify` / `git push --no-verify`
(use sparingly; CI will catch it).

## Dashboard JS type-checking

`npm --prefix dashboard-tests run typecheck` runs `tsc --checkJs` over the dashboard
JS, and the check is gated in CI. The opt-in rollout is **complete**: `checkJs` is now
`true`, so **every** file under `assets/dashboard/js/` is type-checked and any new `.js`
file is covered automatically — no `// @ts-check` line required (the existing ones are
harmless). `noImplicitAny` is on, so annotate with JSDoc rather than leaving an implicit
`any` — usually a cast like `/** @type {HTMLInputElement} */ (el("id"))` or `/** @type
{any} */ (window.VoltDashboard ...)`. Shared globals are declared in
`dashboard-tests/dashboard-globals.d.ts`. `strict`/`strictNullChecks` remain off; turning
`strictNullChecks` on is the next (separate) hardening step.

## Android: Kotlin for new code

The Android module is set up for **Kotlin-first new code**. Java and Kotlin interop freely
in the same module, so:

- **Write new classes in Kotlin** (`.kt`) — put them in `app/src/main/kotlin/…` (or alongside
  Java in `app/src/main/java/…`; both source roots compile Kotlin). Tests go in
  `app/src/test/java/…` or `app/src/test/kotlin/…`. No Kotlin plugin is applied — AGP 9.0+
  compiles Kotlin via its built-in support.
- **Leave existing Java as-is.** This is not a migration — don't rewrite working Java just to
  change the language. Convert a Java file to Kotlin only when you're already substantially
  reworking it and the change is independently justified.
- Bytecode target is Java 17, set once via `compileOptions` in `app/build.gradle`; AGP's
  built-in Kotlin inherits the same `jvmTarget` from it automatically.
- **Formatting:** Spotless runs ktlint on `.kt` (`./gradlew :app:spotlessApply` to fix,
  `:app:spotlessCheck` is the CI gate) — the same lane that formats Java and the dashboard JS.
- **Coverage:** JaCoCo measures Kotlin classes too (`app/jacoco.gradle` scans both the javac and
  kotlin-classes outputs), so new Kotlin is held to the same ratcheting floors as Java — write
  tests for it.

The wave-by-wave conversion plan (which Java files to convert in what order, the `@JvmField`/enum
interop rules) and the dashboard TypeScript roadmap (strictNullChecks → full TS + bundler) live in
[`docs/language-migration.md`](docs/language-migration.md) — a living tracker. Update it in the same
PR as the work.

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
