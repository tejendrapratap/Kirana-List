#!/usr/bin/env bash
# selftest.sh - checks these tools without an emulator.
#
# Usage: selftest.sh [--mock] [--build] [--no-fake]
#   always   bash -n / node --check of every file; uixml.js against the saved dumps in testdata/; emu.sh,
#            ui.sh and app.sh against testdata/fake-adb.sh + fake-emulator.sh (a call without
#            "-s emulator-NNNN" is recorded as a violation and fails the run), in a throw-away GS_E2E_HOME
#   --mock   also mock.sh against the real apps-script/test/mock-server.js on a spare port (needs node + curl)
#   --build  also build-test-apk.sh debug (a real Gradle build in the project; a few minutes the first time)
#   --no-fake  skip the fake-adb part (e.g. selftest.sh --no-fake --mock)
# Scratch files go to a gs-selftest.* folder in $SELFTEST_TMP (default $TMPDIR), removed at the end.
# The fake-adb part takes a few minutes on Windows (every fake adb call starts a bash).
# Exit 0 when everything passed.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# node.exe and adb.exe need C:/... paths once MSYS_NO_PATHCONV is set.
command -v cygpath >/dev/null 2>&1 && HERE=$(cygpath -m "$HERE")
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

with_mock="" with_build="" no_fake=""
for a in "$@"; do
  case $a in
    --mock) with_mock=1;; --build) with_build=1;; --no-fake) no_fake=1;;
    *) echo "usage: selftest.sh [--mock] [--build] [--no-fake]"; exit 2;;
  esac
done

pass=0 fail=0
# <condition>; res $? "name"
res() {
  if [ "$1" -eq 0 ]; then
    pass=$((pass + 1)); printf '  ok   %s\n' "$2"
  else
    fail=$((fail + 1)); printf '  FAIL %s\n' "$2"
    [ -n "${3:-}" ] && printf '%s\n' "$3" | head -n 15 | sed 's/^/         /'
  fi
  return 0
}
contains() { grep -q -F -- "$2" <<<"$1"; }

