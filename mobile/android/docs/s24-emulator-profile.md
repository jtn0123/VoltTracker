# Galaxy S24 Emulator Profile

Date added: 2026-06-05

This profile is for local Android WebView dogfooding on a modern phone-sized
surface. It does not emulate Samsung One UI. It uses the newest Android SDK image
available locally for this pass and matches the Galaxy S24 display geometry.

## Profile

| Setting | Value |
|---|---|
| AVD name | `galaxy-s24-api36` |
| System image | `system-images;android-36;google_apis_playstore;arm64-v8a` |
| Android release | Android 16 |
| API level | 36 |
| Display size | `1080x2340` |
| Density | `416` |
| RAM | `8G` |
| CPU cores | `8` |
| Device frame | off |

## Create Or Refresh

Install the Android 16 image:

```sh
sdkmanager "system-images;android-36;google_apis_playstore;arm64-v8a"
```

Create the AVD:

```sh
avdmanager create avd \
  -n galaxy-s24-api36 \
  -k "system-images;android-36;google_apis_playstore;arm64-v8a" \
  -d pixel \
  --force
```

Then update `~/.android/avd/galaxy-s24-api36.avd/config.ini` with the S24-like
hardware geometry:

```ini
PlayStore.enabled=yes
hw.lcd.width=1080
hw.lcd.height=2340
hw.lcd.density=416
hw.ramSize=8G
hw.cpu.ncore=8
vm.heapSize=512M
showDeviceFrame=no
```

The Android device identity remains a generic Pixel AVD because `avdmanager`
rejects arbitrary Samsung device names unless they exist in the installed device
catalog.

## Boot And Validate

Boot headless:

```sh
emulator -avd galaxy-s24-api36 \
  -no-window \
  -no-audio \
  -no-snapshot \
  -no-boot-anim \
  -gpu swiftshader_indirect
```

Confirm the profile:

```sh
adb shell wm size
adb shell wm density
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

Expected values from the 2026-06-05 validation pass:

```text
Physical size: 1080x2340
Physical density: 416
16
36
```

Run the local runtime smoke against the attached emulator:

```sh
mobile/android/scripts/run-emulator-smoke-local.sh
```

This installs the debug APK, waits for the native/WebView dashboard handshake,
starts demo telemetry through the native service path, taps every bottom-nav tab,
captures screenshots under `mobile/android/build/emulator-smoke/screenshots/`,
and scans logcat for app/dashboard runtime exceptions.
