#!/system/bin/sh

ui_print "***************************************"
ui_print " TapAccounting KeepAlive"
ui_print "***************************************"
ui_print "Package: com.taostudio.tapaccounting"
ui_print "Adds device-idle/appops allowlists."
ui_print "Restores the app if OEM battery manager force-stops it."
ui_print "Log: /data/adb/tapaccounting-keepalive.log"
ui_print "Edit config.env if you want whitelist-only mode."

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/config.env" 0 0 0644
