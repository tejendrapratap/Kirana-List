#!/usr/bin/env bash
# ui.sh - drive and inspect the emulator screen with adb + uiautomator (no app changes needed).
#
# Usage: ui.sh [-s emulator-NNNN] <command> [args]
# Finding things (a fresh uiautomator dump each time, about 1-2 s; matches visible nodes only):
#   dump [NAME]                        list the screen's nodes: class, text or content-desc, bounds, flags;
#                                      saved as out/xml/last.xml (and NAME.xml)
#   texts                              every text / content-desc on screen, one per line (for grep)
#   find [MATCH] TEXT                  print "x y<TAB>bounds<TAB>label<TAB>class"; exit 1 when absent
#   wait-text [MATCH] [-t SECS] [--gone] TEXT    wait (default 15 s) until shown, or gone; exit 1 on timeout
#   tap-text [MATCH] [-t SECS] TEXT    wait for a node whose text matches, tap its centre
#   tap-desc [MATCH] [-t SECS] DESC    the same for a content description (icon buttons: "Settings",
#                                      "Add item", "Search items", "Back", "Got it: Milk", "Need Tea", "1 more Eggs")
#   scroll-to [MATCH] [-n MAX] TEXT    swipe up (then down) until it is visible
#     MATCH: -d match content-desc only, -a text or content-desc (find / wait-text / scroll-to look at
#            text only by default), -r TEXT is a regex (e.g. -r '^Live'), -p TEXT is a substring,
#            -i N the N-th match (0 = first)
# Acting:
#   tap X Y                            tap at pixels
#   type TEXT                          type into the focused field (printable ASCII only)
#   set-field LABEL VALUE              tap the text field labelled LABEL ("Your name", "Web app URL",
#                                      "Secret token", "Item"), clear it, type VALUE, check it
#   key KEY...                         adb input keyevent (BACK, ENTER, TAB, DEL, MOVE_END, or numbers)
#   hide-keyboard                      close the soft keyboard if open (never sends BACK otherwise)
#   swipe up|down|left|right           up = scroll the content up; left = next page (To buy -> All items)
#   shot NAME [DIR]                    screenshot to out/shots/NAME.png (or DIR); prints the path
# Device settings (the first change of each remembers the original; "reset" puts them all back):
#   font-scale F                       e.g. 1.3 or 2.0 (Settings > Display > Font size)
#   screen-size WxH [DPI] | reset      wm size / wm density, e.g. 1080x1920 480 (a 640x360 dp phone)
#   rotate portrait|landscape|reverse-landscape|auto
#   dark on|off                        night mode
#   animations off|on                  window / transition / animator scales
#   network off|on                     airplane mode (the app goes offline; the mock keeps running)
#   reset                              restore everything above to how it was
# Logs:
#   logcat start [FILE] | stop         record logcat (threadtime) to FILE (default out/logs/logcat.txt)
#   logcat mark                        remember "now" for crashes (app.sh install does this too)
#   logcat grep REGEX [FILE]           grep the recording (or the device's current buffer when none)
#   crashes [--all]                    FATAL EXCEPTION / ANR of the app since the mark (or --all since
#                                      boot); prints them and exits 1 when there are any
#
# Every adb call goes to one emulator (EMU_SERIAL / emu.sh); other serials are refused.
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

# Parses MATCH options for a query; sets field, kind, index, timeout, gone, max, query.
parse_match() {
  field=$1; shift
  kind="" index=0 timeout=15 gone="" max=6 query=""
  while [ $# -gt 0 ]; do
    case $1 in
      -d) field=desc; shift;;
      -a) field=any; shift;;
      -r) kind=-re; shift;;
      -p) kind=-has; shift;;
      -i) index=${2:?-i needs a number}; shift 2;;
      -t) timeout=${2:?-t needs seconds}; shift 2;;
      -n) max=${2:?-n needs a number}; shift 2;;
      --gone) gone=1; shift;;
      --) shift; [ $# -eq 1 ] || gs_die "one text after --"; [ -z "$query" ] || gs_die "two texts: $query / $1"; query=$1; shift;;
      -*) gs_die "unknown option $1";;
      *) [ -z "$query" ] || gs_die "two texts: \"$query\" and \"$1\" (quote a text with spaces)"; query=$1; shift;;
    esac
  done
  [ -n "$query" ] || gs_die "$cmd: what to look for?"
  how="$field$kind"
}

