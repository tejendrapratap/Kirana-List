# shellcheck shell=bash
# lib.sh - shared helpers for the Kirana List emulator tools (emu.sh, mock.sh, build-test-apk.sh,
# app.sh, ui.sh). Sourced by them, never run directly. Bash 4+: Git Bash on Windows, Linux or macOS.
#
# Safety rules, enforced here for every tool:
#   - every adb call goes through gs_adb / gs_adb_to, which always pass "-s <serial>" and refuse any
#     serial that is not emulator-<port>, so a phone plugged in over USB is never touched;
#   - adb kill-server, start-server, connect, disconnect, reconnect, tcpip, usb, root, unroot, remount
#     and pair are refused: other testers may share the adb server and the emulator;
#   - the tools stop only what they started themselves (pid files under $GS_STATE).
#
# Environment (all optional):
#   EMU_SERIAL   the emulator to talk to (emulator-NNNN). Default: the one emu.sh last started or
#                reused, else the only emulator running (probed with "adb -s emulator-<port> get-state").
#   ANDROID_SDK  Android SDK folder. Default: $ANDROID_HOME, $ANDROID_SDK_ROOT, %LOCALAPPDATA%/Android/Sdk,
#                ~/Library/Android/sdk, ~/Android/Sdk, or sdk.dir of android/local.properties.
#   GS_REPO      the grocery-sync checkout. Default: two folders above this file (tools/e2e/../..), else
#                ~/workspace/grocery-sync.
#   GS_E2E_HOME  state and output. Default: $TMPDIR/grocery-sync-e2e, holding
#                  state/  pid files, the chosen serial, mock settings, original display settings
#                  out/    xml/ (UI dumps), shots/ (screenshots), logs/, apk/ (test APKs)
#   GS_E2E_OUT   output folder instead of $GS_E2E_HOME/out.
#   GS_ADB, GS_EMULATOR   adb / emulator executables (default: from the SDK). Used by the offline self-test.

# Git Bash would turn device paths such as /sdcard/x into C:/Program Files/Git/sdcard/x.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

GS_TOOLS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Full class name: the qa build's app id (com.grocerysync.app.qa) differs from the code's package.
GS_ACTIVITY=${GS_ACTIVITY:-com.grocerysync.app.MainActivity}

gs_log() { printf '%s\n' "$*" >&2; }
gs_die() { printf 'error: %s\n' "$*" >&2; exit 1; }
gs_have() { command -v "$1" >/dev/null 2>&1; }

# A path that native Windows programs (adb.exe, node.exe, emulator.exe) understand: C:/x/y on Windows.
gs_path() {
  if gs_have cygpath; then cygpath -m -- "$1"; else printf '%s\n' "$1"; fi
}

GS_TOOLS=$(gs_path "$GS_TOOLS")
GS_E2E_HOME=$(gs_path "${GS_E2E_HOME:-${TMPDIR:-/tmp}/grocery-sync-e2e}")
GS_STATE="$GS_E2E_HOME/state"
GS_OUT=$(gs_path "${GS_E2E_OUT:-$GS_E2E_HOME/out}")
mkdir -p "$GS_STATE" "$GS_OUT" || gs_die "cannot create $GS_E2E_HOME"

