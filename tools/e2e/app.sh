#!/usr/bin/env bash
# app.sh - install, run and set up Kirana List on the emulator.
#
# Usage: app.sh [-s emulator-NNNN] <command> [args]
#   install <apk|release|qa|debug> [--fresh] [--low-storage]
#         installs over the app already there (keeps its data). "release" / "qa" / "debug" = the APK that
#         build-test-apk.sh made. Remembers the APK's app id (com.grocerysync.app, or com.grocerysync.app.qa
#         for the qa build): the other commands, and ui.sh crashes, act on the app installed last
#         (override with GS_PACKAGE=...). --fresh uninstalls first (needed when switching between debug
#         and release signing, or going to a lower versionCode). --low-storage lowers the free-space check
#         (sys_storage_threshold_percentage = 2) for this install only, for an AVD whose /data is nearly full.
#         Also sets the crash mark for "ui.sh crashes".
#   uninstall                   removes the app and its data (no error when it is not installed)
#   launch | stop | restart     start the main screen (waits for it), force-stop, or both
#   clear-data                  pm clear: like a fresh install, keeping the APK
#   info                        which app id the tools act on; versionName / versionCode, debuggable, install time
#   configure [--name NAME] [--url URL] [--token TOKEN] [--port P] [--no-test]
#         sets the app up through its Settings screen, as a person would (works for release builds, where
#         run-as cannot write the settings): restarts the app, opens Settings, types Your name (default
#         "Emu"), the Web app URL (default: the emulator URL of the mock on --port / the last mock.sh port)
#         and the Secret token (default: that mock's token; never printed), taps Test connection and waits
#         for "Connected to ..." (skip with --no-test), taps Save and waits for the "Live" status line.
#         Settings is taller than the screen: it scrolls down to each field and button.
#   pull-db [DIR]               debug builds only: copies the app's SQLite files to DIR (default out/db)
#
# Uses the emulator from EMU_SERIAL / emu.sh; refuses anything that is not emulator-NNNN. Safe to re-run.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit "${1:-0}"; }

while [ $# -gt 0 ]; do
  case $1 in
    -s) EMU_SERIAL=${2:?-s needs a serial}; gs_check_serial "$EMU_SERIAL"; shift 2;;
    -h | --help | help) usage 0;;
    *) break;;
  esac
done
cmd=${1:-}
[ -z "$cmd" ] && usage 2
shift

# Never "| grep -q" on adb output: under pipefail an early exit of grep fails the pipeline (SIGPIPE).
installed() { gs_sh pm path "$GS_PACKAGE" 2>/dev/null | grep '^package:' >/dev/null; }

set_crash_mark() {
  local now
  now=$(gs_device_time)
  [ -n "$now" ] && printf '%s\n' "$now" >"$GS_STATE/crash-mark-$GS_SERIAL"
}

