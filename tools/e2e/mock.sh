#!/usr/bin/env bash
# mock.sh - run the local mock of the Apps Script web app (apps-script/test/mock-server.js) and poke it.
#
# Usage: mock.sh [--port P] <command> [args]      (port: --port, else $MOCK_PORT, else the last one started,
#                                                   else 8787; each port has its own state)
# Server:
#   start [--script current|v3|v2|<Code.gs path>] [--token T] [--fail-mode html|404|500|apply_internal] [--fresh]
#         Starts the mock with a fresh fake sheet and waits until it answers. Default script "current"
#         (apps-script/Code.gs), default token "e2e-test-token" (or $MOCK_TOKEN). The token is kept in the
#         state folder for app.sh configure and the commands below; it is never printed.
#         Re-running with the same settings keeps the running mock (and its sheet); --fresh restarts it.
#         A port held by a process mock.sh did not start is never touched: pick another --port.
#   stop                stops the mock mock.sh started on that port (nothing else)
#   status              pid, port, served script and its protocol version, URLs
#   url [--host]        the Web app URL for the emulator (http://10.0.2.2:P/macros/s/TEST/exec), or for this machine
#   log [N]             the last N (default 30) request log lines
# The fake sheet:
#   sheets [SHEET|--names|--json]   rows of Items (default) or another sheet, one JSON array per line
#   hand-edit <row|item name> <col|title> <value>  [--sheet S]
#         edits a cell like a person typing in the sheet (fires onEdit, stamps Updated By "Sheet").
#         value is JSON when it parses (true, 2, "2 kg"), else text. e.g. mock.sh hand-edit Rice Need 3
#   hand-edit '<json body>'        raw POST /debug/hand-edit body, e.g. '{"row":2,"col":2,"value":true}'
# As the other phone (protocol 3 apply through the real redirect, like the app; default device "Home"):
#   upsert [--device D] <name> <true|false|amount> [unit|-]   add or change an item ("-" clears the unit)
#   delete [--device D] <name>
#   apply  [--device D] [--protocol N] '<changes json>'      e.g. '[{"op":"upsert","name":"Tea","type":"boolean","value":true}]'
#         ids and times are filled in. Prints each change's status (applied / stale / duplicate / error).
#   list [--v2] [--since REV]       what a phone gets from ?action=list (one line of JSON)
#   ping [--v2]
# Debug routes (see the header of mock-server.js):
#   fail-mode <''|html|404|500|apply_internal> [detail]
#   script [current|v3|v2|startup]  without an argument: which script is served
#   seed-v2 '<json>'   validation '<json>'
#
# Needs node and curl. Safe to re-run. See lib.sh for GS_REPO / GS_E2E_HOME.
set -uo pipefail
. "$(dirname "$0")/lib.sh"

EXEC_PATH=/macros/s/TEST/exec
port=${MOCK_PORT:-}
device=Home
usage() { sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit "${1:-0}"; }

# Global --port before the command.
while [ $# -gt 0 ]; do
  case $1 in
    --port) port=${2:?--port needs a number}; shift 2;;
    -h | --help | help) usage 0;;
    *) break;;
  esac
done
cmd=${1:-}
[ -z "$cmd" ] && usage 2
shift
[ -z "$port" ] && [ -f "$GS_STATE/mock-last-port" ] && port=$(tr -d '\r\n' <"$GS_STATE/mock-last-port")
port=${port:-8787}
[[ "$port" =~ ^[0-9]+$ ]] || gs_die "bad port: $port"

ENV_FILE="$GS_STATE/mock-$port.env"
LOG_FILE="$GS_OUT/logs/mock-$port.log"
BASE="http://127.0.0.1:$port"

running_pid() {
  local pid winpid
  pid=$(gs_kv_get "$ENV_FILE" pid)
  winpid=$(gs_kv_get "$ENV_FILE" winpid)
  [ -n "$pid" ] && gs_alive "$pid" "$winpid" && printf '%s\n' "$pid"
}

token() {
  local t
  t=$(gs_kv_get "$ENV_FILE" token)
  [ -n "$t" ] || gs_die "no mock started by mock.sh on port $port (mock.sh --port $port start)"
  printf '%s' "$t"
}

need_mock() {
  gs_port_busy "$port" || gs_die "nothing answers on port $port: mock.sh --port $port start"
}