# The app the tools act on: $GS_PACKAGE, else the one "app.sh install" installed last (e.g. the qa build,
# com.grocerysync.app.qa), else the real app.
if [ -z "${GS_PACKAGE:-}" ] && [ -f "$GS_STATE/app-package" ]; then GS_PACKAGE=$(tr -d '\r\n' <"$GS_STATE/app-package"); fi
GS_PACKAGE=${GS_PACKAGE:-com.grocerysync.app}
[[ "$GS_PACKAGE" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] || gs_die "bad package name: $GS_PACKAGE"

gs_repo() {
  local c
  for c in "${GS_REPO:-}" "$GS_TOOLS/../.." "$HOME/workspace/grocery-sync"; do
    [ -n "$c" ] && [ -f "$c/apps-script/test/mock-server.js" ] && [ -d "$c/android" ] && { gs_path "$(cd "$c" && pwd)"; return 0; }
  done
  gs_die "cannot find the grocery-sync checkout: set GS_REPO"
}

gs_sdk() {
  local c props
  for c in "${ANDROID_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:+$LOCALAPPDATA/Android/Sdk}" \
    "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$c" ] && [ -d "$c/platform-tools" ] && { gs_path "$c"; return 0; }
  done
  props="${GS_REPO:-$GS_TOOLS/../..}/android/local.properties"
  if [ -f "$props" ]; then
    c=$(sed -n 's/^sdk\.dir=//p' "$props" | tr -d '\r' | sed 's/\\:/:/g; s/\\\\/\//g')
    [ -n "$c" ] && [ -d "$c/platform-tools" ] && { gs_path "$c"; return 0; }
  fi
  gs_die "cannot find the Android SDK: set ANDROID_SDK"
}

# Tools from the SDK, resolved on first use (so mock.sh works without an SDK).
gs_init_sdk() {
  [ -n "${GS_SDK:-}" ] && return 0
  GS_SDK=$(gs_sdk) || exit 1
  local exe=""
  [ -f "$GS_SDK/platform-tools/adb.exe" ] && exe=.exe
  ADB=${GS_ADB:-$GS_SDK/platform-tools/adb$exe}
  EMULATOR=${GS_EMULATOR:-$GS_SDK/emulator/emulator$exe}
  [ -x "$ADB" ] || [ -f "$ADB" ] || gs_die "adb not found at $ADB"
}

# aapt2 of the newest build-tools (empty when there is none).
gs_aapt2() {
  local bt
  gs_init_sdk
  bt=$(ls -d "$GS_SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)
  bt=${bt%/}
  if [ -f "$bt/aapt2.exe" ]; then printf '%s\n' "$bt/aapt2.exe"; elif [ -f "$bt/aapt2" ]; then printf '%s\n' "$bt/aapt2"; fi
}

# The application id inside an APK (empty when aapt2 can't tell).
gs_apk_package() {
  local aapt2
  aapt2=$(gs_aapt2)
  [ -n "$aapt2" ] || return 0
  "$aapt2" dump badging "$(gs_path "$1")" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1
}

# The JDK for Gradle: $JAVA_HOME, else Android Studio's bundled jbr. Prints its folder or fails.
gs_java_home() {
  local c java_home=${JAVA_HOME:-}
  if [ -z "$java_home" ]; then
    for c in "/c/Program Files/Android/Android Studio/jbr" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "/opt/android-studio/jbr"; do
      [ -d "$c" ] && java_home=$c && break
    done
  fi
  [ -n "$java_home" ] || gs_die "set JAVA_HOME to a JDK 17+"
  java_home=$(gs_path "$java_home")
  [ -f "$java_home/bin/java" ] || [ -f "$java_home/bin/java.exe" ] || gs_die "no java in $java_home/bin (JAVA_HOME)"
  printf '%s\n' "$java_home"
}

# gs_gradle <android dir> <log file> <gradle args...>: runs ./gradlew there with gs_java_home; output to the log.
# Build tasks only: a connected*, install* or deviceCheck task would reach every device adb knows, a phone too.
gs_gradle() {
  local dir=$1 log=$2 java_home a
  shift 2
  for a in "$@"; do
    case $a in
      *onnected* | install* | *:install* | uninstall* | *:uninstall* | *eviceCheck*)
        gs_die "refusing gradle task '$a': it runs on every connected device; these tools use one emulator";;
    esac
  done
  java_home=$(gs_java_home) || exit 1
  mkdir -p "$(dirname "$log")"
  # gradlew converts its own paths on Git Bash; give it the normal environment.
  (cd "$dir" && env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL JAVA_HOME="$java_home" \
    bash ./gradlew --console=plain "$@") >"$log" 2>&1
}

# ---------------------------------------------------------------------------------------------- adb

