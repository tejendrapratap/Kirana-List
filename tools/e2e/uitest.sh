#!/usr/bin/env bash
# uitest.sh - build, install and run the instrumented UI tests (android/app/src/androidTest) on ONE emulator.
#
# Usage: uitest.sh [-s emulator-NNNN] [--class CLASS[#method]]... [--no-build] [--fresh] [--uninstall] [-- ARGS]
#        uitest.sh [-s emulator-NNNN] uninstall
#   Builds the debug app and its test APK (./gradlew assembleDebug assembleDebugAndroidTest), installs both on
#   the emulator (adb -s emulator-NNNN install -r -t), takes back the app's notification permission (Android 13+;
#   a test grants it, and a reinstall keeps it), runs them with "am instrument -w -r" and prints one line per
#   test, then a pass/fail summary (naming any skipped test) and the wall time of each step. Exits 0 only when
#   every test passed.
#   Use it instead of ./gradlew connectedDebugAndroidTest, which runs on EVERY device adb knows (a phone on
#   USB or Wi-Fi too): this script only talks to emulator-NNNN, and lib.sh refuses any other serial.
#   --class C        only test class C (SearchTest, or the full name), or one test with C#method; repeatable
#   --no-build       install the APKs built last instead of building
#   --fresh          uninstall the app and the test app first (needed when an app signed with another key,
#                    e.g. a release build of com.grocerysync.app, is installed on the emulator)
#   --uninstall      uninstall both when the run is over
#   uninstall        only uninstall both (com.grocerysync.app and com.grocerysync.app.test) from the emulator
#   -- ARGS          more "am instrument" arguments, e.g. -- -e notClass com.grocerysync.app.uitest.OfflineTest
# The raw instrumentation output goes to out/logs/uitest-<time>.txt; after a failure the device log of the run
# goes to out/logs/uitest-<time>-logcat.txt. The emulator comes from -s / EMU_SERIAL / emu.sh (see lib.sh).
set -uo pipefail
. "$(dirname "$0")/lib.sh"

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit "${1:-0}"; }

classes=()
build=1
fresh=""
uninstall_after=""
only_uninstall=""
extra=()
while [ $# -gt 0 ]; do
  case $1 in
    -s) EMU_SERIAL=${2:?-s needs a serial}; gs_check_serial "$EMU_SERIAL"; shift 2;;
    --class) classes+=("${2:?--class needs a class name}"); shift 2;;
    --no-build) build=""; shift;;
    --fresh) fresh=1; shift;;
    --uninstall) uninstall_after=1; shift;;
    uninstall) only_uninstall=1; shift;;
    --) shift; extra=("$@"); break;;
    -h | --help | help) usage 0;;
    *) gs_log "unknown option: $1"; usage 2;;
  esac
done

# Fallbacks; the real names are read from the test APK's manifest.
APP_PACKAGE=com.grocerysync.app
TEST_PACKAGE=com.grocerysync.app.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
CLASS_PREFIX=com.grocerysync.app.uitest.

# Resolve (and check) the emulator before anything else, so a wrong serial fails at once.
gs_resolve_serial

wall_start=$SECONDS
fmt() { printf '%dm%02ds' $(($1 / 60)) $(($1 % 60)); }

installed() { gs_sh pm path "$1" 2>/dev/null | grep '^package:' >/dev/null; }
uninstall_pkg() {
  if installed "$1"; then
    gs_adb uninstall "$1" >/dev/null && gs_log "uninstalled $1 from $GS_SERIAL"
  else
    gs_log "$1 is not installed on $GS_SERIAL"
  fi
}

if [ -n "$only_uninstall" ]; then
  uninstall_pkg "$TEST_PACKAGE"
  uninstall_pkg "$APP_PACKAGE"
  exit 0
fi

repo=$(gs_repo)
android="$repo/android"
app_apk="$android/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
stamp=$(date '+%Y%m%d-%H%M%S')-$$
mkdir -p "$GS_OUT/logs"
raw="$GS_OUT/logs/uitest-$stamp.txt"

# ---- build
build_s=0
if [ -n "$build" ]; then
  log="$GS_OUT/logs/uitest-build.log"
  gs_log "building the app and the UI tests: ./gradlew assembleDebug assembleDebugAndroidTest (log: $log)"
  t=$SECONDS
  if ! gs_gradle "$android" "$log" assembleDebug assembleDebugAndroidTest; then
    grep -E '^(e:|w:|FAILURE|\* What went wrong|  >)|error:' "$log" | head -n 30 >&2
    tail -n 15 "$log" >&2
    gs_die "build failed; full log: $log"
  fi
  build_s=$((SECONDS - t))
  gs_log "built in $(fmt $build_s)"
fi
[ -f "$app_apk" ] || gs_die "no app APK at $app_apk (run without --no-build)"
[ -f "$test_apk" ] || gs_die "no test APK at $test_apk (run without --no-build)"