# ---- remembered device settings ("reset" restores them)
ORIG="" # set after the serial is resolved
orig_file() { gs_resolve_serial; ORIG="$GS_STATE/display-orig-$GS_SERIAL"; }
remember() {  # remember <key> <current value>
  orig_file
  grep -q "^$1=" "$ORIG" 2>/dev/null || printf '%s=%s\n' "$1" "$2" >>"$ORIG"
}
setting() { gs_sh settings get "$1" "$2"; }
put_or_delete() {  # put_or_delete <namespace> <key> <value|null>
  if [ -z "$3" ] || [ "$3" = null ]; then gs_adb shell settings delete "$1" "$2" >/dev/null; else gs_adb shell settings put "$1" "$2" "$3"; fi
}
wm_override() {  # wm_override size|density: the override, or "reset" when there is none
  local v
  v=$(gs_sh wm "$1" | sed -n "s/^Override $1: //p" | head -n 1)
  printf '%s\n' "${v:-reset}"
}

do_reset() {
  local key value
  orig_file
  if [ ! -f "$ORIG" ]; then
    echo "nothing to reset on $GS_SERIAL"
    return 0
  fi
  # The file is read on fd 3: adb shell reads stdin, and on fd 0 it would swallow the lines after the first.
  while IFS='=' read -r key value <&3; do
    case $key in
      font_scale) put_or_delete system font_scale "$value";;
      user_rotation) put_or_delete system user_rotation "$value";;
      accelerometer_rotation) put_or_delete system accelerometer_rotation "$value";;
      wm_size) gs_adb shell wm size "$value";;
      wm_density) gs_adb shell wm density "$value";;
      night) gs_adb shell cmd uimode night "$value" >/dev/null;;
      window_animation_scale | transition_animation_scale | animator_duration_scale) put_or_delete global "$key" "$value";;
      airplane)
        if [ "$value" = 1 ]; then gs_adb shell cmd connectivity airplane-mode enable; else gs_adb shell cmd connectivity airplane-mode disable; fi;;
    esac
    echo "restored $key=$value"
  done 3<"$ORIG"
  rm -f "$ORIG"
}

LOGCAT_PID="$GS_STATE/logcat.pid"

# Lines of the app's crashes in a threadtime log on stdin: every line of a process (3rd field = pid) that
# logged "Process: <package>, PID: n" (the second line of a FATAL EXCEPTION).
app_crash_lines() {
  awk -v pkg="$GS_PACKAGE" '
    { line[NR] = $0; pid[NR] = $3 }
    index($0, "Process: " pkg ",") { want[$3] = 1 }
    END { for (i = 1; i <= NR; i++) if (pid[i] in want) print line[i] }'
}

# ANR lines of the app on stdin: "ANR in <package>" (not a longer id such as <package>.qa) or an
# am_anr event "[user,pid,<package>,flags,reason]". Plain string matching, no regex escaping.
app_anr_lines() {
  awk -v pkg="$GS_PACKAGE" '
    {
      i = index($0, "ANR in " pkg)
      if (i > 0) {
        next_char = substr($0, i + length("ANR in " pkg), 1)
        if (next_char !~ /[.A-Za-z0-9_]/) { print; next }
      }
      if (index($0, "am_anr") > 0 && index($0, "," pkg ",") > 0) print
    }'
}

