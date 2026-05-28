# VoltTracker Android Debugging Audit - 2026-05-26

Scope: `mobile/android/` only. The deprecated receiver/archive path was not reviewed.

Validation run:

- `./gradlew :app:testDebugUnitTest` - passed, but printed JaCoCo instrumentation errors against class-file major version 68.
- `./gradlew :app:assembleDebug` - passed.
- `./gradlew generateDashboardHtml` - passed.
- `./gradlew :app:lintDebug` - passed with 1 live warning and 5 baseline-filtered findings.
- `./gradlew spotlessCheck` - passed.
- `./gradlew :app:jacocoTestReport` and `./gradlew :app:jacocoTestCoverageVerification` - passed.
- `cd dashboard-tests && npm ci && npm test -- --run` - 32 tests passed, with repeated stderr noise.
- `cd dashboard-tests && npm run lint` - passed.

## 20 Validated Areas

| # | Area | Layman explanation | Evidence | Impact | Ease |
|---|------|--------------------|----------|--------|------|
| 1 | JaCoCo is noisy under the current Java runtime | The tests say "green", but coverage tooling throws instrumentation errors before finishing, so a future real coverage failure could be harder to notice. | `:app:testDebugUnitTest` printed `Unsupported class file major version 68`; `app/jacoco.gradle` pins JaCoCo `0.8.12`; `app/build.gradle` enables `jacoco.includeNoLocationClasses = true`. | High | Medium |
| 2 | API 23 risk from `java.util.function.BooleanSupplier` | The app supports Android 6, but one connection-path type is flagged as requiring a newer Android API unless desugared. On old phones this can become a runtime crash. | Lint baseline has `NewApi` for `ElmConnection.java:204`; source imports `java.util.function.BooleanSupplier` and calls `getAsBoolean()`. | High | Easy |
| 3 | Lint baseline hides a real error and four warnings | The lint check passes, but only because known problems are grandfathered. New contributors can miss that there is still an API error in the baseline. | `:app:lintDebug` says `1 error and 4 warnings filtered by baseline`; see `app/lint-baseline.xml`. | Medium | Easy |
| 4 | Package visibility warning for competing-app detection | The app tries to list installed apps, but Android 11+ restricts what it can see. The competing-app helper may miss apps users need to close. | Live lint warning: `CompetingAppDetector.java:147` uses `PackageManager.getInstalledApplications(0)`. | Medium | Medium |
| 5 | Manifest/package list mismatch | One known competing app is in code but not in the manifest visibility list, so Android may hide exactly the package the detector is looking for. | `CompetingAppDetector.KNOWN_OBD_PACKAGES` includes `com.pnn.obdcardoctor_full`; `AndroidManifest.xml` queries include `com.pnn.obdcardoctor` but not the `_full` package. | Medium | Easy |
| 6 | Notification permission blocks connecting | On Android 13+, denying notifications can block `ensureGranted()` and therefore connection, even though Bluetooth logging can still work without posting optional notifications. | `PermissionGate.ensureGranted()` adds `POST_NOTIFICATIONS`; `MainActivity.startObdService()` refuses to continue when `ensureGranted()` returns false. | High | Medium |
| 7 | Location permission is required too broadly | A user who only wants OBD data must grant fine and coarse location before connecting. That creates unnecessary friction and may block basic adapter use. | `PermissionGate.ensureGranted()` always requests both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`; `ObdService.startLocationTracking()` already knows how to skip GPS when location is missing. | High | Medium |
| 8 | Restore can proceed even if service stop fails | If stopping the logging service fails during restore, the database file can still be swapped underneath a writer. That risks a corrupt or partial restore. | `BackupController.stopLoggingForRestore()` swallows all `RuntimeException`; `applyRestore()` proceeds to close and replace the DB anyway. | High | Medium |
| 9 | Restore is allowed when background service state is stale | The Activity decides restore safety from its last status JSON, but the foreground service may still be running or finalizing after the Activity missed broadcasts. | `BackupController.launchRestorePicker()` checks `activity.isLoggingActive()`; `isLoggingActive()` depends on `lastStatus`, not a bound/current service state. | Medium | Hard |
| 10 | Backup share label understates raw contents | The disclosure says redacted VIN, but the DB backup also contains raw telemetry, GPS coordinates, adapter addresses, diagnostic payload JSON, and log fields. Users may not realize the whole database is exported. | `BackupController.shareDisclosureMessage()` mentions a short list; `DataBackup.buildBackupFile()` copies the entire SQLite DB file. | Medium | Easy |
| 11 | App backup rules are incomplete for older Android | The app disables Android 12+ backup via data extraction rules, but lint says older devices should also get `fullBackupContent`. | Baseline `DataExtractionRules` finding on `AndroidManifest.xml:41`; `data_extraction_rules.xml` excludes root for cloud and transfer only on newer APIs. | Medium | Easy |
| 12 | `usesPermissionFlags` is baseline-suppressed for old SDKs | The manifest uses an Android 12+ permission flag while supporting minSdk 23. It is probably harmless, but the baseline hides the compatibility warning. | Baseline `UnusedAttribute` finding on `AndroidManifest.xml:6`. | Low | Easy |
| 13 | Release builds are not minified | Release APKs keep all code and symbols, making the app bigger and easier to reverse engineer than necessary. | `app/build.gradle` sets `minifyEnabled = false` in `release`. | Medium | Medium |
| 14 | Launcher monochrome icon missing | On themed Android launchers, the icon may look inconsistent or fall back poorly. | Baseline `MonochromeLauncherIcon` findings for `ic_launcher.xml` and `ic_launcher_round.xml`. | Low | Easy |
| 15 | Bluetooth deprecation warning is unresolved | The build has deprecated Bluetooth API usage. It works today, but future SDK upgrades may make the migration harder. | Gradle problems report points at `BluetoothStateReporter.java`; source uses deprecated `getParcelableExtra(String)` style. | Low | Easy |
| 16 | Dashboard tests need an install step that is not wired into Gradle | The Android build can pass while dashboard JS tests are not runnable until someone manually runs `npm ci` in a subfolder. | Initial `npm test -- --run` failed with `vitest: command not found`; after `npm ci`, 32 tests passed. | Medium | Easy |
| 17 | Dashboard tests print repeated missing-element warnings | The JS tests pass, but every test logs `listener bind skipped: missing #mapDriveChips`, which can bury a real warning later. | `npm test -- --run` passed 32 tests with repeated stderr lines. | Low | Easy |
| 18 | Dashboard code still has several raw `innerHTML` renderers | Some UI rendering builds HTML strings manually. Most inputs look numeric/local, but this is a recurring XSS/markup-breakage risk as more DB/device text reaches the dashboard. | `rg` found raw HTML writes in `map.js`, `drive.js`, `panels.js`, and `scrubber.js`; `telemetry.js` already exposes `escapeHtml()`, but not every renderer uses it. | Medium | Medium |
| 19 | Platform location tracker coverage is very low | The route-recording layer is important, but coverage mostly exercises pure filtering, not the Android wrapper that requests providers and handles callbacks. | JaCoCo location package: `LocationManagerTracker` shows very low line/branch coverage, while `LocationFilter` is well covered. | Medium | Medium |
| 20 | Large classes concentrate too much behavior | Several files exceed the repo's stated size/readability direction, making bugs harder to isolate and reviews harder to trust. | `ObdPollingEngine.java` is 1110 LOC; `ObdStoreTrips.java` 783; `SessionRecorder.java` 735; `VoltTrackerDb.java` 729; `ObdProtocol.java` 679. | Medium | Hard |

## Suggested Order

1. Fix the API/lint tooling issues first: #2, #3, #1.
2. Reduce user-facing blockers: #6, #7, #8, #9.
3. Clean Android policy/security polish: #4, #5, #10, #11, #13.
4. Make future debugging quieter and more trustworthy: #16, #17, #18, #19, #20.
