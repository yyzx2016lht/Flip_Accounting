package com.taostudio.tapaccounting.data.sync

import com.google.gson.Gson
import com.taostudio.tapaccounting.DeviceIdManager
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Budget
import java.util.UUID

object SharedBudgetHooks {
    private val gson = Gson()

    suspend fun save(db: AppDatabase, input: Budget): Budget {
        val ledger = db.sharedLedgerDao().getByBookId(input.bookId) ?: return input
        val slot = input.categoryName?.trim()?.lowercase().orEmpty().ifBlank { "__total__" }
        val entityId = input.sharedId ?: UUID.nameUUIDFromBytes("${ledger.uuid}|${input.yearMonth}|$slot".toByteArray()).toString()
        val revision = db.syncOperationDao().maxRevision(ledger.id, "budget", entityId) + 1
        val value = input.copy(sharedId = entityId, revision = revision, isShared = true, sharedDeviceId = DeviceIdManager.getDeviceId(TapApplication.app()))
        val savedId = db.budgetDao().saveForSlot(value)
        val saved = value.copy(id = savedId)
        SharedLedgerService(TapApplication.app(), db).enqueue(ledger.id, ledger.uuid, "budget", entityId, if (revision == 1L) "create" else "update", revision, ledger.localMemberId, gson.toJsonTree(saved.copy(id = 0)).asJsonObject)
        SharedSyncScheduler.enqueueNow(TapApplication.app())
        return saved
    }

    suspend fun delete(db: AppDatabase, budget: Budget) {
        val ledger = db.sharedLedgerDao().getByBookId(budget.bookId)
        if (ledger != null && budget.sharedId != null) {
            val revision = db.syncOperationDao().maxRevision(ledger.id, "budget", budget.sharedId) + 1
            SharedLedgerService(TapApplication.app(), db).enqueue(ledger.id, ledger.uuid, "budget", budget.sharedId, "delete", revision, ledger.localMemberId, null)
            SharedSyncScheduler.enqueueNow(TapApplication.app())
        }
        db.budgetDao().delete(budget)
    }
}