do_crashes() {
  local all="" mark="" filter out
  [ "${1:-}" = --all ] && all=1
  gs_resolve_serial
  [ -z "$all" ] && mark=$(cat "$GS_STATE/crash-mark-$GS_SERIAL" 2>/dev/null | tr -d '\r\n')
  filter=""
  [ -n "$mark" ] && filter=" -T $(gs_shq "$mark")"
  out=$( {
    gs_sh "logcat -d -b crash -v threadtime$filter" | app_crash_lines
    gs_sh "logcat -d -b main,system,events -v threadtime$filter" | app_anr_lines
  } 2>/dev/null)
  if [ -n "$out" ]; then
    printf '%s\n' "$out" | head -n 80
    gs_log "crashes or ANRs of $GS_PACKAGE found${mark:+ since $mark}"
    return 1
  fi
  echo "no crash or ANR of $GS_PACKAGE${mark:+ since $mark}${all:+ (whole buffer)}"
}

do_logcat() {
  local sub=${1:-} file pid
  shift || true
  case $sub in
    start)
      file=$(gs_path "${1:-$GS_OUT/logs/logcat.txt}")
      pid=$(gs_kv_get "$LOGCAT_PID" pid)
      if [ -n "$pid" ] && gs_alive "$pid" "$(gs_kv_get "$LOGCAT_PID" winpid)"; then
        echo "already recording to $(gs_kv_get "$LOGCAT_PID" file)"
        return 0
      fi
      gs_resolve_serial
      gs_check_serial "$GS_SERIAL"
      gs_init_sdk
      mkdir -p "$(dirname "$file")"
      # The one adb call not made through gs_adb, because it runs in the background (same -s serial).
      # -T 1: start at the newest line instead of replaying the whole buffer.
      nohup "$ADB" -s "$GS_SERIAL" logcat -v threadtime -T 1 >"$file" 2>&1 </dev/null &
      pid=$!
      printf 'pid=%s\nwinpid=%s\nfile=%s\nserial=%s\n' "$pid" "$(gs_winpid_of "$pid")" "$file" "$GS_SERIAL" >"$LOGCAT_PID"
      echo "recording logcat of $GS_SERIAL to $file";;
    stop)
      pid=$(gs_kv_get "$LOGCAT_PID" pid)
      [ -n "$pid" ] || { echo "no logcat recording"; return 0; }
      gs_stop_pid "$pid" "$(gs_kv_get "$LOGCAT_PID" winpid)" 3
      echo "stopped recording: $(gs_kv_get "$LOGCAT_PID" file)"
      rm -f "$LOGCAT_PID";;
    mark)
      gs_resolve_serial
      gs_device_time >"$GS_STATE/crash-mark-$GS_SERIAL"
      echo "crash mark: $(cat "$GS_STATE/crash-mark-$GS_SERIAL")";;
    grep)
      [ -n "${1:-}" ] || gs_die "logcat grep REGEX [FILE]"
      file=${2:-$(gs_kv_get "$LOGCAT_PID" file)}
      [ -z "$file" ] && [ -f "$GS_OUT/logs/logcat.txt" ] && file="$GS_OUT/logs/logcat.txt"
      if [ -n "$file" ]; then grep -E -- "$1" "$file"; else gs_sh logcat -d -v threadtime | grep -E -- "$1"; fi;;
    *) gs_die "logcat start [FILE] | stop | mark | grep REGEX [FILE]";;
  esac
}

