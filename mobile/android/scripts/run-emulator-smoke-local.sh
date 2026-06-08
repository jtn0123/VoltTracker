#!/usr/bin/env bash
# Local wrapper for the CI emulator smoke. Run this with an emulator or Android
# device already attached; it builds the debug APK and then delegates to the same
# runtime smoke script CI uses.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH. Install Android platform-tools or open this from an Android SDK shell."
  exit 1
fi

attached_devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [ -z "$attached_devices" ]; then
  echo "No attached Android device/emulator is ready. Start an emulator, then retry."
  adb devices
  exit 1
fi

chmod +x ./gradlew
./gradlew :app:assembleDebug
bash scripts/emulator-smoke.sh
