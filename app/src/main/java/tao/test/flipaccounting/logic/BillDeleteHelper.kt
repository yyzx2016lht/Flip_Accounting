package tao.test.flipaccounting.logic

import androidx.room.withTransaction
import tao.test.flipaccounting.FlipApplication
import tao.test.flipaccounting.Logger
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill

object BillDeleteHelper {
    private fun logFull(tag: String, message: String) {
        val ctx = runCatching { FlipApplication.app() }.getOrNull() ?: return
        if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return
        Logger.d(ctx, tag, message)
    }

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
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_refund 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT ||
                latestBill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_adjust 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
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
                            val impacted = BillAssetImpactService.revertBillBalanceImpact(db, refund)
                            if (impacted == 0) {
                                logFull("BILL_GUARD", "（警告）delete_refund_linked 删除关联退款时资产未变化，billId=${refund.id}, asset=${refund.accountName}, toAsset=${refund.toAccountName}")
                            }
                        }
                        billDao.delete(refundsToDelete)
                    }
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_expense 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_INCOME -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_income 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                latestBill.type == Bill.TYPE_TRANSFER -> {
                    val impacted = BillAssetImpactService.revertBillBalanceImpact(db, latestBill)
                    if (impacted == 0) {
                        logFull("BILL_GUARD", "（警告）delete_transfer 删除账单时资产未变化，billId=${latestBill.id}, asset=${latestBill.accountName}, toAsset=${latestBill.toAccountName}")
                    }
                    billDao.delete(latestBill)
                }

                else -> billDao.delete(latestBill)
            }
        }
    }
}
