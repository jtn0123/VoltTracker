# VoltTracker Development Guidelines

This repository is the **Volt Tracker Android app** (`mobile/android/`). The
former Flask/PostgreSQL web receiver has been deprecated and moved to `archive/`
— do not work on it unless explicitly asked.

## Android app

- Source: `mobile/android/`
- Build: `cd mobile/android && ./gradlew.bat :app:assembleDebug`
- Install: `./gradlew.bat :app:installDebug` (or `adb install -r`)
- Unit tests: `./gradlew.bat :app:testDebugUnitTest`
- Test location: `mobile/android/app/src/test/java/com/volttracker/obdpoc/`
  (pure JVM + Robolectric for the SQLite layer — no instrumented tests)

## Dashboard (WebView UI)

The dashboard `index.html` is generated — edit the sources, not the generated file:

- Markup: `mobile/android/app/src/main/dashboard-src/partials/*.html`
- Styles: `mobile/android/app/src/main/assets/dashboard/css/*.css`
- Behavior: `mobile/android/app/src/main/assets/dashboard/js/*.js`
- `assets/dashboard/index.html` is assembled from the partials + template by the
  Gradle `generateDashboardHtml` task (wired into `preBuild`). Never hand-edit it.
- After editing a partial, run `./gradlew.bat generateDashboardHtml` to regenerate
  it (CSS/JS edits need no regeneration — they load directly).

## archive/

Deprecated Flask/PostgreSQL web app, its tests, and its CI workflows. Kept for
reference only; not maintained.
