#!/system/bin/sh

MODDIR=${0%/*}
CONFIG="$MODDIR/config.env"
[ -f "$CONFIG" ] && . "$CONFIG"

PKG=${PKG:-com.taostudio.tapaccounting}
USER_ID=${USER_ID:-0}
RESTART_ACTION=${RESTART_ACTION:-com.taostudio.tapaccounting.RESTART_SERVICE}
LOG=/data/adb/tapaccounting-keepalive.log

echo "$(date '+%Y-%m-%d %H:%M:%S') manual action triggered" >> "$LOG"

cmd deviceidle whitelist "+$PKG" >/dev/null 2>&1
am set-inactive --user "$USER_ID" "$PKG" false >/dev/null 2>&1
cmd appops set --user "$USER_ID" "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
cmd appops set --user "$USER_ID" "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1
cmd appops set --user "$USER_ID" "$PKG" START_FOREGROUND allow >/dev/null 2>&1
cmd appops set --user "$USER_ID" "$PKG" WAKE_LOCK allow >/dev/null 2>&1
cmd appops set --user "$USER_ID" "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1

cmd package set-stopped-state --user "$USER_ID" "$PKG" false >/dev/null 2>&1
sleep 1
am broadcast --user "$USER_ID" -a "$RESTART_ACTION" -p "$PKG" >> "$LOG" 2>&1
