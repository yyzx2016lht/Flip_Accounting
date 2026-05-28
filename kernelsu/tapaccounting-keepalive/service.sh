#!/system/bin/sh

MODDIR=${0%/*}
CONFIG="$MODDIR/config.env"
[ -f "$CONFIG" ] && . "$CONFIG"

PKG=${PKG:-com.taostudio.tapaccounting}
USER_ID=${USER_ID:-0}
RESTART_ACTION=${RESTART_ACTION:-com.taostudio.tapaccounting.RESTART_SERVICE}
CHECK_INTERVAL=${CHECK_INTERVAL:-10}
LOG=/data/adb/tapaccounting-keepalive.log

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

run_quiet() {
  "$@" >/dev/null 2>&1
}

wait_boot_completed() {
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 5
  done
  sleep 10
}

package_exists() {
  pm path --user "$USER_ID" "$PKG" >/dev/null 2>&1
}

is_stopped() {
  dumpsys package "$PKG" 2>/dev/null | grep -m 1 "User $USER_ID:" | grep -q "stopped=true"
}

has_process() {
  pidof "$PKG" >/dev/null 2>&1
}

apply_allowlists() {
  run_quiet cmd deviceidle whitelist "+$PKG"
  run_quiet am set-inactive --user "$USER_ID" "$PKG" false
  run_quiet cmd appops set --user "$USER_ID" "$PKG" RUN_ANY_IN_BACKGROUND allow
  run_quiet cmd appops set --user "$USER_ID" "$PKG" RUN_IN_BACKGROUND allow
  run_quiet cmd appops set --user "$USER_ID" "$PKG" START_FOREGROUND allow
  run_quiet cmd appops set --user "$USER_ID" "$PKG" WAKE_LOCK allow
  run_quiet cmd appops set --user "$USER_ID" "$PKG" SYSTEM_ALERT_WINDOW allow
}

clear_stopped_and_restart() {
  run_quiet cmd package set-stopped-state --user "$USER_ID" "$PKG" false
  sleep 1
  am broadcast --user "$USER_ID" -a "$RESTART_ACTION" -p "$PKG" >> "$LOG" 2>&1
}

ensure_running() {
  has_process && return 0

  if is_stopped; then
    log "force-stopped; clearing state + broadcast"
  else
    log "no process; broadcast restart"
  fi
  clear_stopped_and_restart

  sleep 3
  if has_process; then
    log "app restored via broadcast"
    return 0
  fi

  log "broadcast failed; no activity fallback configured"
}

main_loop() {
  wait_boot_completed
  log "TapAccounting KeepAlive started (interval=${CHECK_INTERVAL}s)"

  while true; do
    if package_exists; then
      apply_allowlists
      ensure_running
    fi
    sleep "$CHECK_INTERVAL"
  done
}

main_loop &
