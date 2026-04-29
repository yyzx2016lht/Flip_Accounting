package tao.test.flipaccounting.logic

import androidx.room.withTransaction
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill

object BillDeleteHelper {

    suspend fun deleteBillAndRevertBalance(db: AppDatabase, bill: Bill) {
        deleteBillAndRevertBalanceInternal(
            db = db,
            bill = bill,
            backfillLinks = true,
            scopeBillIds = null
        )
    }

    suspend fun deleteBillsAndRevertBalance(db: AppDatabase, bills: List<Bill>) {
        deleteBillsAndRevertBalanceInternal(db, bills, scopeBillIds = null)
    }

    suspend fun deleteBillsAndRevertBalanceScoped(
        db: AppDatabase,
        bills: List<Bill>,
        scopeBillIds: Set<Long>
    ) {
        deleteBillsAndRevertBalanceInternal(
            db = db,
            bills = bills,
            scopeBillIds = scopeBillIds.filter { it > 0L }.toSet()
        )
    }

    private suspend fun deleteBillsAndRevertBalanceInternal(
        db: AppDatabase,
        bills: List<Bill>,
        scopeBillIds: Set<Long>?
    ) {
        if (bills.isEmpty()) return
        val uniqueBills = bills.distinctBy {
            if (it.id > 0L) {
                "id:${it.id}"
            } else {
                "tmp:${it.time}:${it.type}:${it.subType}:${it.amount}:${it.accountId}:${it.toAccountId}"
            }
        }
        if (uniqueBills.isEmpty()) return

        db.billDao().backfillAssetLinksByName()
        uniqueBills.forEach { bill ->
            deleteBillAndRevertBalanceInternal(
                db = db,
                bill = bill,
                backfillLinks = false,
                scopeBillIds = scopeBillIds
            )
        }
    }

    private suspend fun deleteBillAndRevertBalanceInternal(
        db: AppDatabase,
        bill: Bill,
        backfillLinks: Boolean,
        scopeBillIds: Set<Long>?
    ) {
        db.withTransaction {
            val billDao = db.billDao()
            if (backfillLinks) {
                billDao.backfillAssetLinksByName()
            }
            val latestBill = if (bill.id > 0L) billDao.getBillById(bill.id) ?: bill else bill

            when {
                latestBill.subType == Bill.SUBTYPE_REFUND -> {
                    val sourceId = latestBill.relatedBillId
                    if (sourceId != null) {
                        val original = billDao.getBillById(sourceId)
                        if (original != null) {
                            val baseOriginal = if (original.originalAmount > 0.0) {
                                kotlin.math.max(original.originalAmount, original.amount)
                            } else {
                                original.amount
                            }
                            // 使用 latestBill.amount（最新数据库值），避免传入快照金额不一致
                            val restored = (original.amount + latestBill.amount).coerceAtMost(baseOriginal)
                            billDao.updateBill(original.copy(amount = restored, originalAmount = baseOriginal))
                        }
                    }
                    BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    billDao.delete(latestBill)
                }

                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> {
                    BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_EXPENSE -> {
                    val refunds = billDao.getRefundBillsBySourceId(latestBill.id)
                    val refundsToDelete = when (scopeBillIds) {
                        null -> refunds
                        else -> refunds.filter { refund -> refund.id > 0L && scopeBillIds.contains(refund.id) }
                    }
                    if (refundsToDelete.isNotEmpty()) {
                        refundsToDelete.forEach { refund ->
                            BillAssetImpactService.revertBillBalanceImpact(db, refund)
                        }
                        billDao.delete(refundsToDelete)
                    }
                    BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_INCOME -> {
                    BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_TRANSFER -> {
                    BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    billDao.delete(latestBill)
                }

                else -> billDao.delete(latestBill)
            }
        }
    }
}
