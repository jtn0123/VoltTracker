#!/usr/bin/env bash
# What the no-install adb benchmarks launch, tap and wait for. benchmark-adb-startup-local.sh,
# benchmark-adb-tabs-local.sh and perf-local.sh source this file. The functions below need no
# phone, so `bash tools/benchmark-adb-target.sh --self-test` checks them against fixtures (Gradle:
# verifyBenchmarkTargetSelfTest, part of verifyPerformanceTooling). BenchmarkTargetContractTest pins
# the values and tables here to the app: application IDs, manifests, StartupTrace, the nav bar.
# Bash 3.2 compatible (macOS /bin/bash).

# Class and alias names resolve against the manifest namespace, never the debug application ID:
# the debug launcher is com.volttracker.obdpoc.debug/com.volttracker.obdpoc.ComposeDashboardActivity.
VT_CODE_PACKAGE="com.volttracker.obdpoc"
VT_RELEASE_PACKAGE="com.volttracker.obdpoc"
VT_DEBUG_PACKAGE="com.volttracker.obdpoc.debug"
# The native Compose dashboard, the exported MAIN/LAUNCHER activity.
VT_LAUNCHER_CLASS="com.volttracker.obdpoc.ComposeDashboardActivity"
# The classic WebView dashboard (MainActivity) is not exported. Only debug builds can shell-start
# it, through this alias in app/src/debug/AndroidManifest.xml.
VT_CLASSIC_ALIAS="DebugClassicDashboard"

# Logcat tag of StartupTrace marks. StartupTrace only logs in debug builds, so release runs fall
# back to the accessibility tree.
# shellcheck disable=SC2034 # read by the scripts that source this file
VT_TRACE_TAG="VoltStartup"
# StartupTrace.COMPOSE_SCREEN: "compose_screen:<view>" logs when a native screen composes.
VT_COMPOSE_SCREEN_MARK="compose_screen"
# A fresh native start opens Drive. Its nav bar's Trips button stands in for the ready probe in
# release builds.
VT_NATIVE_START_VIEW="drive"
VT_NATIVE_READY_DESC="Trips"
# The classic dashboard's ready probe: a mark, plus the WebView's content description.
VT_CLASSIC_READY_MARK="dashboard_ready_content_description"
VT_CLASSIC_READY_TEXT="VoltTracker dashboard ready"

# Native targets: tab name, nav content description, and the compose_screen view it logs
# (screenViewName). The five tabs are VoltTab in nav order; Settings is the app bar's gear. A tab's
# description may carry a badge suffix (e.g. "Car, has faults"). Health shares Car's
# "diagnostics" view, so it has no mark of its own and is not a target.
VT_NATIVE_TABS_TABLE='
drive Drive drive
trips Trips map
charge Charge charge
insights Insights insights
car Car diagnostics
settings Settings settings
'
VT_NATIVE_DEFAULT_TABS="trips charge insights car settings"

# Classic targets: data-nav name, nav content description, and page title. The nav has one equal
# slot per row, in this order.
VT_CLASSIC_TABS_TABLE='
drive Drive Drive
map Map Map
charge Charge Charge
insights Insights Insights
diagnostics Diag Diagnostics
settings Settings Settings
'
VT_CLASSIC_DEFAULT_TABS="map charge insights diagnostics settings"

vt_error() {
  echo "$*" >&2
}

# vt_ready_mark DASHBOARD: the StartupTrace mark of a started dashboard (debug builds).
vt_ready_mark() {
  case "$1" in
    native) echo "$VT_COMPOSE_SCREEN_MARK:$VT_NATIVE_START_VIEW" ;;
    classic) echo "$VT_CLASSIC_READY_MARK" ;;
    *) return 1 ;;
  esac
}

# vt_ready_probe DASHBOARD: the text a uiautomator dump of a started dashboard contains.
vt_ready_probe() {
  case "$1" in
    native) echo "content-desc=\"$VT_NATIVE_READY_DESC\"" ;;
    classic) echo "$VT_CLASSIC_READY_TEXT" ;;
    *) return 1 ;;
  esac
}

vt_package_for_variant() {
  case "$1" in
    release) echo "$VT_RELEASE_PACKAGE" ;;
    debug) echo "$VT_DEBUG_PACKAGE" ;;
    *) return 1 ;;
  esac
}