curl_get() { curl -sS --max-time 30 "$@"; }
# POST JSON from stdin, following the 302 like Google does (curl turns the POST into a GET after a 302).
curl_post_json() { curl -sS -L --max-time 30 -H 'Content-Type: application/json' --data-binary @- "$1"; }
mj() { node "$GS_TOOLS/mockjson.js" "$@"; }

do_start() {
  local script=current tok=${MOCK_TOKEN:-e2e-test-token} fail="" fresh="" pid winpid i repo code_gs info
  while [ $# -gt 0 ]; do
    case $1 in
      --script) script=${2:?}; shift 2;;
      --token) tok=${2:?}; shift 2;;
      --fail-mode) fail=${2:-}; shift 2;;
      --fresh) fresh=1; shift;;
      *) gs_die "start: unknown option $1";;
    esac
  done
  repo=$(gs_repo)
  case $script in
    current | v3 | v2) code_gs=$script;;
    *) [ -f "$script" ] || gs_die "no Code.gs at $script"; code_gs=$(gs_path "$script");;
  esac
  pid=$(running_pid)
  if [ -n "$pid" ]; then
    if [ -z "$fresh" ] && [ "$(gs_kv_get "$ENV_FILE" script)" = "$code_gs" ] && [ "$(gs_kv_get "$ENV_FILE" token)" = "$tok" ] &&
      [ "$(gs_kv_get "$ENV_FILE" fail)" = "$fail" ]; then
      gs_log "mock already running on port $port (pid $pid, script $code_gs); keeping it and its sheet (--fresh restarts)"
      printf '%s\n' "$port" >"$GS_STATE/mock-last-port"
      do_status
      return 0
    fi
    gs_log "restarting the mock on port $port"
    do_stop >/dev/null
  fi
  if gs_port_busy "$port"; then
    gs_die "port $port is in use by a process mock.sh did not start: leaving it alone; use --port <another>"
  fi
  mkdir -p "$GS_OUT/logs"
  # CODE_GS "current" is the default of mock-server.js; pass it anyway so the log says what runs.
  PORT=$port TOKEN=$tok CODE_GS=$code_gs FAIL_MODE=$fail \
    nohup node "$repo/apps-script/test/mock-server.js" >"$LOG_FILE" 2>&1 </dev/null &
  pid=$!
  winpid=$(gs_winpid_of "$pid")
  {
    echo "pid=$pid"
    echo "winpid=$winpid"
    echo "port=$port"
    echo "script=$code_gs"
    echo "fail=$fail"
    echo "token=$tok"
  } >"$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null
  for ((i = 0; i < 60; i++)); do
    info=$(curl -s --max-time 2 "$BASE/debug/script" 2>/dev/null) && [ -n "$info" ] && break
    if ! gs_alive "$pid" "$winpid"; then
      gs_log "the mock exited:"
      sed "s/$tok/***/g" "$LOG_FILE" | tail -n 10 >&2
      rm -f "$ENV_FILE"
      exit 1
    fi
    sleep 0.25
  done
  [ -n "$info" ] || { gs_log "the mock did not answer within 15 s (log: $LOG_FILE)"; do_stop; exit 1; }
  printf '%s\n' "$port" >"$GS_STATE/mock-last-port"
  gs_log "mock started"
  do_status
}

do_stop() {
  local pid winpid
  pid=$(gs_kv_get "$ENV_FILE" pid)
  winpid=$(gs_kv_get "$ENV_FILE" winpid)
  if [ -z "$pid" ]; then
    echo "no mock started by mock.sh on port $port: nothing to stop"
    return 0
  fi
  if gs_alive "$pid" "$winpid"; then
    gs_stop_pid "$pid" "$winpid" 5 || gs_die "could not stop the mock (pid $pid)"
    echo "stopped the mock on port $port (pid $pid)"
  else
    echo "the mock on port $port (pid $pid) had already exited"
  fi
  rm -f "$ENV_FILE"
}