gs_valid_serial() { [[ "${1:-}" =~ ^emulator-[0-9]+$ ]]; }
gs_check_serial() {
  gs_valid_serial "${1:-}" || gs_die "refusing serial '${1:-}': these tools only talk to emulators (emulator-NNNN)"
}

gs_refuse_adb_command() {
  case "${1:-}" in
    kill-server | start-server | connect | disconnect | reconnect | tcpip | usb | root | unroot | remount | pair | \
      -s | -t | -d | -e | -a | -H | -P | -L | devices)
      gs_die "refusing 'adb $1': these tools only send device commands to one emulator";;
  esac
}

# gs_adb_to <serial> <adb args...>: one adb call to that emulator.
gs_adb_to() {
  local serial=$1; shift
  gs_check_serial "$serial"
  gs_refuse_adb_command "${1:-}"
  gs_init_sdk
  # Never from the caller's stdin: "adb shell" reads it, which would eat the rest of a "while read" loop's input.
  "$ADB" -s "$serial" "$@" </dev/null
}

# State of an emulator serial: device, offline, ... or empty when adb does not know it.
gs_state_of() { gs_adb_to "$1" get-state 2>/dev/null | tr -d '\r'; }

# Every emulator adb knows, as "<serial> <state>" lines. Probes the usual console ports with -s (never
# "adb devices"), so nothing but emulator-NNNN serials is ever addressed.
gs_list_emulators() {
  local port st
  for port in ${EMU_PORTS:-$(seq 5554 2 5584)}; do
    st=$(gs_state_of "emulator-$port")
    [ -n "$st" ] && printf 'emulator-%s %s\n' "$port" "$st"
  done
  return 0
}

# Resolves the emulator to use (once per process) into GS_SERIAL and exports EMU_SERIAL for child tools.
gs_resolve_serial() {
  [ -n "${GS_SERIAL:-}" ] && return 0
  local s="" saved="" online
  if [ -n "${EMU_SERIAL:-}" ]; then
    s=$EMU_SERIAL
    gs_check_serial "$s"
    [ "$(gs_state_of "$s")" = device ] || gs_die "$s is not online (EMU_SERIAL). Start one with emu.sh."
  else
    [ -f "$GS_STATE/serial" ] && saved=$(tr -d '\r\n' <"$GS_STATE/serial")
    if gs_valid_serial "$saved" && [ "$(gs_state_of "$saved")" = device ]; then
      s=$saved
    else
      online=$(gs_list_emulators | awk '$2 == "device" { print $1 }')
      case $(printf '%s' "$online" | grep -c .) in
        0) gs_die "no emulator is running: start one with emu.sh (or set EMU_SERIAL)";;
        1) s=$online;;
        *) gs_die "several emulators are running ($(echo $online)): choose one with EMU_SERIAL=emulator-NNNN or -s";;
      esac
    fi
  fi
  gs_check_serial "$s"
  GS_SERIAL=$s
  export EMU_SERIAL=$s
}

# gs_adb <adb args...>: one adb call to the resolved emulator.
gs_adb() {
  gs_resolve_serial
  gs_adb_to "$GS_SERIAL" "$@"
}

# Output of an adb shell command without carriage returns.
gs_sh() { gs_adb shell "$@" | tr -d '\r'; }

# Quotes one argument for the device's shell (adb joins shell arguments with spaces).
gs_shq() { local s=$1; printf "'%s'" "${s//\'/\'\\\'\'}"; }

# The device clock as logcat -T wants it ("11-08 10:00:00.000"). Sent as one quoted shell word: adb joins its
# arguments with spaces, so date would otherwise get "+%m-%d" and "%H:%M:%S.000" as two arguments and fail.
gs_device_time() { gs_sh "date '+%m-%d %H:%M:%S.000'"; }

# ----------------------------------------------------------------------------------------- state files

# gs_kv_get <file> <key>: the value of key=value in a state file.
gs_kv_get() { [ -f "$1" ] && sed -n "s/^$2=//p" "$1" | tail -n 1 | tr -d '\r'; }