TMP=$(mktemp -d "${SELFTEST_TMP:-${TMPDIR:-/tmp}}/gs-selftest.XXXXXX")
command -v cygpath >/dev/null 2>&1 && TMP=$(cygpath -m "$TMP")
cleanup() {
  local f
  for f in "$TMP"/fake/devices/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; done
  rm -rf "$TMP"
}
trap cleanup EXIT
export FAKE_DIR="$TMP/fake" GS_E2E_HOME="$TMP/home" GS_ADB="$HERE/testdata/fake-adb.sh" GS_EMULATOR="$HERE/testdata/fake-emulator.sh"
# The fake emulators get console ports nothing on this machine listens on (a real emulator may hold 5554/5555).
busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
fake_ports=()
for p in $(seq 5554 2 5584); do
  busy "$p" || busy $((p + 1)) || fake_ports+=("$p")
  [ ${#fake_ports[@]} -eq 3 ] && break
done
[ ${#fake_ports[@]} -eq 3 ] || { echo "no three free emulator ports between 5554 and 5584"; exit 1; }
P1=${fake_ports[0]} P2=${fake_ports[1]} P3=${fake_ports[2]}
export EMU_PORTS="$P1 $P2 $P3" EMU_BOOT_TIMEOUT=20
unset EMU_SERIAL ANDROID_SERIAL MOCK_PORT
mkdir -p "$FAKE_DIR/devices"
: >"$FAKE_DIR/calls.log"
TD="$HERE/testdata"
screens() { printf '%s\n' "$@" >"$FAKE_DIR/screens"; }
CALLS="$FAKE_DIR/calls.log"

echo "== syntax"
for f in "$HERE"/*.sh "$TD"/*.sh; do
  bash -n "$f" 2>"$TMP/err"; res $? "bash -n $(basename "$f")" "$(cat "$TMP/err")"
done
for f in "$HERE"/*.js; do
  node --check "$f" 2>"$TMP/err"; res $? "node --check $(basename "$f")" "$(cat "$TMP/err")"
done

echo "== uixml.js (saved dumps)"
U() { node "$HERE/uixml.js" "$@"; }
S1="$TD/settings-saved-token.xml" S2="$TD/settings-token-typed.xml" TB="$TD/to-buy.xml" AI="$TD/all-items.xml"
[ "$(U find "$S1" text Save | cut -f1)" = "797 1676" ]; res $? "find text Save"
[ "$(U find "$S1" desc Back | cut -f1)" = "74 226" ]; res $? "find desc Back"
[ "$(U find "$TB" any-re '^Live' | cut -f3)" = '"Live - updated 2s ago"' ]; res $? "find any-re ^Live"
[ "$(U find "$TB" text-has 'To buy' | cut -f3)" = '"To buy (11)"' ]; res $? "find text-has 'To buy'"
[ "$(U find "$TB" desc 'Got it: Butter' | cut -f1)" = "120 1080" ]; res $? "find desc 'Got it: Butter'"
[ "$(U find "$AI" desc-re ' more ' 2 | cut -f3)" = '"0.5 kg more Sugar"' ]; res $? "find the 3rd match of desc-re ' more '"
U find "$S1" text Nope >/dev/null; [ $? -eq 1 ]; res $? "an absent text exits 1"
U find "$S1" bogus x >/dev/null 2>&1; [ $? -eq 2 ]; res $? "a bad 'how' exits 2"
[ "$(U count "$AI" desc-re '^1 more')" = 2 ]; res $? "count desc-re '^1 more'"
[ "$(U count "$S1" text-re '^')" = "$(U count "$S1" text-re '.')" ] && [ "$(U count "$S1" text-re '.')" -lt "$(grep -o '<node ' "$S1" | grep -c .)" ]; res $? "empty labels never match (even regex ^)"
[ "$(U field "$S1" 'Web app URL')" = $'540 1175 39 false false\thttp://10.0.2.2:8787/macros/s/TEST/exec' ]; res $? "field 'Web app URL'"
[ "$(U field "$S1" 'Your name' | cut -f1)" = "540 537 3 false false" ]; res $? "field 'Your name'"
[ "$(U field "$S1" 'Secret token' | cut -f1)" = "540 1449 0 true false" ]; res $? "field 'Secret token' (saved, empty)"
[ "$(U field "$S2" 'Secret token' | cut -f1)" = "540 1662 16 true false" ]; res $? "field 'Secret token' (16 bullets)"
U field "$S1" 'Nope' >/dev/null; [ $? -eq 1 ]; res $? "an absent field exits 1"
cat >"$TMP/entities.xml" <<'EOF'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" class="android.widget.FrameLayout" content-desc="" bounds="[0,0][1080,2424]"><node index="0" text="Delete &quot;Rice &amp; Dal&quot;?" class="android.widget.TextView" content-desc="" bounds="[100,100][500,200]" /><node index="1" text="a &gt; b &lt; c &#233;" class="android.widget.TextView" content-desc="" bounds="[100,300][500,400]" /><node index="2" text="Off screen" class="android.widget.TextView" content-desc="" bounds="[1100,300][1500,400]" /><node index="3" text="Empty" class="android.widget.TextView" content-desc="" bounds="[0,500][0,600]" /></node></hierarchy>
EOF
[ "$(U find "$TMP/entities.xml" text 'Delete "Rice & Dal"?' | cut -f1)" = "300 150" ]; res $? "decodes &quot; and &amp;"
[ "$(U find "$TMP/entities.xml" text 'a > b < c é' | cut -f1)" = "300 350" ]; res $? "decodes &gt; &lt; &#233;"
U find "$TMP/entities.xml" text 'Off screen' >/dev/null; [ $? -eq 1 ]; res $? "an off-screen node is not matched"
U find "$TMP/entities.xml" text 'Empty' >/dev/null; [ $? -eq 1 ]; res $? "a zero-size node is not matched"
[ "$(U list "$S1" 420 | grep -c .)" -gt 20 ]; res $? "list prints the nodes"

if [ -z "$no_fake" ]; then
echo "== serial safety (fake adb)"
out=$(EMU_SERIAL=R58M123ABC "$HERE/ui.sh" texts 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing serial"; res $? "EMU_SERIAL of a phone is refused" "$out"
out=$("$HERE/ui.sh" -s 192.0.2.10:5555 texts 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing serial"; res $? "-s with a network device is refused" "$out"
out=$("$HERE/app.sh" -s emulator-5554x info 2>&1); rc=$?
[ $rc -ne 0 ]; res $? "-s emulator-5554x is refused" "$out"
[ ! -s "$CALLS" ]; res $? "no adb call was made for the refused serials" "$(cat "$CALLS")"
out=$("$HERE/ui.sh" texts 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "no emulator is running"; res $? "no emulator running: a clear error" "$out"
! grep -v -E '^-s emulator-[0-9]+ get-state$' "$CALLS"; res $? "looking for emulators only probes -s emulator-NNNN get-state"
: >"$CALLS"
out=$(EMU_SERIAL=emulator-5554 bash -c '. "$1/lib.sh"; GS_SERIAL=emulator-5554; gs_adb kill-server' _ "$HERE" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing 'adb kill-server'"; res $? "gs_adb refuses kill-server" "$out"
out=$(bash -c '. "$1/lib.sh"; gs_adb_to emulator-5554 disconnect' _ "$HERE" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing 'adb disconnect'"; res $? "gs_adb_to refuses disconnect" "$out"
out=$(bash -c '. "$1/lib.sh"; gs_adb_to emulator-5554 -s R58M123 shell true' _ "$HERE" 2>&1); rc=$?
[ $rc -ne 0 ]; res $? "gs_adb_to refuses a second -s" "$out"
[ ! -s "$CALLS" ]; res $? "still no adb call" "$(cat "$CALLS")"

echo "== emu.sh (fake emulator)"
serial=$("$HERE/emu.sh" 2>"$TMP/emu.err"); rc=$?
[ $rc -eq 0 ] && [ "$serial" = emulator-$P1 ]; res $? "start boots one when none runs" "$(cat "$TMP/emu.err")"
[ -f "$GS_E2E_HOME/state/emu.pid" ]; res $? "pid file written"
grep -q -- "-port $P1 -read-only -no-snapshot-save -no-boot-anim -no-audio -no-window" "$FAKE_DIR/emulator-args"; res $? "headless, read-only, on the first free port ($P1)" "$(cat "$FAKE_DIR/emulator-args")"
pid1=$(cat "$FAKE_DIR/devices/emulator-$P1.pid")
serial=$("$HERE/emu.sh" start 2>"$TMP/emu.err")
[ "$serial" = emulator-$P1 ] && [ "$(cat "$FAKE_DIR/devices/emulator-$P1.pid")" = "$pid1" ]; res $? "start again reuses it (no second boot)"
grep -q "started by emu.sh earlier" "$TMP/emu.err"; res $? "reuse says it was started by emu.sh" "$(cat "$TMP/emu.err")"
echo device >"$FAKE_DIR/devices/emulator-$P2"   # someone else's emulator
[ "$("$HERE/emu.sh" status | grep -c '^emulator-')" = 2 ]; res $? "status lists both emulators"
"$HERE/emu.sh" stop 2>"$TMP/emu.err"; rc=$?
[ $rc -eq 0 ] && [ ! -f "$FAKE_DIR/devices/emulator-$P1" ] && [ ! -f "$GS_E2E_HOME/state/emu.pid" ]; res $? "stop stops the one it started" "$(cat "$TMP/emu.err")"
[ "$(cat "$FAKE_DIR/devices/emulator-$P2")" = device ]; res $? "stop leaves the other emulator running"
out=$("$HERE/emu.sh" stop 2>&1); rc=$?
[ $rc -eq 0 ] && contains "$out" "nothing to stop"; res $? "stop again: nothing to stop" "$out"
serial=$("$HERE/emu.sh" 2>"$TMP/emu.err")
[ "$serial" = emulator-$P2 ] && [ ! -f "$GS_E2E_HOME/state/emu.pid" ]; res $? "start reuses a running emulator it did not start" "$(cat "$TMP/emu.err")"
"$HERE/emu.sh" stop >/dev/null 2>&1
[ "$(cat "$FAKE_DIR/devices/emulator-$P2")" = device ]; res $? "stop never kills an emulator it did not start"
echo device >"$FAKE_DIR/devices/emulator-$P3"
rm -f "$GS_E2E_HOME/state/serial"
out=$("$HERE/emu.sh" serial 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "several emulators"; res $? "two emulators and no choice: an error" "$out"
[ "$(EMU_SERIAL=emulator-$P3 "$HERE/emu.sh" serial 2>/dev/null)" = emulator-$P3 ]; res $? "EMU_SERIAL picks one"
rm -f "$FAKE_DIR/devices/emulator-$P3" "$FAKE_DIR/devices/emulator-$P2"
# Our emulator rebooted its guest (new boot id, same process): still ours, stop stops it.
serial=$("$HERE/emu.sh" 2>/dev/null)
echo "boot-after-reboot" >"$FAKE_DIR/devices/emulator-$P1.bootid"
"$HERE/emu.sh" stop >"$TMP/emu.out" 2>&1
[ ! -f "$FAKE_DIR/devices/emulator-$P1" ] && [ ! -f "$GS_E2E_HOME/state/emu.pid" ]; res $? "stop stops its emulator after a guest reboot" "$(cat "$TMP/emu.out")"
# Our emulator gone, and someone else's booted on the same port since: left alone.
serial=$("$HERE/emu.sh" 2>/dev/null)
kill "$(cat "$FAKE_DIR/devices/emulator-$P1.pid")" 2>/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -f "$FAKE_DIR/devices/emulator-$P1" ] || break; sleep 0.5; done
echo device >"$FAKE_DIR/devices/emulator-$P1"; echo "boot-someone-else" >"$FAKE_DIR/devices/emulator-$P1.bootid"
"$HERE/emu.sh" stop >"$TMP/emu.out" 2>&1
[ -f "$FAKE_DIR/devices/emulator-$P1" ] && grep -q "boot id changed" "$TMP/emu.out" && [ ! -f "$GS_E2E_HOME/state/emu.pid" ]
res $? "stop leaves another emulator on the same port (process gone, other boot id)" "$(cat "$TMP/emu.out")"
rm -f "$FAKE_DIR/devices/emulator-$P1"*
! grep -q VIOLATION "$CALLS"; res $? "no call without -s emulator-NNNN so far" "$(grep VIOLATION "$CALLS")"

echo "== ui.sh (fake adb, saved dumps)"
echo device >"$FAKE_DIR/devices/emulator-5556"
export EMU_SERIAL=emulator-5556
: >"$CALLS"
screens "$S1"
"$HERE/ui.sh" tap-text Save 2>/dev/null
grep -q '^-s emulator-5556 shell input tap 797 1676$' "$CALLS"; res $? "tap-text Save taps its centre" "$(cat "$CALLS")"
[ "$("$HERE/ui.sh" find -d Back | cut -f1)" = "74 226" ]; res $? "find -d Back"
[ "$("$HERE/ui.sh" wait-text -r '^Version' -t 5 | cut -f3)" = '"Version 1.2"' ]; res $? "wait-text -r ^Version"
"$HERE/ui.sh" wait-text -r '^Version' -t 5 >/dev/null; res $? "wait-text exits 0 when the text is there"
start=$SECONDS
"$HERE/ui.sh" wait-text -t 2 Nope 2>/dev/null; rc=$?
[ $rc -eq 1 ] && [ $((SECONDS - start)) -lt 15 ]; res $? "wait-text times out with exit 1"
"$HERE/ui.sh" wait-text --gone -t 2 Nope >/dev/null; res $? "wait-text --gone of an absent text"
screens "$TB"
"$HERE/ui.sh" tap-desc -r '^Got it: B' -i 1 2>/dev/null
grep -q 'shell input tap 120 888$' "$CALLS"; res $? "tap-desc -r -i 1 taps 'Got it: Brown Bread'" "$(tail -n 3 "$CALLS")"
"$HERE/ui.sh" texts >"$TMP/texts" && grep -q '^To buy (11)$' "$TMP/texts"; res $? "texts lists the screen"
"$HERE/ui.sh" dump tobuy >/dev/null && [ -f "$GS_E2E_HOME/out/xml/tobuy.xml" ]; res $? "dump saves NAME.xml"

: >"$FAKE_DIR/typed.log"
weird="it's a \"test\" & (x) \$HOME; \`id\` | * ~ <a> 100% done"
"$HERE/ui.sh" type "$weird"
[ "$(cat "$FAKE_DIR/typed.log")" = "$weird" ]; res $? "type: the device shell gets exactly the text" "$(cat "$FAKE_DIR/typed.log")"
"$HERE/ui.sh" type "Café" 2>/dev/null; [ $? -ne 0 ]; res $? "type refuses non-ASCII"

echo false >"$FAKE_DIR/ime"; : >"$CALLS"
"$HERE/ui.sh" hide-keyboard
! grep -q KEYCODE_BACK "$CALLS"; res $? "hide-keyboard sends no BACK when the keyboard is closed"
echo true >"$FAKE_DIR/ime"
"$HERE/ui.sh" hide-keyboard
grep -q 'input keyevent KEYCODE_BACK' "$CALLS"; res $? "hide-keyboard sends BACK when it is open"

new_url="http://10.0.2.2:18787/macros/s/TEST/exec"
sed "s#http://10.0.2.2:8787/macros/s/TEST/exec#$new_url#" "$S1" >"$TMP/settings-new-url.xml"
screens "$S1" "$TMP/settings-new-url.xml"
: >"$CALLS"; : >"$FAKE_DIR/typed.log"; echo false >"$FAKE_DIR/ime"
"$HERE/ui.sh" set-field "Web app URL" "$new_url" 2>"$TMP/err"; rc=$?
[ $rc -eq 0 ] && [ "$(cat "$FAKE_DIR/typed.log")" = "$new_url" ]; res $? "set-field Web app URL" "$(cat "$TMP/err")"
grep -q 'input tap 540 1175$' "$CALLS" && [ "$(grep 'KEYCODE_MOVE_END' "$CALLS" | grep -o ' 67' | grep -c .)" = 41 ]
res $? "set-field taps the field, then MOVE_END + 41 x DEL" "$(cat "$CALLS")"
screens "$S1" "$S1"
"$HERE/ui.sh" set-field "Web app URL" "http://other" 2>"$TMP/err"; rc=$?
[ $rc -ne 0 ] && grep -q 'expected "http://other"' "$TMP/err"; res $? "set-field fails when the field does not read back" "$(cat "$TMP/err")"
screens "$S1" "$S2"
"$HERE/ui.sh" set-field "Secret token" "0123456789abcdef" 2>"$TMP/err"; rc=$?
[ $rc -eq 0 ] && ! grep -q 0123456789abcdef "$TMP/err"; res $? "set-field Secret token: checks the bullet count, prints no token" "$(cat "$TMP/err")"

: >"$CALLS"
"$HERE/ui.sh" font-scale 1.3 >/dev/null
"$HERE/ui.sh" rotate landscape >/dev/null
"$HERE/ui.sh" animations off >/dev/null
[ "$(cat "$FAKE_DIR/settings/system.font_scale")" = 1.3 ] && [ "$(cat "$FAKE_DIR/settings/system.user_rotation")" = 1 ] &&
  [ "$(cat "$FAKE_DIR/settings/global.animator_duration_scale")" = 0 ]
res $? "font-scale / rotate / animations applied"
"$HERE/ui.sh" font-scale 2.0 >/dev/null
"$HERE/ui.sh" reset >/dev/null
[ ! -f "$FAKE_DIR/settings/system.font_scale" ] && [ ! -f "$FAKE_DIR/settings/system.user_rotation" ] &&
  [ ! -f "$FAKE_DIR/settings/global.animator_duration_scale" ]
res $? "reset restores the originals (unset = deleted), even after two changes"
[ "$("$HERE/ui.sh" reset)" = "nothing to reset on emulator-5556" ]; res $? "reset again: nothing to reset"
f=$("$HERE/ui.sh" shot test1); [ -f "$f" ]; res $? "shot writes a PNG and prints its path"
"$HERE/ui.sh" key back enter 66 >/dev/null
grep -q 'input keyevent KEYCODE_BACK KEYCODE_ENTER 66$' "$CALLS"; res $? "key names become KEYCODE_*"
"$HERE/ui.sh" key 'BACK;reboot' 2>/dev/null; [ $? -ne 0 ]; res $? "key refuses shell characters"

cat >"$FAKE_DIR/logcat-crash.txt" <<'EOF'
11-08 10:00:01.000  4321  4321 E AndroidRuntime: FATAL EXCEPTION: main
11-08 10:00:01.000  4321  4321 E AndroidRuntime: Process: com.other.app, PID: 4321
11-08 10:00:01.000  4321  4321 E AndroidRuntime: java.lang.IllegalStateException: not ours
EOF
"$HERE/ui.sh" crashes >/dev/null 2>&1; res $? "crashes: another app's crash is ignored"
cat >>"$FAKE_DIR/logcat-crash.txt" <<'EOF'
11-08 10:00:02.000  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
11-08 10:00:02.000  1234  1234 E AndroidRuntime: Process: com.grocerysync.app, PID: 1234
11-08 10:00:02.000  1234  1234 E AndroidRuntime: java.lang.NullPointerException: boom
EOF
out=$("$HERE/ui.sh" crashes 2>/dev/null); rc=$?
[ $rc -eq 1 ] && contains "$out" "boom" && contains "$out" "FATAL EXCEPTION" && ! contains "$out" "not ours"
res $? "crashes: the app's crash fails, with its lines only" "$out"
: >"$FAKE_DIR/logcat-crash.txt"
cat >"$FAKE_DIR/logcat-main.txt" <<'EOF'
11-08 10:00:03.000   600   700 E ActivityManager: ANR in com.grocerysync.app.qa (com.grocerysync.app.qa/com.grocerysync.app.MainActivity)
11-08 10:00:03.000   600   700 I am_anr  : [0,1235,com.grocerysync.app.qa,952745540,Input dispatching timed out]
EOF
out=$("$HERE/ui.sh" crashes 2>/dev/null); rc=$?
[ $rc -eq 0 ]; res $? "crashes: an ANR of com.grocerysync.app.qa is not one of com.grocerysync.app" "$out"
out=$(GS_PACKAGE=com.grocerysync.app.qa "$HERE/ui.sh" crashes 2>/dev/null); rc=$?
[ $rc -eq 1 ] && [ "$(printf '%s\n' "$out" | grep -c .)" = 2 ]; res $? "crashes: GS_PACKAGE=...qa finds its ANR (both lines)" "$out"
: >"$FAKE_DIR/logcat-main.txt"

echo "== app.sh (fake adb)"
touch "$TMP/app.apk"
echo "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match]" >"$FAKE_DIR/install-result"
out=$("$HERE/app.sh" install "$TMP/app.apk" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "re-run with --fresh"; res $? "install: a signature mismatch suggests --fresh" "$out"
echo "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" >"$FAKE_DIR/install-result"
out=$("$HERE/app.sh" install "$TMP/app.apk" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "low-storage"; res $? "install: a full /data suggests --low-storage" "$out"
echo Success >"$FAKE_DIR/install-result"
: >"$CALLS"
out=$("$HERE/app.sh" install "$TMP/app.apk" --low-storage 2>&1); rc=$?
[ $rc -eq 0 ] && grep -q 'settings put global sys_storage_threshold_percentage 2' "$CALLS" &&
  grep -q 'settings delete global sys_storage_threshold_percentage' "$CALLS"
res $? "install --low-storage restores the threshold" "$out"
[ -f "$GS_E2E_HOME/state/crash-mark-emulator-5556" ]; res $? "install sets the crash mark"
[ "$(cat "$GS_E2E_HOME/state/app-package")" = com.grocerysync.app ]; res $? "install remembers the app id"
cp "$TMP/app.apk" "$TMP/app-qa-e2e.apk"
: >"$CALLS"
"$HERE/app.sh" install "$TMP/app-qa-e2e.apk" >/dev/null 2>&1 && "$HERE/app.sh" launch >/dev/null 2>&1
[ "$(cat "$GS_E2E_HOME/state/app-package")" = com.grocerysync.app.qa ] &&
  grep -q 'shell am start -W -n com.grocerysync.app.qa/com.grocerysync.app.MainActivity$' "$CALLS"
res $? "after installing the qa build, launch starts com.grocerysync.app.qa" "$(grep 'am start' "$CALLS")"
"$HERE/app.sh" install "$TMP/app.apk" >/dev/null 2>&1
out=$("$HERE/app.sh" install release 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "build-test-apk.sh"; res $? "install release before a build: a clear error" "$out"

touch "$FAKE_DIR/installed"
token=0123456789abcdef
printf 'pid=1\nport=8787\ntoken=%s\n' "$token" >"$GS_E2E_HOME/state/mock-8787.env"
echo 8787 >"$GS_E2E_HOME/state/mock-last-port"
# Dumps in order: list (Settings icon), Settings opened, then field/check pairs for name, URL and token,
# Save, and the list again with "Live".
screens "$TB" "$S1" "$S1" "$S1" "$S1" "$S1" "$S1" "$S2" "$S1" "$TB"
: >"$CALLS"; : >"$FAKE_DIR/typed.log"; echo false >"$FAKE_DIR/ime"
out=$("$HERE/app.sh" configure --name Emu --no-test 2>&1); rc=$?
[ $rc -eq 0 ] && contains "$out" 'configured: status "Live'; res $? "configure walks Settings and waits for Live" "$out"
[ "$(sed -n 1p "$FAKE_DIR/typed.log")" = Emu ] && [ "$(sed -n 2p "$FAKE_DIR/typed.log")" = "http://10.0.2.2:8787/macros/s/TEST/exec" ] &&
  [ "$(sed -n 3p "$FAKE_DIR/typed.log")" = "$token" ]
res $? "configure typed the name, the mock URL and the mock token" "$(cat "$FAKE_DIR/typed.log")"
! contains "$out" "$token"; res $? "configure never prints the token"
grep -q 'input tap 996 252$' "$CALLS" && grep -q 'input tap 797 1676$' "$CALLS"; res $? "configure tapped Settings, then Save"
out=$("$HERE/app.sh" configure --port 9999 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "no token"; res $? "configure without a mock: a clear error" "$out"
! grep -q VIOLATION "$CALLS"; res $? "no call without -s emulator-NNNN at all" "$(grep VIOLATION "$CALLS")"
unset EMU_SERIAL

echo "== build-test-apk.sh (arguments)"
out=$("$HERE/build-test-apk.sh" qa --work "$TMP/w" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "for release builds only"; res $? "--work is refused for qa (qa builds in place)" "$out"
grep -q '^variant=qa$' "$HERE/build-test-apk.sh"; res $? "qa is the default variant"
mkdir -p "$TMP/no-qa/android/app/src/main" && touch "$TMP/no-qa/android/gradlew"
echo 'android { buildTypes { release { } } }' >"$TMP/no-qa/android/app/build.gradle.kts"
out=$("$HERE/build-test-apk.sh" qa --src "$TMP/no-qa/android" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "has no qa build type"; res $? "qa on a checkout without the qa build type: a clear error" "$out"
out=$("$HERE/build-test-apk.sh" --src "$TMP" 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "not the android/ folder"; res $? "a --src that is not android/ is refused" "$out"
"$HERE/build-test-apk.sh" bogus >/dev/null 2>&1; [ $? -eq 2 ]; res $? "an unknown option exits 2"
for task in connectedDebugAndroidTest :app:connectedAndroidTest installDebug :app:installQa uninstallAll deviceCheck; do
  out=$(bash -c '. "$1/lib.sh"; gs_gradle "$2" "$2/gradle.log" assembleDebug "$3"' _ "$HERE" "$TMP" "$task" 2>&1); rc=$?
  [ $rc -ne 0 ] && contains "$out" "refusing gradle task '$task'"; res $? "gs_gradle refuses $task (it reaches every device)" "$out"
done

echo "== uitest.sh (fake adb)"
: >"$CALLS"
out=$("$HERE/uitest.sh" -s 192.0.2.10:44585 --no-build 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing serial"; res $? "-s with a phone on Wi-Fi is refused" "$out"
out=$(EMU_SERIAL='adb-FAKE00-xyz._adb-tls-connect._tcp' "$HERE/uitest.sh" --no-build 2>&1); rc=$?
[ $rc -ne 0 ] && contains "$out" "refusing serial"; res $? "EMU_SERIAL of an mDNS phone is refused" "$out"
[ ! -s "$CALLS" ]; res $? "no adb call for the refused serials" "$(cat "$CALLS")"
R="$TMP/repo"
mkdir -p "$R/apps-script/test" "$R/android/app/build/outputs/apk/debug" "$R/android/app/build/outputs/apk/androidTest/debug"
touch "$R/apps-script/test/mock-server.js" "$R/android/app/build/outputs/apk/debug/app-debug.apk"   "$R/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
instrument() {  # instrument <class> <test> <code> [stack]: one test's start and end, as am instrument -r prints them
  printf 'INSTRUMENTATION_STATUS: class=com.grocerysync.app.uitest.%s
INSTRUMENTATION_STATUS: numtests=%s
INSTRUMENTATION_STATUS: test=%s
INSTRUMENTATION_STATUS_CODE: 1
' "$1" "$NUMTESTS" "$2"
  printf 'INSTRUMENTATION_STATUS: class=com.grocerysync.app.uitest.%s
' "$1"
  [ -n "${4:-}" ] && printf 'INSTRUMENTATION_STATUS: stack=%s
	at com.grocerysync.app.uitest.%s.%s(%s.kt:42)
' "$4" "$1" "$2" "$1"
  printf 'INSTRUMENTATION_STATUS: stream=
Error in %s
INSTRUMENTATION_STATUS: test=%s
INSTRUMENTATION_STATUS_CODE: %s
' "$2" "$2" "$3"
}
NUMTESTS=3
{
  instrument SearchTest a 0
  instrument SearchTest b -2 "java.lang.AssertionError: boom"
  instrument PagesTest c 0
  printf 'INSTRUMENTATION_RESULT: stream=

Time: 3.2
There was 1 failure:
FAILURES!!!
Tests run: 3,  Failures: 1

INSTRUMENTATION_CODE: -1
'
} >"$FAKE_DIR/instrument-output"
out=$(GS_REPO="$R" EMU_SERIAL=emulator-5556 "$HERE/uitest.sh" --no-build --class SearchTest --class PagesTest#c 2>&1); rc=$?
[ $rc -eq 1 ] && contains "$out" "3 tests, 2 passed, 1 failed, 0 skipped -> FAILED" && contains "$out" "FAIL  SearchTest.b" &&
  contains "$out" "java.lang.AssertionError: boom" && contains "$out" "failed: SearchTest.b" && contains "$out" "wall time"
res $? "a failing test: exit 1, its stack and a summary" "$out"
grep -q "^-s emulator-5556 install -r -t $R/android/app/build/outputs/apk/debug/app-debug.apk$" "$CALLS" &&
  grep -q "^-s emulator-5556 install -r -t $R/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk$" "$CALLS"
res $? "installs the app and the test APK on the emulator only (-r -t)" "$(grep install "$CALLS")"
grep -q '^-s emulator-5556 shell am instrument -w -r --no-window-animation -e class com.grocerysync.app.uitest.SearchTest,com.grocerysync.app.uitest.PagesTest#c com.grocerysync.app.test/androidx.test.runner.AndroidJUnitRunner$' "$CALLS"
res $? "am instrument with the classes, the test package and its runner" "$(grep instrument "$CALLS")"
{
  instrument SearchTest a 0
  instrument SearchTest b 0
  instrument PagesTest c 0
  printf 'INSTRUMENTATION_RESULT: stream=

Time: 3.2

OK (3 tests)


INSTRUMENTATION_CODE: -1
'
} >"$FAKE_DIR/instrument-output"
echo 1.0 >"$FAKE_DIR/settings/global.window_animation_scale"
rm -f "$FAKE_DIR/settings/global.animator_duration_scale"
out=$(GS_REPO="$R" EMU_SERIAL=emulator-5556 "$HERE/uitest.sh" --no-build 2>&1); rc=$?
[ $rc -eq 0 ] && contains "$out" "3 tests, 3 passed, 0 failed, 0 skipped -> PASSED"; res $? "all passed: exit 0" "$out"
[ ! -f "$FAKE_DIR/settings/global.animator_duration_scale" ] && [ "$(cat "$FAKE_DIR/settings/global.window_animation_scale")" = 1.0 ]
res $? "the animation scales are as before the run (unset stays unset)" "$out"
rm -f "$FAKE_DIR/settings/global.window_animation_scale"
{
  instrument SearchTest a 0
  printf 'INSTRUMENTATION_STATUS: class=com.grocerysync.app.uitest.SearchTest
INSTRUMENTATION_STATUS: test=b
INSTRUMENTATION_STATUS_CODE: 1
'
  printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
'
} >"$FAKE_DIR/instrument-output"
out=$(GS_REPO="$R" EMU_SERIAL=emulator-5556 "$HERE/uitest.sh" --no-build 2>&1); rc=$?
[ $rc -eq 1 ] && contains "$out" "never finished: Process crashed." && contains "$out" "only 2 of 3 tests reported"
res $? "a crash in the middle: exit 1, the unfinished test named" "$out"
: >"$CALLS"
touch "$FAKE_DIR/installed"
out=$(GS_REPO="$R" EMU_SERIAL=emulator-5556 "$HERE/uitest.sh" uninstall 2>&1); rc=$?
[ $rc -eq 0 ] && grep -q '^-s emulator-5556 uninstall com.grocerysync.app$' "$CALLS"; res $? "uninstall removes the app from the emulator" "$(cat "$CALLS")"
! grep -q VIOLATION "$CALLS"; res $? "uitest.sh: no call without -s emulator-NNNN" "$(grep VIOLATION "$CALLS")"

fi

if [ -n "$with_mock" ]; then
  echo "== mock.sh (real mock-server.js)"
  port=$((18000 + RANDOM % 1000))
  M() { "$HERE/mock.sh" --port "$port" "$@"; }
  out=$(M start 2>&1); rc=$?
  [ $rc -eq 0 ] && contains "$out" "10.0.2.2:$port"; res $? "start on port $port" "$out"
  ! contains "$(M status)" "e2e-test-token"; res $? "status hides the token"
  contains "$(M sheets)" '"Item","Need","Unit","Type","Updated At","Updated By","Added At"'; res $? "sheets: the Items titles"
  out=$(M upsert Rice 2 kg); contains "$out" "applied"; res $? "upsert as Home: applied" "$out"
  contains "$(M sheets | grep Rice)" '"Rice",2,"kg","quantity"'; res $? "the sheet has Rice 2 kg" "$(M sheets)"
  contains "$(M sheets | grep Rice)" '"Home"'; res $? "... updated by Home"
  out=$(M upsert --device Shop 'Dal "toor"' true); contains "$out" "applied"; res $? "upsert a quoted name as another device" "$out"
  contains "$(M sheets)" '"Dal \"toor\"",true,"","boolean"'; res $? "the sheet has it" "$(M sheets)"
  out=$(M upsert 'Pañeer' 1 pcs); contains "$out" "applied" && contains "$(M sheets)" 'Pañeer'; res $? "upsert with a \\u escape" "$out"
  out=$(M hand-edit Rice Need 3); contains "$out" '"range": "B2"'; res $? "hand-edit by item name and column title" "$out"
  row=$(M sheets | grep Rice)
  contains "$row" '"Rice",3,"kg","quantity"' && contains "$row" '"Sheet"'; res $? "hand-edit stamped Updated By Sheet" "$row"
  out=$(M hand-edit '{"row":3,"col":2,"value":false}'); contains "$out" '"ok": true'; res $? "hand-edit with a raw body" "$out"
  out=$(M delete Rice); contains "$out" "applied"; res $? "delete as Home" "$out"
  ! contains "$(M sheets)" '"Rice"' && contains "$(M sheets _Tombstones)" '"rice"'; res $? "Rice gone, tombstone kept (key lowercased)"
  contains "$(M list)" '"version":3'; res $? "list (protocol 3)"
  ! contains "$(M list --v2)" '"version"'; res $? "list --v2 has no version"
  out=$(M apply '[{"op":"upsert","name":"Tea","type":"boolean","value":true}]'); contains "$out" "applied"; res $? "apply raw changes" "$out"
  out=$(M upsert Milk lots 2>&1); contains "$out" "value must be"; res $? "upsert with a bad value: an error" "$out"
  M fail-mode 500 >/dev/null; contains "$(M ping)" "server error"; res $? "fail-mode 500: ping gets the error page"
  M fail-mode '' >/dev/null; contains "$(M ping)" '"ok":true'; res $? "fail-mode '': ping works again"
  contains "$(M script v3)" '"version": 3' && contains "$(M script)" 'Code.v3.gs'; res $? "script v3 switches the script"
  out=$(M start 2>&1); contains "$out" "keeping it" && contains "$(M sheets)" '"Tea"'; res $? "start with the same settings keeps the sheet" "$out"
  out=$(M start --fresh 2>&1); contains "$out" "restarting" && ! contains "$(M sheets)" '"Tea"'; res $? "start --fresh restarts with an empty sheet" "$out"
  out=$(M start --script v2 2>&1); contains "$out" '"version":2'; res $? "start --script v2 restarts with the old script" "$out"
  [ "$(M url)" = "http://10.0.2.2:$port/macros/s/TEST/exec" ] && [ "$(M url --host)" = "http://127.0.0.1:$port/macros/s/TEST/exec" ]; res $? "url / url --host"
  ! M log 100 | grep e2e-test-token >/dev/null; res $? "log hides the token"
  out=$(M stop); contains "$out" "stopped"; res $? "stop" "$out"
  ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; res $? "port $port is closed"
  contains "$(M stop)" "nothing to stop"; res $? "stop again: nothing to stop"
  other=$((port + 1))
  node -e "require('http').createServer((q,s)=>s.end('x')).listen($other,'127.0.0.1')" >/dev/null 2>&1 &
  npid=$!
  for _ in 1 2 3 4 5 6 7 8 9 10; do (exec 3<>"/dev/tcp/127.0.0.1/$other") 2>/dev/null && break; sleep 0.3; done
  out=$("$HERE/mock.sh" --port "$other" start 2>&1); rc=$?
  [ $rc -ne 0 ] && contains "$out" "did not start"; res $? "a port used by another process is left alone" "$out"
  "$HERE/mock.sh" --port "$other" stop >/dev/null 2>&1
  (exec 3<>"/dev/tcp/127.0.0.1/$other") 2>/dev/null; res $? "stop does not kill a server it did not start"
  kill "$npid" 2>/dev/null
fi

if [ -n "$with_build" ]; then
  echo "== build-test-apk.sh debug (real Gradle build)"
  apk=$("$HERE/build-test-apk.sh" debug 2>"$TMP/build.err"); rc=$?
  [ $rc -eq 0 ] && [ -f "$apk" ]; res $? "debug build: APK path printed" "$(tail -n 20 "$TMP/build.err")"
  grep -q "^package: name='com.grocerysync.app'" "$TMP/build.err"; res $? "badging printed"
  sed 's/^/    /' "$TMP/build.err" | tail -n 8
fi

echo "== $pass passed, $fail failed"
[ "$fail" -eq 0 ]