vt_variant_for_package() {
  case "$1" in
    "$VT_RELEASE_PACKAGE") echo "release" ;;
    "$VT_DEBUG_PACKAGE") echo "debug" ;;
    *) return 1 ;;
  esac
}

# vt_select_target VARIANT PACKAGE DASHBOARD
# Picks the installed app to measure and sets VT_VARIANT, VT_PACKAGE and VT_DASHBOARD. Either or
# both of VARIANT (debug|release) and PACKAGE may be given; they must agree. Neither means debug,
# the build that logs StartupTrace marks. DASHBOARD is native (the launcher, default) or classic
# (debug only). Refuses any other package.
vt_select_target() {
  local variant="$1" package="$2" dashboard="${3:-native}"
  if [[ -n "$variant" ]] && ! vt_package_for_variant "$variant" >/dev/null; then
    vt_error "Unknown variant '$variant': use debug ($VT_DEBUG_PACKAGE) or release ($VT_RELEASE_PACKAGE)."
    return 2
  fi
  if [[ -n "$package" ]] && ! vt_variant_for_package "$package" >/dev/null; then
    vt_error "Refusing to benchmark package '$package'. Only $VT_RELEASE_PACKAGE (release) and $VT_DEBUG_PACKAGE (debug) are supported."
    return 2
  fi
  if [[ -n "$variant" && -n "$package" && "$(vt_package_for_variant "$variant")" != "$package" ]]; then
    vt_error "Variant '$variant' installs as $(vt_package_for_variant "$variant"), not '$package'."
    return 2
  fi
  if [[ -z "$variant" ]]; then
    if [[ -n "$package" ]]; then
      variant="$(vt_variant_for_package "$package")"
    else
      variant="debug"
    fi
  fi
  case "$dashboard" in
    native) ;;
    classic)
      if [[ "$variant" != "debug" ]]; then
        vt_error "The classic dashboard has no shell entry point in $variant builds: MainActivity is not exported and the $VT_CLASSIC_ALIAS alias is debug-only. Use --variant debug, or the native dashboard."
        return 2
      fi
      ;;
    *)
      vt_error "Unknown dashboard '$dashboard': use native (the launcher) or classic (debug builds only)."
      return 2
      ;;
  esac
  VT_VARIANT="$variant"
  VT_PACKAGE="$(vt_package_for_variant "$variant")"
  VT_DASHBOARD="$dashboard"
}

vt_target_usage() {
  cat <<'EOF'
Target options (or the environment variable in brackets):
  --variant debug|release   App to measure [VOLTTRACKER_VARIANT]. Default debug
                            (com.volttracker.obdpoc.debug), the build that logs VoltStartup
                            marks. release is com.volttracker.obdpoc: no marks, so readiness
                            comes from the accessibility tree.
  --package PACKAGE         The same choice by application ID [VOLTTRACKER_PACKAGE].
  --dashboard native|classic
                            native (default) starts the launcher that the device resolves.
                            classic starts the WebView dashboard through the debug-only
                            DebugClassicDashboard alias [VOLTTRACKER_DASHBOARD].
EOF
}

# vt_parse_benchmark_args ARGS...: reads the target options (defaults from VOLTTRACKER_VARIANT,
# VOLTTRACKER_PACKAGE and VOLTTRACKER_DASHBOARD), selects the target with vt_select_target, and
# leaves the other arguments in VT_ARGS. Returns 3 for --help and 2 for a bad option or target.
# --variant and --package each name the app, so either flag replaces both environment values;
# only the two flags together can disagree.
vt_parse_benchmark_args() {
  local variant="${VOLTTRACKER_VARIANT:-}" package="${VOLTTRACKER_PACKAGE:-}"
  local dashboard="${VOLTTRACKER_DASHBOARD:-native}" variant_flag="" package_flag=""
  VT_ARGS=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --variant|--package|--dashboard)
        if [[ $# -lt 2 ]]; then
          vt_error "$1 needs a value."
          return 2
        fi
        case "$1" in
          --variant)
            variant="$2" variant_flag=1
            [[ -n "$package_flag" ]] || package=""
            ;;
          --package)
            package="$2" package_flag=1
            [[ -n "$variant_flag" ]] || variant=""
            ;;
          *) dashboard="$2" ;;
        esac
        shift 2
        ;;
      -h|--help) return 3 ;;
      -*)
        vt_error "unknown option: $1"
        return 2
        ;;
      *)
        VT_ARGS+=("$1")
        shift
        ;;
    esac
  done
  vt_select_target "$variant" "$package" "$dashboard"
}

