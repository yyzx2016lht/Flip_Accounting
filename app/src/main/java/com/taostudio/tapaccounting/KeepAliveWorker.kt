package com.taostudio.tapaccounting

import android.content.Context
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
        private const val UNIQUE_HOURLY_RESTART = "overlay_hourly_restart"

        fun cancelPeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
        }

        fun cancelHourlyRestart(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_HOURLY_RESTART)
        }

        fun cancelOneTime(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_RESTART)
        }
    }

    override suspend fun doWork(): Result {
        log("doWork: legacy keep-alive work ignored")
        cancelPeriodic(applicationContext)
        cancelHourlyRestart(applicationContext)
        cancelOneTime(applicationContext)
        return Result.success()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        Logger.d(applicationContext, TAG, message)
    }
}

