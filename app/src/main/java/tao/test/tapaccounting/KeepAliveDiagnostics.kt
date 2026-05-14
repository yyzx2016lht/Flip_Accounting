package tao.test.tapaccounting

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object KeepAliveDiagnostics {
    private const val TAG = "KeepAliveDiag"

    fun logSnapshot(ctx: Context, reason: String) {
        val appCtx = ctx.applicationContext
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notificationChannelEnabled = isForegroundChannelEnabled(appCtx)
        val batteryIgnored = runCatching {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(appCtx.packageName)
        }.getOrDefault(false)
        val backgroundRestricted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.isBackgroundRestricted
            } else {
                false
            }
        }.getOrDefault(false)
        val standbyBucket = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                usm.appStandbyBucket
            } else {
                -1
            }
        }.getOrDefault(-1)
        val accessibilityEnabled = isAccessibilityServiceEnabled(appCtx)

        Logger.d(
            appCtx,
            TAG,
            "snapshot reason=$reason serviceRunning=${OverlayService.isServiceRunning} " +
                "doubleTap=${Prefs.isDoubleTapEnabled(appCtx)} notificationGranted=$notificationGranted " +
                "foregroundChannelEnabled=$notificationChannelEnabled batteryIgnored=$batteryIgnored " +
                "backgroundRestricted=$backgroundRestricted standbyBucket=$standbyBucket " +
                "accessibilityEnabled=$accessibilityEnabled " +
                "permanentWakeLock=${Prefs.isPermanentWakeLockEnabled(appCtx)} shizukuPersistence=${Prefs.isShizukuPersistenceEnabled(appCtx)}"
        )
    }

    private fun isForegroundChannelEnabled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)
    }

    private fun isAccessibilityServiceEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${ctx.packageName}/${KeepAliveAccessibilityService::class.java.name}"
        val expectedShort = "${ctx.packageName}/.${KeepAliveAccessibilityService::class.java.simpleName}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true) }
    }
}
