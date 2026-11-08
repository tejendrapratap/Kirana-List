#!/usr/bin/env bash
# emu.sh - start (or reuse) an Android emulator for Grocery Sync checks, and stop only the one it started.
#
# Usage:
#   emu.sh [start] [--avd NAME] [--window] [--timeout SECS]
#       Reuses a running emulator if there is one (EMU_SERIAL, else the one emu.sh used last, else the only
#       one running); otherwise boots the AVD (default Pixel_9a, or $EMU_AVD) headless on a free console
#       port. Waits until Android has booted, wakes and unlocks the screen, and prints the serial
#       (e.g. emulator-5554) on stdout; everything else goes to stderr. So: S=$(./emu.sh)
#       The emulator runs with -read-only -no-snapshot-save: nothing it does is written back to the AVD,
#       and every boot starts from the same state. --window shows the emulator window.
#       Extra emulator options: EMU_ARGS="-gpu swiftshader_indirect" ./emu.sh
#   emu.sh stop
#       Stops the emulator emu.sh started (tracked in $GS_E2E_HOME/state/emu.pid, and checked by its boot
#       id), with "adb emu kill", then by killing the process tree. Any other emulator is left alone.
#       Does nothing when emu.sh did not start one.
#   emu.sh status     which emulators are running and which one emu.sh started
#   emu.sh serial     prints the emulator the other tools would use (no booting)
#
# Safe to re-run: "start" never boots a second emulator while one is running, "stop" never kills one it
# did not start. Never touches the adb server (no kill-server / start-server). See lib.sh for settings.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

PIDFILE="$GS_STATE/emu.pid"
avd=${EMU_AVD:-Pixel_9a}
window=""
timeout=${EMU_BOOT_TIMEOUT:-300}

usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit "${1:-0}"; }

cmd=start
case "${1:-}" in
  start | stop | status | serial) cmd=$1; shift;;
  -h | --help | help) usage 0;;
esac
while [ $# -gt 0 ]; do
  case $1 in
    --avd) avd=${2:?--avd needs a name}; shift 2;;
    --window) window=1; shift;;
    --timeout) timeout=${2:?--timeout needs seconds}; shift 2;;
    -h | --help) usage 0;;
    *) gs_log "unknown option: $1"; usage 2;;
  esac
done

boot_completed() { [ "$(gs_adb_to "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
boot_id() { gs_adb_to "$1" shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r'; }

# Waits until <serial> is online and booted; $2 = launcher pid to watch (0 = none). Returns 1 on timeout.
wait_for_boot() {
  local serial=$1 pid=${2:-0} winpid=${3:-} end=$((SECONDS + timeout)) st last="" gone_since=""
  while [ $SECONDS -lt $end ]; do
    st=$(gs_state_of "$serial")
    if [ "$st" = device ] && boot_completed "$serial"; then
      # The package manager answers a little after boot_completed on a cold boot.
      gs_adb_to "$serial" shell pm path android >/dev/null 2>&1 && return 0
    fi
    # The launcher gone and adb not seeing the device for 15 s: it failed (see the log).
    if [ "$pid" != 0 ] && [ -z "$st" ] && ! gs_alive "$pid" "$winpid"; then
      [ -z "$gone_since" ] && gone_since=$SECONDS
      if [ $((SECONDS - gone_since)) -ge 15 ]; then
        gs_log "the emulator process exited during boot"
        return 1
      fi
    else
      gone_since=""
    fi
    [ "$st" != "$last" ] && gs_log "  $serial: ${st:-starting}..." && last=$st
    sleep 2
  done
  gs_log "$serial did not finish booting within $timeout s"
  return 1
}

# True while the process emu.sh launched still runs and still is an emulator (pids get reused).
emulator_process_alive() {
  local pid=$1 winpid=${2:-}
  if [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ] && kill -0 "$pid" 2>/dev/null; then
    tr '\0' ' ' <"/proc/$pid/cmdline" | grep -qi emulator && return 0
  fi
  if [ -n "$winpid" ] && gs_have tasklist; then
    tasklist /FI "PID eq $winpid" /NH 2>/dev/null | grep -qiE "^(emulator|qemu)[^ ]* +$winpid " && return 0
  fi
  return 1
}

unlock() {
  gs_adb_to "$1" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  gs_adb_to "$1" shell wm dismiss-keyguard >/dev/null 2>&1
  return 0
}

# A running emulator to reuse: prints its serial, or nothing.
running_serial() {
  local saved="" online
  if [ -n "${EMU_SERIAL:-}" ]; then
    gs_check_serial "$EMU_SERIAL"
    [ -n "$(gs_state_of "$EMU_SERIAL")" ] && printf '%s\n' "$EMU_SERIAL"
    return 0
  fi
  [ -f "$GS_STATE/serial" ] && saved=$(tr -d '\r\n' <"$GS_STATE/serial")
  if gs_valid_serial "$saved" && [ -n "$(gs_state_of "$saved")" ]; then
    printf '%s\n' "$saved"
    return 0
  fi
  online=$(gs_list_emulators | awk '{ print $1 }')
  case $(printf '%s' "$online" | grep -c .) in
    0) return 0;;
    1) printf '%s\n' "$online";;
    *) gs_die "several emulators are running ($(echo $online)): choose one with EMU_SERIAL=emulator-NNNN";;
  esac
}

