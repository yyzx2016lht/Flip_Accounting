package tao.test.tapaccounting

import android.content.Context
import android.content.Intent
import android.os.Build
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
            Log.d(TAG, "doWork: checking OverlayService status")
            val tapEnabled = Prefs.isDoubleTapEnabled(applicationContext)
            if (!tapEnabled) {
                Log.d(TAG, "doWork: tap disabled, nothing to do")
                return@withLock Result.success()
            }

            val isHourlyRestart = tags.contains("hourly_restart")

            if (isHourlyRestart) {
                if (!Prefs.isAggressiveKeepAliveEnabled(applicationContext)) {
                    Log.d(TAG, "doWork: hourly restart skipped, aggressive keep-alive disabled")
                    cancelHourlyRestart(applicationContext)
                    return@withLock Result.success()
                }
                Log.d(TAG, "doWork: hourly restart, restarting service...")
                try {
                    val stopIntent = Intent(applicationContext, OverlayService::class.java)
                    applicationContext.stopService(stopIntent)
                } catch (e: Exception) {
                    Log.d(TAG, "doWork: stop service failed: ${e.message}")
                }
                try {
                    val intent = Intent(applicationContext, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(intent)
                    } else {
                        applicationContext.startService(intent)
                    }
                    Log.d(TAG, "doWork: hourly restart completed")
                } catch (e: Exception) {
                    Log.d(TAG, "doWork: hourly restart failed: ${e.message}")
                }
                return@withLock Result.success()
            }

            if (OverlayService.isServiceRunning) {
                Log.d(TAG, "doWork: service already running")
                return@withLock Result.success()
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
            return@withLock Result.success()
        }
    }
}
