# VoltTracker Development Guidelines

This repository is the **Volt Tracker Android app** (`mobile/android/`). The
former Flask/PostgreSQL web receiver has been removed; do not recreate or work
on that web stack unless explicitly asked.

## Android app

- Source: `mobile/android/`
- Build: `cd mobile/android && ./gradlew :app:assembleDebug`
- Install: `./gradlew :app:installDebug` (or `adb install -r`)
- Unit tests: `./gradlew :app:testDebugUnitTest`
- On Windows, replace `./gradlew` with `.\gradlew.bat`.
- Test location: `mobile/android/app/src/test/java/com/volttracker/obdpoc/`
  (pure JVM + Robolectric for the SQLite layer — no instrumented tests)

### Language: Kotlin for Android code

- **Write new Android code in Kotlin** (`.kt`), not Java. Production Android source is
  Kotlin-complete; existing Java unit/Robolectric tests can stay Java unless they are
  being materially reworked.
- New Kotlin goes in `app/src/main/kotlin/com/volttracker/obdpoc/…`.
- No Kotlin plugin is applied — AGP 9.0+ has built-in Kotlin enabled by default.
  Bytecode target (Java 17) is set once via `compileOptions` in `app/build.gradle`;
  Kotlin's `jvmTarget` inherits it.
- Spotless (ktlint) formats `.kt` and JaCoCo measures Kotlin classes, so new Kotlin
  is held to the same `spotlessCheck` and coverage gates as Java — write tests for it.
  See `mobile/android/CONTRIBUTING.md` → "Android: Kotlin for new code".

## Dashboard (WebView UI)

The dashboard `index.html` AND the shipped JS are generated — edit the sources, not the
generated files:

- Markup: `mobile/android/app/src/main/dashboard-src/partials/*.html`
- Behavior (TypeScript): `mobile/android/app/src/main/dashboard-src/js/*.ts`
- Styles: `mobile/android/app/src/main/assets/dashboard/css/*.css` (CSS loads directly, no build)
- `assets/dashboard/index.html` is assembled from the partials + template by the Gradle
  `generateDashboardHtml` task. `assets/dashboard/js/` is the **built, minified, gitignored
  bundle** (`app.js` + lazy `dtc-*`/`demo-data` chunks), compiled from `dashboard-src/js/` by
  `dashboard-tests/build.mjs` (esbuild) via the Gradle `buildDashboardJs` task. Both are wired
  into `preBuild`. Never hand-edit either generated output.
- After editing a TypeScript source file, rebuild the bundle: `npm --prefix dashboard-tests run build`
  (or just `./gradlew :app:assembleDebug`, which runs it). After editing a partial/template,
  run `./gradlew generateDashboardHtml`.
