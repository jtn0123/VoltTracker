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

Build and install:

```powershell
cd mobile/android
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:installDebug
```

Unit tests:

```powershell
cd mobile/android
.\gradlew.bat :app:testDebugUnitTest
```

## License

MIT License — see LICENSE file for details.