do_install() {
  local apk="" fresh="" low="" orig out rc pkg
  while [ $# -gt 0 ]; do
    case $1 in
      --fresh) fresh=1; shift;;
      --low-storage) low=1; shift;;
      release | qa | debug) apk="$GS_OUT/apk/app-$1-e2e.apk"; shift;;
      *) apk=$1; shift;;
    esac
  done
  [ -n "$apk" ] || gs_die "install <apk|release|qa|debug>"
  [ -f "$apk" ] || gs_die "no APK at $apk (build it with build-test-apk.sh)"
  apk=$(gs_path "$apk")
  # The qa build has its own app id; every later command acts on what was installed last.
  pkg=$(gs_apk_package "$apk")
  if [ -z "$pkg" ]; then
    case $(basename "$apk") in *qa*) pkg=com.grocerysync.app.qa;; *) pkg=com.grocerysync.app;; esac
  fi
  GS_PACKAGE=$pkg
  gs_resolve_serial
  if [ -n "$fresh" ] && installed; then
    gs_log "uninstalling $GS_PACKAGE first (--fresh)"
    gs_adb uninstall "$GS_PACKAGE" >/dev/null
  fi
  if [ -n "$low" ]; then
    orig=$(gs_sh settings get global sys_storage_threshold_percentage)
    gs_adb shell settings put global sys_storage_threshold_percentage 2
  fi
  gs_log "installing $(basename "$apk") on $GS_SERIAL"
  out=$(gs_adb install -r "$apk" 2>&1 | tr -d '\r')
  rc=$?
  if [ -n "$low" ]; then
    if [ -z "$orig" ] || [ "$orig" = null ]; then
      gs_adb shell settings delete global sys_storage_threshold_percentage >/dev/null
    else
      gs_adb shell settings put global sys_storage_threshold_percentage "$orig"
    fi
  fi
  if [ $rc -ne 0 ] || ! grep -q '^Success' <<<"$out"; then
    printf '%s\n' "$out" | tail -n 3 >&2
    case $out in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *signatures*do\ not\ match*)
        gs_die "the installed app is signed with another key (debug vs release?): re-run with --fresh (loses its data)";;
      *INSTALL_FAILED_VERSION_DOWNGRADE*) gs_die "the installed app has a higher versionCode: re-run with --fresh";;
      *INSUFFICIENT_STORAGE*) gs_die "the emulator's /data is (nearly) full: re-run with --low-storage, or uninstall other apps";;
      *INSTALL_PARSE_FAILED_NO_CERTIFICATES* | *no\ signatures*) gs_die "the APK is unsigned: see build-test-apk.sh";;
      *) gs_die "install failed";;
    esac
  fi
  printf '%s\n' "$GS_PACKAGE" >"$GS_STATE/app-package"
  set_crash_mark
  gs_log "installed: $(app_info_line)"
}

app_info_line() {
  local dump
  dump=$(gs_sh dumpsys package "$GS_PACKAGE")
  printf '%s versionName=%s versionCode=%s%s\n' "$GS_PACKAGE" \
    "$(printf '%s\n' "$dump" | sed -n 's/^ *versionName=//p' | head -n 1)" \
    "$(printf '%s\n' "$dump" | sed -n 's/^ *versionCode=\([0-9]*\).*/\1/p' | head -n 1)" \
    "$(grep -q 'pkgFlags=\[.*DEBUGGABLE' <<<"$dump" && echo ' (debuggable)')"
}

do_launch() {
  local out
  out=$(gs_sh am start -W -n "$GS_PACKAGE/$GS_ACTIVITY")
  grep -q 'Status: ok' <<<"$out" || { printf '%s\n' "$out" >&2; gs_die "could not start $GS_PACKAGE"; }
  gs_log "launched ($(printf '%s\n' "$out" | sed -n 's/^TotalTime: /TotalTime /p') ms)"
}

# tap_button <text>: scrolls until the button with that text is on the screen, then taps its centre. The scroll
# keeps it out of the bottom fifth, so the line where Test connection shows its result, under it, is shown too.
tap_button() {
  local hit
  hit=$(gs_ui_scroll_to text "$1") || { gs_log "not found after scrolling: text \"$1\""; return 1; }
  set -- ${hit%%$'\t'*}
  gs_ui_tap "$1" "$2"
  gs_log "tapped ${hit#*$'\t'} at $1,$2"
}

