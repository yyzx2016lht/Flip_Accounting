package tao.test.tapaccounting.logic

import androidx.room.withTransaction
import tao.test.tapaccounting.TapApplication
import tao.test.tapaccounting.Logger
import tao.test.tapaccounting.Prefs
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.data.local.entity.Bill

object BillMutationService {

    private const val REFUND_CATEGORY_PREFIX = "\u9000\u6b3e\uff1a"

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun stripRefundPrefix(categoryName: String): String {
        return CategoryNameNormalizer.stripRefundPrefix(categoryName)
    }

    private fun normalizeBillCategoryName(bill: Bill): Bill {
        return bill.copy(categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName))
    }

    private fun logFull(tag: String, message: String) {
        val ctx = runCatching { TapApplication.app() }.getOrNull() ?: return
        if (!Prefs.isDeveloperFullLoggingEnabled(ctx)) return
        Logger.d(ctx, tag, message)
    }

    private fun auditBill(op: String, bill: Bill) {
        logFull(
            "BILL_MUTATION",
            "AUDIT_BILL|op=$op|billId=${bill.id}|type=${bill.type}|subType=${bill.subType}|amount=${bill.amount}|asset=${bill.accountName}|toAsset=${bill.toAccountName}|currency=${bill.currency}|timeMs=${bill.time}"
        )
    }

    suspend fun insertBillAndApplyImpact(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "insert:start type=${bill.type}, amount=${bill.amount}, account=${bill.accountName}, to=${bill.toAccountName}, assetImpact=$applyAssetImpact")
        return db.withTransaction {
            val normalizedInput = normalizeBillCategoryName(bill)
            if (applyAssetImpact) {
                validateRequiredRatesForBill(db, normalizedInput)
            }
            val savedBill = normalizedInput.copy(id = db.billDao().insertBill(normalizedInput))
            if (applyAssetImpact) {
                val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
                if (impacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logFull("BILL_GUARD", "（警告）insert 已写账单但资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
                }
            }
            logFull("BILL_MUTATION", "insert:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}, accountId=${savedBill.accountId}, toAccountId=${savedBill.toAccountId}")
            auditBill("insert", savedBill)
            savedBill
        }
    }

    suspend fun insertBillWithinActiveTransaction(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "insertTx:start type=${bill.type}, amount=${bill.amount}, account=${bill.accountName}, to=${bill.toAccountName}, assetImpact=$applyAssetImpact")
        val normalizedInput = normalizeBillCategoryName(bill)
        if (applyAssetImpact) {
            validateRequiredRatesForBill(db, normalizedInput)
        }
        val savedBill = normalizedInput.copy(id = db.billDao().insertBill(normalizedInput))
        if (applyAssetImpact) {
            val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
            if (impacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logFull("BILL_GUARD", "（警告）insertTx 已写账单但资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
            }
        }
        logFull("BILL_MUTATION", "insertTx:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}")
        auditBill("insert_tx", savedBill)
        return savedBill
    }

    suspend fun upsertBillAndApplyImpact(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        return insertBillAndApplyImpact(db, bill, applyAssetImpact)
    }

    suspend fun replaceBill(
        db: AppDatabase,
        oldBill: Bill,
        newBill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        logFull("BILL_MUTATION", "replace:start oldId=${oldBill.id}, oldType=${oldBill.type}, oldAmount=${oldBill.amount}, newType=${newBill.type}, newAmount=${newBill.amount}, assetImpact=$applyAssetImpact")
        return db.withTransaction {
            val normalizedBill = when {
                oldBill.subType == Bill.SUBTYPE_REFUND -> {
                    val fallbackCategory = stripRefundPrefix(oldBill.categoryName)
                    val inputCategory = stripRefundPrefix(newBill.categoryName)
                    val normalizedCategory = if (inputCategory.isNotEmpty()) inputCategory else fallbackCategory
                    newBill.copy(
                        subType = Bill.SUBTYPE_REFUND,
                        relatedBillId = oldBill.relatedBillId,
                        categoryName = "$REFUND_CATEGORY_PREFIX$normalizedCategory",
                        originalAmount = newBill.amount
                    )
                }

                oldBill.type == Bill.TYPE_EXPENSE && baseOriginalAmount(oldBill) > oldBill.amount -> {
                    val oldBaseOriginalAmount = baseOriginalAmount(oldBill)
                    newBill.copy(
                        amount = newBill.amount.coerceIn(0.0, oldBaseOriginalAmount),
                        originalAmount = oldBaseOriginalAmount
                    )
                }

                else -> {
                    newBill.copy(
                        originalAmount = newBill.amount
                    )
                }
            }
            val normalizedBillForStorage = normalizeBillCategoryName(normalizedBill)

            if (oldBill.subType == Bill.SUBTYPE_REFUND && oldBill.relatedBillId != null) {
                val sourceBill = db.billDao().getBillById(oldBill.relatedBillId)
                if (sourceBill != null) {
                    val sourceBaseOriginalAmount = baseOriginalAmount(sourceBill)
                    val delta = normalizedBillForStorage.amount - oldBill.amount
                    val newSourceActualAmount = (sourceBill.amount - delta).coerceIn(0.0, sourceBaseOriginalAmount)
                    db.billDao().updateBill(
                        sourceBill.copy(
                            amount = newSourceActualAmount,
                            originalAmount = sourceBaseOriginalAmount
                        )
                    )
                }
            }

            if (applyAssetImpact) {
                validateRequiredRatesForBill(db, normalizedBillForStorage)
            }

            val oldImpacted = BillAssetImpactService.revertBillBalanceImpact(db, oldBill)
            if (applyAssetImpact && oldImpacted == 0 && oldBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logFull("BILL_GUARD", "（警告）replace 回滚旧账单时资产未变化，oldBillId=${oldBill.id}, type=${oldBill.type}, asset=${oldBill.accountName}, toAsset=${oldBill.toAccountName}")
            }

            // 编辑路径：使用 updateBill 而非 insertBill，避免外键级联删除/重建
            val savedBill = if (normalizedBillForStorage.id > 0L) {
                db.billDao().updateBill(normalizedBillForStorage)
                normalizedBillForStorage
            } else {
                normalizedBillForStorage.copy(id = db.billDao().insertBill(normalizedBillForStorage))
            }
            if (applyAssetImpact) {
                val newImpacted = BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
                if (newImpacted == 0 && savedBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logFull("BILL_GUARD", "（警告）replace 写入新账单后资产未变化，billId=${savedBill.id}, type=${savedBill.type}, asset=${savedBill.accountName}, toAsset=${savedBill.toAccountName}")
                }
            }
            logFull("BILL_MUTATION", "replace:done id=${savedBill.id}, type=${savedBill.type}, amount=${savedBill.amount}, category=${savedBill.categoryName}")
            auditBill("replace", savedBill)
            savedBill
        }
    }

    suspend fun saveRefundBill(
        db: AppDatabase,
        originalBill: Bill,
        refundBill: Bill,
        previousRefundBill: Bill? = null
    ): Bill {
        logFull("BILL_MUTATION", "refund:start sourceId=${originalBill.id}, refundId=${refundBill.id}, refundAmount=${refundBill.amount}")
        return db.withTransaction {
            val latestOriginal = db.billDao().getBillById(originalBill.id)
                ?: error("Original bill not found")

            require(latestOriginal.type == Bill.TYPE_EXPENSE && latestOriginal.subType != Bill.SUBTYPE_REFUND) {
                "Original bill is not refundable"
            }

            val existingRefund = when {
                previousRefundBill != null -> {
                    db.billDao().getBillById(previousRefundBill.id) ?: previousRefundBill
                }
                refundBill.id > 0L -> db.billDao().getBillById(refundBill.id)
                else -> null
            }

            val oldRefundAmount = existingRefund?.amount ?: 0.0
            val delta = refundBill.amount - oldRefundAmount
            require(delta <= latestOriginal.amount + 1e-9) {
                "Refund amount exceeds remaining expense"
            }

            val baseOriginalAmount = baseOriginalAmount(latestOriginal)
            val newActualExpense = (latestOriginal.amount - delta).coerceIn(0.0, baseOriginalAmount)
            db.billDao().updateBill(
                latestOriginal.copy(
                    amount = newActualExpense,
                    originalAmount = baseOriginalAmount
                )
            )

            val sourceCategory = stripRefundPrefix(latestOriginal.categoryName)
            val normalizedRefundBill = refundBill.copy(
                id = existingRefund?.id ?: refundBill.id,
                type = Bill.TYPE_INCOME,
                subType = Bill.SUBTYPE_REFUND,
                categoryId = latestOriginal.categoryId,
                categoryName = "$REFUND_CATEGORY_PREFIX$sourceCategory",
                currency = latestOriginal.currency,
                exchangeRate = 1.0,
                relatedBillId = latestOriginal.id,
                bookName = latestOriginal.bookName,
                originalAmount = refundBill.amount
            )
            val normalizedRefundForStorage = normalizeBillCategoryName(normalizedRefundBill)

            // 编辑已有退款账单时用 updateBill，新建时才用 insertBill
            validateRequiredRatesForBill(db, normalizedRefundForStorage)
            existingRefund?.let {
                val impacted = BillAssetImpactService.revertBillBalanceImpact(db, it)
                if (impacted == 0 && it.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                    logFull("BILL_GUARD", "（警告）refund 编辑回滚旧退款时资产未变化，billId=${it.id}, type=${it.type}, asset=${it.accountName}, toAsset=${it.toAccountName}")
                }
            }

            val savedRefundBill = if (normalizedRefundForStorage.id > 0L) {
                db.billDao().updateBill(normalizedRefundForStorage)
                normalizedRefundForStorage
            } else {
                normalizedRefundForStorage.copy(id = db.billDao().insertBill(normalizedRefundForStorage))
            }
            val impacted = BillAssetImpactService.applyBillBalanceImpact(db, savedRefundBill)
            if (impacted == 0 && savedRefundBill.type in setOf(Bill.TYPE_EXPENSE, Bill.TYPE_INCOME, Bill.TYPE_TRANSFER)) {
                logFull("BILL_GUARD", "（警告）refund 写入后资产未变化，billId=${savedRefundBill.id}, type=${savedRefundBill.type}, asset=${savedRefundBill.accountName}, toAsset=${savedRefundBill.toAccountName}")
            }
            logFull("BILL_MUTATION", "refund:done refundId=${savedRefundBill.id}, sourceId=${latestOriginal.id}, refundAmount=${savedRefundBill.amount}, sourceCurrentAmount=${newActualExpense}")
            auditBill("refund", savedRefundBill)
            savedRefundBill
        }
    }

    suspend fun resolveRefundSourceBill(db: AppDatabase, refundBill: Bill): Bill? {
        refundBill.relatedBillId?.let { id ->
            db.billDao().getBillById(id)?.let { return it }
        }
        if (refundBill.subType != Bill.SUBTYPE_REFUND) return null
        val sourceCategory = stripRefundPrefix(refundBill.categoryName).ifBlank { return null }
        val source = db.billDao().findLikelyRefundSourceBill(
            bookName = refundBill.bookName,
            categoryName = sourceCategory,
            refundAmount = refundBill.amount,
            refundAccountName = refundBill.accountName,
            refundTime = refundBill.time
        ) ?: return null
        if (refundBill.id > 0L && refundBill.relatedBillId == null) {
            db.billDao().updateBill(refundBill.copy(relatedBillId = source.id))
        }
        return source
    }

    private suspend fun validateRequiredRatesForBill(db: AppDatabase, bill: Bill) {
        val sourceAsset = bill.accountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.accountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        val targetAsset = bill.toAccountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.toAccountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        BillAssetImpactService.ensureRatesForImpact(bill, sourceAsset, targetAsset)
    }
}
