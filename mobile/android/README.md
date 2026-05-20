# Volt Tracker Android OBD POC

This is a standalone Android proof of concept for replacing Torque as the day-to-day OBD bridge.

It does three things:

1. Hosts a local Volt-style dashboard in an Android `WebView`.
2. Connects to a paired ELM327-style OBD2 adapter over Bluetooth Classic SPP.
3. Streams basic live OBD telemetry into the dashboard.

The POC also includes a demo telemetry mode so the UI can be tested without a scanner.

## On-Phone Storage

The app now writes two layers of local data:

- Raw field-test JSONL files under app-private `files/obd-logs/`.
- Structured SQLite data in `volttracker_obd_poc.db`.

SQLite tables capture OBD sessions, parsed telemetry samples, status/debug events, and adapter history summaries. The Settings screen shows a database summary so field tests can confirm whether sessions and samples are being saved without pulling files from the phone.

## Current PIDs

- `ATRV` adapter voltage
- `010D` vehicle speed
- `010C` engine RPM
- `0105` coolant temp
- `0104` engine load
- `0111` throttle position

Volt-specific hybrid data is intentionally not in this first slice. Once the adapter link is reliable, the next step is adding GM/Volt custom PIDs for SOC, pack data, EV/gas split, and battery health.

## Build

From this directory:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK will be at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install On A Phone

1. Pair your OBD adapter in Android Bluetooth settings.
2. Enable Developer Options and USB debugging.
3. Plug in the phone and accept the USB debugging prompt.
4. Run:

```powershell
adb devices
.\gradlew.bat :app:installDebug
```

Optional phone mirroring:

```powershell
scrcpy
```

## POC Notes

- This uses no AndroidX dependencies yet.
- Bluetooth permissions are requested at runtime on Android 12+.
- A foreground service keeps the OBD session alive while polling.
- The WebView only loads local assets from `app/src/main/assets/dashboard`.
- The service uses the standard ELM327 serial UUID: `00001101-0000-1000-8000-00805F9B34FB`.

## Pulling Field-Test Logs

Every connect or demo session writes JSONL logs on the phone under app-private storage:

```text
files/obd-logs/session-*.jsonl
files/obd-logs/latest.txt
```

After reconnecting the phone with USB debugging:

```powershell
$pkg = "com.volttracker.obdpoc"
$out = "C:\Users\Justin\OneDrive\Documents\Github\VoltTracker\mobile\android\field-test-latest.jsonl"
$latest = adb shell run-as $pkg cat files/obd-logs/latest.txt
adb exec-out run-as $pkg cat "files/obd-logs/$latest" > $out
```

Those logs include status transitions, connection failures, every ELM327 command and response, parsed telemetry samples, timing, empty responses, and whether the adapter prompt (`>`) was seen.

The SQLite database can also be pulled after a test:

```powershell
$pkg = "com.volttracker.obdpoc"
$out = "C:\Users\Justin\OneDrive\Documents\Github\VoltTracker\mobile\android\field-test-db.db"
adb exec-out run-as $pkg cat databases/volttracker_obd_poc.db > $out
```
