#!/system/bin/sh

PKG=com.taostudio.tapaccounting
LOG=/data/adb/tapaccounting-keepalive.log

echo "$(date '+%Y-%m-%d %H:%M:%S') TapAccounting KeepAlive uninstall cleanup" >> "$LOG"
cmd deviceidle whitelist "-$PKG" >/dev/null 2>&1
cmd appops reset "$PKG" >/dev/null 2>&1
