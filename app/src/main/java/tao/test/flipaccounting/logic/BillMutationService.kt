package tao.test.flipaccounting.logic

import androidx.room.withTransaction
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill

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

    suspend fun insertBillAndApplyImpact(
        db: AppDatabase,
        bill: Bill,
        applyAssetImpact: Boolean = true
    ): Bill {
        return db.withTransaction {
            val normalizedInput = normalizeBillCategoryName(bill)
            if (applyAssetImpact) {
                validateRequiredRatesForBill(db, normalizedInput)
            }
            val savedBill = normalizedInput.copy(id = db.billDao().insertBill(normalizedInput))
            if (applyAssetImpact) {
                BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
            }
            savedBill
        }
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

            BillAssetImpactService.revertBillBalanceImpact(db, oldBill)

            // 编辑路径：使用 updateBill 而非 insertBill，避免外键级联删除/重建
            val savedBill = if (normalizedBillForStorage.id > 0L) {
                db.billDao().updateBill(normalizedBillForStorage)
                normalizedBillForStorage
            } else {
                normalizedBillForStorage.copy(id = db.billDao().insertBill(normalizedBillForStorage))
            }
            if (applyAssetImpact) {
                BillAssetImpactService.applyBillBalanceImpact(db, savedBill)
            }
            savedBill
        }
    }

    suspend fun saveRefundBill(
        db: AppDatabase,
        originalBill: Bill,
        refundBill: Bill,
        previousRefundBill: Bill? = null
    ): Bill {
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
            existingRefund?.let { BillAssetImpactService.revertBillBalanceImpact(db, it) }

            val savedRefundBill = if (normalizedRefundForStorage.id > 0L) {
                db.billDao().updateBill(normalizedRefundForStorage)
                normalizedRefundForStorage
            } else {
                normalizedRefundForStorage.copy(id = db.billDao().insertBill(normalizedRefundForStorage))
            }
            BillAssetImpactService.applyBillBalanceImpact(db, savedRefundBill)
            savedRefundBill
        }
    }

    private suspend fun validateRequiredRatesForBill(db: AppDatabase, bill: Bill) {
        val sourceAsset = bill.accountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.accountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        val targetAsset = bill.toAccountId?.let { db.assetDao().getAssetById(it) }
            ?: bill.toAccountName.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
        BillAssetImpactService.ensureRatesForImpact(bill, sourceAsset, targetAsset)
    }
}
