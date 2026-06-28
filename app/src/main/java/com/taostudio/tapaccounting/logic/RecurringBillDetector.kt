package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import kotlin.math.abs

/**
 * 周期账单检测器。
 * 从已有账单中识别"疑似周期支出"。
 */
object RecurringBillDetector {

    data class RecurringCandidate(
        val merchantKey: String,
        val categoryId: Long?,
        val categoryName: String?,
        val accountName: String?,
        val bookName: String,
        val medianAmount: Double,
        val tolerance: Double,
        val frequency: RecurringFrequency,
        val dayOfMonthHint: Int?,
        val lastSeenAt: Long,
        val confidence: Double,
        val billCount: Int
    )

    /**
     * 从近 12 个月支出账单中检测周期模式。
     * @return 最多 5 个候选，按 confidence 降序
     */
    fun detect(bills: List<Bill>): List<RecurringCandidate> {
        val expenses = bills.filter {
            it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND
        }

        // 按归一化 remark + category 分组
        data class GroupKey(val normalizedRemark: String, val category: String)

        fun normalize(text: String): String =
            text.trim().lowercase().replace("\\s+".toRegex(), "")

        val groups = expenses.filter { it.remark.isNotBlank() }
            .groupBy { GroupKey(normalize(it.remark), it.categoryName) }

        val candidates = mutableListOf<RecurringCandidate>()

        for ((key, groupBills) in groups) {
            if (groupBills.size < 3) continue

            // 金额检查：波动 ±15%
            val amounts = groupBills.map { it.amount }.sorted()
            val median = amounts[amounts.size / 2]
            val tolerance = median * 0.15
            val toleranceOk = amounts.all { abs(it - median) <= tolerance }
            if (!toleranceOk) continue

            // 间隔检查
            val times = groupBills.map { it.time }.sorted()
            val intervals = (1 until times.size).map {
                (times[it] - times[it - 1]) / (24.0 * 3600_000)
            }
            if (intervals.isEmpty()) continue
            val avgInterval = intervals.average()

            val frequency = when {
                avgInterval in 6.0..8.0 -> RecurringFrequency.WEEKLY
                avgInterval in 27.0..33.0 -> RecurringFrequency.MONTHLY
                avgInterval in 358.0..372.0 -> RecurringFrequency.YEARLY
                else -> continue
            }

            // 计算置信度
            val countScore = (groupBills.size / 5.0).coerceAtMost(1.0)
            val intervalVariance = intervals.map { abs(it - avgInterval) }.average()
            val intervalScore = (1.0 - intervalVariance / avgInterval).coerceIn(0.0, 1.0)
            val confidence = (countScore * 0.4 + intervalScore * 0.6)

            // 提取扣款日提示
            val dayOfMonthHint = if (frequency == RecurringFrequency.MONTHLY) {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = times.last()
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            } else null

            candidates.add(
                RecurringCandidate(
                    merchantKey = key.normalizedRemark,
                    categoryId = groupBills.first().categoryId,
                    categoryName = key.category,
                    accountName = groupBills.first().accountName,
                    bookName = groupBills.first().bookName,
                    medianAmount = median,
                    tolerance = tolerance,
                    frequency = frequency,
                    dayOfMonthHint = dayOfMonthHint,
                    lastSeenAt = times.last(),
                    confidence = confidence,
                    billCount = groupBills.size
                )
            )
        }

        return candidates.sortedByDescending { it.confidence }.take(5)
    }

    /**
     * 将候选转换为 RecurringPattern 实体。
     */
    fun toPattern(candidate: RecurringCandidate): RecurringPattern {
        val now = System.currentTimeMillis()
        val nextExpected = calculateNextExpected(candidate.lastSeenAt, candidate.frequency)

        return RecurringPattern(
            merchantKey = candidate.merchantKey,
            categoryId = candidate.categoryId,
            categoryName = candidate.categoryName,
            accountName = candidate.accountName,
            bookName = candidate.bookName,
            amountApprox = candidate.medianAmount,
            amountTolerance = candidate.tolerance,
            frequency = candidate.frequency,
            dayOfMonthHint = candidate.dayOfMonthHint,
            lastSeenAt = candidate.lastSeenAt,
            nextExpectedAt = nextExpected,
            status = RecurringStatus.SUGGESTED,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun calculateNextExpected(lastSeen: Long, frequency: RecurringFrequency): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = lastSeen
        when (frequency) {
            RecurringFrequency.WEEKLY -> cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
            RecurringFrequency.MONTHLY -> cal.add(java.util.Calendar.MONTH, 1)
            RecurringFrequency.YEARLY -> cal.add(java.util.Calendar.YEAR, 1)
        }
        return cal.timeInMillis
    }
}
