# Validation Matrix

Use this matrix to describe what a change actually proved. Do not treat a
desktop dashboard pass as proof that the Android WebView or real OBD path works.

| Level | Command / Action | Proves | Does not prove |
|---|---|---|---|
| Source guard | `./gradlew verifyNoMigrationSourceStragglers` | Production Android source is Kotlin-only and dashboard behavior source is TypeScript-only. | Runtime behavior. |
| Android JVM | `./gradlew :app:testDebugUnitTest` | Kotlin/JVM logic, Robolectric SQLite, migration, backup, parser, service, and bridge unit behavior. | Real Bluetooth, foreground service behavior on device, WebView rendering. |
| Android local gate | `./gradlew verifyActiveApp` | Unit tests, lint, Spotless, assemble, JaCoCo, dashboard lint/typecheck/tests/e2e, bundle budgets, generated dashboard drift, migration guards. | Physical phone quirks, real adapter behavior, real car data. |
| Desktop dashboard | `npm --prefix dashboard-tests run typecheck`, `npm --prefix dashboard-tests test`, `npm --prefix dashboard-e2e test` | TypeScript contracts, DOM behavior in jsdom, and rendered dashboard behavior in desktop Chromium. | Android WebView parser/API compatibility. |
| Emulator WebView | `scripts/run-emulator-smoke-local.sh` with an attached emulator/device, or CI `emulator-smoke` | The debug APK installs, dashboard JS handshakes with native, demo telemetry starts, core tabs render, screenshots are captured, and logcat has no fatal/uncaught dashboard runtime errors. | Bluetooth adapter pairing, real ELM327 timing, real GPS/cabin phone conditions. |
| S24-profile emulator | `scripts/run-emulator-smoke-local.sh` on `galaxy-s24-api36` from `s24-emulator-profile.md` | Android 16/API 36 WebView behavior on a 1080x2340, 416-density phone surface close to a Galaxy S24 screen. | Samsung One UI behavior, real Samsung firmware quirks, Bluetooth adapter pairing, real car data. |
| Physical phone | `./gradlew :app:installDebug`, then manual dashboard/demo pass | Device-specific WebView, permission, notification, display cutout, and install/update behavior. | Real OBD/car behavior unless connected to the vehicle. |
| Real car / OBD | Field-test checklist plus pulled JSONL/SQLite artifacts | Adapter/car protocol behavior, live telemetry, GPS route capture, foreground logging, and recovery behavior. | General regressions outside the exercised route/session. |

## Reporting Rule

When summarizing a change, state the highest validation level reached and list
the exact command or field-test artifact. If a real-car check did not run, say
so plainly.
