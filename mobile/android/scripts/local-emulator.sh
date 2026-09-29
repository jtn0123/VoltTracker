#!/usr/bin/env bash
# Local emulator testing that doesn't fall over on a busy Mac.
#
# Past emulator runs "crashed" (ANRs, "failed to complete startup") because of the setup, not the
# app: the AVD lived on a USB flash drive (95% iowait), had 2 GB RAM, software rendering and a Play
# Store image whose own updates ANR'd, all while the host was swapping. This script uses one AVD on
# the internal disk with a host GPU and 4 GB, checks the host before booting, precompiles the app so
# the first launch isn't interpreted, and labels every ANR as the app's or the system's.
#
#   scripts/local-emulator.sh create            # one-time: make the vt_test AVD
#   scripts/local-emulator.sh up [--animations] # preflight + boot + settle
#   scripts/local-emulator.sh install [apk]     # install + AOT compile (default: debug APK)
#   scripts/local-emulator.sh open <tab> [route] [--demo]   # tabs: drive trips charge insights car
#                                               #   routes: settings health adapter
#   scripts/local-emulator.sh shot <name>       # PNG into build/emulator-shots/
#   scripts/local-emulator.sh tour [--demo]     # every tab + Health + Settings, one PNG each
#   scripts/local-emulator.sh check             # crashes/ANRs since boot, app vs system
#   scripts/local-emulator.sh down
set -uo pipefail

AVD="${VT_AVD:-vt_test}"
IMAGE="system-images;android-36;google_apis;arm64-v8a"
PKG="com.volttracker.obdpoc"
ACTIVITY="$PKG/.ComposeDashboardActivity"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd "$here/.." && pwd)"
shots="$android_dir/build/emulator-shots"
MIN_FREE_MB="${VT_MIN_FREE_MB:-3000}"

say() { printf '%s\n' "$*"; }
die() { say "error: $*" >&2; exit 1; }

# Free + inactive + purgeable pages: what macOS can hand the emulator without swapping.
host_free_mb() {
  vm_stat | awk -v ps="$(pagesize)" '
    /Pages free/ {f=$3} /Pages inactive/ {i=$3} /Pages purgeable/ {p=$3}
    END {gsub(/\./,"",f); gsub(/\./,"",i); gsub(/\./,"",p); printf "%d", (f+i+p)*ps/1048576}'
}

preflight() {
  local free
  free=$(host_free_mb)
  say "host: ${free} MB available (want ${MIN_FREE_MB}+ for a 4 GB emulator)"
  if [ "$free" -lt "$MIN_FREE_MB" ]; then
    say "warning: the Mac is short on memory; the emulator will stall and ANR. Biggest users:"
    ps -axo rss,command | sort -rn | head -6 | awk '{printf "  %5.1f GB  %s\n",$1/1048576,substr($0,index($0,$2),90)}'
    say "stopping Gradle daemons (they come back on the next build)"
    (cd "$android_dir" && ./gradlew --stop >/dev/null 2>&1) || true
    free=$(host_free_mb)
    say "host now: ${free} MB available"
    [ "$free" -lt "$MIN_FREE_MB" ] && say "still low: close other test runs/VMs, or expect system ANRs (check labels them)"
  fi
  local disk
  disk=$(df -m "$HOME/.android" | awk 'NR==2{print $4}')
  [ "$disk" -lt 8000 ] && say "warning: only ${disk} MB free on the internal disk"
  return 0
}

create() {
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMAGE" >/dev/null || die "sdkmanager failed"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 --force >/dev/null ||
    die "avdmanager failed"
  local cfg="$HOME/.android/avd/$AVD.avd/config.ini"
  # S24-like screen; host GPU (software rendering made frames slow enough to ANR on input).
  local k
  for k in hw.ramSize=4096 hw.cpu.ncore=4 hw.gpu.enabled=yes hw.gpu.mode=host disk.dataPartition.size=6G \
    hw.lcd.width=1080 hw.lcd.height=2340 hw.lcd.density=416 vm.heapSize=512 showDeviceFrame=no \
    hw.keyboard=yes hw.audioInput=no hw.audioOutput=no; do
    grep -v "^${k%%=*}[ =]" "$cfg" >"$cfg.tmp" && mv "$cfg.tmp" "$cfg"
    echo "$k" >>"$cfg"
  done
  say "created $AVD (internal disk: $HOME/.android/avd)"
}

# Percent of CPU time idle across a 3 s window, from /proc/stat.
guest_idle_pct() {
  adb shell 'head -1 /proc/stat; sleep 3; head -1 /proc/stat' 2>/dev/null | awk '
    { t=0; for (k=2; k<=NF; k++) t+=$k; idle=$5+$6 }
    NR==1 { t1=t; i1=idle } NR==2 && t>t1 { printf "%d", 100*(idle-i1)/(t-t1) }'
}

