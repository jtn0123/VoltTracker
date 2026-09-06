#!/usr/bin/env bash
# Preserve the native emulator's exit status; the runner launches it in the
# background, so its usual logs only show the later adb disconnection.
set -uo pipefail
binary="$1"
shift
artifact_dir="${VOLT_EMULATOR_DIAGNOSTICS:?Set the emulator diagnostics directory}"
mkdir -p "$artifact_dir"
log="$artifact_dir/process.log"
{
  date -u '+started=%Y-%m-%dT%H:%M:%SZ'
  printf 'command='; printf '%q ' "$binary" "$@"; printf '\n'
} >> "$log"
command=("$binary" "$@")
if [ "${VOLT_EMULATOR_GDB:-0}" = 1 ]; then
  command=(gdb --batch --return-child-result
    -ex 'set pagination off'
    -ex 'handle SIGPIPE nostop noprint pass'
    -ex 'handle SIGUSR1 nostop noprint pass'
    -ex 'handle SIGUSR2 nostop noprint pass'
    -ex run -ex 'thread apply all bt' --args "${command[@]}")
fi
"${command[@]}" > >(tee -a "$artifact_dir/stdout.log") 2>&1 &
emulator_pid=$!
printf 'pid=%s\n' "$emulator_pid" >> "$log"
if wait "$emulator_pid"; then
  result=0
else
  result=$?
fi
{
  date -u '+exited=%Y-%m-%dT%H:%M:%SZ'
  printf 'exit_status=%s\n' "$result"
  if [ "$result" -gt 128 ]; then
    printf 'signal=%s\n' "$((result - 128))"
  fi
} >> "$log"
exit "$result"
