package com.taostudio.tapaccounting.logic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.taostudio.tapaccounting.data.local.AppDatabase
import java.util.concurrent.TimeUnit

class InvestmentInterestWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val db = AppDatabase.getDatabase(applicationContext)
            InvestmentInterestService.settleDueInterest(db)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        private const val UNIQUE_NAME = "investment_interest_settlement"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<InvestmentInterestWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
