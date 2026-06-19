# Dependency And Runtime Policy

Date: 2026-06-17

The active app lives under `mobile/android/`. The former web receiver is gone,
so dependency work here should stay scoped to the Android app, dashboard build
workspace, dashboard e2e workspace, and GitHub Actions workflows.

## Runtime Pins

- JDK: Gradle runs the Android build on a JDK 21 toolchain.
- Node: dashboard tooling requires Node 22. Use the repo-root `.nvmrc` or
  `.node-version`.
- Android SDK: compile SDK 37 with the wrapper in `mobile/android/gradlew`.

Check a local shell with:

```sh
./gradlew doctor
./scripts/doctor.sh
```

`dashboardNpmCi` and `dashboardE2eNpmCi` fail early when Node is not 22.x. The
temporary override is `VOLTTRACKER_ALLOW_UNSUPPORTED_NODE=1`.

## App Dependency Scope

The shipped release runtime classpath is the locked and audited Gradle scope.
After changing release dependencies, regenerate and commit the lockfile:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks
```

Do not lock every test-host configuration just to quiet scanners. The OSV job
reads `app/gradle.lockfile`, which represents the dependencies bundled into the
release APK.

## Update And Audit Commands

```sh
bash tools/dependency-updates-local.sh
npm --prefix dashboard-tests audit --audit-level=high
npm --prefix dashboard-e2e audit --audit-level=high
```

The weekly `Dependency snapshot` workflow runs a fuller advisory snapshot with
`npm audit --audit-level=low` for both npm workspaces and uploads the Gradle
dependency report as an artifact. The wrapper pins the Gradle Versions plugin
to `--no-configuration-cache --no-parallel`, which is the supported mode for the
current plugin path.

## Review Rules

- Keep runtime dependencies small. Prefer platform SDK or existing AndroidX
  APIs before adding a new library.
- Separate release-runtime dependency changes from test-only tooling changes
  when that makes review and OSV signal clearer.
- If `dependencyUpdates --warning-mode all` reports a Gradle deprecation from
  the Versions plugin, track it as update-tooling debt unless the active app
  build path reports the same warning.
- For major npm or Gradle plugin upgrades, run the relevant lint/typecheck/test
  lane plus `verifyPerformance` when the dashboard bundle or build output can
  change.
- Current minor drift from the 2026-06-18 audit: Spotless was updated to 8.7.0;
  Kotlin stdlib drift is plugin-transitive only because AGP owns Kotlin
  compilation here.

## Gradle 10 Deprecation Tracking

Probe command:

```sh
VOLTTRACKER_ALLOW_UNSUPPORTED_NODE=1 ./gradlew --no-daemon --warning-mode all verifyDashboardBundleSize :app:testDebugUnitTest --tests 'com.volttracker.obdpoc.data.ObdStoreReportsDbTest'
```

2026-06-17 result: the active app build warning is
`ReportingExtension.file(String)` during Detekt plugin application at
`app/build.gradle:9`. Treat this as Detekt plugin/tooling debt and re-check when
upgrading Detekt. The older `dependencyUpdates` warning remains isolated to the
Gradle Versions plugin path documented in `dependency-report-2026-05-26.md`.

## Configuration Cache Compatibility

| Command | Cache mode | Why |
|---|---|---|
| `./gradlew verifyFast --configuration-cache` | compatible | Inner-loop Kotlin/dashboard checks declare stable inputs. |
| `./gradlew verifyPerformance --configuration-cache` | compatible | Bundle budgets, dashboard tests, script self-tests, and JVM tests are cache-safe. |
| `./gradlew verifyActiveApp --configuration-cache` | mostly compatible | Full local PR gate; Playwright and Android packaging still dominate wall-clock. |
| `./gradlew verifyStartupPerformance --no-configuration-cache` | no cache | Connected-device Macrobenchmark launches/install flows should stay explicit. |
| `bash tools/dependency-updates-local.sh` | no cache/no parallel | Gradle Versions plugin currently requires this mode for reliable snapshots. |
