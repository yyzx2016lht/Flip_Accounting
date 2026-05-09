package tao.test.tapaccounting

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*

class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val UNIQUE_NAME = "overlay_keep_alive"
        private const val UNIQUE_RESTART = "overlay_restart"

        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
        }

        fun scheduleOneTime(ctx: Context, delayMs: Long = 3_000L) {
            val request = OneTimeWorkRequestBuilder<KeepAliveWorker>()
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_RESTART,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancelOneTime(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_RESTART)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: checking OverlayService status")
        val tapEnabled = Prefs.isDoubleTapEnabled(applicationContext)
        if (!tapEnabled) {
            Log.d(TAG, "doWork: tap disabled, nothing to do")
            return Result.success()
        }
        if (OverlayService.isServiceRunning) {
            Log.d(TAG, "doWork: service already running")
            return Result.success()
        }
        Log.d(TAG, "doWork: service not running, restarting...")
        try {
            val intent = Intent(applicationContext, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.d(TAG, "doWork: restart failed: ${e.message}")
        }
        return Result.success()
    }
}
