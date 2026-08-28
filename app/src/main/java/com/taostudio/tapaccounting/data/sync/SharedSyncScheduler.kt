package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.work.*
import com.taostudio.tapaccounting.data.local.AppDatabase
import java.util.concurrent.TimeUnit

class SharedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val db = AppDatabase.getDatabase(applicationContext)
        val engine = SharedSyncEngine(applicationContext, db)
        val forceFull = inputData.getBoolean(KEY_FORCE_FULL, false)
        repeat(2) {
            engine.syncAll(forceFull = forceFull)
            if (db.syncQueueDao().countAll() == 0) return Result.success()
        }
        Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val KEY_FORCE_FULL = "force_full"
    }
}

object SharedSyncScheduler {
    private const val PRIMARY_WORK = "shared-ledger-sync"
    private const val SAFETY_NET_WORK = "shared-ledger-sync-safety-net"
    private const val FULL_REFRESH_WORK = "shared-ledger-sync-full-refresh"
    private const val FULL_REFRESH_DEBOUNCE_MS = 15_000L
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private var lastFullRefreshEnqueuedAt = 0L

    fun enqueueNow(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            PRIMARY_WORK,
            ExistingWorkPolicy.KEEP,
            request(initialDelaySeconds = 3)
        )
        // KEEP can ignore a request arriving while the primary worker is finishing.
        // A replaceable delayed tail coalesces bursts and guarantees one pass after
        // the final mutation without increasing WebDAV traffic during the quiet period.
        workManager.enqueueUniqueWork(
            SAFETY_NET_WORK,
            ExistingWorkPolicy.REPLACE,
            request(initialDelaySeconds = 12)
        )
    }

    /** Coalesced user-visible refresh; the global gate still serializes it with uploads. */
    @Synchronized
    fun enqueueFullNow(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFullRefreshEnqueuedAt < FULL_REFRESH_DEBOUNCE_MS) return
        lastFullRefreshEnqueuedAt = now
        WorkManager.getInstance(context).enqueueUniqueWork(
            FULL_REFRESH_WORK,
            ExistingWorkPolicy.KEEP,
            request(initialDelaySeconds = 0, forceFull = true)
        )
    }

    private fun request(initialDelaySeconds: Long, forceFull: Boolean = false): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<SharedSyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(SharedSyncWorker.KEY_FORCE_FULL to forceFull))
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

    fun cancelPending(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PRIMARY_WORK)
            cancelUniqueWork(SAFETY_NET_WORK)
            cancelUniqueWork(FULL_REFRESH_WORK)
        }
    }
}
