package tao.test.flipaccounting.logic

import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill

object BillDeleteHelper {

    suspend fun deleteBillAndRevertBalance(db: AppDatabase, bill: Bill) {
        deleteBillAndRevertBalanceInternal(db, bill, backfillLinks = true)
    }

    suspend fun deleteBillsAndRevertBalance(db: AppDatabase, bills: List<Bill>) {
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
            deleteBillAndRevertBalanceInternal(db, bill, backfillLinks = false)
        }
    }

    private suspend fun deleteBillAndRevertBalanceInternal(
        db: AppDatabase,
        bill: Bill,
        backfillLinks: Boolean
    ) {
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
                billDao.delete(latestBill)
            }

            latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
            latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> {
                BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                billDao.delete(latestBill)
            }

            latestBill.type == Bill.TYPE_EXPENSE -> {
                val refunds = billDao.getRefundBillsBySourceId(latestBill.id)
                if (refunds.isNotEmpty()) {
                    billDao.delete(refunds)
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