do_status() {
  local pid info
  pid=$(running_pid)
  if [ -z "$pid" ]; then
    echo "no mock started by mock.sh is running on port $port"
    gs_port_busy "$port" && echo "(something else listens on $port)"
    return 1
  fi
  info=$(curl -s --max-time 5 "$BASE/debug/script" | mj compact)
  echo "pid:       $pid"
  echo "script:    $(gs_kv_get "$ENV_FILE" script)  $info"
  [ -n "$(gs_kv_get "$ENV_FILE" fail)" ] && echo "fail mode: $(gs_kv_get "$ENV_FILE" fail) (at start)"
  echo "emulator:  http://10.0.2.2:$port$EXEC_PATH"
  echo "this PC:   $BASE$EXEC_PATH"
  echo "token:     (hidden; in $ENV_FILE)"
  echo "sheet:     $BASE/debug/sheets"
  echo "log:       $(gs_path "$LOG_FILE")"
}

post_debug() {  # post_debug <route> <json>
  need_mock
  printf '%s' "$2" | curl -sS --max-time 30 -H 'Content-Type: application/json' --data-binary @- "$BASE/debug/$1"
  echo
}

apply_changes() {  # apply_changes <protocol> <changes json>
  local body
  need_mock
  body=$(GS_MOCK_TOKEN=$(token) mj apply-body "$device" "$1" "$2") || exit 1
  printf '%s' "$body" | curl_post_json "$BASE$EXEC_PATH" | mj results
}

case $cmd in
  start) do_start "$@";;
  stop) do_stop;;
  status) do_status;;
  url)
    if [ "${1:-}" = --host ]; then echo "$BASE$EXEC_PATH"; else echo "http://10.0.2.2:$port$EXEC_PATH"; fi;;
  log) tail -n "${1:-30}" "$LOG_FILE" | sed "s/token \"[^\"]*\"/token \"***\"/";;
  sheets)
    need_mock
    case ${1:-Items} in
      --json) curl_get "$BASE/debug/sheets";;
      --names) curl_get "$BASE/debug/sheets" | mj sheet-names;;
      *) curl_get "$BASE/debug/sheets" | mj rows "${1:-Items}";;
    esac;;
  hand-edit)
    need_mock
    sheet=Items
    args=()
    while [ $# -gt 0 ]; do
      case $1 in --sheet) sheet=${2:?}; shift 2;; *) args+=("$1"); shift;; esac
    done
    if [ ${#args[@]} -eq 1 ] && [[ "${args[0]}" == \{* ]]; then
      post_debug hand-edit "${args[0]}"
    else
      [ ${#args[@]} -eq 3 ] || gs_die "hand-edit <row|item name> <col|title> <value> [--sheet S]"
      body=$(curl_get "$BASE/debug/sheets" | mj cell "$sheet" "${args[0]}" "${args[1]}" "${args[2]}") || exit 1
      post_debug hand-edit "$body"
    fi;;
  upsert | delete | apply)
    proto=3
    args=()
    while [ $# -gt 0 ]; do
      case $1 in
        --device) device=${2:?}; shift 2;;
        --protocol) proto=${2:?}; shift 2;;
        *) args+=("$1"); shift;;
      esac
    done
    case $cmd in
      upsert) changes=$(mj upsert-change "${args[@]}") || exit 1;;
      delete) changes=$(mj delete-change "${args[@]}") || exit 1;;
      apply) [ ${#args[@]} -eq 1 ] || gs_die "apply '<changes json>'"; changes=${args[0]};;
    esac
    apply_changes "$proto" "$changes";;
  list | ping)
    need_mock
    q="action=$cmd"
    v3="&v=3"
    while [ $# -gt 0 ]; do
      case $1 in
        --v2) v3=""; shift;;
        --since) q+="&since=${2:?}"; shift 2;;
        *) gs_die "$cmd: unknown option $1";;
      esac
    done
    curl -sS -L --max-time 30 "$BASE$EXEC_PATH?$q$v3&token=$(token)" | mj compact;;
  fail-mode)
    if [ -n "${2:-}" ]; then
      body=$(node -e 'console.log(JSON.stringify({mode: process.argv[1], detail: process.argv[2]}))' "${1:-}" "$2")
    else
      body=$(node -e 'console.log(JSON.stringify({mode: process.argv[1] || ""}))' "${1:-}")
    fi
    post_debug fail-mode "$body";;
  script)
    need_mock
    if [ -n "${1:-}" ]; then post_debug script "{\"script\":\"$1\"}"; else curl_get "$BASE/debug/script"; echo; fi;;
  seed-v2 | validation)
    [ -n "${1:-}" ] || gs_die "$cmd '<json>'"
    post_debug "$cmd" "$1";;
  *) gs_log "unknown command: $cmd"; usage 2;;
esac