# vt_component_class PACKAGE COMPONENT: the fully qualified class of PACKAGE/.Class or PACKAGE/a.b.Class.
vt_component_class() {
  local package="$1" component="$2" cls
  [[ "$component" == "$package/"* ]] || return 1
  cls="${component#"$package/"}"
  [[ -n "$cls" ]] || return 1
  if [[ "$cls" == .* ]]; then
    cls="$package$cls"
  fi
  echo "$cls"
}

# vt_parse_resolved_component PACKAGE < resolve-activity output
# Prints the PACKAGE/Class line of `cmd package resolve-activity --brief` output; fails on "No
# activity found" or on a component outside PACKAGE (a chooser, another app).
vt_parse_resolved_component() {
  local package="$1" line found="" pattern='^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$'
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    if [[ "$line" =~ $pattern ]]; then
      found="$line"
    fi
  done
  [[ -n "$found" ]] || return 1
  vt_component_class "$package" "$found" >/dev/null || return 1
  echo "$found"
}

# vt_check_launcher PACKAGE COMPONENT: the resolved launcher must be the native dashboard.
vt_check_launcher() {
  local cls
  cls="$(vt_component_class "$1" "$2")" || {
    vt_error "Launcher '$2' is not in package $1."
    return 2
  }
  if [[ "$cls" != "$VT_LAUNCHER_CLASS" ]]; then
    vt_error "The installed launcher is $cls, not the native dashboard ($VT_LAUNCHER_CLASS). This install predates the native launcher: update it, or set VOLTTRACKER_COMPONENT."
    return 2
  fi
}

vt_classic_component() {
  [[ "$1" == "$VT_DEBUG_PACKAGE" ]] || return 1
  echo "$1/$VT_CODE_PACKAGE.$VT_CLASSIC_ALIAS"
}

vt_launcher_query() {
  echo "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $1"
}

# vt_check_component_override PACKAGE COMPONENT: an explicit VOLTTRACKER_COMPONENT must stay in PACKAGE.
vt_check_component_override() {
  vt_component_class "$1" "$2" >/dev/null || {
    vt_error "Refusing VOLTTRACKER_COMPONENT '$2': it must be a $1/<activity> component."
    return 2
  }
}

# vt_resolve_component SERIAL PACKAGE DASHBOARD OVERRIDE [RAW_FILE]: the component `am start -n`
# launches, after checking it on the device. RAW_FILE keeps the device's resolve-activity output.
vt_resolve_component() {
  local serial="$1" package="$2" dashboard="$3" override="$4" raw_file="${5:-/dev/null}" output component
  if [[ -n "$override" ]]; then
    vt_check_component_override "$package" "$override" || return 2
    echo "$override"
    return 0
  fi
  if [[ "$dashboard" == "classic" ]]; then
    component="$(vt_classic_component "$package")" || return 2
    output="$(adb -s "$serial" shell cmd package resolve-activity --brief -n "$component" 2>&1 || true)"
    printf '%s\n' "$output" >"$raw_file"
    if ! printf '%s\n' "$output" | vt_parse_resolved_component "$package" >/dev/null; then
      vt_error "No $component on $serial: install a debug build of $package (the $VT_CLASSIC_ALIAS alias is debug-only)."
      return 2
    fi
    echo "$component"
    return 0
  fi
  # shellcheck disable=SC2046 # the query is a fixed word list
  output="$(adb -s "$serial" shell $(vt_launcher_query "$package") 2>&1 || true)"
  printf '%s\n' "$output" >"$raw_file"
  component="$(printf '%s\n' "$output" | vt_parse_resolved_component "$package")" || {
    vt_error "No launcher activity resolved for $package on $serial (is it installed?): ${output//$'\n'/ }"
    return 2
  }
  vt_check_launcher "$package" "$component" || return 2
  echo "$component"
}

# vt_tab_field DASHBOARD TAB COLUMN: column 2 (content description) or 3 (view or title) of TAB.
vt_tab_field() {
  local table
  case "$1" in
    native) table="$VT_NATIVE_TABS_TABLE" ;;
    classic) table="$VT_CLASSIC_TABS_TABLE" ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$table" | awk -v tab="$2" -v column="$3" '$1 == tab { print $column; found = 1 } END { exit !found }'
}