# Windows process id of an MSYS/Cygwin pid (empty elsewhere).
gs_winpid_of() { [ -r "/proc/$1/winpid" ] && cat "/proc/$1/winpid" 2>/dev/null; }

# True while the process (pid, or winpid on Windows) is alive.
gs_alive() {
  local pid=$1 winpid=${2:-}
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && return 0
  if [ -n "$winpid" ] && gs_have tasklist; then
    tasklist /FI "PID eq $winpid" /NH 2>/dev/null | grep " $winpid " >/dev/null && return 0
  fi
  return 1
}

# Stops a process that this tool started: TERM, wait up to $3 seconds, then force (whole tree on Windows).
gs_stop_pid() {
  local pid=$1 winpid=${2:-} wait=${3:-10} i
  gs_alive "$pid" "$winpid" || return 0
  kill "$pid" 2>/dev/null
  for ((i = 0; i < wait * 4; i++)); do
    gs_alive "$pid" "$winpid" || return 0
    sleep 0.25
  done
  if [ -n "$winpid" ] && gs_have taskkill; then
    taskkill /PID "$winpid" /T /F >/dev/null 2>&1
  else
    kill -9 "$pid" 2>/dev/null
  fi
  for ((i = 0; i < 20; i++)); do
    gs_alive "$pid" "$winpid" || return 0
    sleep 0.25
  done
  return 1
}

# True when something accepts TCP connections on 127.0.0.1:<port>.
gs_port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# ------------------------------------------------------------------------------------------------- UI

GS_UI_REMOTE=/data/local/tmp/gs-e2e-ui.xml
GS_UI_LAST="$GS_OUT/xml/last.xml"

gs_node() { node "$@"; }
gs_uixml() { node "$GS_TOOLS/uixml.js" "$@"; }

# gs_ui_dump [name]: saves the current UI tree to $GS_OUT/xml/last.xml (and <name>.xml).
gs_ui_dump() {
  local name=${1:-} try out
  mkdir -p "$GS_OUT/xml"
  for try in 1 2 3; do
    out=$(gs_sh uiautomator dump "$GS_UI_REMOTE" 2>&1)
    if grep -q 'dumped to' <<<"$out"; then
      gs_adb exec-out cat "$GS_UI_REMOTE" >"$GS_UI_LAST" 2>/dev/null
      if grep -q '<hierarchy' "$GS_UI_LAST" 2>/dev/null; then
        [ -n "$name" ] && cp "$GS_UI_LAST" "$GS_OUT/xml/$name.xml"
        return 0
      fi
    fi
    # "could not get idle state" happens while something animates; try again.
    sleep 0.5
  done
  gs_log "uiautomator dump failed: ${out:-no output}"
  return 1
}

# Screen density (dpi) for dp conversions.
gs_density() {
  local d
  d=$(gs_sh wm density | sed -n 's/^Override density: //p; s/^Physical density: //p' | head -n 1)
  [ -n "$d" ] && printf '%s\n' "$d" || printf '420\n'
}

# Current screen size in px as "W H" (override if set).
gs_screen_size() {
  local out w
  out=$(gs_sh wm size)
  w=$(printf '%s\n' "$out" | sed -n 's/^Override size: //p' | head -n 1)
  [ -z "$w" ] && w=$(printf '%s\n' "$out" | sed -n 's/^Physical size: //p' | head -n 1)
  [ -n "$w" ] || w=1080x2424
  printf '%s %s\n' "${w%x*}" "${w#*x}"
}

# gs_ui_find <how> <query> [index]: from a fresh dump, prints "x y<TAB>bounds<TAB>label<TAB>class" or fails.
gs_ui_find() {
  gs_ui_dump || return 1
  gs_uixml find "$GS_UI_LAST" "$@"
}

