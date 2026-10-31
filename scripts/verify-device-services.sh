#!/bin/bash
# F7: one place that starts/stops/health-checks every service the strict device gate depends on.
#   start <log>  — starts 18080 network fixture + 18450 sync server with a throwaway DB, waits for health, fails loudly
#   stop  <log>  — stops only the processes this script started (PID file), never someone else's servers
#   status       — prints health of both ports
# Ports already in use (or services that never become healthy) are a hard error: an old process must not silently stand
# in for this run's dependency.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ACTION="${1:-status}"
LOG="${2:-/tmp/subtrack-device-services.log}"
PIDFILE="${SUBTRACK_SERVICE_PIDFILE:-/tmp/subtrack-device-services.pids}"
FIXTURE_PORT="${SUBTRACK_FIXTURE_PORT:-18080}"
SYNC_PORT="${SUBTRACK_SYNC_PORT:-18450}"
SYNC_DATA="${SUBTRACK_SYNC_DATA:-$(mktemp -d /tmp/subtrack-sync.XXXXXX)/subtrack.sqlite}"
# Same fabricated test-only setup token the device tests use (single source: the test source).
SETUP_TOKEN="${SUBTRACK_SETUP_TOKEN:-$(sed -n 's/.*private val setupToken = "\(.*\)"/\1/p' \
  "$ROOT/app/src/androidTest/java/com/subtrack/SyncE2EDeviceTest.kt" | head -1)}"
[ "${#SETUP_TOKEN}" -ge 32 ] || { echo "FAIL: could not derive the test setup token" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing dependency: $1" >&2; exit 1; }; }
healthy_fixture() { curl -fsS -m 2 "http://127.0.0.1:${FIXTURE_PORT}/health" >/dev/null 2>&1 || \
  curl -fsS -m 2 "http://127.0.0.1:${FIXTURE_PORT}/templates" >/dev/null 2>&1; }
healthy_sync() { curl -fsS -m 2 -o /dev/null "http://127.0.0.1:${SYNC_PORT}/v1/register" -X POST -d '{}' 2>/dev/null && return 0; \
  # 401 is the expected "server is up but unauthenticated" answer
  [ "$(curl -s -m 2 -o /dev/null -w '%{http_code}' "http://127.0.0.1:${SYNC_PORT}/v1/register" -X POST -d '{}' 2>/dev/null)" = "401" ]; }

port_busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

start() {
  need python3; need curl; need lsof
  : > "$LOG"
  if port_busy "$FIXTURE_PORT"; then echo "FAIL: port ${FIXTURE_PORT} already in use (refusing to reuse an unknown process)" | tee -a "$LOG" >&2; exit 1; fi
  if port_busy "$SYNC_PORT"; then echo "FAIL: port ${SYNC_PORT} already in use (refusing to reuse an unknown process)" | tee -a "$LOG" >&2; exit 1; fi
  : > "$PIDFILE"
  # `exec` keeps the recorded PID identical to the python process, so `stop` kills exactly what this script started.
  ( cd "$ROOT" && exec python3 scripts/network-fixture.py >>"$LOG" 2>&1 ) & echo "fixture=$!" >>"$PIDFILE"
  ( cd "$ROOT/server" && SUBTRACK_DATA="$SYNC_DATA" SUBTRACK_BIND=127.0.0.1 PORT="$SYNC_PORT" \
      SUBTRACK_SETUP_TOKEN="$SETUP_TOKEN" SUBTRACK_DEBUG_HTTP=1 \
      exec python3 app.py >>"$LOG" 2>&1 ) & echo "sync=$!" >>"$PIDFILE"
  for _ in $(seq 1 40); do
    if healthy_fixture && healthy_sync; then
      echo "services ready: fixture ${FIXTURE_PORT}, sync ${SYNC_PORT}, db ${SYNC_DATA}" | tee -a "$LOG"
      return 0
    fi
    sleep 0.25
  done
  echo "FAIL: services did not become healthy in time; see ${LOG}" | tee -a "$LOG" >&2
  stop || true
  exit 1
}

stop() {
  if [ -f "$PIDFILE" ]; then
    while IFS='=' read -r name pid; do
      [ -n "${pid:-}" ] && kill "$pid" 2>/dev/null || true
    done < "$PIDFILE"
    rm -f "$PIDFILE"
  fi
  echo "services stopped" >> "$LOG" 2>/dev/null || true
}

status() {
  healthy_fixture && echo "fixture: up" || echo "fixture: down"
  healthy_sync && echo "sync: up" || echo "sync: down"
}

case "$ACTION" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "usage: $0 {start|stop|status} [log]" >&2; exit 2 ;;
esac
