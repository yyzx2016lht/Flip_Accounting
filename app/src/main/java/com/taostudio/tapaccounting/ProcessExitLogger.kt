package com.taostudio.tapaccounting

import android.app.Application
import android.util.Log

object ProcessExitLogger {
    private const val TAG = "ProcessExitLogger"
    private const val PREFS_KEY_LAST_HEARTBEAT = "last_overlay_heartbeat_ms"

    fun onAppCreate(app: Application) {
        logHeartbeatGap(app)
    }

    private fun logHeartbeatGap(app: Application) {
        val prefs = app.getSharedPreferences("flip_prefs", 0)
        val lastHeartbeat = prefs.getLong(PREFS_KEY_LAST_HEARTBEAT, 0L)
        if (lastHeartbeat > 0L) {
            val gap = (System.currentTimeMillis() - lastHeartbeat) / 1000L
            Log.i(TAG, "overlay heartbeat last seen ${gap}s ago")
        } else {
            Log.i(TAG, "no overlay heartbeat recorded yet (fresh install or first run)")
        }
    }

    fun recordHeartbeat(app: Application) {
        app.getSharedPreferences("flip_prefs", 0)
            .edit()
            .putLong(PREFS_KEY_LAST_HEARTBEAT, System.currentTimeMillis())
            .apply()
    }
}