do_configure() {
  local name=Emu url="" tok="" port="" test=1 env
  while [ $# -gt 0 ]; do
    case $1 in
      --name) name=${2?}; shift 2;;
      --url) url=${2:?}; shift 2;;
      --token) tok=${2:?}; shift 2;;
      --port) port=${2:?}; shift 2;;
      --no-test) test=""; shift;;
      *) gs_die "configure: unknown option $1";;
    esac
  done
  if [ -z "$port" ]; then
    port=${MOCK_PORT:-$(cat "$GS_STATE/mock-last-port" 2>/dev/null | tr -d '\r\n')}
    port=${port:-8787}
  fi
  env="$GS_STATE/mock-$port.env"
  [ -n "$url" ] || url="http://10.0.2.2:$port/macros/s/TEST/exec"
  if [ -z "$tok" ]; then
    tok=$(gs_kv_get "$env" token)
    [ -n "$tok" ] || gs_die "no token: start the mock with mock.sh (port $port) or pass --token"
  fi
  gs_resolve_serial
  installed || gs_die "$GS_PACKAGE is not installed (app.sh install ...)"
  gs_adb shell am force-stop "$GS_PACKAGE"
  do_launch
  # From 1.4 Settings is in the app bar's ⋮ menu (More options); before, it was an icon of its own.
  local hit label
  hit=$(gs_ui_wait desc-re '^(More options|Settings)$' 0 20) || gs_die "the More options (or Settings) button did not appear"
  label=$(printf '%s' "$hit" | cut -f3)
  gs_ui_tap ${hit%%$'\t'*}
  gs_log "tapped $label"
  if [ "$label" = '"More options"' ]; then
    gs_ui_tap_match text Settings 0 10 || gs_die "no Settings in the More options menu"
  fi
  # Settings is taller than the screen: from 1.6 it starts with "This phone" (Your name, Language, Simple mode),
  # so on a phone the Web app URL field and the Test connection and Save buttons are below the screen when it
  # opens. Wait for Your name, which is at the top (Web app URL too, for a build without it), and scroll to each
  # button before tapping it: gs_ui_tap_match only finds what is on the screen. gs_ui_set_field scrolls to its
  # field itself.
  gs_ui_wait text-re '^(Your name|Web app URL)$' 0 15 >/dev/null || gs_die "the Settings screen did not open"
  gs_ui_set_field "Your name" "$name" || gs_die "could not set Your name"
  gs_ui_set_field "Web app URL" "$url" || gs_die "could not set the Web app URL"
  gs_ui_set_field "Secret token" "$tok" || gs_die "could not set the Secret token"
  gs_ui_hide_keyboard
  if [ -n "$test" ]; then
    tap_button "Test connection" || gs_die "no Test connection button"
    if hit=$(gs_ui_wait text-re '^Connected to ' 0 30); then
      gs_log "test connection: $(printf '%s' "$hit" | cut -f3)"
    else
      gs_log "test connection did not succeed; the screen says:"
      gs_uixml texts "$GS_UI_LAST" | grep -v -E '^(You|Google Sheet|Settings|Back)$' | head -n 25 >&2
      gs_ui_shot configure-failed >&2
      exit 1
    fi
  fi
  tap_button Save || gs_die "no Save button"
  if hit=$(gs_ui_wait text-re '^Live' 0 30); then
    gs_log "configured: status \"$(printf '%s' "$hit" | cut -f3 | tr -d '"')\""
  else
    gs_log "saved, but the status line never said Live:"
    gs_uixml find "$GS_UI_LAST" any-re '^(Connecting|Offline|Not syncing|Not connected)' >&2
    gs_ui_shot configure-not-live >&2
    exit 1
  fi
}

case $cmd in
  install) do_install "$@";;
  uninstall)
    if installed; then gs_adb uninstall "$GS_PACKAGE"; else gs_log "$GS_PACKAGE is not installed"; fi;;
  launch) do_launch;;
  stop) gs_adb shell am force-stop "$GS_PACKAGE" && gs_log "stopped";;
  restart) gs_adb shell am force-stop "$GS_PACKAGE"; do_launch;;
  clear-data) gs_sh pm clear "$GS_PACKAGE";;
  info)
    installed || gs_die "$GS_PACKAGE is not installed (the tools act on it; GS_PACKAGE=... for another)"
    app_info_line
    gs_sh dumpsys package "$GS_PACKAGE" | grep -E '^ *(firstInstallTime|lastUpdateTime)=' | sed 's/^ *//';;
  configure) do_configure "$@";;
  pull-db)
    dir=$(gs_path "${1:-$GS_OUT/db}")
    mkdir -p "$dir"
    gs_adb shell run-as "$GS_PACKAGE" true 2>/dev/null || gs_die "run-as failed: only debug builds can be read"
    for f in $(gs_sh run-as "$GS_PACKAGE" ls databases); do
      gs_adb exec-out run-as "$GS_PACKAGE" cat "databases/$f" >"$dir/$f" && echo "$dir/$f"
    done;;
  *) gs_log "unknown command: $cmd"; usage 2;;
esac
