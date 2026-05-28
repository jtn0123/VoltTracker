# Release and Install Notes

VoltTracker ships two APK streams:

- `latest-debug`: a rolling debug APK built from every push to `main` by
  `.github/workflows/android.yml`. It is attached to the prerelease tag
  `latest-debug` as `volttracker-vX.Y.Z-<commit>-debug.apk`.
- Tagged releases: conventional-commit merges to `main` run
  `.github/workflows/release.yml`. `feat:` creates a minor bump, `fix:` and
  `perf:` create patch bumps, and `BREAKING CHANGE:` creates a major bump. When
  a release is cut, the workflow attaches both an install-oriented release APK
  and a debug-key APK to the tagged release.

## Debug APKs

Debug APKs use the standard Android debug key. They are intended for quick
on-phone validation from the rolling release artifact or a local build:

```sh
cd mobile/android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If installing from a browser or file manager, Android must allow "Install
unknown apps" for that app. A debug build installs separately from a signed
release build if their signatures differ.

## Tagged Release APKs

Tagged releases attach clearly named APKs to the `vX.Y.Z` GitHub release:

- `volttracker-vX.Y.Z-release.apk`: the normal signed user build.
- `volttracker-vX.Y.Z-release-unsigned.apk`: the fallback when release signing
  secrets are unavailable. It is inspectable, but Android will not treat it as
  the same signed app as the normal release APK.
- `volttracker-vX.Y.Z-debug.apk`: the debug-key build for developers and field
  diagnostics. It cannot install over the release build; back up data, uninstall
  release, install debug, then restore.

The signing secrets are:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow writes `mobile/android/keystore.properties` and
`mobile/android/app/release.keystore` only on the runner, verifies the keystore
with `keytool`, and removes both files in the final cleanup step.

## Release Validation

Before changing release automation, run:

```sh
python .github/scripts/check_release_config.py
cd mobile/android && ./gradlew --no-daemon verifyActiveApp
```

The Python check keeps the semantic-release config, PR title lint types, release
workflow guardrails, and `VERSION` source of truth aligned.
