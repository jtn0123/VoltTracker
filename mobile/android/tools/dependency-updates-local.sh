#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"

cd "$android_dir"
chmod +x ./gradlew
exec ./gradlew --no-daemon --no-configuration-cache --no-parallel dependencyUpdates -Drevision=release "$@"