# gs_ui_wait <how> <query> <index> <timeout-seconds> [gone]: waits until the node is shown (or gone);
# prints the match like gs_ui_find. Polls as fast as uiautomator dumps (about 1-2 s each).
gs_ui_wait() {
  local how=$1 query=$2 index=$3 timeout=$4 gone=${5:-} hit end
  end=$((SECONDS + timeout))
  while :; do
    if gs_ui_dump; then
      hit=$(gs_uixml find "$GS_UI_LAST" "$how" "$query" "$index")
      local rc=$?
      [ $rc -eq 2 ] && return 2
      if [ -z "$gone" ] && [ $rc -eq 0 ]; then printf '%s\n' "$hit"; return 0; fi
      if [ -n "$gone" ] && [ $rc -ne 0 ]; then return 0; fi
    fi
    [ $SECONDS -ge $end ] && return 1
    sleep 0.3
  done
}

gs_ui_tap() { gs_adb shell input tap "$1" "$2"; }

# gs_ui_tap_match <how> <query> <index> <timeout>: waits for the node, then taps its centre.
gs_ui_tap_match() {
  local hit
  hit=$(gs_ui_wait "$1" "$2" "$3" "$4") || { gs_log "not found within $4 s: $1 \"$2\""; return 1; }
  set -- ${hit%%$'\t'*}
  gs_ui_tap "$1" "$2"
  gs_log "tapped ${hit#*$'\t'} at $1,$2"
}

# Reads the whole dump first: "dumpsys ... | grep -q" fails under pipefail on a real device, where the dump is
# thousands of lines and grep -q exits at the first match (SIGPIPE, status 141), so it said "hidden".
gs_ui_keyboard_shown() {
  local out
  out=$(gs_sh dumpsys input_method)
  grep -Eq 'mInputShown=true|isInputViewShown=true' <<<"$out"
}

# Closes the soft keyboard if it is open. Never sends BACK otherwise (that would leave the screen).
gs_ui_hide_keyboard() {
  local i
  gs_ui_keyboard_shown || return 0
  gs_adb shell input keyevent KEYCODE_BACK
  for ((i = 0; i < 12; i++)); do
    gs_ui_keyboard_shown || return 0
    sleep 0.25
  done
  gs_log "the keyboard is still shown"
  return 1
}

# Types ASCII text into the focused field (adb "input text" cannot type other characters).
gs_ui_type() {
  local s=$1
  [ -z "$s" ] && return 0
  if LC_ALL=C grep -q '[^ -~]' <<<"$s"; then
    gs_log "ui type: only printable ASCII can be typed with adb (got non-ASCII text)"
    return 1
  fi
  # "input text" turns %s into a space, so spaces are sent as %s; the device shell gets one quoted word.
  gs_adb shell "input text $(gs_shq "${s// /%s}")"
}

# gs_ui_scroll <up|down|left|right>: one swipe across the middle of the screen. "up" moves the content up
# (shows what is further down), "left" shows the next pager page.
gs_ui_swipe() {
  local w h
  read -r w h < <(gs_screen_size)
  case $1 in
    up) gs_adb shell input swipe $((w / 2)) $((h * 70 / 100)) $((w / 2)) $((h * 35 / 100)) 300;;
    down) gs_adb shell input swipe $((w / 2)) $((h * 35 / 100)) $((w / 2)) $((h * 70 / 100)) 300;;
    left) gs_adb shell input swipe $((w * 85 / 100)) $((h / 2)) $((w * 15 / 100)) $((h / 2)) 250;;
    right) gs_adb shell input swipe $((w * 15 / 100)) $((h / 2)) $((w * 85 / 100)) $((h / 2)) 250;;
    *) gs_log "swipe: up, down, left or right"; return 2;;
  esac
}

# gs_ui_low <match>: true when a match (as gs_ui_find or uixml.js field prints it) is in the bottom fifth of the
# screen. A node cut off by the bottom of a list still matches there, and a dump does not always clip it: a
# Compose text field keeps its whole bounds, so its centre can be under the navigation bar, where a tap does not
# reach it.
gs_ui_low() {
  local w h y
  read -r w h < <(gs_screen_size)
  read -r _ y _ <<<"${1%%$'\t'*}"
  [ "${y:-0}" -gt $((h * 4 / 5)) ]
}

