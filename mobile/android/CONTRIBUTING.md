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

Under `app/src/main/assets/dashboard/`, both `index.html` **and** `js/` are
**generated** — edit the sources, not the generated files:

- Markup: `app/src/main/dashboard-src/partials/*.html`
- Behavior (TypeScript): `app/src/main/dashboard-src/js/*.ts`
- Styles: `app/src/main/assets/dashboard/css/*.css` (CSS loads directly — no build)

**The dashboard TypeScript is bundled.** `dashboard-tests/build.mjs` (esbuild) compiles the
source in `dashboard-src/js/` into `app/src/main/assets/dashboard/js/` — a single
classic-IIFE `app.js` (the eager scripts, in dependency order) plus the lazy
`dtc-lookup`/`dtc-causes`/`demo-data` chunks. That output dir is **gitignored** (a
build artifact). It stays a classic IIFE, never an ES module: the WebView serves the
dashboard from `file://`, where `<script type=module>` silently never runs.

After editing a TypeScript source file, rebuild the bundle:

```sh
npm --prefix dashboard-tests run build
# or just build the app — Gradle's buildDashboardJs runs in preBuild:
./gradlew :app:assembleDebug
```

After editing a partial or the template, regenerate the assembled HTML:

```sh
./gradlew generateDashboardHtml
```

Tests read the **source** (`dashboard-src/js/`): Vitest/ESLint/`tsc` all point there,
so you don't need to rebuild to run them. Playwright e2e serves the real `index.html`
(→ the built `app.js`), so it builds the bundle first. CI runs `verifyGeneratedDashboardClean`
(index.html freshness) and `verifyDashboardBundleSize` (against the built bundle).

## Pre-commit hooks

```sh
brew install lefthook && lefthook install
```

The **pre-commit** hook runs Spotless and ESLint on staged dashboard TypeScript. Install
dashboard Node dependencies once with `npm --prefix dashboard-tests ci`;
otherwise the local ESLint hook prints a warning and CI becomes the first strict
check.

The **pre-push** hook runs the Android unit tests and the dashboard Vitest suite so
a broken push is caught in ~30-60s instead of ~8min later in CI.

To bypass in an emergency: `git commit --no-verify` / `git push --no-verify`
(use sparingly; CI will catch it).

## Dashboard TypeScript type-checking

`npm --prefix dashboard-tests run typecheck` runs `tsc` over the dashboard TypeScript
source, and the check is gated in CI. **Full `strict` is on** (noImplicitAny +
strictNullChecks + strictFunctionTypes + useUnknownInCatchVariables + …), so: annotate
every value whose type cannot be inferred, handle every possibly-null/undefined value by
guarding or narrowing it (`x?.y`, `value ?? fallback`), and narrow `catch` variables before
use (`err instanceof Error ? err.message : String(err)`). Shared globals are declared in
`dashboard-tests/dashboard-globals.d.ts`; the `VoltDashboard` members an eager script
always attaches are typed **required**, so a new cross-file helper should be added there
with a real signature (not left optional).

## Android: Kotlin for new code

The Android module is **Kotlin-first**. Production Android source is now Kotlin-complete,
and existing Java tests remain as legacy coverage unless they are being materially reworked.

- **Write new classes in Kotlin** (`.kt`) — put them in `app/src/main/kotlin/…`. Tests go in
  `app/src/test/java/…` or `app/src/test/kotlin/…`. No Kotlin plugin is applied — AGP 9.0+
  compiles Kotlin via its built-in support.
- Bytecode target is Java 17, set once via `compileOptions` in `app/build.gradle`; AGP's
  built-in Kotlin inherits the same `jvmTarget` from it automatically.
- **Formatting:** Spotless runs ktlint on `.kt` (`./gradlew :app:spotlessApply` to fix,
  `:app:spotlessCheck` is the CI gate) — the same lane that formats Java tests and the dashboard HTML.
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
| UI       | `MainActivity.kt`, `VoltBridge.kt`, `assets/dashboard/*`                    | `MainActivity.onCreate`    |
| Service  | `ObdService.kt`, `ObdNotifications.kt`, `PermissionGate.kt`                 | `ObdService.onStartCommand`|
| Engine   | `ObdPollingEngine.kt`, `SessionRecorder.kt`, `ObdProtocol.kt`, …            | `ObdPollingEngine.runBluetoothLoop` |
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
