# Dependency And Runtime Policy

Date: 2026-06-17

The active app lives under `mobile/android/`. The former web receiver is gone,
so dependency work here should stay scoped to the Android app, dashboard build
workspace, dashboard e2e workspace, and GitHub Actions workflows.

## Runtime Pins

- JDK: Gradle runs the Android build on a JDK 21 toolchain.
- Node: dashboard tooling requires Node 24. Use the repo-root `.nvmrc` or
  `.node-version`.
- Android SDK: compile SDK 37 with the wrapper in `mobile/android/gradlew`.

Check a local shell with:

```sh
./gradlew doctor
./scripts/doctor.sh
```

`dashboardNpmCi` and `dashboardE2eNpmCi` fail early when Node is not 24.x. The
temporary override is `VOLTTRACKER_ALLOW_UNSUPPORTED_NODE=1`.

## App Dependency Scope

The shipped release runtime classpath is the locked and audited Gradle scope.
After changing release dependencies, regenerate and commit the lockfile:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks
```

Check the lockfile is current without rewriting it:

```sh
./gradlew :app:verifyReleaseDependencyLock
```

That task resolves `releaseRuntimeClasspath` for real, so lock drift is fatal;
CI runs it in the `gradle-dependency-audit` job. Do not substitute
`:app:dependencies` — the report task prints `FAILED` rows for unresolvable
entries and still exits 0, so it cannot detect a stale lockfile.

Do not lock every test-host configuration just to quiet scanners. The OSV job
reads `app/gradle.lockfile`, which represents the dependencies bundled into the
release APK.

## Update And Audit Commands

```sh
./gradlew dependencyUpdates -Drevision=release
npm --prefix dashboard-tests audit --audit-level=moderate
npm --prefix dashboard-e2e audit --audit-level=moderate
```

The weekly `Dependency snapshot` workflow runs a fuller advisory snapshot with
`npm audit --audit-level=low` for both npm workspaces and uploads the Gradle
dependency report as an artifact.

## npm Overrides

`dashboard-tests/package.json` has one override, added 2026-11-07:

- `minimatch: ^11.0.5`. Advisory GHSA-g3hv-75wg-m249 (glob DoS) affects
  minimatch `>=7.0.0 <11.0.1`. eslint 10.14 and typescript-eslint 8.72 still
  declare `^10`, so `npm audit` stays red without the override. Their only
  minimatch calls are `new Minimatch(pattern, { dot: true })`, `.match(path)`,
  `.match(path, true)` and `minimatch(path, pattern, { dot: true })`, which are
  unchanged in 11. The 11.0 removals (`makeRe`, `regexp`, `matchOne`, `debug`)
  are not used. Remove the override once eslint, `@eslint/config-array` and
  `@typescript-eslint/typescript-estree` all depend on minimatch `>=11.0.1`
  (`npm view <pkg> dependencies.minimatch`), then rerun lint and the audit.

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

## Gradle 10 Deprecation Tracking

Probe command:

```sh
VOLTTRACKER_ALLOW_UNSUPPORTED_NODE=1 ./gradlew --no-daemon --warning-mode all verifyDashboardBundleSize :app:testDebugUnitTest --tests 'com.volttracker.obdpoc.data.ObdStoreReportsDbTest'
```

2026-06-17 result: the active app build warning is
`ReportingExtension.file(String)` during Detekt plugin application at
`app/build.gradle:9`. Treat this as Detekt plugin/tooling debt and re-check when
upgrading Detekt. The older `dependencyUpdates` warning remains isolated to the
Gradle Versions plugin path documented in `archive/dependency-report-2026-05-26.md`.
