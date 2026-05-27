# VoltTracker Development Guidelines

This repository is the **Volt Tracker Android app** (`mobile/android/`). The
former Flask/PostgreSQL web receiver has been removed; do not recreate or work
on that web stack unless explicitly asked.

## Testing

- **Test Location**: `mobile/android/app/src/test/java/com/volttracker/obdpoc/`.
  Do not create test files outside this directory.
- **Test Command**: `cd mobile/android && ./gradlew :app:testDebugUnitTest`
  (on Windows use `.\gradlew.bat`).
- **Style**: Pure JVM + Robolectric for the SQLite layer — no instrumented tests.

## Building

- **Build**: `cd mobile/android && ./gradlew :app:assembleDebug`
- **Install**: `./gradlew :app:installDebug` (or `adb install -r`)
- **Dashboard**: `assets/dashboard/index.html` is generated — edit the partials in
  `app/src/main/dashboard-src/partials/*.html`, then run
  `./gradlew generateDashboardHtml`. Never hand-edit the generated file.

See `CLAUDE.md` for full details.
