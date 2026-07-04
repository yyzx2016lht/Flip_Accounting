package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import kotlin.math.abs

class RecurringBillingService(
    private val db: AppDatabase
) {
    suspend fun scanRecentBills(limit: Int = 500): Int {
        val bills = db.billDao().getRecentExpenseBills(limit)
        val candidates = RecurringBillDetector.detect(bills)
        var changed = 0
        for (candidate in candidates) {
            val existing = db.recurringPatternDao().getByMerchantKey(candidate.merchantKey)
            if (existing?.status == RecurringStatus.DISMISSED) continue

            val detected = RecurringBillDetector.toPattern(candidate)
            if (existing == null) {
                db.recurringPatternDao().insert(detected)
                changed++
            } else if (candidate.lastSeenAt > existing.lastSeenAt) {
                db.recurringPatternDao().update(
                    existing.copy(
                        categoryId = candidate.categoryId,
                        categoryName = candidate.categoryName,
                        accountName = candidate.accountName,
                        bookName = candidate.bookName,
                        amountApprox = candidate.medianAmount,
                        amountTolerance = candidate.tolerance,
                        frequency = candidate.frequency,
                        dayOfMonthHint = candidate.dayOfMonthHint,
                        lastSeenAt = candidate.lastSeenAt,
                        nextExpectedAt = calculateNextExpected(candidate.lastSeenAt, candidate.frequency),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                changed++
            }
        }
        return changed
    }

    suspend fun createManualPattern(
        merchantText: String,
        amountApprox: Double,
        amountTolerance: Double,
        frequency: RecurringFrequency,
        dayOfMonthHint: Int?,
        bookName: String,
        categoryId: Long?,
        categoryName: String?,
        accountName: String?
    ): RecurringPattern {
        val now = System.currentTimeMillis()
        val lastSeenAt = lastSeenForHint(now, frequency, dayOfMonthHint)
        val pattern = RecurringPattern(
            merchantKey = normalizeMerchantKey(merchantText),
            categoryId = categoryId,
            categoryName = categoryName,
            accountName = accountName,
            bookName = bookName,
            amountApprox = amountApprox,
            amountTolerance = amountTolerance.coerceAtLeast(0.0),
            frequency = frequency,
            dayOfMonthHint = dayOfMonthHint,
            lastSeenAt = lastSeenAt,
            nextExpectedAt = calculateNextExpected(lastSeenAt, frequency),
            status = RecurringStatus.CONFIRMED,
            createdAt = now,
            updatedAt = now
        )
        val existing = db.recurringPatternDao().getByMerchantKey(pattern.merchantKey)
        return if (existing == null) {
            pattern.copy(id = db.recurringPatternDao().insert(pattern))
        } else {
            val updated = pattern.copy(id = existing.id, createdAt = existing.createdAt)
            db.recurringPatternDao().update(updated)
            updated
        }
    }

    suspend fun matchNewBill(bill: Bill): RecurringPattern? {
        if (bill.type != Bill.TYPE_EXPENSE || bill.subType == Bill.SUBTYPE_REFUND) return null
        val merchantKey = normalizeMerchantKey(bill.remark.ifBlank { bill.categoryName })
        if (merchantKey.isBlank()) return null

        val patterns = db.recurringPatternDao().getAll()
            .filter { it.status == RecurringStatus.CONFIRMED || it.status == RecurringStatus.SUGGESTED }
        val matched = patterns.firstOrNull { pattern ->
            pattern.bookName == bill.bookName &&
                    pattern.merchantKey == merchantKey &&
                    (pattern.categoryId == null || bill.categoryId == pattern.categoryId) &&
                    (pattern.accountName.isNullOrBlank() || pattern.accountName == bill.accountName) &&
                    abs(bill.amount - pattern.amountApprox) <= pattern.amountTolerance.coerceAtLeast(1.0)
        } ?: return null

        val updated = matched.copy(
            amountApprox = (matched.amountApprox + bill.amount) / 2.0,
            lastSeenAt = bill.time,
            nextExpectedAt = calculateNextExpected(bill.time, matched.frequency),
            updatedAt = System.currentTimeMillis()
        )
        db.recurringPatternDao().update(updated)
        return updated
    }

    companion object {
        fun normalizeMerchantKey(text: String): String =
            text.trim().lowercase().replace("\\s+".toRegex(), "")

        fun calculateNextExpected(lastSeen: Long, frequency: RecurringFrequency): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = lastSeen
            when (frequency) {
                RecurringFrequency.WEEKLY -> cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                RecurringFrequency.MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
                RecurringFrequency.YEARLY -> cal.add(java.util.Calendar.YEAR, 1)
            }
            return cal.timeInMillis
        }

        private fun lastSeenForHint(
            now: Long,
            frequency: RecurringFrequency,
            dayOfMonthHint: Int?
        ): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = now
            if (frequency == RecurringFrequency.MONTHLY && dayOfMonthHint != null) {
                val safeDay = dayOfMonthHint.coerceIn(1, cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH))
                cal.set(java.util.Calendar.DAY_OF_MONTH, safeDay)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                if (cal.timeInMillis > now) {
                    cal.add(java.util.Calendar.MONTH, -1)
                }
                return cal.timeInMillis
            }
            return now
        }
    }
}