case $cmd in
  dump)
    gs_ui_dump "${1:-}" || exit 1
    gs_uixml list "$GS_UI_LAST" "$(gs_density)";;
  texts) gs_ui_dump && gs_uixml texts "$GS_UI_LAST";;
  find)
    parse_match text "$@"
    gs_ui_find "$how" "$query" "$index";;
  wait-text)
    parse_match text "$@"
    if gs_ui_wait "$how" "$query" "$index" "$timeout" "$gone"; then
      if [ -n "$gone" ]; then echo "gone: $query"; fi
    else
      gs_log "timed out after $timeout s waiting for${gone:+ the end of} $how \"$query\""
      exit 1
    fi;;
  tap-text | tap-desc | tap-any)
    f=text; [ "$cmd" = tap-desc ] && f=desc; [ "$cmd" = tap-any ] && f=any
    parse_match "$f" "$@"
    gs_ui_tap_match "$how" "$query" "$index" "$timeout";;
  scroll-to)
    parse_match text "$@"
    gs_ui_scroll_to "$how" "$query" "$max" || { gs_log "not found after scrolling: $query"; exit 1; };;
  tap)
    [[ "${1:-}" =~ ^[0-9]+$ && "${2:-}" =~ ^[0-9]+$ ]] || gs_die "tap X Y (pixels)"
    gs_ui_tap "$1" "$2";;
  type) [ $# -eq 1 ] || gs_die "type TEXT (quote it)"; gs_ui_type "$1";;
  set-field) [ $# -eq 2 ] || gs_die "set-field LABEL VALUE"; gs_ui_set_field "$1" "$2";;
  key)
    [ $# -gt 0 ] || gs_die "key KEY..."
    keys=()
    for k in "$@"; do
      [[ "$k" =~ ^[A-Za-z0-9_]+$ ]] || gs_die "bad key name: $k"
      if [[ "$k" =~ ^[0-9]+$ || "$k" == KEYCODE_* ]]; then keys+=("$k"); else keys+=("KEYCODE_${k^^}"); fi
    done
    gs_adb shell input keyevent "${keys[@]}";;
  back) gs_adb shell input keyevent KEYCODE_BACK;;
  hide-keyboard) gs_ui_hide_keyboard;;
  swipe) gs_ui_swipe "${1:-}";;
  shot) [ -n "${1:-}" ] || gs_die "shot NAME [DIR]"; gs_ui_shot "$1" "${2:-}";;
  font-scale)
    [[ "${1:-}" =~ ^[0-9]+(\.[0-9]+)?$ ]] || gs_die "font-scale F (e.g. 1.3)"
    remember font_scale "$(setting system font_scale)"
    gs_adb shell settings put system font_scale "$1" && echo "font_scale=$1";;
  screen-size)
    [ -n "${1:-}" ] || gs_die "screen-size WxH [DPI] | reset"
    if [ "$1" = reset ]; then
      gs_adb shell wm size reset && gs_adb shell wm density reset && echo "screen size and density reset"
    else
      [[ "$1" =~ ^[0-9]+x[0-9]+$ ]] || gs_die "screen-size WxH, e.g. 1080x1920"
      remember wm_size "$(wm_override size)"
      gs_adb shell wm size "$1"
      if [ -n "${2:-}" ]; then
        [[ "$2" =~ ^[0-9]+$ ]] || gs_die "DPI must be a number"
        remember wm_density "$(wm_override density)"
        gs_adb shell wm density "$2"
      fi
      echo "screen $1${2:+ at $2 dpi}"
    fi;;
  rotate)
    remember accelerometer_rotation "$(setting system accelerometer_rotation)"
    remember user_rotation "$(setting system user_rotation)"
    case ${1:-} in
      portrait) r=0;; landscape) r=1;; reverse-landscape) r=3;;
      auto) gs_adb shell settings put system accelerometer_rotation 1; echo "auto-rotate on"; exit 0;;
      *) gs_die "rotate portrait|landscape|reverse-landscape|auto";;
    esac
    gs_adb shell settings put system accelerometer_rotation 0
    gs_adb shell settings put system user_rotation "$r" && echo "rotation $1";;
  dark)
    case ${1:-} in on) v=yes;; off) v=no;; *) gs_die "dark on|off";; esac
    remember night "$(gs_sh cmd uimode night | sed -n 's/^Night mode: //p')"
    gs_adb shell cmd uimode night "$v";;
  animations)
    case ${1:-} in off) v=0;; on) v=1;; *) gs_die "animations off|on";; esac
    for k in window_animation_scale transition_animation_scale animator_duration_scale; do
      remember "$k" "$(setting global "$k")"
      gs_adb shell settings put global "$k" "$v"
    done
    echo "animations $1";;
  network)
    case ${1:-} in off) v=enable;; on) v=disable;; *) gs_die "network off|on";; esac
    remember airplane "$(setting global airplane_mode_on)"
    gs_adb shell cmd connectivity airplane-mode "$v" && echo "airplane mode ${v}d";;
  reset) do_reset;;
  logcat) do_logcat "$@";;
  crashes) do_crashes "$@";;
  *) gs_log "unknown command: $cmd"; usage 2;;
esac