# vt_canonical_tab DASHBOARD TAB: the table name of TAB (classic accepts "diag"), or a failure.
vt_canonical_tab() {
  local tab="$2"
  if [[ "$1" == "classic" && "$tab" == "diag" ]]; then
    tab="diagnostics"
  fi
  vt_tab_field "$1" "$tab" 1 >/dev/null || return 1
  echo "$tab"
}

vt_tab_names() {
  local table
  case "$1" in
    native) table="$VT_NATIVE_TABS_TABLE" ;;
    classic) table="$VT_CLASSIC_TABS_TABLE" ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$table" | awk 'NF { printf "%s%s", sep, $1; sep = " " } END { print "" }'
}

# vt_classic_tab_index TAB: the classic nav slot of TAB, counted from 0.
vt_classic_tab_index() {
  printf '%s\n' "$VT_CLASSIC_TABS_TABLE" | awk -v tab="$1" 'NF { if ($1 == tab) { print n; found = 1 } n++ } END { exit !found }'
}

# vt_tab_mark DASHBOARD TAB: the StartupTrace mark of TAB on screen (native) or painted (classic).
vt_tab_mark() {
  local view
  case "$1" in
    native)
      view="$(vt_tab_field native "$2" 3)" || return 1
      echo "$VT_COMPOSE_SCREEN_MARK:$view"
      ;;
    classic)
      vt_tab_field classic "$2" 1 >/dev/null || return 1
      echo "js:tab:$2:paint"
      ;;
    *) return 1 ;;
  esac
}

vt_classic_tab_count() {
  printf '%s\n' "$VT_CLASSIC_TABS_TABLE" | awk 'NF { n++ } END { print n }'
}

vt_default_tabs() {
  case "$1" in
    native) echo "$VT_NATIVE_DEFAULT_TABS" ;;
    classic) echo "$VT_CLASSIC_DEFAULT_TABS" ;;
    *) return 1 ;;
  esac
}

# vt_check_tabs DASHBOARD TAB...: every TAB must be a target of DASHBOARD. Prints the canonical names.
vt_check_tabs() {
  local dashboard="$1" tab canonical out=""
  shift
  for tab in "$@"; do
    canonical="$(vt_canonical_tab "$dashboard" "$tab")" || {
      vt_error "Unknown $dashboard tab '$tab'. Use: $(vt_tab_names "$dashboard")."
      return 2
    }
    out="${out:+$out }$canonical"
  done
  echo "$out"
}