# ---- the test APK's package, runner and target, from its manifest
aapt2=$(gs_aapt2)
if [ -n "$aapt2" ]; then
  manifest=$("$aapt2" dump xmltree --file AndroidManifest.xml "$(gs_path "$test_apk")" 2>/dev/null | tr -d '\r' |
    sed -n '/E: instrumentation/,/E: /p')
  v=$(gs_apk_package "$test_apk"); [ -n "$v" ] && TEST_PACKAGE=$v
  v=$(printf '%s\n' "$manifest" | sed -n 's/.*android:name([^)]*)="\([^"]*\)".*/\1/p' | head -n 1); [ -n "$v" ] && RUNNER=$v
  v=$(printf '%s\n' "$manifest" | sed -n 's/.*android:targetPackage([^)]*)="\([^"]*\)".*/\1/p' | head -n 1); [ -n "$v" ] && APP_PACKAGE=$v
fi
name_re='^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_$]*)+$'
for v in "$TEST_PACKAGE" "$APP_PACKAGE" "$RUNNER"; do
  [[ "$v" =~ $name_re ]] || gs_die "unexpected name in the test APK: $v"
done

# ---- install
t=$SECONDS
if [ -n "$fresh" ]; then
  uninstall_pkg "$TEST_PACKAGE"
  uninstall_pkg "$APP_PACKAGE"
fi
install_apk() {
  local out
  gs_log "installing $(basename "$1") on $GS_SERIAL"
  out=$(gs_adb install -r -t "$(gs_path "$1")" 2>&1 | tr -d '\r')
  if ! grep -q '^Success' <<<"$out"; then
    printf '%s\n' "$out" | tail -n 3 >&2
    case $out in
      *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *signatures*do\ not\ match*)
        gs_die "an app signed with another key is installed: re-run with --fresh (it loses that app's data)";;
      *INSTALL_FAILED_VERSION_DOWNGRADE*) gs_die "a higher versionCode is installed: re-run with --fresh";;
      *INSUFFICIENT_STORAGE*) gs_die "the emulator's /data is (nearly) full: uninstall other apps";;
      *) gs_die "install failed";;
    esac
  fi
}
install_apk "$app_apk"
install_apk "$test_apk"
# Android keeps a granted permission when the app is reinstalled (install -r), so ThisPhoneSwitchesTest's grant of
# POST_NOTIFICATIONS would last into every later run on this emulator, and NotifyOfferOnListTest would skip the tests
# that need the app without it. Take it back (and forget a "Don't allow" from a session by hand) before each run.
# Only on Android 13+, where the permission exists; done here, as no test may run while it is taken away (that ends
# the app's process, which the tests share).
sdk=$(gs_sh getprop ro.build.version.sdk)
if [[ "$sdk" =~ ^[0-9]+$ ]] && [ "$sdk" -ge 33 ]; then
  gs_adb shell pm revoke "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
  gs_adb shell pm clear-permission-flags "$APP_PACKAGE" android.permission.POST_NOTIFICATIONS user-set user-fixed \
    >/dev/null 2>&1
fi
install_s=$((SECONDS - t))

# ---- run
targets=()
for c in "${classes[@]}"; do
  [[ "$c" == *.* ]] || c="$CLASS_PREFIX$c"
  class_re='^[A-Za-z0-9_.$#]+$'
  [[ "$c" =~ $class_re ]] || gs_die "bad class name: $c"
  targets+=("$c")
