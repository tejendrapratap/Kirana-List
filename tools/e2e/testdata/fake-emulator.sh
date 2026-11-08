#!/usr/bin/env bash
# fake-emulator.sh - stand-in for the emulator binary used by selftest.sh (GS_EMULATOR=...). Registers
# emulator-<port> with fake-adb.sh: "offline" at once, "device" (booted) a second later, until killed.
set -u
D=${FAKE_DIR:?FAKE_DIR is not set}
port=""
all="$*"
while [ $# -gt 0 ]; do
  case $1 in -port) port=$2; shift 2;; *) shift;; esac
done
[ -n "$port" ] || { echo "fake-emulator: no -port" >&2; exit 2; }
mkdir -p "$D/devices"
echo "$all" >"$D/emulator-args"
echo $$ >"$D/devices/emulator-$port.pid"
echo "boot-$$" >"$D/devices/emulator-$port.bootid"
trap 'rm -f "$D/devices/emulator-$port" "$D/devices/emulator-$port.pid"; exit 0' TERM INT
echo offline >"$D/devices/emulator-$port"
sleep 1
echo device >"$D/devices/emulator-$port"
while :; do sleep 1; done
