#!/usr/bin/env bash
# Called by android-emulator-runner after SDK installation and before launch.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
emulator="${ANDROID_HOME:?ANDROID_HOME must point to the installed SDK}/emulator/emulator"
original="$emulator.volt-original"
if [ -e "$original" ]; then
  echo "Emulator diagnostics already installed; refusing to wrap twice." >&2
  exit 1
fi
mv "$emulator" "$original"
{
  printf '#!/usr/bin/env bash\n'
  printf 'export VOLT_EMULATOR_DIAGNOSTICS=%q\n' "$script_dir/../build/emulator-process"
  printf 'exec bash %q %q "$@"\n' "$script_dir/emulator-process.sh" "$original"
} > "$emulator"
chmod +x "$emulator"
