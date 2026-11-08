#!/usr/bin/env bash
# fake-adb.sh - stand-in for adb used by selftest.sh (GS_ADB=.../fake-adb.sh). No real device is touched.
# State lives in $FAKE_DIR:
#   devices/<serial>         its state (device / offline); absent = adb does not know it
#   devices/<serial>.pid     pid of fake-emulator.sh serving it (emu kill stops it)
#   screens                  dump files, one per line: each uiautomator dump serves the first line and
#                            drops it while more than one is left
#   ime                      "true" while the soft keyboard is shown (BACK hides it)
#   install-result           what "adb install" prints (default "Success")
#   logcat-crash.txt         the crash buffer;  logcat-main.txt  the main/system/events buffers
#   calls.log                every call, one per line; a call without "-s emulator-NNNN" is logged as
#                            VIOLATION and fails with exit 99
#   typed.log                the text each "input text" would type (after the device shell's quoting)
#   instrument-output        what "am instrument" prints (uitest.sh)
set -u
D=${FAKE_DIR:?FAKE_DIR is not set}
mkdir -p "$D/devices" "$D/settings"
echo "$*" >>"$D/calls.log"

if [ "${1:-}" != -s ] || ! [[ "${2:-}" =~ ^emulator-[0-9]+$ ]]; then
  echo "VIOLATION: $*" >>"$D/calls.log"
  echo "fake-adb: call without -s emulator-NNNN: $*" >&2
  exit 99
fi
serial=$2
shift 2
case "${1:-}" in kill-server | start-server | disconnect | reconnect | connect | devices)
  echo "VIOLATION: $*" >>"$D/calls.log"; exit 99;;
esac

state=$(cat "$D/devices/$serial" 2>/dev/null)
if [ -z "$state" ]; then
  echo "error: device '$serial' not found" >&2
  exit 1
fi

sub=$1
shift
# Like adb (adb shell forwards stdin to the device): read whatever stdin holds, unless it is a terminal.
[ -t 0 ] || cat >/dev/null
case $sub in
  get-state) echo "$state";;
  emu)
    if [ "$*" = kill ]; then
      pid=$(cat "$D/devices/$serial.pid" 2>/dev/null)
      rm -f "$D/devices/$serial" "$D/devices/$serial.pid"
      [ -n "$pid" ] && kill "$pid" 2>/dev/null
      echo "OK: killing emulator, bye bye"
    fi;;
  install)
    cat "$D/install-result" 2>/dev/null || echo Success;;
  uninstall) rm -f "$D/installed"; echo Success;;
  exec-out)
    case "$*" in
      "cat /data/local/tmp/gs-e2e-ui.xml") cat "$D/remote-ui.xml";;
      "screencap -p") printf '\211PNG\r\n\032\nfake';;
      *) ;;
    esac;;
  logcat) ;;  # background recording: nothing to print
  shell)
    cmdline="$*"
    case $cmdline in
      "getprop sys.boot_completed") cat "$D/devices/$serial.boot" 2>/dev/null || echo 1;;
      "getprop ro.build.version.sdk") cat "$D/devices/$serial.sdk" 2>/dev/null || echo 36;;
      "pm path android") echo "package:/system/framework/framework-res.apk";;
      "pm path com.grocerysync.app") [ -f "$D/installed" ] && echo "package:/data/app/base.apk";;
      "cat /proc/sys/kernel/random/boot_id") cat "$D/devices/$serial.bootid" 2>/dev/null || echo boot-1;;
      "uiautomator dump /data/local/tmp/gs-e2e-ui.xml")
        first=$(head -n 1 "$D/screens")
        cp "$first" "$D/remote-ui.xml"
        [ "$(grep -c . "$D/screens")" -gt 1 ] && sed -i 1d "$D/screens"
        echo "UI hierchary dumped to: /data/local/tmp/gs-e2e-ui.xml";;
      # As long as a real one (thousands of lines), so "| grep -q" on it would fail under pipefail (SIGPIPE).
      "dumpsys input_method") echo "  mInputShown=$(cat "$D/ime" 2>/dev/null || echo false)"; seq 1 20000 | sed 's/^/  mFiller=/';;
      "wm size") echo "Physical size: 1080x2424";;
      "wm density") echo "Physical density: 420";;
      "input keyevent KEYCODE_BACK") echo false >"$D/ime";;
      "input tap "*) echo true >"$D/ime";;
      "input text "*)
        # Evaluate the argument the way the device shell would, then undo input's %s = space.
        eval "set -- ${cmdline#input text }"
        printf '%s\n' "${1//%s/ }" >>"$D/typed.log";;
      # Like the device's date: the format has a space, so it must arrive as one quoted word.
      "date '+%m-%d %H:%M:%S.000'") echo "11-08 10:00:00.000";;
      date*) echo 'date: Max 1 argument (see "date --help")' >&2;;
      "am instrument "*)
        # Like Android 16: --no-window-animation leaves an unset animator_duration_scale at 0.0.
        case $cmdline in *--no-window-animation*) echo 0.0 >"$D/settings/global.animator_duration_scale";; esac
        cat "$D/instrument-output" 2>/dev/null;;
      "settings get "*) set -- $cmdline; cat "$D/settings/$3.$4" 2>/dev/null || echo null;;
      "settings put "*) set -- $cmdline; echo "$5" >"$D/settings/$3.$4";;
      "settings delete "*) set -- $cmdline; rm -f "$D/settings/$3.$4";;
      "logcat -d -b crash"*) cat "$D/logcat-crash.txt" 2>/dev/null;;
      "logcat -d -b main"*) cat "$D/logcat-main.txt" 2>/dev/null;;
      "am start -W -n "*) printf 'Status: ok\nTotalTime: 812\n';;
      *) ;;
    esac;;
  *) ;;
esac
exit 0
