# Contributing to VoltTracker

VoltTracker is the Android OBD-II companion app for the 2017 Chevy Volt. Source
lives under `mobile/android/`. The former Flask/Postgres web receiver has been
removed; this repo now ships only the Android app.

## Quick start

```bash
git clone https://github.com/jtn0123/VoltTracker.git
cd VoltTracker/mobile/android
./gradlew :app:assembleDebug         # build the APK
./gradlew :app:testDebugUnitTest     # run JVM + Robolectric unit tests
./gradlew :app:installDebug          # install to a connected device or AVD
```

Requires JDK 17 and Android SDK 36 (the wrapper handles Gradle).

## The five gates we run in CI

1. `./gradlew :app:testDebugUnitTest` — JUnit + Robolectric unit tests.
2. `./gradlew :app:spotlessCheck` — google-java-format (AOSP) for Java, Prettier for dashboard.
3. `./gradlew :app:lintDebug` — Android Lint, baseline-tracked in `lint-baseline.xml`.
4. `./gradlew :app:jacocoTestReport` + `jacocoTestCoverageVerification` — coverage floors.
5. `cd dashboard-tests && npm run lint && npm run test:coverage` — ESLint plus Vitest/jsdom dashboard smoke tests.

For local pre-PR validation, run:

```bash
cd mobile/android
npm --prefix dashboard-tests ci
./gradlew verifyActiveApp
```

If a gate complains: `./gradlew :app:spotlessApply` reformats; lint findings need
a real fix (don't add to baseline without justification); coverage floors ratchet
upward as the test suite grows.

## Dashboard partial workflow

The shipped `assets/dashboard/index.html` AND the shipped JS are generated. Edit:

- Markup: `app/src/main/dashboard-src/partials/*.html` (+ `index.template.html`)
- Behavior (TypeScript): `app/src/main/dashboard-src/js/*.ts`
- Styles: `app/src/main/assets/dashboard/css/*.css` (CSS loads directly, no build)

`assets/dashboard/js/` is the **built, minified, gitignored** bundle compiled from
`dashboard-src/js/*.ts` by esbuild (`buildDashboardJs`), so it can't be hand-edited.
After editing a TypeScript source, rebuild the bundle with
`npm --prefix dashboard-tests run build` (or `./gradlew :app:assembleDebug`, which runs it).
After editing a partial/template, run `./gradlew generateDashboardHtml`. Only CSS needs no build.
CI fails if the committed generated file drifts from the partial/template output.

## Layering rule (see ADR 0002)

UI → Service → Engine → Data. Calls flow downward only.

- `data/*` may import only itself + Android SDK.
- Engine code may use `data/*` but never `MainActivity` / WebView.
- Service layer orchestrates engine; never touches WebView.
- `MainActivity` / `VoltBridge` may call into the service via Intents and into `data/*` for read-only DTOs.

If a change would require a `data/*` class to import from above, the abstraction is in the wrong file.

## Security scan scope

GitHub security dashboards should stay focused on the active Android app. New
dependency or code-scanning findings in `mobile/android/` should be treated as
active release blockers.

## Pre-commit hooks (optional)

Install [lefthook](https://github.com/evilmartians/lefthook):

```bash
brew install lefthook
lefthook install
```

Hooks run Spotless on staged files. They're optional; CI catches the same issues
on PR, but local hooks cut the feedback loop.

## Commit messages

We don't enforce conventional commits, but a short imperative title (≤72 chars)
plus a body explaining "why, not what" is encouraged. Include the grade-report
item ID if you're executing one (e.g. `D1`, `B2`).

## Project structure

See `mobile/android/README.md` for the codebase map and
`mobile/android/docs/mobile-architecture-roadmap.md` for product/architecture direction.
