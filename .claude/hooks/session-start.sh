#!/bin/bash
set -euo pipefail

# Only provision the Android SDK in the remote Claude Code environment.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;36.0.0"

mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$tmp_zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  unzip -q "$tmp_zip" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -f "$tmp_zip"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -d "$ANDROID_SDK_ROOT/platforms/android-36" ] || [ ! -d "$ANDROID_SDK_ROOT/build-tools/36.0.0" ] || [ ! -d "$ANDROID_SDK_ROOT/platform-tools" ]; then
  yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
  "$SDKMANAGER" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" > /dev/null
fi

# Persist environment for the rest of the session.
{
  echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
  echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
  echo "export PATH=\"$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:\$PATH\""
} >> "$CLAUDE_ENV_FILE"
