# Dependency Report

Date: 2026-05-26

Scope: active Android app only (`mobile/android/`). Deprecated `archive/` package and Python dependency files were intentionally excluded per repo guidance.

## Upgrade Update

Applied and validated after the initial report:

- `com.github.ben-manes.versions`: `0.51.0` -> `0.54.0`
- JaCoCo: `0.8.13` -> `0.8.14`
- google-java-format: `1.22.0` -> `1.28.0`
- Prettier: `3.2.5` -> `3.8.3`
- ESLint: `^9.0.0` / resolved `9.39.4` -> `^10.4.0` / resolved `10.4.0`

Validation after applying:

- `cd mobile/android && ./gradlew dependencyUpdates`: passed; the previously broken update report now runs.
- `cd mobile/android && ./gradlew :app:assembleDebug :app:lintDebug :app:spotlessCheck :app:jacocoTestCoverageVerification`: passed.
- `cd mobile/android/dashboard-tests && npm run lint && npm test`: passed.

ESLint 10 investigation:

- Official ESLint v10 migration notes call out Node `^20.19.0 || ^22.13.0 || >=24`, flat-config-only behavior, changed config lookup, and `eslint-env` comments becoming errors.
- Local Node is `v24.2.0`, which satisfies the requirement.
- The repo already uses flat config at `mobile/android/eslint.config.js`; no `.eslintrc` / `.eslintignore` migration is needed.
- The lint script runs from `mobile/android`, so ESLint finds the config from the intended project root.
- Search found no production `eslint-env` comments to break under v10.
- Benefit: current major lint engine, supported runtime baseline, smaller npm lockfile, and no code/config migration needed for this repo.
- Residual risk: ESLint 10 is a major upgrade, so any future ESLint plugins/config extensions should be checked against v10 compatibility before adding them.

## Summary

- Runtime dependency surface is small: the Android app directly ships only `androidx.core:core`.
- Android app/test dependencies are mostly current against stable releases.
- The Gradle Versions plugin was upgraded and `dependencyUpdates` now runs successfully.
- Dashboard-test npm deps are installed and top-level packages resolve cleanly.

## Android / Gradle Tooling

| Area | Current | Latest stable checked | Status | Notes |
|---|---:|---:|---|---|
| Gradle wrapper | 9.5.1 | 9.5.1 | Current | `mobile/android/gradle/wrapper/gradle-wrapper.properties`; Gradle current service also reports 9.5.1. |
| Android Gradle Plugin | 9.2.1 | 9.2.1 | Current stable | Latest pre-release in Google Maven is 9.3.0-alpha07. |
| Spotless Gradle plugin | 8.5.1 | 8.5.1 | Current | Declared in root build and version catalog. |
| Gradle Versions plugin | 0.54.0 | 0.54.0 | Current | `./gradlew dependencyUpdates` now passes. |
| JaCoCo | 0.8.14 | 0.8.14 | Current | Coverage verification passes. |
| google-java-format | 1.28.0 | 1.35.0 | Held below latest | `1.29.0+` requires running the formatter on JDK 21+, while Android CI currently runs JDK 17. `1.28.0` is the newest compatible formatter upgrade for this CI/runtime shape. |
| Prettier | 3.8.3 | 3.8.3 | Current | Used by Spotless dashboard formatting; `spotlessCheck` passes. |

## Android App Dependencies

| Configuration | Dependency | Current | Latest stable checked | Status | Notes |
|---|---|---:|---:|---|---|
| `implementation` | `androidx.core:core` | 1.18.0 | 1.18.0 | Current stable | Latest pre-release is 1.19.0-rc01. |
| `testImplementation` | `junit:junit` | 4.13.2 | 4.13.2 | Current | Brings `org.hamcrest:hamcrest-core:1.3`. |
| `testImplementation` | `org.json:json` | 20260522 | 20260522 | Current | Used to avoid android.jar JSON stubs in JVM tests. |
| `testImplementation` | `org.robolectric:robolectric` | 4.16.1 | 4.16.1 | Current | Brings AndroidX Test, Guava, ASM, ICU4J, BouncyCastle, and Robolectric shadow modules transitively. |

Resolved debug runtime highlights from `:app:dependencies --configuration debugRuntimeClasspath`:

- `org.jetbrains.kotlin:kotlin-stdlib:2.2.10` is pulled transitively by AGP/AndroidX.
- `androidx.core:core:1.18.0` pulls `androidx.annotation`, `androidx.collection`, `androidx.lifecycle`, `androidx.tracing`, `androidx.versionedparcelable`, coroutines, and `org.jspecify`.
- No direct app runtime dependencies beyond AndroidX Core.

## Dashboard Test Dependencies

`npm ls --depth=0`:

| Package | Installed | Wanted | Latest | Status |
|---|---:|---:|---:|---|
| `vitest` | 4.1.7 | 4.1.7 | 4.1.7 | Current |
| `@vitest/coverage-v8` | 4.1.7 | 4.1.7 | 4.1.7 | Current |
| `jsdom` | 29.1.1 | 29.1.1 | 29.1.1 | Current |
| `eslint` | 10.4.0 | 10.4.0 | 10.4.0 | Current |

`package.json` ranges:

- `vitest`: `^4.1.7`
- `@vitest/coverage-v8`: `^4.1.7`
- `jsdom`: `^29.1.1`
- `eslint`: `^10.4.0`

## Commands Run

- `rg --files` for dependency manifests.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath`
- `./gradlew :app:dependencies --configuration debugUnitTestRuntimeClasspath`
- `./gradlew dependencyUpdates`
- `npm ls --depth=0`
- `npm outdated --long`
- Maven / Google Maven / Gradle / npm metadata checks for latest versions.

## Recommended Follow-Up

1. Watch only the Kotlin milestone line from `dependencyUpdates`; it is transitive from AGP/AndroidX, not directly declared by this app.
2. Keep dependency update checks in CI or a recurring maintenance habit now that the Gradle Versions plugin works again.
3. Re-check ESLint plugin compatibility if the project later adds third-party ESLint plugins or shared configs.
