package com.taostudio.tapaccounting.data.sync

import android.content.Context
import androidx.work.*
import com.taostudio.tapaccounting.data.local.AppDatabase
import java.util.concurrent.TimeUnit

class SharedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SharedSyncEngine(applicationContext, AppDatabase.getDatabase(applicationContext)).syncAll()
        Result.success()
    } catch (_: Exception) {
        // 待上传队列保存在数据库中；下一次启动、记账或下拉会重试，避免 503 时持续轰炸服务端。
        Result.success()
    }
}

object SharedSyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun enqueueNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("shared-ledger-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SharedSyncWorker>().setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build())
    }

    fun cancelPending(context: Context) = WorkManager.getInstance(context).cancelUniqueWork("shared-ledger-sync")
}