done
args=(-w -r --no-window-animation)
if [ ${#targets[@]} -gt 0 ]; then
  args+=(-e class "$(IFS=,; printf '%s' "${targets[*]}")")
fi
for a in "${extra[@]}"; do
  arg_re='^[A-Za-z0-9_.$#,=:/-]+$'
  [[ "$a" =~ $arg_re ]] || gs_die "unsupported character in am instrument argument: $a"
  args+=("$a")
done
args+=("$TEST_PACKAGE/$RUNNER")

mark=$(gs_device_time)
# "am instrument --no-window-animation" turns the animation scales off for the run, and on Android 16 it leaves an
# animator_duration_scale that was unset at 0.0 afterwards: note them now, put back what changed at the end.
anim_keys=(window_animation_scale transition_animation_scale animator_duration_scale)
declare -A anim_before=()
for k in "${anim_keys[@]}"; do anim_before[$k]=$(gs_sh settings get global "$k"); done
restore_animations() {
  local k now was
  for k in "${anim_keys[@]}"; do
    was=${anim_before[$k]}
    now=$(gs_sh settings get global "$k")
    [ "$now" = "$was" ] && continue
    if [ -z "$was" ] || [ "$was" = null ]; then
      gs_adb shell settings delete global "$k" >/dev/null
    else
      gs_adb shell settings put global "$k" "$was"
    fi
    gs_log "put back $k=${was:-null} (the run left $now)"
  done
}
trap restore_animations EXIT
gs_log "running: am instrument ${args[*]} (on $GS_SERIAL; raw output: $raw)"
t=$SECONDS

now_us() { local e=${EPOCHREALTIME:-}; if [ -n "$e" ]; then printf '%s\n' "${e/./}"; else printf '%s000000\n' "$(date +%s)"; fi; }
total="" passed=0 failed=0 skipped=0 started=0 result_code="" short_msg="" key="" cls="" test="" stack="" t_test=0
failures=()
skips=()
while IFS= read -r line; do
  line=${line%$'\r'}
  printf '%s\n' "$line" >>"$raw"
  case $line in
    "INSTRUMENTATION_STATUS: "*)
      kv=${line#INSTRUMENTATION_STATUS: }
      key=${kv%%=*}
      val=${kv#*=}
      case $key in
        class) cls=$val;;
        test) test=$val;;
        stack) stack=$val;;
        numtests) total=$val;;
      esac;;
    "INSTRUMENTATION_STATUS_CODE: "*)
      code=${line#INSTRUMENTATION_STATUS_CODE: }
      name="${cls#"$CLASS_PREFIX"}.$test"
      case $code in
        1) started=$((started + 1)); t_test=$(now_us);;
        0) passed=$((passed + 1)); printf '  ok    %-70s %6.1f s\n' "$name" "$(( ($(now_us) - t_test) / 100000 ))e-1";;
        -1 | -2)
          failed=$((failed + 1))
          printf '  FAIL  %-70s %6.1f s\n' "$name" "$(( ($(now_us) - t_test) / 100000 ))e-1"
          printf '%s\n' "$stack" | head -n 6 | sed 's/^/          /'
          failures+=("$name");;
        -3) skipped=$((skipped + 1)); printf '  skip  %s (ignored)\n' "$name"; skips+=("$name (ignored)");;
        -4) skipped=$((skipped + 1)); printf '  skip  %s (assumption failed)\n' "$name"; skips+=("$name (assumption failed)");;
      esac
      [ "$code" != 1 ] && stack=""
      key="";;
    "INSTRUMENTATION_RESULT: "*)
      kv=${line#INSTRUMENTATION_RESULT: }
      key=${kv%%=*}
      [ "$key" = shortMsg ] && short_msg=${kv#*=};;
    "INSTRUMENTATION_CODE: "*) result_code=${line#INSTRUMENTATION_CODE: }; key="";;
    "INSTRUMENTATION_FAILED: "*) short_msg=${line#INSTRUMENTATION_FAILED: };;
    *) [ "$key" = stack ] && stack+=$'\n'"$line";;  # a stack trace goes on over several lines
  esac
done < <(gs_adb shell am instrument "${args[@]}" 2>&1)
run_s=$((SECONDS - t))

# A test that started but never finished: the app or the test process died.
unfinished=$((started - passed - failed - skipped))
if [ "$unfinished" -gt 0 ]; then
  failed=$((failed + unfinished))
  failures+=("${cls#"$CLASS_PREFIX"}.$test (never finished: ${short_msg:-the instrumentation stopped})")
fi
ok=1
[ "$failed" -eq 0 ] || ok=""
[ "$result_code" = -1 ] || ok=""
[ "$passed" -gt 0 ] || ok=""
[ -n "$total" ] && [ $((passed + failed + skipped)) -ne "$total" ] && ok=""

if [ -z "$ok" ]; then
  logcat="$GS_OUT/logs/uitest-$stamp-logcat.txt"
  gs_sh "logcat -d -v threadtime -T $(gs_shq "$mark")" >"$logcat" 2>/dev/null
fi
if [ -n "$uninstall_after" ]; then
  uninstall_pkg "$TEST_PACKAGE"
  uninstall_pkg "$APP_PACKAGE"
fi

wall_s=$((SECONDS - wall_start))
echo
echo "UI tests on $GS_SERIAL: ${total:-$started} tests, $passed passed, $failed failed, $skipped skipped -> $([ -n "$ok" ] && echo PASSED || echo FAILED)"
echo "wall time $(fmt $wall_s) (build $(fmt $build_s), install $(fmt $install_s), run $(fmt $run_s))"
# A skipped test checked nothing: name each one again here, so a skip isn't lost above a PASSED.
for s in "${skips[@]}"; do echo "  skipped: $s"; done
if [ -z "$ok" ]; then
  for f in "${failures[@]}"; do echo "  failed: $f"; done
  [ -n "$short_msg" ] && echo "  instrumentation: $short_msg"
  [ "$result_code" = -1 ] || echo "  instrumentation result code: ${result_code:-none} (expected -1)"
  [ -n "$total" ] && [ $((passed + failed + skipped)) -ne "$total" ] && echo "  only $((passed + failed + skipped)) of $total tests reported"
  echo "  raw output: $raw"
  echo "  device log: $logcat"
  exit 1
fi
echo "  raw output: $raw"
