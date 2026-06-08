#!/usr/bin/env bash
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$ANDROID_ROOT/../.." && pwd)"

failures=0

section() {
  printf '\n== %s ==\n' "$1"
}

ok() {
  printf 'ok: %s\n' "$1"
}

warn() {
  printf 'warn: %s\n' "$1"
}

missing() {
  printf 'missing: %s\n' "$1"
  failures=1
}

need_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    ok "$1 -> $(command -v "$1")"
    return 0
  fi
  missing "$1 is not on PATH"
  return 1
}

print_cmd_version() {
  local cmd="$1"
  shift
  if command -v "$cmd" >/dev/null 2>&1; then
    "$cmd" "$@" 2>&1 | head -n 1
  fi
}

section "Java and Gradle"
if need_cmd java; then
  print_cmd_version java -version
fi
if [ -x "$ANDROID_ROOT/gradlew" ]; then
  ok "Gradle wrapper is executable"
  if [ -f "$ANDROID_ROOT/gradle/wrapper/gradle-wrapper.properties" ]; then
    grep '^distributionUrl=' "$ANDROID_ROOT/gradle/wrapper/gradle-wrapper.properties" || true
  fi
else
  missing "gradlew is missing or not executable"
fi

section "Android SDK"
if [ -n "${ANDROID_HOME:-}" ]; then
  ok "ANDROID_HOME=$ANDROID_HOME"
else
  warn "ANDROID_HOME is not set"
fi
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  ok "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
fi
need_cmd adb && adb version | head -n 1
if command -v sdkmanager >/dev/null 2>&1; then
  ok "sdkmanager -> $(command -v sdkmanager)"
  sdkmanager --version 2>/dev/null | head -n 1 || true
else
  warn "sdkmanager is not on PATH"
fi
if command -v emulator >/dev/null 2>&1; then
  ok "emulator -> $(command -v emulator)"
  emulator -version 2>&1 | head -n 1 || true
else
  warn "emulator is not on PATH"
fi

section "Connected Devices"
if command -v adb >/dev/null 2>&1; then
  if ! adb devices; then
    missing "adb devices failed; check ADB daemon permissions or restart the server"
  fi
else
  warn "Skipping device list because adb is missing"
fi

section "Node and Dashboard Tooling"
if need_cmd node; then
  node_version="$(node --version 2>&1 | head -n 1)"
  printf '%s\n' "$node_version"
  expected_node_major="20"
  if [ -f "$REPO_ROOT/.nvmrc" ]; then
    expected_node_major="$(sed -E 's/^v?([0-9]+).*/\1/' "$REPO_ROOT/.nvmrc")"
  fi
  node_major="$(printf '%s\n' "$node_version" | sed -E 's/^v?([0-9]+).*/\1/')"
  if [ "$node_major" != "$expected_node_major" ]; then
    missing "Node ${expected_node_major}.x is required by .nvmrc and dashboard package engines"
  fi
fi
if need_cmd npm; then
  print_cmd_version npm --version
fi
if [ -d "$ANDROID_ROOT/dashboard-tests/node_modules" ]; then
  ok "dashboard-tests node_modules present"
else
  warn "dashboard-tests dependencies are not installed; run: npm --prefix dashboard-tests ci"
fi
if [ -d "$ANDROID_ROOT/dashboard-e2e/node_modules" ]; then
  ok "dashboard-e2e node_modules present"
else
  warn "dashboard-e2e dependencies are not installed; run: npm --prefix dashboard-e2e ci"
fi
if [ -x "$ANDROID_ROOT/dashboard-e2e/node_modules/.bin/playwright" ]; then
  ok "Playwright CLI present"
else
  warn "Playwright CLI is not installed under dashboard-e2e"
fi

section "Local Smoke Scripts"
if [ -x "$ANDROID_ROOT/scripts/run-emulator-smoke-local.sh" ]; then
  ok "scripts/run-emulator-smoke-local.sh is executable"
else
  missing "scripts/run-emulator-smoke-local.sh is missing or not executable"
fi
if [ -x "$ANDROID_ROOT/scripts/emulator-smoke.sh" ]; then
  ok "scripts/emulator-smoke.sh is executable"
else
  missing "scripts/emulator-smoke.sh is missing or not executable"
fi

section "Summary"
if [ "$failures" -eq 0 ]; then
  ok "required tools are present"
else
  missing "one or more required tools are missing"
fi
exit "$failures"