free_port() {
  local port
  for port in $(seq 5554 2 5584); do
    [ -z "$(gs_state_of "emulator-$port")" ] && ! gs_port_busy "$port" && ! gs_port_busy $((port + 1)) && {
      printf '%s\n' "$port"
      return 0
    }
  done
  return 1
}

do_start() {
  local serial pid winpid port log args
  serial=$(running_serial) || exit 1
  if [ -n "$serial" ]; then
    if [ -f "$PIDFILE" ] && [ "$(gs_kv_get "$PIDFILE" serial)" = "$serial" ]; then
      gs_log "reusing $serial (started by emu.sh earlier)"
    else
      gs_log "reusing running emulator $serial (emu.sh stop will leave it running)"
    fi
    wait_for_boot "$serial" 0 || exit 1
    unlock "$serial"
    printf '%s\n' "$serial" >"$GS_STATE/serial"
    printf '%s\n' "$serial"
    return 0
  fi

  gs_init_sdk
  [ -f "$EMULATOR" ] || gs_die "emulator not found at $EMULATOR"
  port=$(free_port) || gs_die "no free emulator console port between 5554 and 5584"
  serial="emulator-$port"
  mkdir -p "$GS_OUT/logs"
  log="$GS_OUT/logs/emulator-$port.log"
  args=(-avd "$avd" -port "$port" -read-only -no-snapshot-save -no-boot-anim -no-audio)
  [ -z "$window" ] && args+=(-no-window)
  # shellcheck disable=SC2206
  [ -n "${EMU_ARGS:-}" ] && args+=($EMU_ARGS)
  gs_log "booting $avd as $serial (log: $log)"
  nohup "$EMULATOR" "${args[@]}" >"$log" 2>&1 </dev/null &
  pid=$!
  winpid=$(gs_winpid_of "$pid")
  {
    echo "pid=$pid"
    echo "winpid=$winpid"
    echo "serial=$serial"
    echo "avd=$avd"
    echo "log=$log"
    echo "started=$(date '+%Y-%m-%d %H:%M:%S')"
  } >"$PIDFILE"
  if ! wait_for_boot "$serial" "$pid" "$winpid"; then
    gs_log "last lines of $log:"
    tail -n 15 "$log" >&2
    gs_log "stopping it again"
    do_stop
    exit 1
  fi
  echo "boot_id=$(boot_id "$serial")" >>"$PIDFILE"
  unlock "$serial"
  printf '%s\n' "$serial" >"$GS_STATE/serial"
  gs_log "$serial booted in ${SECONDS}s"
  printf '%s\n' "$serial"
}

do_stop() {
  local pid winpid serial saved_boot st i
  if [ ! -f "$PIDFILE" ]; then
    gs_log "emu.sh has not started an emulator (no $PIDFILE): nothing to stop; running emulators are left alone"
    return 0
  fi
  pid=$(gs_kv_get "$PIDFILE" pid)
  winpid=$(gs_kv_get "$PIDFILE" winpid)
  serial=$(gs_kv_get "$PIDFILE" serial)
  saved_boot=$(gs_kv_get "$PIDFILE" boot_id)
  gs_check_serial "$serial"
  st=$(gs_state_of "$serial")
  # Ours while the process emu.sh launched is alive (a guest reboot changes the boot id but not the
  # process). Without it (the launcher can exit on Windows), only when the boot id is still the same:
  # a different one means someone else booted an emulator on that port since.
  if [ -n "$st" ] && ! emulator_process_alive "$pid" "$winpid" && [ -n "$saved_boot" ] &&
    [ "$(boot_id "$serial")" != "$saved_boot" ]; then
    gs_log "$serial is not the emulator emu.sh started (its process is gone and the boot id changed): leaving it running"
    st=""
  elif [ -n "$st" ]; then
    gs_log "stopping $serial (adb emu kill)"
    gs_adb_to "$serial" emu kill >/dev/null 2>&1
    for ((i = 0; i < 60; i++)); do
      [ -z "$(gs_state_of "$serial")" ] && ! emulator_process_alive "$pid" "$winpid" && break
      sleep 1
    done
  fi
  if emulator_process_alive "$pid" "$winpid"; then
    gs_log "killing the emulator process (pid $pid${winpid:+, Windows pid $winpid})"
    gs_stop_pid "$pid" "$winpid" 5 || gs_log "could not kill pid $pid"
  fi
  if [ -n "$st" ] && [ -n "$(gs_state_of "$serial")" ]; then
    gs_log "$serial is still listed by adb; check it by hand"
    return 1
  fi
  rm -f "$PIDFILE"
  [ "$(cat "$GS_STATE/serial" 2>/dev/null)" = "$serial" ] && rm -f "$GS_STATE/serial"
  gs_log "stopped"
}

do_status() {
  local list
  list=$(gs_list_emulators)
  if [ -n "$list" ]; then printf '%s\n' "$list"; else echo "no emulator running"; fi
  if [ -f "$PIDFILE" ]; then
    echo "started by emu.sh: $(gs_kv_get "$PIDFILE" serial) ($(gs_kv_get "$PIDFILE" avd), pid $(gs_kv_get "$PIDFILE" pid), since $(gs_kv_get "$PIDFILE" started))"
  else
    echo "started by emu.sh: none"
  fi
  [ -f "$GS_STATE/serial" ] && echo "tools use: $(cat "$GS_STATE/serial")"
  return 0
}

case $cmd in
  start) do_start;;
  stop) do_stop;;
  status) do_status;;
  serial) gs_resolve_serial && printf '%s\n' "$GS_SERIAL";;
esac