# gs_ui_lift <how> <query> <match>: prints the match, after one more swipe up when it is low (gs_ui_low): then a
# tap reaches it, and a line that appears under it (like Test connection's result) is on the screen too.
gs_ui_lift() {
  gs_ui_low "$3" || { printf '%s\n' "$3"; return 0; }
  gs_ui_swipe up
  gs_ui_find "$1" "$2"
}

# gs_ui_scroll_to <how> <query> [max-swipes]: swipes up until the node is visible, then back down; lifts it out of
# the bottom fifth (gs_ui_lift). Prints the match like gs_ui_find.
gs_ui_scroll_to() {
  local how=$1 query=$2 max=${3:-6} i hit
  for ((i = 0; i <= max; i++)); do
    hit=$(gs_ui_find "$how" "$query") && { gs_ui_lift "$how" "$query" "$hit"; return; }
    [ $i -lt "$max" ] && gs_ui_swipe up
  done
  for ((i = 0; i < max * 2; i++)); do
    gs_ui_swipe down
    hit=$(gs_ui_find "$how" "$query") && { gs_ui_lift "$how" "$query" "$hit"; return; }
  done
  return 1
}

# gs_ui_set_field <label> <value>: taps the text field with that label, clears it and types the value.
# Checks the result (a password field only by its length: it shows bullets).
gs_ui_set_field() {
  local label=$1 value=$2 hit x y len pw rest dels i
  gs_ui_hide_keyboard
  gs_ui_dump || return 1
  hit=$(gs_uixml field "$GS_UI_LAST" "$label")
  # A field at the bottom edge is found but may not take a tap (gs_ui_low): the focus then stays in the field
  # set before, which gets this value. Scroll to it then too.
  if [ -z "$hit" ] || gs_ui_low "$hit"; then
    gs_ui_scroll_to text "$label" 4 >/dev/null || { gs_log "no text field labelled \"$label\""; return 1; }
    gs_ui_dump || return 1
    hit=$(gs_uixml field "$GS_UI_LAST" "$label") || { gs_log "no text field labelled \"$label\""; return 1; }
  fi
  read -r x y len pw rest <<<"${hit%%$'\t'*}"
  gs_ui_tap "$x" "$y" || return 1
  # Cursor to the end, then one DEL per character (one input call).
  dels=""
  for ((i = 0; i < len + 2; i++)); do dels+=" 67"; done
  gs_adb shell input keyevent KEYCODE_MOVE_END$dels || return 1
  gs_ui_type "$value" || return 1
  gs_ui_dump || return 1
  hit=$(gs_uixml field "$GS_UI_LAST" "$label") || { gs_log "field \"$label\" vanished after typing"; return 1; }
  read -r x y len pw rest <<<"${hit%%$'\t'*}"
  if [ "$pw" = true ]; then
    [ "$len" -eq "${#value}" ] || { gs_log "field \"$label\": $len characters after typing, expected ${#value}"; return 1; }
    gs_log "set \"$label\" (${#value} characters, hidden)"
  else
    [ "${hit#*$'\t'}" = "$value" ] || { gs_log "field \"$label\" reads \"${hit#*$'\t'}\", expected \"$value\""; return 1; }
    gs_log "set \"$label\" = \"$value\""
  fi
}

# gs_ui_shot <name> [dir]: screenshot to <dir or $GS_OUT/shots>/<name>.png; prints the path.
gs_ui_shot() {
  local dir=${2:-$GS_OUT/shots} file
  mkdir -p "$dir"
  file="$dir/$1.png"
  gs_adb exec-out screencap -p >"$file" || return 1
  if [ "$(head -c 4 "$file" | tail -c 3)" != PNG ]; then
    gs_log "screencap did not return a PNG"
    return 1
  fi
  printf '%s\n' "$(gs_path "$file")"
}
