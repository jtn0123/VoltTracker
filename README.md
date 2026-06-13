# Volt Tracker

An Android app for the 2017 Chevy Volt (Gen 2). It connects directly to a
Bluetooth OBD-II adapter, logs telemetry and GPS routes to an on-device SQLite
database, and shows a mobile dashboard in a WebView. It is designed to replace
Torque as the day-to-day OBD bridge — fully standalone, with all data kept on
the phone.

## The app

Everything lives in [`mobile/android/`](mobile/android/). See
[`mobile/android/README.md`](mobile/android/README.md) for full build, install,
and PID details, including an
[architecture overview](mobile/android/README.md#architecture) with a component
diagram and dashboard screenshots.

- Connects to a paired ELM327-style OBD-II adapter over Bluetooth Classic.
- Logs live OBD telemetry + GPS to a local SQLite database; works offline.
- Dashboard screens: Drive, Trips, Map (Leaflet), Charge, Insights, Diagnostics.
- All data stays on-device. **Back up data** exports the full database to a file
  via the Android share sheet, so you can keep a copy anywhere (cloud, PC).

## Repository structure

| Part        | Location                                       | What                                            |
|-------------|------------------------------------------------|-------------------------------------------------|
| Android app | `mobile/android/`                              | Kotlin source, Gradle build, WebView, SQLite    |
| Dashboard   | `mobile/android/app/src/main/dashboard-src/`   | TypeScript + HTML partials (built by Gradle)    |
| Docs        | `mobile/android/docs/`                         | Architecture roadmap, ADRs, field guides        |
| CI          | `.github/workflows/`                           | Tests, lint, coverage, CodeQL, release pipeline |

Build and install (on Windows, replace `./gradlew` with `.\gradlew.bat`):

```sh
cd mobile/android
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Unit tests:

```sh
cd mobile/android
./gradlew :app:testDebugUnitTest
```

## License

MIT License — see LICENSE file for details.
