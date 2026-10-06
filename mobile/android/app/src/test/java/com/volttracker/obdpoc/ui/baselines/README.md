# Reviewed native UI baselines

`GradeVisualRegressionTest` checks seven states in OLED, Saddle and Latte: normal
Drive, connected but never-reported values, a continuing recording failure, a
connection failure with recovery controls, Health at 1.5x text, an unknown DTC,
and a pending refresh at 1.5x text. Fault states are injected test fixtures; these
images prove the real Compose renderer, not a fault on a physical vehicle.

The fixture pins Android SDK 34, 412 × 915 dp at 420 dpi, English/US, UTC, 12-hour
time, app-bundled fonts, reduced motion and a fixed animation frame. The CI lane
uses Temurin 21 and `macos-15` (Apple Silicon). Baselines were reviewed locally
with the same JVM and architecture; the first remote run still verifies host parity.

From `mobile/android`:

```sh
./gradlew verifyNativeVisual --no-configuration-cache
./gradlew :app:testDebugUnitTest -PnativeVisualRecord --no-configuration-cache
```

The second command is only for intentional changes: review each changed PNG
before committing it. Verify refuses recording flags, does not rewrite these
images, and fails for missing or changed baselines. Keep CI failures visible;
do not auto-update images to make a failing check green. The required `ci-success`
aggregate includes `native-visual`; failures upload the native diffs and test report.
