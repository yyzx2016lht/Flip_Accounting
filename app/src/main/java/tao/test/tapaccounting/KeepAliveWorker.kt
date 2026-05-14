package tao.test.tapaccounting

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val UNIQUE_NAME = "overlay_keep_alive"
        private const val UNIQUE_RESTART = "overlay_restart"
        private const val UNIQUE_HOURLY_RESTART = "overlay_hourly_restart"
        private val restartMutex = Mutex()

        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).addTag("periodic_check")
             .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
        }

        fun scheduleHourlyRestart(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                1, java.util.concurrent.TimeUnit.HOURS
            ).setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)
             .addTag("hourly_restart")
             .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_HOURLY_RESTART,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelHourlyRestart(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_HOURLY_RESTART)
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
        return restartMutex.withLock {
            log("doWork: checking OverlayService status")
            val tapEnabled = Prefs.isDoubleTapEnabled(applicationContext)
            if (!tapEnabled) {
                log("doWork: tap disabled, nothing to do")
                return@withLock Result.success()
            }

            val isHourlyRestart = tags.contains("hourly_restart")

            if (isHourlyRestart) {
                log("doWork: hourly restart disabled in quiet background mode")
                cancelHourlyRestart(applicationContext)
                return@withLock Result.success()
            }

            if (OverlayService.isServiceRunning) {
                log("doWork: service already running")
                return@withLock Result.success()
            }
            log("doWork: service not running, restarting...")
            try {
                val intent = Intent(applicationContext, OverlayService::class.java)
                OverlayService.startCompat(applicationContext, intent)
                log("doWork: restart requested")
            } catch (e: Exception) {
                log("doWork: restart failed: ${e.message}")
            }
            return@withLock Result.success()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        Logger.d(applicationContext, TAG, message)
    }
}