wait_settled() {
  adb wait-for-device

  for _ in $(seq 1 120); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  # First-boot dexopt and GMS setup keep the guest busy for a while; launching into that is how
  # "failed to complete startup" happens. Wait until the CPU is mostly idle over a 3 s window
  # (Linux loadavg counts sleeping IO threads, so it stays high on an idle emulator).
  for _ in $(seq 1 60); do
    local idle
    idle=$(guest_idle_pct)
    [ -n "$idle" ] && [ "$idle" -ge 70 ] && { say "guest settled (${idle}% idle)"; return 0; }
    sleep 1
  done
  say "warning: guest still busy after 3 min; results may include system stalls"
}

up() {
  local animations=0
  [ "${1:-}" = "--animations" ] && animations=1
  [ -d "$HOME/.android/avd/$AVD.avd" ] || create
  preflight
  if adb devices | grep -q "^emulator-"; then
    say "an emulator is already running"
  else
    nohup "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save -no-audio -no-boot-anim -gpu host \
      >"${TMPDIR:-/tmp}/vt-emulator.log" 2>&1 &
    say "booting $AVD..."
  fi
  wait_settled
  adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1
  adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1
  local scale=0
  [ "$animations" = 1 ] && scale=1
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$k" "$scale"
  done
  adb logcat -c
  say "ready"
}

install_app() {
  local apk="${1:-$android_dir/app/build/outputs/apk/debug/app-debug.apk}"
  [ -f "$apk" ] || die "no APK at $apk (run ./gradlew :app:assembleDebug)"
  adb install -r "$apk" | tail -1
  # AOT-compile now so the first launch isn't a slow interpreted run racing the ANR timer.
  adb shell cmd package compile -m speed -f "$PKG" | tail -1
}

open_screen() {
  local tab="${1:-drive}" route="" demo=false arg
  shift || true
  for arg in "$@"; do
    case "$arg" in
      --demo) demo=true ;;
      *) route="$arg" ;;
    esac
  done
  local extras=(--es vt.tab "$tab" --ez vt.demo "$demo")
  [ -n "$route" ] && extras+=(--es vt.route "$route")
  adb shell am start --activity-clear-top -W -n "$ACTIVITY" "${extras[@]}" | grep -E "TotalTime|Status|Error"
}

shot() {
  mkdir -p "$shots"
  adb exec-out screencap -p >"$shots/$1.png" && say "$shots/$1.png"
}

tour() {
  local demo_flag="${1:-}" t
  if [ "$demo_flag" = "--demo" ]; then
    open_screen drive --demo >/dev/null
    sleep 8
  fi
  for t in drive trips charge insights car; do
    open_screen "$t" >/dev/null
    sleep 3
    shot "$t"
  done
  open_screen car health >/dev/null && sleep 3 && shot health
  open_screen drive settings >/dev/null && sleep 3 && shot settings
}

# Crashes and ANRs since the logcat was last cleared, each ANR labelled: a stall of the whole guest
# (IO pressure high, or other apps ANR'd within 30 s) is the emulator/host, not the app.
check() {
  local crashes
  crashes=$(adb logcat -d -b crash 2>/dev/null | grep -A12 "Process: $PKG" || true)
  if [ -n "$crashes" ]; then
    say "APP CRASH:"
    say "$crashes"
  else
    say "no app crashes"
  fi
  adb shell dumpsys dropbox --print data_app_anr 2>/dev/null | awk -v pkg="$PKG" '
    /^Process: / {proc=$2}
    /^Timestamp: / {ts=$2" "$3}
    /^Subject: / {subj=substr($0,10)}
    /^some avg10=/ && prev ~ /pressure\/io/ {io=$2; sub("avg10=","",io)}
    {prev=$0}
    /^Data File:/ || /^RssHwmKb:/ {
      if (proc != "" && subj != "") { n++; P[n]=proc; T[n]=ts; S[n]=subj; I[n]=io; proc=""; subj=""; io="" }
    }
    END {
      for (k=1; k<=n; k++) if (P[k]==pkg) {
        others=0
        for (j=1; j<=n; j++) if (P[j]!=pkg && substr(T[j],1,16)==substr(T[k],1,16)) others++
        label = (I[k]+0 > 50 || others > 0) ? "SYSTEM STALL (io " I[k] "%, " others " other apps ANR) - not the app" : "APP ANR - investigate"
        printf "ANR %s  %s\n    %s\n", T[k], label, S[k]
        found=1
      }
      if (!found) print "no app ANRs"
    }'
}

case "${1:-}" in
  create) create ;;
  up) shift; up "$@" ;;
  install) shift; install_app "$@" ;;
  open) shift; open_screen "$@" ;;
  shot) [ -n "${2:-}" ] || die "shot needs a name"; shot "$2" ;;
  tour) shift; tour "$@" ;;
  check) check ;;
  down) adb emu kill ;;
  *) sed -n '2,21p' "$0"; exit 2 ;;
esac
