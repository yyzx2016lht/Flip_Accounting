package com.taostudio.tapaccounting

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuSafe {
    private const val TAG = "ShizukuSafe"

    @Volatile
    private var cachedReady: Boolean = false

    @Volatile
    private var cachedAtMs: Long = 0L

    private const val CACHE_WINDOW_MS = 500L

    fun isReady(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs <= CACHE_WINDOW_MS) {
            return cachedReady
        }
        val ready = isBinderAlive() && hasPermission(context)
        cachedReady = ready
        cachedAtMs = now
        return ready
    }

    fun isBinderAlive(): Boolean {
        return runSafely(false, "pingBinder") {
            Shizuku.pingBinder()
        }
    }

    fun hasPermission(context: Context): Boolean {
        return runSafely(false, "checkSelfPermission") {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermission(activity: Activity, requestCode: Int): Boolean {
        return runSafely(false, "requestPermission") {
            if (!isBinderAlive()) return@runSafely false
            Shizuku.requestPermission(requestCode)
            true
        }
    }

    private inline fun <T> runSafely(defaultValue: T, action: String, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku $action failed: ${t.message}")
            defaultValue
        }
    }
}


