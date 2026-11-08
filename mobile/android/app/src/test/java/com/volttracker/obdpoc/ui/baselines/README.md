# Reviewed native UI baselines

Two classes share one fixture (`NativeVisualRule`) and are compared here, each state in
OLED, Saddle and Latte:

- `GradeVisualRegressionTest`, seven states: normal Drive, connected but never-reported
  values, a continuing recording failure, a connection failure with recovery controls,
  Health at 1.5x text, an unknown DTC, and a pending refresh at 1.5x text.
- `LauncherTabsVisualRegressionTest`, eleven states for the other launcher tabs: Trips
  (demo drives, nothing logged, drives that couldn't be read), Insights (the demo month,
  nothing logged, a failed read), Car (parked and ready, a low tyre with codes and dash
  warnings, an adapter that hears no body bus) and Settings (the index, and the adapter
  page before Nearby devices is allowed).

Fault states are injected test fixtures; these images prove the real Compose renderer,
not a fault on a physical vehicle. All data is the synthetic demo data.

The fixture pins Android SDK 34, 412 × 915 dp at 420 dpi, English/US, UTC, 12-hour
time, app-bundled fonts, reduced motion and a fixed animation frame. The launcher tabs
differ in two pinned ways: they run at a fixed UTC−7 offset, because the demo's drives
and "now" are written in Pacific daylight time, and Car captures its whole page at
1460 dp tall. Settings shows a pinned version label, not `BuildConfig.VERSION_NAME`.
The CI lane uses Temurin 21 and `macos-15` (Apple Silicon). Baselines were reviewed
locally with the same JVM and architecture; the first remote run still verifies host parity.

From `mobile/android`:

```sh
./gradlew verifyNativeVisual --no-configuration-cache
./gradlew :app:testDebugUnitTest -PnativeVisualRecord --no-configuration-cache
```

The second command is only for intentional changes: review each changed PNG
before committing it. Add `--tests` with one class to record only its images. Verify
refuses recording flags, does not rewrite these images, and fails for missing or
changed baselines. Keep CI failures visible; do not auto-update images to make a
failing check green. The required `ci-success` aggregate includes `native-visual`;
failures upload the native diffs and test report.