vt_self_test() {
  local failures=0 checks=0 got
  # Fixtures, not the caller's choice of target.
  unset VOLTTRACKER_VARIANT VOLTTRACKER_PACKAGE VOLTTRACKER_DASHBOARD

  expect() {
    checks=$((checks + 1))
    if [[ "$2" != "$3" ]]; then
      failures=$((failures + 1))
      echo "FAIL $1: expected [$3], got [$2]" >&2
    fi
  }
  expect_fail() {
    checks=$((checks + 1))
    if "${@:2}" >/dev/null 2>&1; then
      failures=$((failures + 1))
      echo "FAIL $1: expected a failure from: ${*:2}" >&2
    fi
  }
  select_summary() {
    vt_select_target "$@" 2>/dev/null || return $?
    echo "$VT_VARIANT $VT_PACKAGE $VT_DASHBOARD"
  }

  # Variant and package selection.
  expect "default target" "$(select_summary "" "" native)" "debug com.volttracker.obdpoc.debug native"
  expect "release variant" "$(select_summary release "" native)" "release com.volttracker.obdpoc native"
  expect "package implies variant" "$(select_summary "" com.volttracker.obdpoc native)" "release com.volttracker.obdpoc native"
  expect "debug package" "$(select_summary "" com.volttracker.obdpoc.debug "")" "debug com.volttracker.obdpoc.debug native"
  expect "classic debug" "$(select_summary debug com.volttracker.obdpoc.debug classic)" "debug com.volttracker.obdpoc.debug classic"
  expect_fail "classic release" vt_select_target release "" classic
  expect_fail "classic via release package" vt_select_target "" com.volttracker.obdpoc classic
  expect_fail "variant/package mismatch" vt_select_target release com.volttracker.obdpoc.debug native
  expect_fail "other package" vt_select_target "" com.example.app native
  expect_fail "unknown variant" vt_select_target benchmark "" native
  expect_fail "unknown dashboard" vt_select_target debug "" webview

  # Script arguments: target options anywhere, the rest kept in order; environment defaults.
  parsed() {
    vt_parse_benchmark_args "$@" 2>/dev/null || return $?
    echo "$VT_VARIANT $VT_PACKAGE $VT_DASHBOARD | ${VT_ARGS[*]:-}"
  }
  expect "plain args" "$(parsed 10.0.0.2:5555 3)" "debug com.volttracker.obdpoc.debug native | 10.0.0.2:5555 3"
  expect "flags between args" "$(parsed R5CX --variant release 3)" "release com.volttracker.obdpoc native | R5CX 3"
  expect "environment target" \
    "$(VOLTTRACKER_PACKAGE=com.volttracker.obdpoc.debug VOLTTRACKER_DASHBOARD=classic parsed R5CX)" \
    "debug com.volttracker.obdpoc.debug classic | R5CX"
  expect "flag beats environment" "$(VOLTTRACKER_DASHBOARD=classic parsed --dashboard native)" \
    "debug com.volttracker.obdpoc.debug native | "
  expect "variant flag replaces an environment package" \
    "$(VOLTTRACKER_PACKAGE=com.volttracker.obdpoc parsed --variant debug)" \
    "debug com.volttracker.obdpoc.debug native | "
  expect "package flag replaces an environment variant" \
    "$(VOLTTRACKER_VARIANT=debug parsed --package com.volttracker.obdpoc)" \
    "release com.volttracker.obdpoc native | "
  expect "environment mismatch" \
    "$(VOLTTRACKER_VARIANT=release VOLTTRACKER_PACKAGE=com.volttracker.obdpoc.debug parsed; echo "rc=$?")" "rc=2"
  expect "flag mismatch" "$(parsed --package com.volttracker.obdpoc --variant debug; echo "rc=$?")" "rc=2"
  expect "help" "$(parsed --help; echo "rc=$?")" "rc=3"
  expect "missing value" "$(parsed --variant; echo "rc=$?")" "rc=2"
  expect "unknown option" "$(parsed --runs 3; echo "rc=$?")" "rc=2"
  expect "classic release flags" "$(parsed --variant release --dashboard classic; echo "rc=$?")" "rc=2"

  # `cmd package resolve-activity --brief` output, as adb returns it (CRLF on some devices).
  got="$(printf 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\r\ncom.volttracker.obdpoc.debug/com.volttracker.obdpoc.ComposeDashboardActivity\r\n' |
    vt_parse_resolved_component com.volttracker.obdpoc.debug)"
  expect "debug launcher" "$got" "com.volttracker.obdpoc.debug/com.volttracker.obdpoc.ComposeDashboardActivity"
  expect "debug launcher is native" "$(vt_check_launcher com.volttracker.obdpoc.debug "$got" 2>&1 && echo ok)" "ok"
  got="$(printf 'priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\ncom.volttracker.obdpoc/.ComposeDashboardActivity' |
    vt_parse_resolved_component com.volttracker.obdpoc)"
  expect "release launcher (short form, no newline)" "$got" "com.volttracker.obdpoc/.ComposeDashboardActivity"
  expect "short form class" "$(vt_component_class com.volttracker.obdpoc "$got")" "com.volttracker.obdpoc.ComposeDashboardActivity"
  expect "release launcher is native" "$(vt_check_launcher com.volttracker.obdpoc "$got" 2>&1 && echo ok)" "ok"
  expect_fail "no activity found" vt_parse_resolved_component com.volttracker.obdpoc.debug <<<"No activity found"
  expect_fail "chooser is not the app" vt_parse_resolved_component com.volttracker.obdpoc \
    <<<"android/com.android.internal.app.ResolverActivity"
  expect_fail "release launcher is not debug's" vt_parse_resolved_component com.volttracker.obdpoc.debug \
    <<<"com.volttracker.obdpoc/.ComposeDashboardActivity"
  expect_fail "old classic launcher" vt_check_launcher com.volttracker.obdpoc com.volttracker.obdpoc/.MainActivity
  # The short form expands against the application ID, so it names the wrong class in debug builds.
  expect_fail "debug short form" vt_check_launcher com.volttracker.obdpoc.debug \
    com.volttracker.obdpoc.debug/.ComposeDashboardActivity
  expect "launcher query" "$(vt_launcher_query com.volttracker.obdpoc.debug)" \
    "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.volttracker.obdpoc.debug"

  # Classic alias and component overrides.
  expect "classic alias" "$(vt_classic_component com.volttracker.obdpoc.debug)" \
    "com.volttracker.obdpoc.debug/com.volttracker.obdpoc.DebugClassicDashboard"
  expect_fail "no classic alias in release" vt_classic_component com.volttracker.obdpoc
  expect "override in package" \
    "$(vt_resolve_component unused com.volttracker.obdpoc.debug native com.volttracker.obdpoc.debug/com.volttracker.obdpoc.DebugComposeDashboard)" \
    "com.volttracker.obdpoc.debug/com.volttracker.obdpoc.DebugComposeDashboard"
  expect_fail "override outside package" vt_resolve_component unused com.volttracker.obdpoc native com.example/.Main

  # Native tab mapping: nav content description and the compose_screen view it logs.
  expect "native tabs" "$(vt_tab_names native)" "drive trips charge insights car settings"
  expect "trips desc" "$(vt_tab_field native trips 2)" "Trips"
  expect "trips view" "$(vt_tab_field native trips 3)" "map"
  expect "car view" "$(vt_tab_field native car 3)" "diagnostics"
  expect "settings desc" "$(vt_tab_field native settings 2)" "Settings"
  expect "settings view" "$(vt_tab_field native settings 3)" "settings"
  expect "start view" "$(vt_tab_field native "$VT_NATIVE_START_VIEW" 3)" "drive"
  expect "native ready mark" "$(vt_ready_mark native)" "compose_screen:drive"
  expect "native ready probe" "$(vt_ready_probe native)" 'content-desc="Trips"'
  expect "trips mark" "$(vt_tab_mark native trips)" "compose_screen:map"
  expect "settings mark" "$(vt_tab_mark native settings)" "compose_screen:settings"
  expect_fail "no mark for unknown tab" vt_tab_mark native health
  # shellcheck disable=SC2086 # a tab list splits into words
  expect "native defaults" "$(vt_check_tabs native $VT_NATIVE_DEFAULT_TABS)" "trips charge insights car settings"
  expect_fail "classic name in native" vt_check_tabs native map
  expect_fail "health has no mark" vt_check_tabs native health
  expect_fail "unknown native tab" vt_canonical_tab native diag

  # Classic tab mapping: slot order, content description and title.
  expect "classic tabs" "$(vt_tab_names classic)" "drive map charge insights diagnostics settings"
  expect "classic slots" "$(vt_classic_tab_count)" "6"
  expect "map slot" "$(vt_classic_tab_index map)" "1"
  expect "settings slot" "$(vt_classic_tab_index settings)" "5"
  expect "diag alias" "$(vt_check_tabs classic diag map)" "diagnostics map"
  expect "diagnostics desc" "$(vt_tab_field classic diagnostics 2)" "Diag"
  expect "diagnostics title" "$(vt_tab_field classic diagnostics 3)" "Diagnostics"
  expect "classic ready mark" "$(vt_ready_mark classic)" "dashboard_ready_content_description"
  expect "classic ready probe" "$(vt_ready_probe classic)" "VoltTracker dashboard ready"
  expect "classic paint mark" "$(vt_tab_mark classic map)" "js:tab:map:paint"
  expect_fail "unknown dashboard ready mark" vt_ready_mark webview
  # shellcheck disable=SC2086 # a tab list splits into words
  expect "classic defaults" "$(vt_check_tabs classic $VT_CLASSIC_DEFAULT_TABS)" "map charge insights diagnostics settings"
  expect_fail "native name in classic" vt_check_tabs classic trips
  expect_fail "no slot for unknown tab" vt_classic_tab_index car

  if [[ "$failures" -ne 0 ]]; then
    echo "benchmark-adb-target self-test: $failures of $checks checks failed" >&2
    return 1
  fi
  echo "benchmark-adb-target self-test: $checks checks passed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  case "${1:-}" in
    --self-test)
      vt_self_test
      ;;
    *)
      echo "usage: bash tools/benchmark-adb-target.sh --self-test" >&2
      echo "(source it from the adb benchmark scripts for the target helpers)" >&2
      exit 2
      ;;
  esac
fi
