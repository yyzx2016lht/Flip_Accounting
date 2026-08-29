package com.taostudio.tapaccounting.data.sync

import androidx.room.withTransaction
import com.taostudio.tapaccounting.DeviceIdManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.TapApplication
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Budget
import java.util.UUID

object SharedBudgetHooks {
    suspend fun save(db: AppDatabase, input: Budget): Budget {
        var shouldScheduleSync = false
        val saved = db.withTransaction {
            val ledger = db.sharedLedgerDao().getByBookId(input.bookId)
            if (ledger == null) {
                return@withTransaction input.copy(id = db.budgetDao().saveForSlot(input))
            }
            val slot = input.categoryName?.trim()?.lowercase().orEmpty().ifBlank { "__total__" }
            val entityId = input.sharedId
                ?: UUID.nameUUIDFromBytes("${ledger.uuid}|${input.yearMonth}|$slot".toByteArray()).toString()
            val revision = db.syncOperationDao().maxRevision(ledger.id, "budget", entityId) + 1
            val value = input.copy(
                sharedId = entityId,
                revision = revision,
                isShared = true,
                sharedDeviceId = DeviceIdManager.getDeviceId(TapApplication.app())
            )
            val persisted = value.copy(id = db.budgetDao().saveForSlot(value))
            SharedLedgerService(TapApplication.app(), db).enqueue(
                ledger.id,
                ledger.uuid,
                "budget",
                entityId,
                if (revision == 1L) "create" else "update",
                revision,
                ledger.localMemberId,
                SharedBudgetPayloadCodec.encode(persisted)
            )
            shouldScheduleSync = true
            persisted
        }
        if (shouldScheduleSync) {
            Prefs.enableSharedBudgetDisplayDefaultsIfUnset(TapApplication.app(), input.bookName)
            SharedSyncScheduler.enqueueNow(TapApplication.app())
        }
        return saved
    }

    suspend fun delete(db: AppDatabase, budget: Budget) {
        var shouldScheduleSync = false
        db.withTransaction {
            val ledger = db.sharedLedgerDao().getByBookId(budget.bookId)
            if (ledger != null && budget.sharedId != null) {
                val revision = db.syncOperationDao().maxRevision(ledger.id, "budget", budget.sharedId) + 1
                SharedLedgerService(TapApplication.app(), db).enqueue(
                    ledger.id,
                    ledger.uuid,
                    "budget",
                    budget.sharedId,
                    "delete",
                    revision,
                    ledger.localMemberId,
                    null
                )
                shouldScheduleSync = true
            }
            db.budgetDao().delete(budget)
        }
        if (shouldScheduleSync) {
            SharedSyncScheduler.enqueueNow(TapApplication.app())
        }
    }
}
